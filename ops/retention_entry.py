#!/usr/bin/env python3
"""Root-owned SSH entrypoint: protect server state for the entire retention run.

Install with retention.py and release.py in /usr/local/lib/unimeow-retention.
The sudo wrapper must invoke this file with /usr/bin/python3 -I; no uploaded
Python or caller-supplied paths are executed. Token input is never persisted.
"""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import tarfile
import uuid

# Python's isolated mode deliberately excludes the script directory. This is
# the fixed, root-owned installation directory, never an SSH-writable upload.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from retention import (BASELINE_ID, GitHub, apply_plan, image_parts,
                       plan_retention, protected_state)
from release import RELEASE_ID, SERVICES, validate

BASE = Path('/srv/unimeow')
REPOSITORY = 'Kaze-nomi/UniMeow'
MAX_SECONDS = 30 * 60


def trusted(path, directory=False):
    path = Path(path)
    if path.resolve() != path or path.is_symlink():
        raise ValueError('Protected path must not contain symlinks')
    info = path.stat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if not kind(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError('Protected path must be root-owned and not writable by other users')
    return path


def read_json(path):
    path = trusted(path)
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Protected metadata is unexpectedly large')
    return json.loads(path.read_text(encoding='utf-8'))


def fingerprint(path):
    info = trusted(path).stat()
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


@contextmanager
def deployment_lock(base):
    import fcntl
    trusted(base, directory=True)
    path = base / 'deployment.lock'
    # The common deployment lock must already exist. Never create a second
    # inode by replacing a missing lock while another process could hold it.
    trusted(path)
    descriptor = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
    try:
        if os.fstat(descriptor).st_ino != path.stat().st_ino:
            raise ValueError('Deployment lock changed while opening it')
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Deployment, rollback, or another retention run holds the server lock') from None
        yield
    finally:
        os.close(descriptor)


class ServerState:
    """Read fresh references; verify the large, immutable baseline only once."""
    def __init__(self, base):
        self.base = base
        self.baseline_fingerprints = None
        self.baseline_id = None

    def verify_baseline(self, baseline_id, baseline):
        trusted(baseline, directory=True)
        names = ['manifest.json', 'COMPLETE', 'images.tar', 'configuration.private.tar',
                 'compose.private.json', 'images.private.json', 'containers.private.json']
        before = {name: fingerprint(baseline / name) for name in names}
        if self.baseline_fingerprints is not None:
            if baseline_id != self.baseline_id or before != self.baseline_fingerprints:
                raise ValueError('Preserved baseline changed during retention')
            return
        manifest = read_json(baseline / 'manifest.json')
        expected = manifest.get('images_archive_sha256')
        if manifest.get('release_id') != baseline_id or not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected):
            raise ValueError('Invalid baseline identity or archive checksum')
        if (baseline / 'COMPLETE').read_text().strip() != expected:
            raise ValueError('Baseline is not complete')
        digest = hashlib.sha256()
        with (baseline / 'images.tar').open('rb') as archive:
            for chunk in iter(lambda: archive.read(8 * 1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise ValueError('Preserved baseline image archive failed verification')
        runtime = read_json(baseline / 'compose.private.json')
        images = manifest.get('images', {})
        if not set(SERVICES).issubset(images) or not set(SERVICES).issubset(runtime.get('services', {})):
            raise ValueError('Baseline does not cover all eight application services')
        for service in SERVICES:
            entry = images[service]
            if (not re.fullmatch(r'sha256:[0-9a-f]{64}', entry.get('image_id', ''))
                    or not entry.get('local_reference')
                    or runtime['services'][service].get('image') != entry['local_reference']
                    or runtime['services'][service].get('pull_policy') != 'never'):
                raise ValueError('Baseline image/configuration mapping is incomplete')
        # Read tar headers only; no image loading, extraction, Docker commands,
        # production stops, or additional archive copies are needed.
        with tarfile.open(baseline / 'images.tar') as archive:
            if not archive.getmember('manifest.json').isfile():
                raise ValueError('Baseline images are not a Docker save archive')
        with tarfile.open(baseline / 'configuration.private.tar') as archive:
            names_in_tar = {item.name.lstrip('./') for item in archive}
            if not {'root/UniMeow/.env', 'root/UniMeow/docker-compose.yml'}.issubset(names_in_tar):
                raise ValueError('Baseline private configuration archive is incomplete')
            if any(not any(name.startswith(prefix) for name in names_in_tar)
                   for prefix in ['etc/nginx/', 'etc/letsencrypt/', 'root/UniMeow/Monitoring/']):
                raise ValueError('Baseline proxy, TLS, or monitoring configuration is missing')
        if before != {name: fingerprint(baseline / name) for name in before}:
            raise ValueError('Baseline changed while verifying its archives')
        self.baseline_id, self.baseline_fingerprints = baseline_id, before

    def __call__(self):
        state = read_json(self.base / 'state.json')
        installation = read_json(self.base / 'config/installation.json')
        if state.get('status') != 'healthy' or state.get('restore_verified') is not True:
            raise ValueError('Production must be healthy with a verified baseline restore')
        if (self.base / 'maintenance').exists():
            raise ValueError('Production is in maintenance mode')
        baseline_id = installation.get('created_from_release')
        if not isinstance(baseline_id, str) or not BASELINE_ID.fullmatch(baseline_id):
            raise ValueError('Installation metadata does not identify the original baseline')
        public_baseline = installation.get('public_baseline_release', baseline_id)
        if not isinstance(public_baseline, str) or not BASELINE_ID.fullmatch(public_baseline):
            raise ValueError('Installation metadata does not identify a valid public baseline release')
        if installation.get('project') != state.get('project') or not state.get('project'):
            raise ValueError('Installation and production project differ')
        baseline = self.base / baseline_id
        if state.get('baseline_path') != str(baseline):
            raise ValueError('Baseline path differs from the recorded installation')
        self.verify_baseline(baseline_id, baseline)
        current = state.get('current_release')
        previous = state.get('previous_release')
        if public_baseline != baseline_id and public_baseline in {current, previous}:
            raise ValueError('Public baseline identity collides with an installed managed release')
        # Public naming does not rename private archives, deployment journals,
        # compatibility proofs, or the current/previous IDs in state.json.
        public_current = public_baseline if current == baseline_id else current
        public_previous = public_baseline if previous == baseline_id else previous
        result = {'repository': REPOSITORY, 'status': 'healthy', 'current_release': public_current,
                  'previous_release': public_previous, 'baseline_release': public_baseline,
                  'baseline_local_verified': True, 'observed_at': datetime.now(timezone.utc).isoformat(),
                  'protected_images': []}
        # Protect the exact installed image references as well as GitHub's
        # release manifests, including the rollback release outside newest five.
        for release_id in {current, previous} - {None, baseline_id}:
            if not isinstance(release_id, str) or not RELEASE_ID.fullmatch(release_id):
                raise ValueError('Invalid installed release identifier')
            manifest = validate(read_json(self.base / 'releases' / release_id / 'release.json'))
            if manifest['release_id'] != release_id:
                raise ValueError('Installed release identity mismatch')
            for reference in manifest['images'].values():
                image_parts(reference, REPOSITORY)
                result['protected_images'].append(reference)
        protected_state(result, REPOSITORY)
        return result


def arguments(argv):
    if argv in (['plan'], ['maintain']):
        return argv[0], None
    if len(argv) == 2 and argv[0] == 'apply' and re.fullmatch('[0-9a-f]{64}', argv[1]):
        return 'apply', argv[1]
    raise ValueError('Usage: unimeow-retention plan | maintain | apply REVIEWED_PLAN_SHA256')


def execute(base, mode, reviewed, token, client_factory=GitHub):
    if mode not in {'plan', 'apply', 'maintain'} or (mode == 'apply' and not re.fullmatch('[0-9a-f]{64}', reviewed or '')):
        raise ValueError('Invalid retention mode or reviewed plan hash')
    with deployment_lock(base):
        provider = ServerState(base)
        state = provider()
        client = client_factory(REPOSITORY, token)
        plan = plan_retention(client, state)
        if mode in {'apply', 'maintain'}:
            journal_dir = base / 'retention'
            journal_dir.mkdir(mode=0o700, exist_ok=True)
            trusted(journal_dir, directory=True)
            journal = journal_dir / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex + '.jsonl')
            # Scheduled maintenance implements the authorized retention policy;
            # its freshly computed plan is still revalidated before removal.
            apply_plan(client, plan, provider, plan['plan_sha256'] if mode == 'maintain' else reviewed, journal)
        return {'mode': mode, 'result': 'read-only-plan' if mode == 'plan' else 'applied', 'plan': plan}


def main(argv=None):
    if os.geteuid() != 0:
        raise ValueError('This entrypoint must run as root through the restricted sudo wrapper')
    mode, reviewed = arguments(sys.argv[1:] if argv is None else argv)
    token = sys.stdin.buffer.read(16 * 1024 + 1).decode('ascii').strip()
    if not token or len(token) > 16 * 1024 or not re.fullmatch('[A-Za-z0-9_]+', token):
        raise ValueError('A bounded GitHub token is required on standard input')
    os.umask(0o077)
    def timeout(signum, frame):
        raise TimeoutError('Retention exceeded its bounded execution time; review its journal before retrying')
    signal.signal(signal.SIGALRM, timeout)
    signal.alarm(MAX_SECONDS)
    try:
        result = execute(BASE, mode, reviewed, token)
        print(json.dumps(result, indent=2), flush=True)
    finally:
        signal.alarm(0)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError, tarfile.TarError, KeyError) as error:
        # Never emit metadata, archive contents, tokens, or signed download URLs.
        print('Retention stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
