#!/usr/bin/env python3
"""Record discovered production details once; no new user-facing env knobs."""
import json
import os
from pathlib import Path
import uuid
from backup import atomic_json, inspect_project, run


def prepare(base=Path('/srv/unimeow')):
    state=json.loads((base/'state.json').read_text())
    if state['status']!='healthy' or not state.get('backup'):
        raise RuntimeError('A healthy backed-up baseline is required before installation setup')
    target=base/'config';target.mkdir(mode=0o700,exist_ok=True)
    if (target/'installation.json').exists():
        print('Installation metadata already exists; preserving identifiers')
        return
    cs={c['Config']['Labels']['com.docker.compose.service']:c for c in inspect_project(state['project'])}
    envs={s:dict(v.split('=',1) for v in c['Config']['Env'] if '=' in v) for s,c in cs.items()}
    user=envs['user-service'];gateway=envs['api-gateway'];media=envs['media-service']
    values={
        'DB_USERNAME':user['SPRING_DATASOURCE_USERNAME'],'DB_PASSWORD':user['SPRING_DATASOURCE_PASSWORD'],
        'MINIO_ROOT_USER':envs['minio']['MINIO_ROOT_USER'],'MINIO_ROOT_PASSWORD':envs['minio']['MINIO_ROOT_PASSWORD'],
        'MAIL_USERNAME':user['SPRING_MAIL_USERNAME'],'MAIL_PASSWORD':user['SPRING_MAIL_PASSWORD'],
        'GOOGLE_CLIENT_ID':gateway['GOOGLE_CLIENT_ID'],'GOOGLE_CLIENT_SECRET':gateway['GOOGLE_CLIENT_SECRET'],
        'JWT_SECRET':gateway['JWT_SECRET'],
        'APP_PUBLIC_URL':gateway['APP_SECURITY_OAUTH2_SUCCESS_REDIRECT'].rstrip('/'),
        'APP_MINIO_PUBLIC_URL':media['MINIO_PUBLIC_URL'],
        'APP_SECURITY_SECURE_COOKIE':gateway['APP_SECURITY_SECURE_COOKIE'],
        'APP_SECURITY_ALLOWED_ORIGINS':gateway['APP_SECURITY_ALLOWED_ORIGINS'],
        'GRAFANA_USER':envs['grafana']['GF_SECURITY_ADMIN_USER'],'GRAFANA_PASSWORD':envs['grafana']['GF_SECURITY_ADMIN_PASSWORD'],
    }
    # Compose supports literal single-quoted values, including dollar signs.
    def write_env(path,items):
        if any('\n' in str(v) or '\r' in str(v) or "'" in str(v) for v in items.values()):
            raise RuntimeError('Unsupported quoted environment value; use a JSON Compose override instead')
        path.write_text(''.join(f"{k}='{v}'\n" for k,v in items.items()));path.chmod(0o600)
    write_env(target/'production.env',values)
    install_id=uuid.uuid4().hex
    generated={'COMPOSE_PROJECT_NAME':state['project'],'OUTBOX_NAMESPACE':'unimeow-'+install_id,
               'GATEWAY_SESSION_NAMESPACE':'unimeow:'+install_id+':gateway:sessions'}
    infra={'postgres-user':'POSTGRES','redis':'REDIS','kafka':'KAFKA','minio':'MINIO','prometheus':'PROMETHEUS','grafana':'GRAFANA'}
    for service,key in infra.items():
        info=json.loads(run('docker','image','inspect',cs[service]['Image'],capture=True))[0]
        generated[key+'_IMAGE']=(info.get('RepoDigests') or [cs[service]['Image']])[0]
    mounts={
      ('postgres-user','/var/lib/postgresql/data'):'POSTGRES_USER_DATA',
      ('postgres-post','/var/lib/postgresql/data'):'POSTGRES_POST_DATA',
      ('postgres-notification','/var/lib/postgresql/data'):'POSTGRES_NOTIFICATION_DATA',
      ('redis','/data'):'REDIS_DATA',('minio','/data'):'MINIO_DATA',
      ('kafka','/var/lib/kafka/data'):'KAFKA_DATA',('kafka','/etc/kafka/secrets'):'KAFKA_SECRETS',
      ('kafka','/mnt/shared/config'):'KAFKA_CONFIG',('prometheus','/prometheus'):'PROMETHEUS_DATA',
      ('grafana','/var/lib/grafana'):'GRAFANA_DATA'}
    volumes=[]
    for (service,destination),key in mounts.items():
        mount=next(m for m in cs[service]['Mounts'] if m['Destination']==destination)
        if mount['Type']!='volume':raise RuntimeError('Unexpected production data mount type')
        generated['VOLUME_'+key]=mount['Name'];volumes.append(mount['Name'])
    write_env(target/'installation.env',generated)
    atomic_json(target/'installation.json',{'installation_id':install_id,'project':state['project'],'volumes':volumes,'created_from_release':state['current_release']})
    print('Original application settings preserved; infrastructure metadata generated automatically')


if __name__=='__main__':
    os.umask(0o077)
    prepare()
