#!/usr/bin/env python3
"""Wait for outbox and required consumer offsets to drain using metadata only.

Ingress must already be closed. Call once with core services running, then once
after stopping them with require_active=False and stable_samples=1. No payload,
user data, database passwords, or Kafka message bodies are decoded or printed.
An exact one-offset control tail is checked using Kafka's readonly batch dump;
consumer offsets are never changed by this checker.
"""
import argparse
import json
from pathlib import PurePosixPath
import re
import subprocess
import time

SERVICE_LABEL = 'com.docker.compose.service'
GROUPS = {'feed-service': ('feed-service-group', ('post-events', 'user-events')),
          'notification-service': ('notification-service', ('post-events', 'user-events')),
          'post-service': ('post-service-group', ('user-events',))}


def execute(args, timeout_seconds):
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=max(0.1, timeout_seconds))
    except subprocess.TimeoutExpired:
        raise RuntimeError('Drain metadata command timed out') from None
    if result.returncode:
        raise RuntimeError('Drain metadata command failed; ingress must remain closed')
    return result.stdout


def consumer_requirements(containers):
    required = {}
    for service, (default_group, default_topics) in GROUPS.items():
        matches = [c for c in containers if c['Config']['Labels'].get(SERVICE_LABEL) == service]
        running = [c for c in matches if c['State']['Running']]
        matches = running or matches
        if not matches:
            raise RuntimeError('Required consumer container is missing: ' + service)
        for container in matches:
            env = dict(item.split('=', 1) for item in container['Config'].get('Env', []) if '=' in item)
            group = env.get('SPRING_KAFKA_CONSUMER_GROUP_ID', default_group)
            topics = {env.get('APP_KAFKA_TOPICS_' + topic.upper().replace('-', '_'), topic)
                      for topic in default_topics}
            if not group or any(not re.fullmatch(r'[A-Za-z0-9._-]+', topic) for topic in topics):
                raise RuntimeError('Invalid expected consumer metadata configuration')
            required.setdefault(group, set()).update(topics)
    return required


def topic_partitions(output, topics):
    found = {topic: set() for topic in topics}
    declared = {}
    for line in output.splitlines():
        name = re.search(r'\bTopic:\s+(\S+)', line)
        if not name or name[1] not in found:
            continue
        topic = name[1]
        count = re.search(r'\bPartitionCount:\s+(\d+)', line)
        partition = re.search(r'\bPartition:\s+(\d+)', line)
        if count:
            declared[topic] = int(count[1])
        if partition:
            index = int(partition[1])
            if index in found[topic]:
                raise RuntimeError('Duplicate topic partition in Kafka metadata')
            found[topic].add(index)
    for topic in topics:
        if not declared.get(topic) or found[topic] != set(range(declared[topic])):
            raise RuntimeError('Cannot verify every partition of required Kafka topic: ' + topic)
    return found


def group_offsets(output, required):
    """Parse Kafka 4 consumer-groups --describe tabular metadata, fail closed."""
    rows = {}
    for line in output.splitlines():
        fields = line.split()
        if not fields or fields[0] not in required:
            continue
        if len(fields) != 9 or not fields[2].isdigit():
            raise RuntimeError('Unrecognized required consumer-group metadata row')
        group, topic = fields[:2]
        if topic not in required[group]:
            continue
        values = []
        for item in fields[3:6]:
            if item == '-':
                values.append(None)
            elif item.isdigit():
                values.append(int(item))
            else:
                raise RuntimeError('Unrecognized Kafka consumer offset')
        key = group, topic, int(fields[2])
        if key in rows:
            raise RuntimeError('Duplicate required Kafka consumer offset')
        rows[key] = (*values, all(item != '-' for item in fields[6:9]))
    return rows


def end_offsets(output, partitions):
    found = {}
    for line in output.splitlines():
        fields = line.strip().split(':')
        if not line.strip():
            continue
        if len(fields) != 3 or fields[0] not in partitions or not all(v.isdigit() for v in fields[1:]):
            raise RuntimeError('Unrecognized Kafka latest-offset metadata')
        key = fields[0], int(fields[1])
        if key in found:
            raise RuntimeError('Duplicate Kafka latest-offset metadata')
        found[key] = int(fields[2])
    if set(found) != {(topic, partition) for topic, indices in partitions.items() for partition in indices}:
        raise RuntimeError('Latest-offset metadata is missing required partitions')
    return found


