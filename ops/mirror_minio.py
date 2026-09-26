#!/usr/bin/env python3
"""Mirror only the verified upstream MinIO image; never copy production data."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

source=Path(sys.argv[1])
target=sys.argv[2]
if not re.fullmatch(r'ghcr\.io/[a-z0-9-]+/unimeow-minio:baseline-20260926',target):
    raise SystemExit('Unexpected mirror destination')
manifest=json.loads((source/'baseline.json').read_text())
with (source/'minio-image.tar').open('rb') as stream:
    if hashlib.file_digest(stream,'sha256').hexdigest()!=manifest['minio_archive_sha256']:
        raise SystemExit('Baseline MinIO archive checksum mismatch')
subprocess.run(['docker','load','-i',str(source/'minio-image.tar')],check=True,stdout=sys.stderr)
tag=manifest['minio_export_tag']
image=json.loads(subprocess.check_output(['docker','image','inspect',tag]))[0]
if image['RootFS']['Layers']!=manifest['minio_rootfs_layers'] or image['Architecture']!='amd64':
    raise SystemExit('MinIO image content differs from protected baseline')
subprocess.run(['docker','tag',tag,target],check=True)
subprocess.run(['docker','push',target],check=True,stdout=sys.stderr)
image=json.loads(subprocess.check_output(['docker','image','inspect',target]))[0]
repository=target.split(':')[0]
digest=next(value for value in image['RepoDigests'] if value.startswith(repository+'@sha256:'))
print('MINIO_IMAGE='+digest)
