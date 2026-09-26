import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import drain


class DrainMetadataTests(unittest.TestCase):
    def setUp(self):
        self.required = {'feed-service-group': {'post-events', 'user-events'},
                         'notification-service': {'post-events', 'user-events'},
                         'post-service-group': {'user-events'}}
        self.partitions = {'post-events': {0, 1}, 'user-events': {0, 1}}

    def output(self, *, active=True, offset=7):
        members = 'consumer-1 /172.22.0.2 client-1' if active else '- - -'
        return 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n' + '\n'.join(
            f'{group} {topic} {partition} {offset} {offset} 0 {members}'
            for group, topics in self.required.items() for topic in sorted(topics)
            for partition in sorted(self.partitions[topic]))

    def test_all_required_partitions_zero_and_owned(self):
        rows = drain.group_offsets(self.output(), self.required)
        self.assertTrue(drain.is_drained(0, rows, self.required, self.partitions, True))
        self.assertFalse(drain.is_drained(1, rows, self.required, self.partitions, True))
        rows.pop(next(iter(rows)))
        self.assertFalse(drain.is_drained(0, rows, self.required, self.partitions, True))

    def test_final_pass_accepts_preserved_offsets_without_active_members(self):
        rows = drain.group_offsets(self.output(active=False), self.required)
        self.assertTrue(drain.is_drained(0, rows, self.required, self.partitions, False))
        self.assertFalse(drain.is_drained(0, rows, self.required, self.partitions, True))

    def test_never_used_partition_needs_no_committed_offset_but_must_be_empty(self):
        rows = drain.group_offsets(self.output().replace('7 7 0', '- 0 -', 1), self.required)
        latest = {(topic, index): 7 for topic, indices in self.partitions.items() for index in indices}
        empty = next(iter(rows))
        latest[empty[1:]] = 0
        # Other groups still report 7 for this partition, so the cross-group
        # sample is inconsistent until every corresponding row reports empty.
        for key in rows:
            if key[1:] == empty[1:]:
                rows[key] = (None, 0, None, True)
        self.assertTrue(drain.is_drained(0, rows, self.required, self.partitions, True, latest))
        for key in list(rows):
            if key[1:] == empty[1:]:
                rows.pop(key)
        self.assertTrue(drain.is_drained(0, rows, self.required, self.partitions, False, latest))
        latest[empty[1:]] = 1
        self.assertFalse(drain.is_drained(0, rows, self.required, self.partitions, False, latest))

    def test_unknown_offsets_lag_and_malformed_rows_fail_closed(self):
        for replacement in ('- 7 -', '6 7 1', '6 7 0', '7 8 0'):
            with self.subTest(replacement=replacement):
                rows = drain.group_offsets(self.output().replace('7 7 0', replacement, 1), self.required)
                self.assertFalse(drain.is_drained(0, rows, self.required, self.partitions, True))
        with self.assertRaisesRegex(RuntimeError, 'Unrecognized'):
            drain.group_offsets('feed-service-group post-events 0 7 7\n', self.required)
        with self.assertRaisesRegex(RuntimeError, 'Duplicate'):
            drain.group_offsets(self.output() + '\n' + self.output().splitlines()[1], self.required)

    def test_partition_description_requires_declared_complete_set(self):
        output = '\n'.join(f'Topic: {topic} TopicId: id PartitionCount: 2 ReplicationFactor: 1\n'
                           f'  Topic: {topic} Partition: 0 Leader: 1 Replicas: 1 Isr: 1\n'
                           f'  Topic: {topic} Partition: 1 Leader: 1 Replicas: 1 Isr: 1'
                           for topic in self.partitions)
        self.assertEqual(drain.topic_partitions(output, set(self.partitions)), self.partitions)
        with self.assertRaisesRegex(RuntimeError, 'every partition'):
            drain.topic_partitions(output.replace('Partition: 1', 'Partition: 2'), set(self.partitions))

    def test_requirements_use_running_replica_over_stale_stopped_container(self):
        containers = []
        for name in drain.GROUPS:
            containers.extend([
                {'State': {'Running': False}, 'Config': {'Labels': {drain.SERVICE_LABEL: name},
                    'Env': ['SPRING_KAFKA_CONSUMER_GROUP_ID=obsolete']}},
                {'State': {'Running': True}, 'Config': {'Labels': {drain.SERVICE_LABEL: name}, 'Env': []}}])
        self.assertEqual(drain.consumer_requirements(containers), self.required)
        for container in containers:
            container['State']['Running'] = False
        final = drain.consumer_requirements(containers[1::2])
        self.assertEqual(final, self.required)

    def test_drain_requires_stable_tail_and_two_zero_samples(self):
        containers = [{'Id': name, 'State': {'Running': True},
                       'Config': {'Labels': {drain.SERVICE_LABEL: name}, 'Env': []}}
                      for name in [*drain.GROUPS, 'postgres-user', 'postgres-post', 'kafka']]
        group_calls = []
        def execute(args, timeout):
            if args[:3] == ['docker', 'ps', '-aq']:
                return ' '.join(c['Id'] for c in containers)
            if args[:2] == ['docker', 'inspect']:
                return json.dumps(containers)
            if 'sh' in args:
                return '0\n'
            if '/opt/kafka/bin/kafka-topics.sh' in args:
                return '\n'.join(f'Topic: {topic} PartitionCount: 2\nTopic: {topic} Partition: 0\nTopic: {topic} Partition: 1'
                                 for topic in self.partitions)
            if '/opt/kafka/bin/kafka-get-offsets.sh' in args:
                return '\n'.join(f'{topic}:{index}:{7 if len(group_calls) == 1 else 8}'
                                 for topic, indices in self.partitions.items() for index in indices)
            group_calls.append(1)
            return self.output(offset=7 if len(group_calls) == 1 else 8)
        with patch.object(drain, 'execute', execute), patch.object(drain.time, 'sleep'):
            result = drain.drain('synthetic-project')
        self.assertEqual(len(group_calls), 3)
        self.assertEqual(result['stable_samples'], 2)
        self.assertEqual(result['group_partitions'], 10)


