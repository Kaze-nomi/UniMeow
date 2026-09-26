#!/usr/bin/env python3
"""Restore the full backup into new, isolated volumes. Never overwrites originals."""
import fcntl
import json
import os
from pathlib import Path
import subprocess
import time
from backup import atomic_json, inspect_project, run, validate_backup
from deploy import wait_healthy, INFRA, stop_applications

BASE=Path('/srv/unimeow')


def restore():
    state=json.loads((BASE/'state.json').read_text())
    if state['status']!='healthy':raise RuntimeError('Production must be healthy before restore rehearsal')
    backup=Path(state['backup'])
    manifest=validate_backup(backup)
    project='unimeow-restore-'+time.strftime('%Y%m%d%H%M%S',time.gmtime())
    work=BASE/'rehearsals'/project
    work.mkdir(parents=True,mode=0o700)
    os.chmod(work.parent,0o700)
    original=inspect_project(state['project'])
    baseline=Path(state['baseline_path'])
    config=json.loads((baseline/'compose.private.json').read_text())
    config['name']=project
    for name,volume in config['volumes'].items():
        source=volume['name']
        target=project+'-'+source
        if source not in manifest['volumes']:raise RuntimeError('Restore volume not in backup')
        existing=subprocess.run(['docker','volume','inspect',target],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if existing.returncode==0:raise RuntimeError('Refusing to overwrite an existing restore volume')
        run('docker','volume','create','--label','unimeow.restore='+project,target)
        mount=json.loads(run('docker','volume','inspect',target,capture=True))[0]['Mountpoint']
        # This target was just created, is empty, and its name cannot equal the source.
        if target==source or any(Path(mount).iterdir()):raise RuntimeError('Unsafe restore target')
        run('tar','--numeric-owner','--same-owner','--xattrs','--acls','-xpf',str(backup/'volumes'/f'{source}.tar'),'-C',mount)
        volume['name']=target
    for network in config['networks'].values():
        network.pop('name',None);network['internal']=True
    for name,s in config['services'].items():
        s.pop('container_name',None);s.pop('ports',None)
        s['restart']='no'
        if name not in INFRA:
            s['mem_limit']='512m'
            s['environment']['JAVA_TOOL_OPTIONS']='-Xmx256m -XX:ActiveProcessorCount=2 -Dspring.mail.host=127.0.0.1 -Dspring.mail.port=9'
            s['environment']['SPRING_MAIL_HOST']='127.0.0.1'
            s['environment']['SPRING_MAIL_PORT']='9'
            s['environment']['GOOGLE_CLIENT_ID']='isolated-restore'
            s['environment']['GOOGLE_CLIENT_SECRET']='isolated-restore'
        if name=='api-gateway':s['ports']=[{'target':8080,'published':'18081','host_ip':'127.0.0.1','protocol':'tcp'}]
        if name=='minio':s['ports']=[{'target':9000,'published':'19000','host_ip':'127.0.0.1','protocol':'tcp'}]
        if name=='frontend':s['ports']=[{'target':80,'published':'15173','host_ip':'127.0.0.1','protocol':'tcp'}]
    path=work/'compose.private.json'
    atomic_json(path,config)
    compose=['docker','compose','-p',project,'-f',str(path)]
    marker=BASE/'maintenance';marker.touch(mode=0o644);marker.chmod(0o644)
    apps=[c['Id'] for c in original if c['Config']['Labels']['com.docker.compose.service'] not in INFRA]
    infra=[c['Id'] for c in original if c['Config']['Labels']['com.docker.compose.service'] in INFRA]
    verified=False
    try:
        # One small server cannot safely run two complete Java stacks concurrently.
        stop_applications(original)
        run('docker','stop','-t','90',*infra)
        run(*compose,'up','-d','--no-build','--pull','never','--wait','--wait-timeout','480')
        restored={c['Config']['Labels']['com.docker.compose.service']:c for c in inspect_project(project)}
        gateway=restored['api-gateway']
        minio_ip=next(n['IPAddress'] for n in restored['minio']['NetworkSettings']['Networks'].values())
        run('nsenter','--target',str(gateway['State']['Pid']),'--net','python3',str(Path(__file__).with_name('smoke.py')),'--project',project,'--base-url','http://127.0.0.1:8080','--minio-base',f'http://{minio_ip}:9000','--read-only')
        # Kafka offsets and all private stores were restored in full, on the internal network.
        kafka=next(c for c in inspect_project(project) if c['Config']['Labels']['com.docker.compose.service']=='kafka')
        offsets=run('docker','exec',kafka['Id'],'/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:9092','--all-groups','--describe',capture=True)
        (work/'consumer-offsets.private.txt').write_text(offsets)
        verified=True
        atomic_json(work/'result.json',{'backup':str(backup),'project':project,'baseline_release':state['current_release'],'restore_verified':True,'network_internal':True,'external_notifications_disabled':True})
        print('Full isolated baseline restoration verified',flush=True)
    finally:
        run(*compose,'stop','-t','60')
        run('docker','start',*infra);wait_healthy(infra)
        eureka=[c['Id'] for c in original if c['Config']['Labels']['com.docker.compose.service']=='eureka-server']
        run('docker','start',*eureka);wait_healthy(eureka)
        run('docker','start',*apps);wait_healthy(apps,480)
        marker.unlink()
        if verified:
            state.update(restore_verified=True,rehearsal_path=str(work),rehearsal_project=project)
            atomic_json(BASE/'state.json',state)
        print('Original production running again; rehearsal containers stopped, volumes retained',flush=True)


if __name__=='__main__':
    os.umask(0o077)
    with (BASE/'deployment.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        restore()
