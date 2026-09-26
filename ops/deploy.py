#!/usr/bin/env python3
"""Run by the restricted root entrypoint, never by a PR workflow.

All state transitions are journalled before mutation. A failed deployment keeps
its original good backup; retries never snapshot partially migrated stores.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

from backup import atomic_json, create_backup, inspect_project, run, validate_backup, volumes_for, recover_rotation
from drain import drain
from release import validate

BASE=Path('/srv/unimeow')
BACKUPS=Path('/srv/unimeow-backups')
INFRA={'postgres-user','postgres-post','postgres-notification','redis','kafka','minio','prometheus','grafana'}
APPLICATIONS=['eureka-server','media-service','user-service','post-service','feed-service','notification-service','api-gateway','frontend','gateway-proxy']
INGRESS={'api-gateway','frontend','gateway-proxy'}
MIGRATORS={'user-migrate':'user-service','post-migrate':'post-service','notification-migrate':'notification-service'}


def log(event, **fields):
    record={'at':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'event':event,**fields}
    with (BASE/'deployments.jsonl').open('a') as out:
        out.write(json.dumps(record)+'\n');out.flush();os.fsync(out.fileno())
    print(json.dumps(record),flush=True)


def transition(state, status, **fields):
    state.update(status=status,**fields)
    atomic_json(BASE/'state.json',state)
    log(status,release=state.get('target_release') or state.get('current_release'))


def should_make_backup(state):
    return state['status']=='healthy'


def stop_applications(containers):
    clients=[c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service'] not in INFRA|{'eureka-server'}]
    discovery=[c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service']=='eureka-server']
    if clients:run('docker','stop','-t','60',*clients)
    if discovery:run('docker','stop','-t','60',*discovery)


def stop_ingress(containers):
    ids=[c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service'] in INGRESS]
    if ids:run('docker','stop','-t','60',*ids)


def verify_config_images(config, manifest):
    expected={**manifest['images'], **manifest['infrastructure_images'],
              **{name:manifest['images'][app] for name,app in MIGRATORS.items()}}
    for service,image in expected.items():
        if config.get('services',{}).get(service,{}).get('image')!=image:
            raise RuntimeError('Resolved image differs from the release manifest: '+service)


def reconcile_backup(state):
    """A completed rotation may have retired state.backup before its update."""
    recover_rotation(BACKUPS)
    pointer=BACKUPS/'current'
    if not pointer.is_symlink():raise RuntimeError('No canonical current backup pointer')
    current=pointer.resolve()
    if current.parent!=BACKUPS.resolve() or not current.name.startswith('backup-'):
        raise RuntimeError('Current backup pointer is outside the backup directory')
    validate_backup(current)
    state['backup']=str(current)


def configuration_paths(state):
    paths=[BASE/'config',Path('/root/UniMeow/docker-compose.yml'),Path('/root/UniMeow/.env'),
           Path('/root/UniMeow/Monitoring'),Path('/etc/nginx'),Path('/etc/letsencrypt'),BASE/'state.json']
    if state['current_release'].startswith('baseline-'):
        baseline=Path(state['baseline_path'])
        if baseline.resolve()!=BASE/state['current_release']:
            raise RuntimeError('Previous baseline runtime path is not canonical')
        paths.extend([baseline/'compose.private.json',baseline/'manifest.json'])
    else:
        previous=BASE/'releases'/state['current_release']
        if Path(state.get('runtime_path','')).resolve()!=previous or previous.resolve()!=previous:
            raise RuntimeError('Previous installed runtime path is not canonical')
        # Runtime bundles contain configuration/code, never image archives.
        paths.append(previous)
    if (BASE/'deployments.jsonl').is_file():paths.append(BASE/'deployments.jsonl')
    return [str(path) for path in paths]


def verify_installed(project, config):
    """Verify one live, healthy container for each required service and exact images."""
    required=INFRA|set(APPLICATIONS)
    def cohort():
        groups={name:[] for name in required}
        for container in inspect_project(project):
            name=container['Config']['Labels']['com.docker.compose.service']
            if name in groups:groups[name].append(container)
        for name,containers in groups.items():
            if len(containers)!=1:
                raise RuntimeError('Expected exactly one installed container for '+name)
        return {name:containers[0] for name,containers in groups.items()}
    initial=cohort()
    identities={name:c['Id'] for name,c in initial.items()}
    wait_healthy(list(identities.values()))
    installed=cohort()
    if {name:c['Id'] for name,c in installed.items()}!=identities:
        raise RuntimeError('Installed container identities changed during final readiness verification')
    image_ids={}
    for name,container in installed.items():
        state=container.get('State',{})
        if not state.get('Running') or state.get('Health',{}).get('Status')!='healthy':
            raise RuntimeError('Installed service is not running and healthy: '+name)
        reference=config['services'][name]['image']
        if reference not in image_ids:
            image_ids[reference]=json.loads(run('docker','image','inspect',reference,capture=True))[0]['Id']
        if container['Image']!=image_ids[reference]:
            raise RuntimeError('Running image does not match the resolved release: '+name)


def wait_healthy(ids, seconds=1200):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        containers=json.loads(run('docker','inspect',*ids,capture=True))
        if all(c['State']['Running'] and c['State'].get('Health',{}).get('Status','healthy')=='healthy' for c in containers):return
        if any(c['State'].get('OOMKilled') for c in containers):raise RuntimeError('A service was killed for exceeding memory')
        time.sleep(3)
    unhealthy=[c['Name'] for c in containers if not c['State']['Running'] or c['State'].get('Health',{}).get('Status','healthy')!='healthy']
    raise RuntimeError('Readiness timeout: '+','.join(unhealthy))


def start_original(containers):
    """Restore observed containers one at a time on the small production host."""
    order=[*sorted(INFRA),'eureka-server','media-service','user-service','post-service',
           'feed-service','notification-service','api-gateway','frontend','gateway-proxy']
    for service in order:
        ids=[c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service']==service]
        if ids:
            run('docker','start',*ids)
            wait_healthy(ids)


def deploy(release_dir):
    release_dir=Path(release_dir).resolve()
    manifest=validate(json.loads((release_dir/'release.json').read_text()))
    if release_dir.parent!=BASE/'releases' or release_dir.name!=manifest['release_id']:
        raise RuntimeError('Release must be installed under the canonical releases directory')
    state=json.loads((BASE/'state.json').read_text())
    if not state.get('restore_verified'):
        raise RuntimeError('Baseline restore rehearsal is not verified; production deployment blocked')
    if state['status']!='healthy' and state.get('target_release')!=manifest['release_id']:
        raise RuntimeError('A previous deployment needs recovery; choose its release or compatible rollback first')
    project=state['project']
    compose=['docker','compose','--project-name',project,'--env-file',str(BASE/'config/production.env'),'--env-file',str(BASE/'config/installation.env'),'--env-file',str(release_dir/'release.env'),'-f',str(release_dir/'compose.production.yml')]
    config=json.loads(run(*compose,'--profile','migrate','config','--format','json',capture=True))
    if any('build' in s for s in config['services'].values()):raise RuntimeError('Production cannot build images')
    verify_config_images(config,manifest)
    gateway_env=config['services']['api-gateway']['environment']
    if gateway_env['APP_SECURITY_SECURE_COOKIE'].lower()!='true':raise RuntimeError('Production requires secure cookies')
    public_urls=[gateway_env['APP_SECURITY_OAUTH2_SUCCESS_REDIRECT'],config['services']['frontend']['environment']['FRONTEND_API_BASE'],config['services']['frontend']['environment']['MINIO_PUBLIC_URL']]
    if any(not url.startswith('https://') or 'localhost' in url or '127.0.0.1' in url for url in public_urls):
        raise RuntimeError('Production public URLs must be explicit HTTPS addresses')
    if state['status']=='healthy' and state['current_release']==manifest['release_id']:
        verify_installed(project,config)
        if (BASE/'maintenance').exists():
            run('python3',str(release_dir/'ops/smoke.py'),'--mode','production','--read-only','--project',project,'--base-url','http://127.0.0.1:8081')
            (BASE/'maintenance').unlink()
        log('already_installed',release=manifest['release_id']);return
    expected=json.loads((BASE/'config/installation.json').read_text())
    actual_volumes={v['name'] for v in config['volumes'].values()}
    if actual_volumes!=set(expected['volumes']):raise RuntimeError('Release volumes differ from the recorded installation')
    for name in actual_volumes:run('docker','volume','inspect',name,capture=True)
    # Only the manifest's explicitly approved MinIO upgrade may change infrastructure.
    # Download it before maintenance; do not start it until the fresh backup is complete.
    for image in [*manifest['images'].values(),*manifest['infrastructure_images'].values(),config['services']['gateway-proxy']['image']]:
        run('docker','pull','--platform','linux/amd64',image)
    current=inspect_project(project)
    if state['status']=='healthy':
        current=[c for c in current if c.get('State',{}).get('Running',True)]
    # These phases occur before any schema mutation. A crash here can leave an old
    # state.backup value; recover the original containers and take a fresh copy.
    if state['status'] in ['closing_traffic','backing_up','backup_recovery_failed']:
        reconcile_backup(state)
        original=json.loads((BASE/'config/pre-deploy-containers.private.json').read_text())
        start_original(original)
        transition(state,'healthy',target_release=None)
        current=[c for c in inspect_project(project) if c.get('State',{}).get('Running',True)]
    old_infra={c['Config']['Labels']['com.docker.compose.service']:c for c in current if c['Config']['Labels']['com.docker.compose.service'] in INFRA}
    if set(old_infra)!=INFRA:raise RuntimeError('Missing original infrastructure containers')
    for name,c in old_infra.items():
        info=json.loads(run('docker','image','inspect',config['services'][name]['image'],capture=True))[0]
        if info['Id']!=c['Image'] and name not in manifest['infrastructure_images']:
            raise RuntimeError('This deployment cannot change infrastructure versions: '+name)
    if state['status']=='healthy' and state['current_release'].startswith('baseline-'):
        proof=state.get('upgrade_rehearsals',{}).get(manifest['release_id'],{})
        if not (proof.get('verified') and proof.get('old_code_compatible')
                and proof.get('release_commit')==manifest['commit'] and proof.get('images')==manifest['images']
                and proof.get('infrastructure_images')==manifest['infrastructure_images']):
            from upgrade_rehearsal import rehearse
            log('upgrade_rehearsal_started',release=manifest['release_id'])
            rehearse(release_dir,resolved_config=config)
            state=json.loads((BASE/'state.json').read_text())
            current=[c for c in inspect_project(project) if c.get('State',{}).get('Running',True)]
            log('upgrade_rehearsal_complete',release=manifest['release_id'])
    making_backup=should_make_backup(state)
    previous=state['current_release']
    state['target_release']=manifest['release_id']
    if making_backup:
        # The complete observed starting state is retained even after containers are recreated.
        atomic_json(BASE/'config/pre-deploy-containers.private.json',current)
        transition(state,'closing_traffic')
    else:
        validate_backup(state['backup'])
        log('retry_preserving_backup',backup=state['backup'])
    marker=BASE/'maintenance';marker.touch(mode=0o644);marker.chmod(0o644)
    infra_ids=[c['Id'] for c in old_infra.values()]
    mutated=False
    try:
        if making_backup:
            # Let active HTTP finish, then drain asynchronous consequences while
            # publishers and consumers are still alive. No new ingress can write.
            stop_ingress(current)
            drain(project)
            stop_applications([c for c in current if c['Config']['Labels']['com.docker.compose.service'] not in INGRESS])
            drain(project,require_active=False,stable_samples=1)
            # Capture fingerprints only after all application writers have drained.
            run('python3',str(release_dir/'ops/smoke.py'),'--project',project,'--snapshot',str(BASE/'config/data-before.json'))
            transition(state,'backing_up')
            run('docker','stop','-t','90',*infra_ids)
            config_paths=configuration_paths(state)
            backup=create_backup(BACKUPS,project,previous,current,config_paths,state['baseline_path'])
            transition(state,'backup_verified',backup=str(backup),previous_release=previous)
        else:
            # Retrying a migrated installation must preserve its pre-change copy.
            stop_applications(current)
        run('docker','start',*infra_ids)
        wait_healthy(infra_ids)
        # Adopt the durable Redis command using the SAME image and external volume.
        # This phase is retryable but must never replace the pre-change backup.
        transition(state,'configuring_stores')
        mutated=True
        run(*compose,'up','-d','--no-build','--pull','never','--no-deps','--wait','redis')
        run(*compose,'up','-d','--no-build','--pull','never','--no-deps','--wait','--wait-timeout','1200','minio')
        # This durable marker precedes the first possibly schema-changing operation.
        transition(state,'migrating')
        for service in manifest['migration_services']:
            run(*compose,'--profile','migrate','run','--rm','--no-deps','--pull','never',service)
            log('migration_complete',service=service,release=manifest['release_id'])
        transition(state,'starting')
        for service in APPLICATIONS:
            run(*compose,'up','-d','--no-build','--pull','never','--no-deps','--wait','--wait-timeout','1200',service)
        transition(state,'verifying')
        # Only local backend endpoints are used while the public nginx gate is closed.
        run('python3',str(release_dir/'ops/smoke.py'),'--mode','production','--project',project,'--base-url','http://127.0.0.1:8081')
        verify_installed(project,config)
        # Journal the fully verified installation before exposing it to traffic.
        transition(state,'healthy',current_release=manifest['release_id'],previous_release=previous,
                   installed_commit=manifest['commit'],runtime_path=str(release_dir),target_release=None)
        marker.unlink()
        log('traffic_opened',release=manifest['release_id'])
    except BaseException as error:
        log('failed',release=manifest['release_id'],error_type=type(error).__name__,data_may_have_changed=mutated)
        if state['status']=='healthy' and state['current_release']==manifest['release_id']:
            # The verified installation is durable already; a late marker/log
            # failure must remain eligible for the idempotent maintenance retry.
            raise
        if not mutated and making_backup:
            # No migrations ran: restart the exact prior containers, without pull/build/recreate.
            try:
                reconcile_backup(state)
                start_original(current)
                transition(state,'healthy',target_release=None)
                marker.unlink(missing_ok=True)
                log('original_containers_restored')
            except BaseException as recovery_error:
                transition(state,'backup_recovery_failed',last_error_type=type(recovery_error).__name__)
                raise RuntimeError('Pre-migration recovery is incomplete; maintenance remains closed') from recovery_error
        else:
            transition(state,'failed',last_error_type=type(error).__name__)
        raise


if __name__=='__main__':
    os.umask(0o077)
    parser=argparse.ArgumentParser()
    parser.add_argument('release_directory')
    args=parser.parse_args()
    with (BASE/'deployment.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        deploy(args.release_directory)