class ControlTailTests(unittest.TestCase):
    @staticmethod
    def batch(offset, *, control=True, valid=True, count=1, last=None):
        return (f'baseOffset: {offset} lastOffset: {offset if last is None else last} count: {count} '
                f'isTransactional: true isControl: {str(control).lower()} position: 0 '
                f'magic: 2 compresscodec: none crc: 123 isvalid: {str(valid).lower()}')

    def test_exact_single_control_offset_is_safe_but_same_size_business_record_is_not(self):
        self.assertTrue(drain.single_control_tail(self.batch(7), 7, 8))
        self.assertFalse(drain.single_control_tail(self.batch(7, control=False), 7, 8))
        rows = {('group', 'events', 0): (7, 8, 1, True)}
        args = (0, rows, {'group': {'events'}}, {'events': {0}}, True, {('events', 0): 8})
        self.assertFalse(drain.is_drained(*args))
        self.assertTrue(drain.is_drained(*args, {('events', 0, 7, 8)}))
        self.assertFalse(drain.is_drained(*args, {('events', 0, 6, 7)}))

    def test_marker_does_not_hide_unread_business_record_larger_gap_or_changed_end(self):
        metadata = self.batch(6, control=False) + '\n' + self.batch(7)
        self.assertFalse(drain.single_control_tail(metadata, 6, 8))
        self.assertTrue(drain.single_control_tail(metadata, 7, 8))
        self.assertFalse(drain.single_control_tail(metadata + '\n' + self.batch(8, control=False), 7, 8))
        rows = {('group', 'events', 0): (7, 8, 1, True)}
        self.assertFalse(drain.is_drained(0, rows, {'group': {'events'}}, {'events': {0}}, True,
                                         {('events', 0): 9}, {('events', 0, 7, 8)}))

    def test_corrupt_truncated_duplicate_or_unknown_metadata_is_not_a_proof(self):
        samples = [self.batch(7, valid=False), self.batch(7, count=2), self.batch(6, last=7),
                   self.batch(7).replace('magic: 2', 'magic: 1'),
                   self.batch(7).replace('isTransactional: true', 'isTransactional: false'),
                   self.batch(7) + '\nFound 3 invalid bytes at the end of segment.log',
                   self.batch(7) + '\n' + self.batch(7),
                   self.batch(7) + ' isControl: false', '', 'offset: 7 magic: 1']
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertFalse(drain.single_control_tail(sample, 7, 8))

    def test_segment_selection_handles_empty_next_segment_and_rejects_unknown_paths(self):
        directory = '/var/lib/kafka/data/events-0'
        segments = [directory + '/' + f'{offset:020d}.log' for offset in (0, 5, 8)]
        self.assertEqual(drain.select_segment('\n'.join(segments), directory, 7), segments[1])
        for listing in ('/other/00000000000000000000.log', directory + '/unexpected.log', ''):
            with self.subTest(listing=listing), self.assertRaises(RuntimeError):
                drain.select_segment(listing, directory, 7)

    def metadata(self, directories=None):
        return json.dumps({'brokers': [{'broker': 1, 'logDirs': directories or [
            {'logDir': '/var/lib/kafka/data', 'error': None, 'partitions': [
                {'partition': 'events-0', 'isFuture': False}]}]}], 'version': 1})

    def test_directories_require_one_local_current_replica(self):
        wanted = {('events', 0)}
        self.assertEqual(drain.log_directories(self.metadata(), wanted),
                         {('events', 0): '/var/lib/kafka/data/events-0'})
        for output in (self.metadata().replace('"isFuture": false', '"isFuture": true'),
                       self.metadata().replace('/var/lib/kafka/data', '/var/../data'),
                       self.metadata().replace('"error": null', '"error": "KAFKA_STORAGE_ERROR"'),
                       '{"brokers": []}'):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                drain.log_directories(output, wanted)

    def test_probe_uses_only_readonly_batch_metadata_and_deduplicates_group_candidates(self):
        commands = []
        def run(*args):
            commands.append(args)
            if '/opt/kafka/bin/kafka-log-dirs.sh' in args:
                return self.metadata()
            if 'sh' in args:
                return '/var/lib/kafka/data/events-0/00000000000000000000.log\n'
            return self.batch(6, control=False) + '\n' + self.batch(7)
        offsets = {(group, 'events', 0): (7, 8, 1, True) for group in ('feed', 'notification')}
        self.assertEqual(drain.control_tail_proofs(run, ['docker', 'exec', 'broker'], offsets,
                                                 {('events', 0): 8}), {('events', 0, 7, 8)})
        self.assertEqual(len(commands), 3)
        for args in commands:
            self.assertFalse({'--deep-iteration', '--print-data-log', '--reset-offsets', '--execute', '--group'} & set(args))
        self.assertEqual(drain.control_tail_proofs(run, ['docker', 'exec', 'broker'], offsets,
                                                 {('events', 0): 9}), set())
        self.assertEqual(len(commands), 3)


if __name__ == '__main__':
    unittest.main()
