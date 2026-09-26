#!/usr/bin/env python3
"""Verify immutable application images on newly created, synthetic local volumes.

Never reads the production environment, starts a production project, builds an
image, or removes a volume. Retained containers/volumes are named in the report.
Only the Compose configuration is adapted for isolation, credentials and ports.
"""
import argparse
import base64
import concurrent.futures
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

from release import SERVICES
import drain
import smoke

ROOT = Path(__file__).resolve().parent.parent
JAVA = set(SERVICES) - {'frontend'}
INFRA = ('postgres-user', 'postgres-post', 'postgres-notification', 'redis', 'kafka', 'minio')
MIGRATORS = ('user-migrate', 'post-migrate', 'notification-migrate')
SCALABLE = ('api-gateway', 'user-service', 'post-service', 'media-service')
IMMUTABLE = re.compile(r'(?:[^\s]+@)?sha256:[0-9a-f]{64}\Z')
INFRA_DEFAULTS = {
    'POSTGRES_IMAGE': 'postgres@sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50',
    'REDIS_IMAGE': 'redis@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99',
    'KAFKA_IMAGE': 'apache/kafka@sha256:3f7b939115cd4872e9cee9369d80bd69712fde55f9902f46d793f64848dedc75',
    'MINIO_IMAGE': 'ghcr.io/coollabsio/minio:RELEASE.2025-10-15T17-29-55Z@sha256:4f75fd76598afa23919555d1363e1fb13c632d9bcd4ce8edcf21d3cca2ed0579',
}


def run(*args, capture=False, check=True, env=None):
    return subprocess.run([str(a) for a in args], check=check, text=True, encoding='utf-8', env=env,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None)


def json_output(*args):
    return json.loads(run(*args, capture=True).stdout)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def endpoint(url):
    with urllib.request.urlopen(url, timeout=8) as response:
        return json.load(response)


def wait(check, label, seconds=180):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
            last = type(error).__name__
        time.sleep(2)
    raise RuntimeError(f'{label} did not become ready ({last or "condition false"})')


def local_daemon_only():
    context = os.environ.get('DOCKER_CONTEXT') or run('docker', 'context', 'show', capture=True).stdout.strip()
    data = json_output('docker', 'context', 'inspect', context)[0]
    # Docker gives an explicit context precedence over DOCKER_HOST.
    host = (data['Endpoints']['docker']['Host'] if os.environ.get('DOCKER_CONTEXT')
            else os.environ.get('DOCKER_HOST') or data['Endpoints']['docker']['Host'])
    if not host.startswith(('unix://', 'npipe://', 'tcp://127.0.0.1:', 'tcp://localhost:')):
        raise RuntimeError('Verification requires a local Docker daemon; remote contexts are refused')


def release_images(path):
    entries = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            entries[key.strip()] = value.strip()
    required = {service.upper().replace('-', '_') + '_IMAGE' for service in SERVICES}
    if set(entries) != required or not all(IMMUTABLE.fullmatch(value) for value in entries.values()):
        raise ValueError('release.env must contain exactly eight immutable application image references')
    return entries


