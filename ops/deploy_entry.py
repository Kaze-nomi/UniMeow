#!/usr/bin/env python3
"""Installed root-owned entrypoint for the dedicated, restricted SSH account."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile

BASE=Path('/srv/unimeow')


def install(release_id):
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9][a-zA-Z0-9.-]{0,40})?',release_id):
        raise ValueError('Invalid release identifier')
    incoming=BASE/'incoming'/release_id
    if incoming.resolve()!=incoming or not incoming.is_dir():raise ValueError('Unsafe incoming directory')
    checks={}
    for line in (incoming/'SHA256SUMS').read_text().splitlines():
        value,name=line.split(None,1);name=name.strip()
        if name not in ['release.json','release.env','runtime.tar.gz'] or not re.fullmatch('[0-9a-f]{64}',value) or name in checks:
            raise ValueError('Invalid release checksum manifest')
        checks[name]=value
    if set(checks)!={'release.json','release.env','runtime.tar.gz'}:raise ValueError('Incomplete release')
    # First copy verified input into a private root-owned directory. The SSH user
    # cannot change an archive between checksum verification and extraction.
    staging=BASE/'releases'/('.incoming-'+release_id)
    if staging.exists():
        if staging.is_symlink() or staging.resolve().parent!=BASE/'releases' or staging.stat().st_uid!=0:
            raise ValueError('Unsafe staging directory')
        import shutil
        shutil.rmtree(staging)  # Only an interrupted root-owned release extraction.
    staging.mkdir(mode=0o700,parents=True,exist_ok=False)
    for name,digest in checks.items():
        data=(incoming/name).read_bytes()
        if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Release checksum mismatch')
        (staging/name).write_bytes(data)
    with tarfile.open(staging/'runtime.tar.gz','r:gz') as archive:
        names=set()
        if sum(member.size for member in archive.getmembers())>20*1024*1024:
            raise ValueError('Runtime configuration bundle is unexpectedly large')
        for member in archive.getmembers():
            path=Path(member.name)
            allowed=member.name in ['release.json','release.env','compose.production.yml'] or (path.parts and path.parts[0] in ['ops','Monitoring'])
            if not allowed or path.is_absolute() or '..' in path.parts or not member.isfile() or member.name in names:
                raise ValueError('Unsafe archive member')
            names.add(member.name)
        # Python 3.12's data filter rejects path escapes and dangerous metadata.
        archive.extractall(staging,filter='data')
    for name in ['release.json','release.env']:
        if hashlib.sha256((staging/name).read_bytes()).hexdigest()!=checks[name]:
            raise ValueError('Embedded and published release metadata differ')
    manifest=json.loads((staging/'release.json').read_text())
    if manifest.get('release_id')!=release_id:raise ValueError('Release identity mismatch')
    target=BASE/'releases'/release_id
    if target.exists():
        if hashlib.sha256((target/'runtime.tar.gz').read_bytes()).hexdigest()!=checks['runtime.tar.gz']:
            raise ValueError('Refusing to overwrite an immutable installed release')
        # A valid same-release retry uses the original root-owned bundle.
        import shutil
        shutil.rmtree(staging)
    else:
        staging.rename(target)
    unit='unimeow-deploy-'+release_id.replace('.','-')
    command=['systemd-run','--unit='+unit,'--collect','--wait','--property=Type=exec','/usr/bin/python3','-u',str(target/'ops/deploy.py'),str(target)]
    completed=subprocess.run(command)
    subprocess.run(['journalctl','-u',unit,'--no-pager','-n','250'])
    return completed.returncode


if __name__=='__main__':
    os.umask(0o077)
    if os.geteuid()!=0 or len(sys.argv)!=2:raise SystemExit('Usage: unimeow-deploy RELEASE_ID (root)')
    raise SystemExit(install(sys.argv[1]))
