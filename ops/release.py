#!/usr/bin/env python3
"""Produce and validate the immutable, public portion of a release."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile

SERVICES = {
    'api-gateway': 'APIGateway', 'eureka-server': 'Eureka',
    'user-service': 'UserService', 'post-service': 'PostService',
    'feed-service': 'FeedService', 'notification-service': 'NotificationService',
    'media-service': 'MediaService', 'frontend': 'Frontend',
}
RELEASE_ID = re.compile(r'^v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9][a-zA-Z0-9.-]{0,40})?$')
IMAGE = re.compile(r'^ghcr\.io/[a-z0-9-]+/unimeow-[a-z-]+@sha256:[0-9a-f]{64}$')
APPROVED_MINIO_IMAGE = ('ghcr.io/coollabsio/minio:RELEASE.2025-10-15T17-29-55Z@'
                        'sha256:4f75fd76598afa23919555d1363e1fb13c632d9bcd4ce8edcf21d3cca2ed0579')


def validate(manifest):
    if not RELEASE_ID.fullmatch(manifest.get('release_id', '')):
        raise ValueError('Invalid release ID; expected vMAJOR.MINOR.PATCH[-suffix]')
    if not re.fullmatch('[0-9a-f]{40}', manifest.get('commit', '')):
        raise ValueError('Release must identify a full source commit')
    if manifest.get('architecture') != 'linux/amd64':
        raise ValueError('Unsupported production architecture')
    if set(manifest.get('images', {})) != set(SERVICES):
        raise ValueError('Release does not contain all eight application images')
    for service, image in manifest['images'].items():
        if not IMAGE.fullmatch(image) or f'/unimeow-{service}@' not in image:
            raise ValueError('Image reference must match its service and exact GHCR digest')
    if manifest.get('migration_services') != ['user-migrate','post-migrate','notification-migrate']:
        raise ValueError('Release must contain exactly the three approved migration services')
    if manifest.get('infrastructure_images') != {'minio': APPROVED_MINIO_IMAGE}:
        raise ValueError('Only the explicitly approved immutable MinIO infrastructure image is permitted')
    return manifest


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def make_release(root, output, release_id, commit, build_id, digest_dir, owner):
    output.mkdir(parents=True, exist_ok=True)
    images={}
    for service in SERVICES:
        value=(digest_dir / f'{service}.txt').read_text().strip()
        if not re.fullmatch('sha256:[0-9a-f]{64}',value):
            raise ValueError(f'Invalid image digest for {service}')
        images[service]=f'ghcr.io/{owner.lower()}/unimeow-{service}@{value}'
    manifest=validate({'format':1,'release_id':release_id,'commit':commit,
        'build_id':build_id,'architecture':'linux/amd64','images':images,
        'infrastructure_images':{'minio':APPROVED_MINIO_IMAGE},
        'migration_services':['user-migrate','post-migrate','notification-migrate'],
        'compatibility':{
            'schema':'Additive migrations. Applied historical migrations are unchanged.',
            'publisher_cutover':'Stop every old publisher before starting this release.',
            'rollback':'One replica per old publisher; preserve read_committed. Rehearse old image against expanded schema before rollback.',
            'data_restore':'Never automatically restore data after opening traffic. Preserve all stores as one consistent set.',
            'minio':'Only the declared MinIO image may replace existing infrastructure, after a complete backup and restored-data compatibility rehearsal.',
            'configuration':'Private server config preserves JWT/OAuth, cookies, public URLs, existing project and external volumes.'}})
    (output/'release.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (output/'release.env').write_text(''.join(f'{s.upper().replace("-","_")}_IMAGE={v}\n' for s,v in images.items()))
    with tarfile.open(output/'runtime.tar.gz','w:gz') as archive:
        for name in ['compose.production.yml','ops','Monitoring']:
            source=root/name
            if source.is_file():
                archive.add(source,arcname=name)
            else:
                for path in sorted(source.rglob('*')):
                    if path.is_file() and '__pycache__' not in path.parts and path.suffix!='.pyc':
                        archive.add(path,arcname=path.relative_to(root).as_posix())
        for name in ['release.json','release.env']:
            archive.add(output/name,arcname=name)
    (output/'SHA256SUMS').write_text(''.join(f'{digest(output/n)}  {n}\n' for n in ['release.json','release.env','runtime.tar.gz']))
    return manifest


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--release-id',required=True)
    p.add_argument('--commit',required=True)
    p.add_argument('--build-id',required=True)
    p.add_argument('--owner',required=True)
    p.add_argument('--digests',type=Path,default=Path('digests'))
    p.add_argument('--output',type=Path,default=Path('release-output'))
    args=p.parse_args()
    make_release(Path.cwd(),args.output,args.release_id,args.commit,args.build_id,args.digests,args.owner)