def prepare(release_env, work, project):
    source = ROOT / 'compose.production.yml'
    variables = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)', source.read_text()))
    ports = {name: free_port() for name in ('gateway', 'frontend', 'variant', 'eureka', 'minio')}
    values = {
        'COMPOSE_PROJECT_NAME': project, 'DB_USERNAME': 'postgres', 'DB_PASSWORD': secrets.token_hex(20),
        'MINIO_ROOT_USER': 'verification', 'MINIO_ROOT_PASSWORD': secrets.token_hex(20),
        'MAIL_USERNAME': 'verification@example.invalid', 'MAIL_PASSWORD': 'synthetic-no-mail',
        'GOOGLE_CLIENT_ID': 'synthetic-oauth-client', 'GOOGLE_CLIENT_SECRET': 'synthetic-oauth-secret',
        'JWT_SECRET': base64.b64encode(secrets.token_bytes(64)).decode(),
        'APP_PUBLIC_URL': f'http://127.0.0.1:{ports["frontend"]}',
        'FRONTEND_API_BASE': f'http://127.0.0.1:{ports["gateway"]}',
        'APP_MINIO_PUBLIC_URL': f'http://127.0.0.1:{ports["minio"]}',
        'APP_SECURITY_SECURE_COOKIE': 'false', 'APP_SECURITY_ALLOWED_ORIGINS': 'http://127.0.0.1:*',
        'OUTBOX_NAMESPACE': project, 'GATEWAY_SESSION_NAMESPACE': project + ':gateway:sessions',
        'GRAFANA_USER': 'verification', 'GRAFANA_PASSWORD': secrets.token_hex(16),
        # Monitoring is not started in this synthetic acceptance test.
        'PROMETHEUS_IMAGE': 'unused-by-verification', 'GRAFANA_IMAGE': 'unused-by-verification',
        'JAVA_TOOL_OPTIONS': '-Xms64m -Xmx384m -XX:ActiveProcessorCount=2',
    }
    for name, default in INFRA_DEFAULTS.items():
        values[name] = os.environ.get(name, default)
        if not IMMUTABLE.fullmatch(values[name]):
            raise ValueError(f'{name} must identify immutable infrastructure content')
    values.update(release_images(release_env))
    for name in variables:
        if name.startswith('VOLUME_'):
            values[name] = project + '-' + name.removeprefix('VOLUME_').lower().replace('_', '-')
    missing = variables - values.keys()
    if missing:
        raise ValueError('Verification has no synthetic value for: ' + ','.join(sorted(missing)))
    # Ambient shell values must not override synthetic credentials or volume names.
    environment = {key: value for key, value in os.environ.items() if key not in variables}
    environment.update(values)
    env_path = work / 'synthetic.env'
    env_path.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
    env_path.chmod(0o600)
    result = run('docker', 'compose', '--project-name', project, '--env-file', env_path,
                 '-f', source, '--profile', 'migrate', 'config', '--format', 'json', capture=True, env=environment)
    config = json.loads(result.stdout)
    config['name'] = project
    for name in ('prometheus', 'grafana'):
        config['services'].pop(name, None)
    for name, service in config['services'].items():
        if 'build' in service or service.get('container_name'):
            raise RuntimeError('The release must support immutable images and replica-safe names')
        service.pop('ports', None)
        service['restart'] = 'no'
        if name in JAVA or name in MIGRATORS:
            service.setdefault('environment', {})['JAVA_TOOL_OPTIONS'] = values['JAVA_TOOL_OPTIONS']
            service['mem_limit'] = '768m'
            # The synthetic environment must never send real mail.
            service['environment']['SPRING_MAIL_HOST'] = '127.0.0.1'
            service['environment']['SPRING_MAIL_PORT'] = '9'
        if 'healthcheck' in service:
            service['healthcheck'].update(interval='5s', timeout='10s', retries=60, start_period='10s')
    bindings = {'gateway-proxy': ('gateway', 8080), 'frontend': ('frontend', 80),
                'eureka-server': ('eureka', 8761), 'minio': ('minio', 9000)}
    for name, (port_key, target) in bindings.items():
        config['services'][name]['ports'] = [{'target': target, 'published': str(ports[port_key]),
                                              'host_ip': '127.0.0.1', 'protocol': 'tcp'}]
    config['services']['frontend']['environment'].update(
        FRONTEND_API_BASE=values['FRONTEND_API_BASE'], PRIVATE_SENTINEL='must-never-reach-browser')
    variant = json.loads(json.dumps(config['services']['frontend']))
    variant['ports'][0]['published'] = str(ports['variant'])
    variant['environment'].update(FRONTEND_API_BASE='https://different-api.example',
                                  MINIO_PUBLIC_URL='https://different-media.example')
    config['services']['frontend-variant'] = variant
    used_volumes = {mount['source'] for service in config['services'].values()
                    for mount in service.get('volumes', []) if mount['type'] == 'volume'}
    config['volumes'] = {name: value for name, value in config.get('volumes', {}).items() if name in used_volumes}
    for volume in config['volumes'].values():
        if not volume.get('external') or not volume['name'].startswith(project + '-'):
            raise RuntimeError('A verification volume escaped its newly generated namespace')
    for network in config.get('networks', {}).values():
        network.pop('external', None)
        network['name'] = project + '-network'
    for service in config['services'].values():
        for mount in service.get('volumes', []):
            if mount['type'] == 'bind':
                path = Path(mount['source']).resolve()
                if not path.is_relative_to(ROOT) or not mount.get('read_only'):
                    raise RuntimeError('Only readonly checkout configuration may be bind-mounted')
    target = work / 'compose.synthetic.json'
    target.write_text(json.dumps(config, indent=2))
    target.chmod(0o600)
    return config, ports, values, ['docker', 'compose', '--project-name', project, '-f', str(target)]


