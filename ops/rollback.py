#!/usr/bin/env python3
"""Compatible code rollback. Never restores, deletes, or downgrades any store."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re

from backup import atomic_json, inspect_project, run, volumes_for
from deploy import BASE, INFRA, APPLICATIONS, stop_applications, transition, log
from release import validate


def compatible_rehearsal(state, target):
    source=state.get('target_release') or state['current_release']
    proof=state.get('upgrade_rehearsals',{}).get(source,{})
    if not (proof.get('verified') and proof.get('old_code_compatible')
            and proof.get('baseline_release')==target):
        raise RuntimeError('This exact rollback target has not passed an expanded-schema rehearsal')
    return source


def rollback(target):
    if not re.fullmatch(r'(?:baseline-[0-9]{8}|v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9][a-zA-Z0-9.-]{0,40})?)',target):
        raise ValueError('Invalid rollback target')
    state=json.loads((BASE/'state.json').read_text())
    if state['status']=='healthy' and state['current_release']==target:
        log('rollback_already_installed',release=target);return
    source=compatible_rehearsal(state,target)
    current=inspect_project(state['project'])
    if target.startswith('baseline-'):
        directory=Path(state['baseline_path'])
        manifest=json.loads((directory/'manifest.json').read_text())
        if manifest['release_id']!=target:raise RuntimeError('Baseline identity mismatch')
        path=directory/'compose.private.json'
        config=json.loads(path.read_text())
        for service in ('post-service','feed-service','notification-service'):
            config['services'][service]['environment']['SPRING_KAFKA_CONSUMER_PROPERTIES_ISOLATION_LEVEL']='read_committed'
        # Reuse exactly the baseline image contents; tags alone are not evidence.
        for service,item in manifest['images'].items():
            actual=json.loads(run('docker','image','inspect',config['services'][service]['image'],capture=True))[0]
            if actual['Id']!=item['image_id']:raise RuntimeError('Baseline image identity changed')
        path=BASE/'config/rollback.private.json';atomic_json(path,config)
        compose=['docker','compose','-p',state['project'],'-f',str(path)]
    else:
        directory=BASE/'releases'/target
        validate(json.loads((directory/'release.json').read_text()))
        compose=['docker','compose','-p',state['project'],'--env-file',str(BASE/'config/production.env'),
                 '--env-file',str(BASE/'config/installation.env'),'--env-file',str(directory/'release.env'),
                 '-f',str(directory/'compose.production.yml')]
        config=json.loads(run(*compose,'config','--format','json',capture=True))
    expected=set(json.loads((BASE/'config/installation.json').read_text())['volumes'])
    if {v['name'] for v in config['volumes'].values()}!=expected:
        raise RuntimeError('Rollback volume set does not match this installation')
    if any('build' in s for s in config['services'].values()):raise RuntimeError('Rollback cannot rebuild')
    for service,s in config['services'].items():
        if service in INFRA:continue
        run('docker','image','inspect',s['image'],capture=True)
    marker=BASE/'maintenance';marker.touch(mode=0o644);marker.chmod(0o644)
    transition(state,'rolling_back',rollback_from=source,rollback_target=target)
    try:
        stop_applications(current)
        run('python3',str(Path(__file__).with_name('smoke.py')),'--project',state['project'],
            '--snapshot',str(BASE/'config/rollback-data-before.json'))
        for service in APPLICATIONS:
            if service in config['services']:
                run(*compose,'up','-d','--no-build','--pull','never','--no-deps','--wait','--wait-timeout','1200',service)
        run('python3',str(Path(__file__).with_name('smoke.py')),'--project',state['project'],
            '--read-only','--base-url','http://127.0.0.1:8081','--compare-snapshot',str(BASE/'config/rollback-data-before.json'))
        transition(state,'healthy',current_release=target,previous_release=source,target_release=None,
                   runtime_path=str(directory),rollback_target=None)
        marker.unlink();log('rollback_traffic_opened',release=target,data_restored=False)
    except BaseException as error:
        transition(state,'rollback_failed',last_error_type=type(error).__name__)
        raise


if __name__=='__main__':
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('target')
    args=parser.parse_args()
    with (BASE/'deployment.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        rollback(args.target)
