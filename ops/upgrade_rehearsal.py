#!/usr/bin/env python3
"""Rehearse the first release on existing restored volumes, never on live stores.

Call rehearse(release_dir, resolved_config=config) while holding deployment.lock.
The CLI acquires that lock itself. Images must already be pulled; nothing builds,
restores, deletes, or recreates production containers in this operation.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from backup import atomic_json, inspect_project
from deploy import APPLICATIONS, BASE, INFRA, wait_healthy
from release import SERVICES, validate

LABEL = 'com.docker.compose.service'
INFRA_ORDER = ['postgres-user', 'postgres-post', 'postgres-notification',
               'redis', 'minio', 'kafka', 'prometheus', 'grafana']
MIGRATORS = {'user-migrate': 'user-service', 'post-migrate': 'post-service',
             'notification-migrate': 'notification-service'}
DIAGNOSTICS = None


def command(*args):
    """Capture diagnostics privately: Compose configuration may contain secrets."""
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if DIAGNOSTICS is not None:
        atomic_json(DIAGNOSTICS / (str(time.time_ns()) + '.private.json'),
                    {'command': list(args), 'returncode': result.returncode,
                     'stdout': result.stdout, 'stderr': result.stderr})
    if result.returncode:
        raise RuntimeError('Rehearsal command failed (' + args[0] + ', exit ' + str(result.returncode)
                           + '); diagnostics retained in the private rehearsal directory')
    return result.stdout


def image_id(reference):
    return json.loads(command('docker', 'image', 'inspect', reference))[0]['Id']


def service(container):
    return container['Config']['Labels'][LABEL]


def stop_stack(containers):
    # A single failed stop must not prevent the remaining groups from stopping.
    errors = []
    groups = [[c for c in containers if service(c) not in INFRA | {'eureka-server'}],
              [c for c in containers if service(c) == 'eureka-server'],
              [c for c in containers if service(c) in INFRA]]
    for group in groups:
        if group:
            try:
                command('docker', 'stop', '-t', '90', *(c['Id'] for c in group))
            except BaseException as error:
                errors.append(error)
    if errors:
        raise RuntimeError('Could not stop every container in the stack') from errors[0]


def restart_original(containers):
    errors = []
    known = set(INFRA_ORDER + APPLICATIONS)
    order = INFRA_ORDER + APPLICATIONS + sorted({service(c) for c in containers} - known)
    for name in order:
        for container in containers:
            if service(container) != name:
                continue
            try:
                command('docker', 'start', container['Id'])
                wait_healthy([container['Id']])
            except BaseException as error:
                errors.append(error)
    if errors:
        raise RuntimeError('Original production recovery requires attention; maintenance remains closed') from errors[0]


def isolated(config, project, volumes):
    config = copy.deepcopy(config)
    config['name'] = project
    config['volumes'] = {key: {'name': value, 'external': True} for key, value in volumes.items()}
    for key, network in config.get('networks', {}).items():
        network.clear()
        network.update(name=project + '_' + key, driver='bridge', internal=True)
    for name, item in config['services'].items():
        if 'build' in item:
            raise RuntimeError('Rehearsal must use saved or released images, never builds')
        for key in ('container_name', 'ports', 'network_mode', 'extra_hosts', 'links'):
            item.pop(key, None)
        item['restart'] = 'no'
        item['scale'] = 1
        item.pop('deploy', None)
        for mount in item.get('volumes', []):
            if not isinstance(mount, dict):
                raise RuntimeError('Rehearsal requires resolved Compose mounts')
            if mount['type'] == 'bind' and not mount.get('read_only'):
                raise RuntimeError('Rehearsal cannot share writable host paths')
            if mount['type'] == 'volume' and mount.get('source') not in volumes:
                raise RuntimeError('Rehearsal mount is not one of the restored volumes')
        if name in INFRA:
            continue
        env = item.setdefault('environment', {})
        if name != 'frontend' and name != 'gateway-proxy':
            item['mem_limit'] = '512m'
            env['JAVA_TOOL_OPTIONS'] = ('-Xmx256m -XX:ActiveProcessorCount=2 '
                                       '-Dspring.mail.host=127.0.0.1 -Dspring.mail.port=9')
        env.update(SPRING_MAIL_HOST='127.0.0.1', SPRING_MAIL_PORT='9',
                   SPRING_MAIL_USERNAME='isolated-rehearsal', SPRING_MAIL_PASSWORD='isolated-rehearsal',
                   GOOGLE_CLIENT_ID='isolated-rehearsal', GOOGLE_CLIENT_SECRET='isolated-rehearsal')
        if name in ('post-service', 'feed-service', 'notification-service'):
            env['SPRING_KAFKA_CONSUMER_PROPERTIES_ISOLATION_LEVEL'] = 'read_committed'
    return config


def compose_literal(value):
    # The input is already resolved. A dollar inside a private value is data.
    if isinstance(value, str):
        return value.replace('$', '$$')
    if isinstance(value, list):
        return [compose_literal(item) for item in value]
    if isinstance(value, dict):
        return {key: compose_literal(item) for key, item in value.items()}
    return value


def verify_volumes(config, original, project):
    originals = {m['Name'] for c in original for m in c.get('Mounts', []) if m['Type'] == 'volume'}
    volumes = {key: item['name'] for key, item in config['volumes'].items()}
    if len(set(volumes.values())) != len(volumes) or originals.intersection(volumes.values()):
        raise RuntimeError('Restored and production volume sets must be distinct')
    for name in volumes.values():
        info = json.loads(command('docker', 'volume', 'inspect', name))[0]
        if info.get('Labels', {}).get('unimeow.restore') != project:
            raise RuntimeError('A volume is not owned by this restore rehearsal')
    return volumes


def verify_networks(config):
    # Existing networks must also be internal; Compose cannot convert them in place.
    existing = set(command('docker', 'network', 'ls', '--format', '{{.Name}}').splitlines())
    for network in config['networks'].values():
        if network['name'] in existing:
            info = json.loads(command('docker', 'network', 'inspect', network['name']))[0]
            if not info['Internal']:
                raise RuntimeError('Existing rehearsal network permits external traffic')


def map_release_volumes(config, baseline, restored_volumes):
    """Baseline export uses saved_* aliases; match actual production volume names."""
    source_to_saved = {value['name']: key for key, value in baseline['volumes'].items()}
    if len(source_to_saved) != len(baseline['volumes']) or set(source_to_saved.values()) != set(restored_volumes):
        raise RuntimeError('Baseline and restored volume identities differ')
    aliases = {}
    for key, volume in config['volumes'].items():
        if volume['name'] not in source_to_saved:
            raise RuntimeError('Release volume is not in the saved baseline')
        aliases[key] = source_to_saved[volume['name']]
    if set(aliases.values()) != set(restored_volumes) or len(aliases) != len(restored_volumes):
        raise RuntimeError('Release and baseline actual volume sets differ')
    config = copy.deepcopy(config)
    for item in config['services'].values():
        for mount in item.get('volumes', []):
            if isinstance(mount, dict) and mount['type'] == 'volume':
                if mount.get('source') not in aliases:
                    raise RuntimeError('Release refers to an undeclared volume')
                mount['source'] = aliases[mount['source']]
    config['volumes'] = {key: {'name': value} for key, value in restored_volumes.items()}
    return config


def recover(directory):
    """Explicit recovery after process/host interruption; caller holds the lock."""
    global DIAGNOSTICS
    directory = Path(directory).resolve()
    if directory.parent.parent != BASE / 'rehearsals' or not directory.name.startswith('upgrade-'):
        raise RuntimeError('Recovery must use a canonical rehearsal directory')
    journal_path = directory / 'journal.private.json'
    journal = json.loads(journal_path.read_text())
    if journal['phase'] != 'running':
        raise RuntimeError('This rehearsal does not require interrupted recovery')
    if directory.parent.name != journal['clone_project']:
        raise RuntimeError('Recovery project identity differs from its directory')
    original = json.loads((directory / 'original-containers.private.json').read_text())
    DIAGNOSTICS = directory / 'diagnostics'
    DIAGNOSTICS.mkdir(mode=0o700, exist_ok=True)
    actual = json.loads(command('docker', 'inspect', *(c['Id'] for c in original)))
    if any(c['Config']['Labels'].get('com.docker.compose.project') != journal['production_project'] for c in actual):
        raise RuntimeError('Saved original container ownership changed')
    errors = []
    try:
        stop_stack(inspect_project(journal['clone_project']))
    except BaseException as error:
        errors.append(error)
    try:
        restart_original(original)
    except BaseException as error:
        errors.append(error)
    if errors:
        raise RuntimeError('Interrupted rehearsal recovery incomplete; maintenance remains closed') from errors[0]
    (BASE / 'maintenance').unlink(missing_ok=True)
    journal['phase'] = 'recovered'
    atomic_json(journal_path, journal)
    print('Interrupted rehearsal recovered; original containers healthy, clone volumes retained', flush=True)


def start_services(compose, names, config):
    for name in names:
        if name in config['services']:
            command(*compose, 'up', '-d', '--no-build', '--pull', 'never', '--no-deps',
                    '--wait', '--wait-timeout', '1200', name)


def running_images(project, expected):
    running = [c for c in inspect_project(project) if c['State']['Running']]
    for name, expected_id in expected.items():
        matches = [c for c in running if service(c) == name]
        if len(matches) != 1 or matches[0]['Image'] != expected_id:
            raise RuntimeError('Running image does not match verified image: ' + name)
    return {service(c): c for c in running}


def smoke(project, script, snapshot, read_only):
    current = {service(c): c for c in inspect_project(project) if c['State']['Running']}
    gateway = current['api-gateway']
    minio_ip = next(n['IPAddress'] for n in current['minio']['NetworkSettings']['Networks'].values() if n['IPAddress'])
    args = ['nsenter', '--target', str(gateway['State']['Pid']), '--net', sys.executable,
            str(script), '--project', project, '--mode', 'isolated',
            '--base-url', 'http://127.0.0.1:8080', '--minio-base', 'http://' + minio_ip + ':9000',
            '--compare-snapshot', str(snapshot)]
    if read_only:
        args.append('--read-only')
    command(*args)


def rehearse(release_dir, *, resolved_config=None):
    """Caller must hold deployment.lock. Returns proof after original recovery."""
    global DIAGNOSTICS
    release_dir = Path(release_dir).resolve()
    manifest = validate(json.loads((release_dir / 'release.json').read_text()))
    if release_dir.parent != BASE / 'releases' or release_dir.name != manifest['release_id']:
        raise RuntimeError('Release must use its canonical installed directory')
    state_path = BASE / 'state.json'
    state = json.loads(state_path.read_text())
    if state['status'] != 'healthy' or not state.get('restore_verified'):
        raise RuntimeError('A healthy production and verified baseline restoration are required')
    baseline_manifest = json.loads((Path(state['baseline_path']) / 'manifest.json').read_text())
    if state['current_release'] != baseline_manifest['release_id']:
        raise RuntimeError('This rehearsal supports only the first release from the saved baseline')
    project = state['rehearsal_project']
    work = Path(state['rehearsal_path']).resolve()
    if not re.fullmatch(r'unimeow-restore-[0-9]{14}', project) or work != BASE / 'rehearsals' / project:
        raise RuntimeError('Invalid existing rehearsal location')
    directory = work / ('upgrade-' + manifest['release_id'])
    directory.mkdir(mode=0o700, exist_ok=True)
    DIAGNOSTICS = directory / 'diagnostics'
    DIAGNOSTICS.mkdir(mode=0o700, exist_ok=True)
    journal_path = directory / 'journal.private.json'
    if journal_path.exists() and json.loads(journal_path.read_text())['phase'] == 'running':
        raise RuntimeError('Interrupted rehearsal must be recovered first with --recover ' + str(directory))
    original = [c for c in inspect_project(state['project']) if c['State']['Running']]
    if not set(INFRA).issubset({service(c) for c in original}):
        raise RuntimeError('Original infrastructure is not fully running')
    wait_healthy([c['Id'] for c in original])
    restored = json.loads((work / 'compose.private.json').read_text())
    volumes = verify_volumes(restored, original, project)
    old = isolated(restored, project, volumes)
    baseline_ids = {}
    for name in SERVICES:
        expected = baseline_manifest['images'][name]['image_id']
        if image_id(old['services'][name]['image']) != expected:
            raise RuntimeError('Saved baseline image identity changed: ' + name)
        baseline_ids[name] = expected
    if resolved_config is None:
        resolved_config = json.loads(command('docker', 'compose', '--project-name', state['project'],
            '--env-file', str(BASE / 'config/production.env'), '--env-file', str(BASE / 'config/installation.env'),
            '--env-file', str(release_dir / 'release.env'), '-f', str(release_dir / 'compose.production.yml'),
            '--profile', 'migrate', 'config', '--format', 'json'))
    baseline_config = json.loads((Path(state['baseline_path']) / 'compose.private.json').read_text())
    resolved_config = map_release_volumes(resolved_config, baseline_config, volumes)
    new = isolated(resolved_config, project, volumes)
    infrastructure = manifest.get('infrastructure_images', {})
    if set(infrastructure) - {'minio'}:
        raise RuntimeError('Only the approved MinIO infrastructure upgrade can be rehearsed')
    # Keep all restored stores except the explicitly released MinIO version.
    # Its cloned data is upgraded before testing either application's version.
    for name in INFRA:
        if name not in infrastructure:
            new['services'][name] = copy.deepcopy(old['services'][name])
    if set(new['networks']) != set(old['networks']):
        raise RuntimeError('Release and baseline network names differ')
    release_ids = {}
    for name, reference in manifest['images'].items():
        if new['services'][name]['image'] != reference:
            raise RuntimeError('Resolved Compose image differs from release manifest: ' + name)
        release_ids[name] = image_id(reference)
    for name, application in MIGRATORS.items():
        if new['services'][name]['image'] != manifest['images'][application]:
            raise RuntimeError('Migrator differs from released application image')
    infrastructure_ids = {}
    for name, reference in infrastructure.items():
        if new['services'][name]['image'] != reference:
            raise RuntimeError('Infrastructure Compose image differs from release manifest: ' + name)
        infrastructure_ids[name] = image_id(reference)
    image_id(new['services']['gateway-proxy']['image'])
    verify_networks(old)
    old_path, new_path = directory / 'baseline.private.json', directory / 'release.private.json'
    atomic_json(old_path, compose_literal(old))
    atomic_json(new_path, compose_literal(new))
    atomic_json(directory / 'original-containers.private.json', original)
    old_compose = ['docker', 'compose', '-p', project, '-f', str(old_path)]
    new_compose = ['docker', 'compose', '-p', project, '-f', str(new_path)]
    script = release_dir / 'ops/smoke.py'
    if not script.is_file():
        raise RuntimeError('Released smoke checker is missing')
    snapshot = directory / 'data-before.private.json'
    marker = BASE / 'maintenance'
    if marker.exists():
        raise RuntimeError('Existing maintenance must be resolved before rehearsal')
    journal = {'phase': 'running', 'production_project': state['project'], 'clone_project': project,
               'release_id': manifest['release_id'], 'release_commit': manifest['commit']}
    atomic_json(journal_path, journal)
    marker.touch(mode=0o644)
    marker.chmod(0o644)
    failure = None
    recovery_errors = []
    try:
        stop_stack(original)
        stop_stack(inspect_project(project))
        # Never restart old MinIO on clone data a previous rehearsal may have
        # upgraded. SQL snapshot/migrations need only the unchanged stores.
        start_services(old_compose, [name for name in INFRA_ORDER if name not in infrastructure], old)
        command(sys.executable, str(script), '--project', project, '--snapshot', str(snapshot))
        for _ in range(2):
            for name in manifest['migration_services']:
                command(*new_compose, '--profile', 'migrate', 'run', '--rm', '--no-deps', '--pull', 'never', name)
        start_services(new_compose, infrastructure, new)
        running_images(project, infrastructure_ids)
        start_services(old_compose, APPLICATIONS, old)
        running_images(project, {**baseline_ids, **infrastructure_ids})
        smoke(project, script, snapshot, read_only=True)
        print('Saved baseline images passed reads against expanded SQL and released restored infrastructure', flush=True)
        stop_stack([c for c in inspect_project(project) if service(c) not in INFRA])
        start_services(new_compose, APPLICATIONS, new)
        running_images(project, {**release_ids, **infrastructure_ids})
        smoke(project, script, snapshot, read_only=False)
        # Validate fingerprints again after all technical writes/cleanup completed.
        smoke(project, script, snapshot, read_only=True)
        print('Exact release images passed restored-data and event smoke checks', flush=True)
    except BaseException as error:
        failure = error
    finally:
        try:
            stop_stack(inspect_project(project))
        except BaseException as error:
            recovery_errors.append(error)
        # Recovery is attempted even when stopping the isolated stack failed.
        try:
            restart_original(original)
        except BaseException as error:
            recovery_errors.append(error)
        if not recovery_errors:
            marker.unlink()
            journal['phase'] = 'recovered'
            atomic_json(journal_path, journal)
    if recovery_errors:
        raise RuntimeError('Rehearsal recovery incomplete; original IDs retained and maintenance closed') from recovery_errors[0]
    if failure:
        raise RuntimeError('Upgrade rehearsal failed; original production recovered (' + type(failure).__name__ + ')') from failure
    proof = {'verified': True, 'old_code_compatible': True,
             'baseline_release': baseline_manifest['release_id'], 'release_commit': manifest['commit'],
             'backup': state['backup'], 'project': project, 'images': manifest['images'],
             'infrastructure_images': infrastructure, 'infrastructure_image_ids': infrastructure_ids,
             'baseline_image_ids': baseline_ids, 'network_internal': True,
             'external_notifications_disabled': True, 'path': str(directory)}
    atomic_json(directory / 'result.json', proof)
    state.setdefault('upgrade_rehearsals', {})[manifest['release_id']] = proof
    atomic_json(state_path, state)
    print('Upgrade rehearsal verified; original containers healthy, restored volumes retained', flush=True)
    return proof


if __name__ == '__main__':
    import fcntl
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release_directory', nargs='?')
    parser.add_argument('--recover', type=Path, metavar='REHEARSAL_DIRECTORY',
                        help='Restore exact original containers after interrupted rehearsal')
    args = parser.parse_args()
    if bool(args.release_directory) == bool(args.recover):
        parser.error('Choose a release directory or --recover, exclusively')
    with (BASE / 'deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.recover:
            recover(args.recover)
        else:
            rehearse(args.release_directory)