def project_containers(project, service=None):
    args = ['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]
    if service:
        args += ['--filter', 'label=com.docker.compose.service=' + service]
    ids = run(*args, capture=True).stdout.split()
    return json_output('docker', 'inspect', *ids) if ids else []


def running(project, service):
    return [c for c in project_containers(project, service) if c['State']['Running']]


def registry(port, service, expected):
    request = urllib.request.Request(f'http://127.0.0.1:{port}/eureka/apps', headers={'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=8) as response:
        apps = json.load(response)['applications'].get('application', [])
    if isinstance(apps, dict):
        apps = [apps]
    for app in apps:
        if app['name'] == service.upper():
            instances = app['instance'] if isinstance(app['instance'], list) else [app['instance']]
            return len([instance for instance in instances if instance['status'] == 'UP']) == expected
    return expected == 0


def probe_routing(base):
    query = ('query{listUniversities{id} trendingFeed(size:1){posts{id}} '
             'getUserPosts(userId:"00000000-0000-0000-0000-000000000000",size:1){posts{id}}}')
    request = urllib.request.Request(base.rstrip('/') + '/graphql',
        data=json.dumps({'query': query}).encode(),
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    if result.get('errors'):
        raise RuntimeError('Anonymous routing probe failed')
    data = result.get('data', {})
    return all(field in data for field in ('listUniversities', 'trendingFeed', 'getUserPosts'))


def verify_images(project, config):
    for container in project_containers(project):
        service = container['Config']['Labels']['com.docker.compose.service']
        if not container['State']['Running']:
            continue
        expected = json_output('docker', 'image', 'inspect', config['services'][service]['image'])[0]['Id']
        if container['Image'] != expected:
            raise RuntimeError('Running content differs from the release: ' + service)


def smoke_scenario(project, ports):
    run(sys.executable, ROOT / 'ops/smoke.py', '--project', project, '--mode', 'isolated',
        '--base-url', f'http://127.0.0.1:{ports["gateway"]}', '--minio-base', f'http://127.0.0.1:{ports["minio"]}')


def media_roundtrip(project, ports):
    """Exercise Gateway -> User/Media discovery and bytes stored in synthetic MinIO."""
    cs = smoke.containers(project)
    user = str(uuid.uuid4())
    username = 'media_check_' + user.replace('-', '')[:12]
    smoke.sql(cs['postgres-user'], f"INSERT INTO users(id,email_google,username,name,created_at) "
              f"VALUES ('{user}','{username}@example.invalid','{username}','Media verification',now());")
    environment = dict(entry.split('=', 1) for entry in cs['api-gateway']['Config']['Env'] if '=' in entry)
    token = smoke.jwt(user, environment['JWT_SECRET'])
    base = f'http://127.0.0.1:{ports["gateway"]}'
    # A complete one-pixel PNG; no user media or credentials are involved.
    payload = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
    boundary = 'verification' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="pixel.png"\r\n'
            'Content-Type: image/png\r\n\r\n').encode() + payload + f'\r\n--{boundary}--\r\n'.encode()
    try:
        for _ in range(4):
            request = urllib.request.Request(base + '/api/upload?bucket=post-media', data=body,
                headers={'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Cookie': 'ACCESS_TOKEN=' + token})
            with urllib.request.urlopen(request, timeout=30) as response:
                url = json.load(response)['url']
            # MediaService normalizes the upload's user/object separator to '-'.
            if not url.startswith(f'http://127.0.0.1:{ports["minio"]}/post-media/{user}-'):
                raise RuntimeError('Media returned an unexpected public URL')
            with urllib.request.urlopen(url, timeout=15) as response:
                if response.read() != payload:
                    raise RuntimeError('Uploaded media bytes changed')
    finally:
        smoke.graphql(base, token, 'mutation{deleteAccount{success}}')
        smoke.wait_until(lambda: smoke.sql(cs['postgres-user'], f"SELECT count(*) FROM users WHERE id='{user}';") == '0')
    print('Media gRPC upload and public byte roundtrip succeeded', flush=True)


def scale_check(compose, project, config, ports, service):
    base = f'http://127.0.0.1:{ports["gateway"]}'
    run(*compose, 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '420',
        '--scale', service + '=2', service)
    wait(lambda: registry(ports['eureka'], service, 2), service + ' two discovery registrations')
    wait(lambda: probe_routing(base), service + ' routing with two replicas')
    verify_images(project, config)
    smoke_scenario(project, ports)
    if service == 'media-service':
        media_roundtrip(project, ports)
    replicas = sorted(running(project, service), key=lambda c: c['Config']['Labels'].get('com.docker.compose.container-number', ''))
    if len(replicas) != 2:
        raise RuntimeError('Incorrect replica count for ' + service)
    stopped = replicas[-1]['Id']
    # Concurrent readonly requests exercise shutdown while traffic is present.
    stop_readers = threading.Event()
    samples = {'ok': 0, 'transition_errors': 0}
    def reader():
        while not stop_readers.is_set():
            try:
                probe_routing(base)
                samples['ok'] += 1
            except Exception:
                samples['transition_errors'] += 1
            stop_readers.wait(0.1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(reader)
        start = time.monotonic()
        try:
            run('docker', 'stop', '-t', '60', stopped)
        finally:
            stop_readers.set()
            future.result(timeout=40)
    elapsed = round(time.monotonic() - start, 2)
    state = json_output('docker', 'inspect', stopped)[0]['State']
    if state.get('OOMKilled') or state['ExitCode'] not in (0, 143):
        raise RuntimeError('Replica did not complete SIGTERM shutdown: ' + service)
    logs = run('docker', 'logs', stopped, capture=True).stdout
    if 'Graceful shutdown complete' not in logs:
        raise RuntimeError('No HTTP graceful shutdown completion for ' + service)
    run(*compose, 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '420',
        '--scale', service + '=1', service)
    wait(lambda: registry(ports['eureka'], service, 1), service + ' one discovery registration')
    wait(lambda: probe_routing(base), service + ' routing after scale down')
    if service == 'media-service':
        media_roundtrip(project, ports)
    return {'service': service, 'replicas': '1->2->1', 'sigterm_seconds': elapsed, 'read_requests': samples}


def verify(release_env, output):
    local_daemon_only()
    project = 'unimeow-verify-' + uuid.uuid4().hex[:12]
    work = output.resolve() / project
    work.mkdir(parents=True, mode=0o700)
    config, ports, values, compose = prepare(release_env.resolve(), work, project)
    report = {'project': project, 'result': 'running', 'volumes_retained': [v['name'] for v in config['volumes'].values()],
              'application_images': release_images(release_env), 'scaling': []}
    started = False
    try:
        if project_containers(project):
            raise RuntimeError('The generated verification project already exists')
        for volume in config['volumes'].values():
            if run('docker', 'volume', 'inspect', volume['name'], capture=True, check=False).returncode == 0:
                raise RuntimeError('A supposedly fresh verification volume already exists')
            run('docker', 'volume', 'create', '--label', 'unimeow.verification=' + project, volume['name'], capture=True)
        for image in sorted({service['image'] for service in config['services'].values()}):
            if run('docker', 'image', 'inspect', image, capture=True, check=False).returncode != 0:
                run('docker', 'pull', '--platform', 'linux/amd64', image)
        started = True
        run(*compose, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '420', *INFRA)
        for attempt in (1, 2):
            for migrator in MIGRATORS:
                result = run(*compose, '--profile', 'migrate', 'run', '--rm', '--no-deps', '--pull', 'never', migrator, capture=True)
                (work / f'{migrator}-{attempt}.log').write_text(result.stdout + result.stderr)
        report['migrations'] = 'empty schemas and repeat succeeded for all three databases'
        apps = [*SERVICES, 'gateway-proxy', 'frontend-variant']
        run(*compose, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600', *apps)
        base = f'http://127.0.0.1:{ports["gateway"]}'
        wait(lambda: probe_routing(base), 'initial Eureka/gRPC routing')
        verify_images(project, config)
        public = endpoint(f'http://127.0.0.1:{ports["frontend"]}/runtime-config.json')
        variant = endpoint(f'http://127.0.0.1:{ports["variant"]}/runtime-config.json')
        if public != {'apiBase': base, 'minioPublicUrl': values['APP_MINIO_PUBLIC_URL']}:
            raise RuntimeError('Frontend did not use its runtime configuration or exported extra values')
        if variant != {'apiBase': 'https://different-api.example', 'minioPublicUrl': 'https://different-media.example'}:
            raise RuntimeError('The same frontend image did not accept the second runtime configuration')
        with urllib.request.urlopen(f'http://127.0.0.1:{ports["frontend"]}/runtime-config.json') as response:
            if 'no-store' not in response.headers.get('Cache-Control', ''):
                raise RuntimeError('Frontend runtime configuration can be cached')
        report['frontend'] = 'same image, two configurations, no-store, public allowlist'
        smoke_scenario(project, ports)
        for service in SCALABLE:
            report['scaling'].append(scale_check(compose, project, config, ports, service))
        victim = running(project, 'post-service')[0]['Id']
        run('docker', 'kill', '--signal', 'KILL', victim)
        state = json_output('docker', 'inspect', victim)[0]['State']
        if state['ExitCode'] != 137 or state.get('OOMKilled'):
            raise RuntimeError('SIGKILL test did not terminate the intended synthetic replica')
        run(*compose, 'up', '-d', '--force-recreate', '--no-deps', '--no-build', '--pull', 'never',
            '--wait', '--wait-timeout', '420', 'post-service')
        wait(lambda: registry(ports['eureka'], 'post-service', 1), 'Post replacement registration')
        wait(lambda: probe_routing(base), 'routing after SIGKILL replacement')
        smoke_scenario(project, ports)
        verify_images(project, config)
        report['sigkill'] = 'PostService replaced from the same immutable image; event flow recovered'
        report['drain'] = drain.drain(project, timeout_seconds=300)
        report['json_logs'] = {}
        for service in sorted(JAVA):
            container = running(project, service)[0]
            logs = run('docker', 'logs', container['Id'], capture=True)
            (work / f'{service}.log').write_text(logs.stdout + logs.stderr)
            records = [json.loads(line) for line in logs.stdout.splitlines() if line.startswith('{')]
            if not records or any('service' not in record or 'message' not in record for record in records):
                raise RuntimeError('Missing or malformed structured application logs: ' + service)
            report['json_logs'][service] = len(records)
        report['result'] = 'passed'
        print(json.dumps({'verification': 'passed', 'project': project, 'report': str(work / 'report.json')}), flush=True)
    except BaseException as error:
        report.update(result='failed', error_type=type(error).__name__, error=str(error))
        raise
    finally:
        if started:
            try:
                for container in project_containers(project):
                    name = container['Name'].lstrip('/')
                    logs = run('docker', 'logs', container['Id'], capture=True, check=False)
                    (work / f'{name}.log').write_text(logs.stdout + logs.stderr)
                run(*compose, 'stop', '-t', '60')
            except Exception as error:
                report['stop_error'] = type(error).__name__
        (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print('Synthetic volumes retained; no databases or volumes were removed: ' + project, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--release-env', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/release-verification')
    args = parser.parse_args()
    verify(args.release_env, args.output)