def log_directories(output, wanted):
    documents = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
    if len(documents) != 1 or len(documents[0].get('brokers', [])) != 1:
        raise RuntimeError('Control-tail inspection requires one local Kafka broker')
    names = {f'{topic}-{partition}': (topic, partition) for topic, partition in wanted}
    found = {}
    for directory in documents[0]['brokers'][0].get('logDirs', []):
        if directory.get('error') is not None:
            raise RuntimeError('Kafka log-directory metadata reports an error')
        path = PurePosixPath(directory.get('logDir', ''))
        if not path.is_absolute() or '..' in path.parts:
            raise RuntimeError('Invalid Kafka log-directory path')
        for replica in directory.get('partitions', []):
            name = replica.get('partition')
            if name not in names or replica.get('isFuture') is True:
                continue
            key = names[name]
            if key in found or replica.get('isFuture') is not False:
                raise RuntimeError('Ambiguous Kafka partition log directory')
            found[key] = str(path / name)
    if set(found) != set(wanted):
        raise RuntimeError('Required Kafka partition log directory is missing')
    return found


def select_segment(output, directory, offset):
    segments = {}
    for line in output.splitlines():
        path = PurePosixPath(line)
        if str(path.parent) != directory or not re.fullmatch(r'[0-9]{20}\.log', path.name):
            raise RuntimeError('Unrecognized Kafka segment path')
        base = int(path.stem)
        if base in segments:
            raise RuntimeError('Duplicate Kafka log segment')
        segments[base] = str(path)
    eligible = [base for base in segments if base <= offset]
    if not eligible:
        raise RuntimeError('Committed Kafka offset has no retained log segment')
    return segments[max(eligible)]


def single_control_tail(output, current, end):
    """Prove the sole remaining offset is a valid Kafka control batch.

    Kafka 4 message-format defines control batches as one non-application record.
    Shallow DumpLogSegments prints only batch fields and validates their CRC;
    deep iteration/record headers and payload decoders must never be enabled.
    https://kafka.apache.org/40/implementation/message-format/#control-batches
    """
    if end != current + 1:
        return False
    batches = []
    for line in output.splitlines():
        if line.startswith(('Dumping ', 'Log starting offset: ')) or not line.strip():
            continue
        if not line.startswith('baseOffset: '):
            return False  # Includes truncated/corrupt segments and unknown formats.
        fields = {}
        for name in ('baseOffset', 'lastOffset', 'count', 'magic', 'isTransactional', 'isControl', 'isvalid'):
            matches = re.findall(r'(?:^|\s)' + name + r': (\S+)', line)
            if len(matches) != 1:
                return False
            fields[name] = matches[0]
        if any(not fields[name].isdigit() for name in ('baseOffset', 'lastOffset', 'count', 'magic')):
            return False
        start, last = int(fields['baseOffset']), int(fields['lastOffset'])
        if start > last or fields['magic'] != '2' or fields['isvalid'] != 'true':
            return False
        if batches and start <= batches[-1][1]:
            return False
        batches.append((start, last, fields))
    if not batches or batches[-1][1] != current:
        return False
    start, last, fields = batches[-1]
    return (start == last == current and fields['count'] == '1'
            and fields['isControl'] == fields['isTransactional'] == 'true')


def control_tail_proofs(run, kafka, offsets, latest):
    candidates = {(topic, partition, current, end)
                  for (_, topic, partition), (current, end, lag, _) in offsets.items()
                  if current is not None and end == current + 1 and lag == 1
                  and latest.get((topic, partition)) == end}
    if not candidates:
        return set()
    wanted = {(topic, partition) for topic, partition, _, _ in candidates}
    directories = log_directories(run(*kafka, '/opt/kafka/bin/kafka-log-dirs.sh',
        '--bootstrap-server', 'localhost:9092', '--describe', '--topic-list',
        ','.join(sorted({topic for topic, _ in wanted}))), wanted)
    proven = set()
    for topic, partition, current, end in sorted(candidates):
        directory = directories[topic, partition]
        listing = run(*kafka, 'sh', '-c',
            'for segment in "$1"/*.log; do [ -f "$segment" ] || continue; printf "%s\\n" "$segment"; done',
            'drain-segments', directory)
        segment = select_segment(listing, directory, current)
        metadata = run(*kafka, '/opt/kafka/bin/kafka-dump-log.sh', '--files', segment)
        if single_control_tail(metadata, current, end):
            proven.add((topic, partition, current, end))
    return proven


