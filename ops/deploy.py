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
from release import validate

BASE=Path('/srv/unimeow')
INFRA={'postgres-user','postgres-post','postgres-notification','redis','kafka','minio','prometheus','grafana'}
APPLICATIONS=['eureka-server','media-service','user-service','post-service','feed-service','notification-service','api-gateway','frontend','gateway-proxy']


def log(event, **fields):
    record={'at':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'event':event,**fields}
    with (BASE/'deployments.jsonl').open('a') as out:
        out.write(json.dumps(record)+'\n');out.flush();os.fsync(out.fileno())
    print(json.dumps(record),flush=True)


def transition(state, status, **fields):
    state.update(status=status,**fields)
    atomic_json(BASE/'state.json',state)
    log(status,release=state.get('target_release',state.get('current_release')))


def should_make_backup(state):
    return state['status']=='healthy'


def stop_applications(containers):
    clients=[c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service'] not in INFRA|{'eureka-server'}]
    discovery=[c['Id'] for c in containers if c['Config']['Labels']['com.docker.compose.service']=='eureka-server']
    if clients:run('docker','stop','-t','60',*clients)
    if discovery:run('docker','stop','-t','60',*discovery)


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
    if state['status']=='healthy' and state['current_release']==manifest['release_id']:
        if (BASE/'maintenance').exists():
            wait_healthy([c['Id'] for c in inspect_project(state['project'])])
            run('python3',str(release_dir/'ops/smoke.py'),'--mode','production','--read-only','--project',state['project'],'--base-url','http://127.0.0.1:8081')
            (BASE/'maintenance').unlink()
        log('already_installed',release=manifest['release_id']);return
    project=state['project']
    compose=['docker','compose','--project-name',project,'--env-file',str(BASE/'config/production.env'),'--env-file',str(BASE/'config/installation.env'),'--env-file',str(release_dir/'release.env'),'-f',str(release_dir/'compose.production.yml')]
    config=json.loads(run(*compose,'config','--format','json',capture=True))
    if any('build' in s for s in config['services'].values()):raise RuntimeError('Production cannot build images')
    gateway_env=config['services']['api-gateway']['environment']
    if gateway_env['APP_SECURITY_SECURE_COOKIE'].lower()!='true':raise RuntimeError('Production requires secure cookies')
    public_urls=[gateway_env['APP_SECURITY_OAUTH2_SUCCESS_REDIRECT'],config['services']['frontend']['environment']['FRONTEND_API_BASE'],config['services']['frontend']['environment']['MINIO_PUBLIC_URL']]
    if any(not url.startswith('https://') or 'localhost' in url or '127.0.0.1' in url for url in public_urls):
        raise RuntimeError('Production public URLs must be explicit HTTPS addresses')
    expected=json.loads((BASE/'config/installation.json').read_text())
    actual_volumes={v['name'] for v in config['volumes'].values()}
    if actual_volumes!=set(expected['volumes']):raise RuntimeError('Release volumes differ from the recorded installation')
    for name in actual_volumes:run('docker','volume','inspect',name,capture=True)
    # Pull immutable application images and proxy before closing traffic. Infrastructure is unchanged.
    for image in [*manifest['images'].values(),config['services']['gateway-proxy']['image']]:
        run('docker','pull','--platform','linux/amd64',image)
    current=inspect_project(project)
    # These phases occur before any schema mutation. A crash here can leave an old
    # state.backup value; recover the original containers and take a fresh copy.
    if state['status'] in ['closing_traffic','backing_up']:
        recover_rotation('/srv/unimeow-backups')
        original=json.loads((BASE/'config/pre-deploy-containers.private.json').read_text())
        original_infra=[c['Id'] for c in original if c['Config']['Labels']['com.docker.compose.service'] in INFRA]
        original_apps=[c['Id'] for c in original if c['Config']['Labels']['com.docker.compose.service'] not in INFRA]
        start_original(original)
        transition(state,'healthy',target_release=None)
        current=inspect_project(project)
    old_infra={c['Config']['Labels']['com.docker.compose.service']:c for c in current if c['Config']['Labels']['com.docker.compose.service'] in INFRA}
    if set(old_infra)!=INFRA:raise RuntimeError('Missing original infrastructure containers')
    for name,c in old_infra.items():
        info=json.loads(run('docker','image','inspect',config['services'][name]['image'],capture=True))[0]
        if info['Id']!=c['Image']:raise RuntimeError('This deployment cannot change infrastructure versions: '+name)
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
    app_ids=[c['Id'] for c in current if c['Config']['Labels']['com.docker.compose.service'] not in INFRA]
    infra_ids=[c['Id'] for c in old_infra.values()]
    mutated=False
    try:
        stop_applications(current)
        if making_backup:
            # Capture fingerprints only after all application writers have drained.
            run('python3',str(release_dir/'ops/smoke.py'),'--project',project,'--snapshot',str(BASE/'config/data-before.json'))
            transition(state,'backing_up')
            run('docker','stop','-t','90',*infra_ids)
            config_paths=[str(BASE/'config'),'/root/UniMeow/docker-compose.yml','/root/UniMeow/.env','/root/UniMeow/Monitoring','/etc/nginx','/etc/letsencrypt']
            backup=create_backup('/srv/unimeow-backups',project,previous,current,config_paths,state['baseline_path'])
            transition(state,'backup_verified',backup=str(backup),previous_release=previous)
        run('docker','start',*infra_ids)
        wait_healthy(infra_ids)
        # Adopt the durable Redis command using the SAME image and external volume.
        # This phase is retryable but must never replace the pre-change backup.
        transition(state,'configuring_stores')
        mutated=True
        run(*compose,'up','-d','--no-build','--pull','never','--no-deps','--wait','redis')
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
        installed=inspect_project(project)
        for c in installed:
            service=c['Config']['Labels']['com.docker.compose.service']
            if service in manifest['images']:
                image=json.loads(run('docker','image','inspect',manifest['images'][service],capture=True))[0]
                if image['Id']!=c['Image']:raise RuntimeError('Running image does not match release: '+service)
        # Journal the fully verified installation before exposing it to traffic.
        transition(state,'healthy',current_release=manifest['release_id'],previous_release=previous,
                   installed_commit=manifest['commit'],runtime_path=str(release_dir),target_release=None)
        marker.unlink()
        log('traffic_opened',release=manifest['release_id'])
    except BaseException as error:
        log('failed',release=manifest['release_id'],error_type=type(error).__name__,data_may_have_changed=mutated)
        if not mutated and making_backup:
            # No migrations ran: restart the exact prior containers, without pull/build/recreate.
            start_original(current)
            transition(state,'healthy',target_release=None)
            marker.unlink(missing_ok=True)
            log('original_containers_restored')
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
