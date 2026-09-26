#!/usr/bin/env python3
"""Cold, complete Docker-volume backup. Must run as root under the deployment lock.

No production volume is ever deleted by this module. Only a previously recorded,
verified backup directory can be retired after an atomic pointer replacement.
"""
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tarfile
import time


def run(*args, capture=False):
    return subprocess.run(args, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.new')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.chmod(0o600)
    with temporary.open('rb') as stream:
        os.fsync(stream.fileno())
    temporary.replace(path)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def sync_file(path):
    with Path(path).open('rb') as stream:
        os.fsync(stream.fileno())


def inspect_project(project):
    ids = run('docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={project}', capture=True).split()
    if not ids:
        raise RuntimeError('No existing production containers; refusing an empty backup')
    return json.loads(run('docker', 'inspect', *ids, capture=True))


def volumes_for(containers):
    mounts = {m['Name']: m['Source'] for c in containers for m in c['Mounts'] if m['Type'] == 'volume'}
    if not mounts:
        raise RuntimeError('No data volumes discovered')
    for name, path in mounts.items():
        info = json.loads(run('docker', 'volume', 'inspect', name, capture=True))[0]
        if info['Mountpoint'] != path or not Path(path).is_dir():
            raise RuntimeError(f'Volume missing or changed: {name}')
    return mounts


def validate_backup(path):
    path = Path(path)
    if not (path / 'COMPLETE').is_file():
        raise RuntimeError('Backup is incomplete')
    manifest = json.loads((path / 'manifest.json').read_text())
    if not manifest.get('volumes') or not manifest.get('files'):
        raise RuntimeError('Empty backup manifest')
    if any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', name) for name in manifest['volumes']):
        raise RuntimeError('Unsafe backup volume name')
    required = {f'volumes/{name}.tar' for name in manifest['volumes']} | {'config.tar','containers.private.json'}
    if set(manifest['files']) != required:
        raise RuntimeError('Backup manifest does not cover every recorded volume and configuration')
    for relative, expected in manifest['files'].items():
        item = path / relative
        if item.resolve().parent != path.resolve() and path.resolve() not in item.resolve().parents:
            raise RuntimeError('Unsafe manifest path')
        if sha256(item) != expected:
            raise RuntimeError(f'Checksum mismatch: {relative}')
        if item.suffix == '.tar':
            # GNU tar validates the stream without extracting anything.
            run('tar', '-tf', str(item), capture=True)
    return manifest


def retire_previous(root, current, previous):
    if Path(current).is_symlink() or Path(previous).is_symlink():
        raise RuntimeError('Refusing unsafe backup retirement through a symlink')
    root, current, previous = map(lambda p: Path(p).resolve(), (root, current, previous))
    if current.parent != root or not current.name.startswith('backup-') or previous == current or previous.parent != root or not previous.name.startswith('backup-'):
        raise RuntimeError('Refusing unsafe backup retirement')
    validate_backup(current)
    if previous.exists():
        intent_path = root / 'rotation.json'
        intent = json.loads(intent_path.read_text()) if intent_path.exists() else {}
        already_retiring = (intent.get('phase') == 'retiring' and
                            intent.get('current') == str(current) and intent.get('previous') == str(previous))
        if not already_retiring:
            validate_backup(previous)
        atomic_json(intent_path, {'current': str(current), 'previous': str(previous), 'phase': 'retiring'})
        shutil.rmtree(previous)
        sync_directory(root)


def recover_rotation(root):
    root = Path(root).resolve()
    intent_path = root / 'rotation.json'
    if not intent_path.exists():
        return
    intent = json.loads(intent_path.read_text())
    if not intent.get('previous') and intent.get('phase') != 'prepared':
        return
    if Path(intent['current']).is_symlink():
        raise RuntimeError('Unsafe backup rotation intent symlink')
    current = Path(intent['current']).resolve()
    if current.parent != root or not current.name.startswith('backup-'):
        raise RuntimeError('Unsafe backup rotation intent')
    validate_backup(current)
    pointer = root / 'current.new'
    if pointer.is_symlink():
        pointer.unlink()
    pointer.symlink_to(current.name)
    pointer.replace(root / 'current')
    sync_directory(root)
    if intent.get('previous'):
        retire_previous(root, current, intent['previous'])
    atomic_json(intent_path, {'current': str(current), 'previous': None, 'phase': 'complete'})


def create_backup(root, project, release, containers, config_paths, baseline_path):
    """Requires every container mounting any source volume to be stopped."""
    root = Path(root)
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    root.chmod(0o700)
    recover_rotation(root)
    mounts = volumes_for(containers)
    source_names = set(mounts)
    running = run('docker', 'ps', '-q', capture=True).split()
    if running:
        for c in json.loads(run('docker', 'inspect', *running, capture=True)):
            if any(m.get('Name') in source_names for m in c['Mounts']):
                raise RuntimeError(f'A writer is still running: {c["Name"]}')
    required = sum(int(run('du', '-sb', p, capture=True).split()[0]) for p in mounts.values())
    required += 512 * 1024 * 1024
    if shutil.disk_usage(root).free < required:
        raise RuntimeError('Insufficient free space for a new full backup; previous backup preserved')
    previous = (root / 'current').resolve() if (root / 'current').is_symlink() else None
    if previous:
        validate_backup(previous)
    target = root / ('backup-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
    target.mkdir(mode=0o700)
    (target / 'volumes').mkdir(mode=0o700)
    atomic_json(target / 'containers.private.json', containers)
    manifest = {'version': 1, 'created_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'project': project, 'source_release': release, 'baseline_path': str(baseline_path),
                'volumes': mounts, 'files': {}}
    for name, source in sorted(mounts.items()):
        relative = f'volumes/{name}.tar'
        run('tar', '--numeric-owner', '--xattrs', '--acls', '--sparse', '-cpf', str(target / relative), '-C', source, '.')
        (target / relative).chmod(0o600)
        sync_file(target / relative)
        run('tar', '-tf', str(target / relative), capture=True)
        manifest['files'][relative] = sha256(target / relative)
        print(f'Archived and verified {name}', flush=True)
    for p in config_paths:
        if not Path(p).exists():
            raise RuntimeError(f'Required configuration is missing: {p}')
    run('tar', '--numeric-owner', '--xattrs', '--acls', '-cpf', str(target / 'config.tar'), *config_paths)
    (target / 'config.tar').chmod(0o600)
    sync_file(target / 'config.tar')
    manifest['files']['config.tar'] = sha256(target / 'config.tar')
    manifest['files']['containers.private.json'] = sha256(target / 'containers.private.json')
    atomic_json(target / 'manifest.json', manifest)
    (target / 'SHA256SUMS').write_text(''.join(f'{v}  {k}\n' for k, v in manifest['files'].items()))
    for item in target.iterdir():
        if item.is_file():
            item.chmod(0o600)
            with item.open('rb') as stream:
                os.fsync(stream.fileno())
    sync_directory(target / 'volumes')
    sync_directory(target)
    (target / 'COMPLETE').write_text(manifest['created_at'] + '\n')
    (target / 'COMPLETE').chmod(0o600)
    sync_file(target / 'COMPLETE')
    sync_directory(target)
    sync_directory(root)
    validate_backup(target)
    # Durable intent precedes the pointer switch, so power loss cannot orphan retirement.
    atomic_json(root / 'rotation.json', {'current': str(target), 'previous': str(previous) if previous else None, 'phase': 'prepared'})
    recover_rotation(root)
    print(f'COMPLETE {target}', flush=True)
    return target