def is_drained(pending, offsets, required, partitions, require_active, latest=None, control_tails=None):
    if pending != 0:
        return False
    expected = {(group, topic, partition) for group, topics in required.items()
                for topic in topics for partition in partitions[topic]}
    if set(offsets) - expected:
        return False
    for key in expected:
        if key not in offsets:
            # An unused partition has no committed offset after its member stops.
            if not require_active and latest is not None and latest[key[1:]] == 0:
                continue
            return False
        current, end, lag, active = offsets[key]
        if require_active and not active:
            return False
        if latest is not None and latest[key[1:]] != end:
            return False
        if current is None and end == 0 and lag is None:
            continue
        if current == end and lag == 0:
            continue
        proof = (*key[1:], current, end)
        if (latest is not None and current is not None and lag == 1 and end == current + 1
                and proof in (control_tails or set())):
            continue
        else:
            return False
    return True


def drain(project, timeout_seconds=180, stable_samples=2, require_active=True):
    if timeout_seconds <= 0 or stable_samples < 1:
        raise ValueError('Drain timeout and sample count must be positive')
    deadline = time.monotonic() + timeout_seconds

    def run(*args):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError('Outbox/consumer drain timed out; ingress must remain closed')
        return execute(list(args), min(60, remaining))

    ids = run('docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project).split()
    if not ids:
        raise RuntimeError('No project containers available for drain')
    containers = json.loads(run('docker', 'inspect', *ids))
    required = consumer_requirements(containers)
    backing = {}
    for service in ('postgres-user', 'postgres-post', 'kafka'):
        matches = [c for c in containers if c['Config']['Labels'].get(SERVICE_LABEL) == service
                   and c['State']['Running']]
        if len(matches) != 1:
            raise RuntimeError('Drain requires one running backing service: ' + service)
        backing[service] = matches[0]['Id']
    topics = set().union(*required.values())
    kafka = ['docker', 'exec', backing['kafka']]
    topic_filter = '^(' + '|'.join(re.escape(topic) for topic in sorted(topics)) + ')$'
    partitions = topic_partitions(run(*kafka, '/opt/kafka/bin/kafka-topics.sh',
        '--bootstrap-server', 'localhost:9092', '--describe', '--topic', topic_filter), topics)
    stable = 0
    previous = None
    while time.monotonic() < deadline:
        pending = 0
        for service in ('postgres-user', 'postgres-post'):
            count = run('docker', 'exec', backing[service], 'sh', '-c',
                        'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
                        '-Atc "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"').strip()
            if not count.isdigit():
                raise RuntimeError('Unrecognized unpublished outbox count')
            pending += int(count)
        offsets = group_offsets(run(*kafka, '/opt/kafka/bin/kafka-consumer-groups.sh',
            '--bootstrap-server', 'localhost:9092', '--all-groups', '--describe'), required)
        latest = end_offsets(run(*kafka, '/opt/kafka/bin/kafka-get-offsets.sh',
            '--bootstrap-server', 'localhost:9092', '--topic', topic_filter, '--time', '-1'), partitions)
        control_tails = control_tail_proofs(run, kafka, offsets, latest) if pending == 0 else set()
        if control_tails:
            # Appends/segment changes during inspection invalidate this sample.
            after = end_offsets(run(*kafka, '/opt/kafka/bin/kafka-get-offsets.sh',
                '--bootstrap-server', 'localhost:9092', '--topic', topic_filter, '--time', '-1'), partitions)
            if after != latest:
                control_tails = set()
                latest = after
        tail = ({key: value[:3] for key, value in offsets.items()}, latest)
        if is_drained(pending, offsets, required, partitions, require_active, latest, control_tails):
            stable = stable + 1 if tail == previous else 1
            if stable >= stable_samples:
                result = {'outbox_pending': 0, 'required_groups': len(required),
                          'group_partitions': len(offsets), 'stable_samples': stable,
                          'active_consumers_required': require_active,
                          'verified_control_tails': len(control_tails)}
                print('Outbox and consumer offsets drained: ' + json.dumps(result), flush=True)
                return result
        else:
            stable = 0
        previous = tail
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(5, remaining))
    raise RuntimeError('Outbox/consumer drain timed out; ingress must remain closed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--stable-samples', type=int, default=2)
    parser.add_argument('--allow-inactive', action='store_true')
    args = parser.parse_args()
    drain(args.project, args.timeout, args.stable_samples, not args.allow_inactive)
