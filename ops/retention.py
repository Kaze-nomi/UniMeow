#!/usr/bin/env python3
"""Plan/apply retention of immutable UniMeow releases and their GHCR graph.

Default is read-only. The caller must fetch a fresh, sanitized server-state file
while holding the deployment lock, and exclude concurrent Release/Deploy jobs
through the same workflow concurrency group for the entire apply operation.
This program never touches local images, baseline archives, backups, or volumes.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

from release import SERVICES, RELEASE_ID, validate

# This validates an explicitly supplied identity; matching a semver tag alone
# never classifies a GitHub release as the preserved baseline.
BASELINE_ID = re.compile(r'(?:baseline-[A-Za-z0-9][A-Za-z0-9._-]{0,120}|'
                         + RELEASE_ID.pattern.removeprefix('^').removesuffix('$') + r')')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
REPOSITORY = re.compile(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+')
ASSETS = {'release.json', 'release.env', 'runtime.tar.gz', 'SHA256SUMS'}
MANIFEST_TYPES = {
    'application/vnd.docker.distribution.manifest.v2+json',
    'application/vnd.oci.image.manifest.v1+json',
}
INDEX_TYPES = {
    'application/vnd.docker.distribution.manifest.list.v2+json',
    'application/vnd.oci.image.index.v1+json',
}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def checksum(data):
    return hashlib.sha256(data).hexdigest()


def utc(value):
    if not isinstance(value, str):
        raise ValueError('Missing UTC timestamp')
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('Timestamp must contain a timezone')
    return parsed.astimezone(timezone.utc)


def protected_state(state, repository, now=None):
    required = {'repository', 'status', 'current_release', 'previous_release',
                'baseline_release', 'baseline_local_verified', 'observed_at'}
    if not isinstance(state, dict) or not required.issubset(state):
        raise ValueError('Incomplete protected server state')
    if not isinstance(state['repository'], str) or state['repository'].lower() != repository.lower() or state['status'] != 'healthy':
        raise ValueError('Wrong installation or deployment is not healthy')
    baseline = state['baseline_release']
    if not isinstance(baseline, str) or not BASELINE_ID.fullmatch(baseline):
        raise ValueError('A valid baseline release is required')
    if state['baseline_local_verified'] is not True:
        raise ValueError('Preserved baseline image/config archives must be verified on the server')
    now = now or datetime.now(timezone.utc)
    age = (now - utc(state['observed_at'])).total_seconds()
    if age < -30 or age > 300:
        raise ValueError('Protected server state must be fetched within the last five minutes')
    refs = {baseline}
    for field in ['current_release', 'previous_release']:
        value = state[field]
        if field == 'previous_release' and value is None:
            if state['current_release'] != baseline:
                raise ValueError('A deployed managed release must identify its rollback release')
            continue
        if not isinstance(value, str) or not (RELEASE_ID.fullmatch(value) or value == baseline):
            raise ValueError('Invalid protected release reference: ' + field)
        refs.add(value)
    images = state.get('protected_images', [])
    if not isinstance(images, list):
        raise ValueError('protected_images must be a list')
    for image in images:
        image_parts(image, repository)
    return {'current_release': state['current_release'], 'previous_release': state['previous_release'],
            'baseline_release': baseline, 'protected_images': sorted(set(images)),
            'protected_releases': sorted(refs)}


def image_parts(reference, repository):
    owner = repository.split('/')[0].lower()
    if not isinstance(reference, str):
        raise ValueError('Image reference must be a string')
    match = re.fullmatch(r'ghcr\.io/([a-z0-9-]+)/(unimeow-[a-z-]+)@(sha256:[0-9a-f]{64})', reference)
    if not match or match[1] != owner or match[2] not in {'unimeow-' + s for s in SERVICES}:
        raise ValueError('Image is outside the managed installation')
    return match[2], match[3]


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        host = parsed.hostname or ''
        if parsed.scheme != 'https' or not (host in {'api.github.com', 'ghcr.io', 'github.com'}
                                           or host.endswith('.githubusercontent.com')):
            raise ValueError('Unexpected download redirect')
        result = super().redirect_request(request, fp, code, msg, headers, newurl)
        if result and urllib.parse.urlparse(request.full_url).netloc != parsed.netloc:
            result.remove_header('Authorization')
        return result


class GitHub:
    def __init__(self, repository, token):
        if not REPOSITORY.fullmatch(repository) or repository.split('/')[1] in {'.', '..'}:
            raise ValueError('Invalid owner/repository')
        if not token:
            raise ValueError('GH_TOKEN is required')
        self.repository = repository
        self.owner = repository.split('/')[0].lower()
        self.token = token
        self.opener = urllib.request.build_opener(SafeRedirect())
        self.registry_tokens = {}
        owner = self.api('/users/' + self.owner)
        if owner.get('type') not in {'User', 'Organization'}:
            raise ValueError('Unrecognized package owner type')
        self.package_prefix = '/orgs/' if owner['type'] == 'Organization' else '/users/'

    def request(self, url, method='GET', headers=None, limit=64 * 1024 * 1024):
        req = urllib.request.Request(url, method=method, headers=headers or {})
        try:
            with self.opener.open(req, timeout=30) as response:
                data = response.read(limit + 1) if method != 'HEAD' else b''
                if len(data) > limit:
                    raise ValueError('Response exceeds the bounded download size')
                return data, response.headers
        except urllib.error.HTTPError as error:
            # URLs and response bodies can contain signed credentials; do not print them.
            raise RuntimeError(f'{method} failed with HTTP {error.code} at {urllib.parse.urlparse(url).hostname}') from None

    def api(self, path, method='GET', raw=False):
        if not path.startswith('/') or path.startswith('//'):
            raise ValueError('API path must be relative to api.github.com')
        data, _ = self.request('https://api.github.com' + path, method, {
            'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/octet-stream' if raw else 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'UniMeow-release-retention',
        })
        return data if raw else (json.loads(data) if data else None)

    def pages(self, path):
        result = []
        for page in range(1, 101):
            rows = self.api(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
            if not isinstance(rows, list):
                raise ValueError('Expected a paginated list')
            result.extend(rows)
            if len(rows) < 100:
                return result
        raise ValueError('Pagination limit reached; refusing a partial inventory')

    def releases(self):
        return self.pages('/repos/' + self.repository + '/releases')

    def release_assets(self, release_id):
        return self.pages(f'/repos/{self.repository}/releases/{release_id}/assets')

    def asset(self, asset_id):
        return self.api(f'/repos/{self.repository}/releases/assets/{asset_id}', raw=True)

    def versions_path(self, package):
        if package not in {'unimeow-' + s for s in SERVICES}:
            raise ValueError('Package is outside this application')
        return f'{self.package_prefix}{self.owner}/packages/container/{package}/versions'

    def versions(self, package):
        return self.pages(self.versions_path(package))

    def registry_headers(self, package):
        if package not in self.registry_tokens:
            scope = urllib.parse.urlencode({'service': 'ghcr.io', 'scope': f'repository:{self.owner}/{package}:pull'})
            auth = base64.b64encode((self.owner + ':' + self.token).encode()).decode()
            data, _ = self.request('https://ghcr.io/token?' + scope, headers={'Authorization': 'Basic ' + auth})
            token = json.loads(data).get('token')
            if not isinstance(token, str) or not token:
                raise ValueError('Registry did not return a pull token')
            self.registry_tokens[package] = token
        return {'Authorization': 'Bearer ' + self.registry_tokens[package],
                'Accept': ', '.join(sorted(MANIFEST_TYPES | INDEX_TYPES))}

    def manifest(self, package, digest):
        if not DIGEST.fullmatch(digest):
            raise ValueError('Invalid OCI digest')
        data, _ = self.request(f'https://ghcr.io/v2/{self.owner}/{package}/manifests/{digest}',
                               headers=self.registry_headers(package), limit=4 * 1024 * 1024)
        if 'sha256:' + checksum(data) != digest:
            raise ValueError('OCI manifest content does not match its immutable digest')
        return json.loads(data)

    def blob_available(self, package, digest):
        if not DIGEST.fullmatch(digest):
            raise ValueError('Invalid OCI blob digest')
        self.request(f'https://ghcr.io/v2/{self.owner}/{package}/blobs/{digest}', method='HEAD',
                     headers=self.registry_headers(package))

    def delete_version(self, package, version_id):
        self.api(self.versions_path(package) + '/' + str(version_id), method='DELETE')

    def delete_release(self, release_id):
        self.api(f'/repos/{self.repository}/releases/{release_id}', method='DELETE')


def identifier(value):
    if type(value) is not int or value < 1:
        raise ValueError('Malformed GitHub object ID')
    return value


def release_inventory(rows, baseline=None):
    result = []
    for row in rows:
        tag = row.get('tag_name')
        if isinstance(tag, str) and (RELEASE_ID.fullmatch(tag) or tag == baseline):
            result.append({'id': identifier(row.get('id')), 'tag': tag, 'draft': row.get('draft'),
                           'published_at': row.get('published_at'), 'updated_at': row.get('updated_at')})
    return sorted(result, key=lambda r: (r['tag'], r['id']))


def version_inventory(rows):
    result = []
    for row in rows:
        digest = row.get('name')
        tags = row.get('metadata', {}).get('container', {}).get('tags')
        if not isinstance(digest, str) or not DIGEST.fullmatch(digest) or not isinstance(tags, list):
            raise ValueError('Malformed container package version')
        if not all(isinstance(tag, str) for tag in tags):
            raise ValueError('Malformed container tags')
        result.append({'id': identifier(row.get('id')), 'digest': digest, 'tags': sorted(set(tags))})
    if len({r['digest'] for r in result}) != len(result):
        raise ValueError('Ambiguous duplicate digest versions')
    return sorted(result, key=lambda r: r['id'])


def load_release(client, row):
    tag = row['tag_name']
    assets = client.release_assets(identifier(row['id']))
    named = {}
    for asset in assets:
        name = asset.get('name')
        if name in ASSETS:
            if name in named or asset.get('state') != 'uploaded' or not isinstance(asset.get('size'), int) or asset['size'] <= 0:
                raise ValueError('Missing, partial, or duplicate release assets: ' + tag)
            named[name] = asset
    if set(named) != ASSETS:
        raise ValueError('Release is incomplete: ' + tag)
    files = {name: client.asset(identifier(named[name]['id'])) for name in ASSETS}
    sums = {}
    for line in files['SHA256SUMS'].decode('ascii').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  (release\.json|release\.env|runtime\.tar\.gz)', line)
        if not match or match[2] in sums:
            raise ValueError('Malformed release checksums: ' + tag)
        sums[match[2]] = match[1]
    if set(sums) != ASSETS - {'SHA256SUMS'}:
        raise ValueError('Release checksums do not cover the complete release')
    for name, expected in sums.items():
        if checksum(files[name]) != expected or len(files[name]) != named[name]['size']:
            raise ValueError('Release asset checksum or size mismatch: ' + tag)
    manifest = json.loads(files['release.json'])
    if not isinstance(manifest, dict) or not isinstance(manifest.get('images'), dict):
        raise ValueError('Release manifest must contain an image mapping')
    manifest = validate(manifest)
    if manifest['release_id'] != tag:
        raise ValueError('Manifest release ID differs from its GitHub tag')
    for image in manifest['images'].values():
        image_parts(image, client.repository)
    expected_env = ''.join(f'{service.upper().replace("-", "_")}_IMAGE={image}\n'
                           for service, image in manifest['images'].items())
    actual_env = files['release.env'].decode('utf-8')
    if sorted(actual_env.splitlines()) != sorted(expected_env.splitlines()):
        raise ValueError('Release environment differs from the image manifest')
    return {'tag': tag, 'id': row['id'], 'published_at': utc(row['published_at']).isoformat(),
            'images': manifest['images'], 'manifest_sha256': checksum(files['release.json']),
            'assets_sha256': {name: checksum(data) for name, data in files.items()},
            'has_extra_assets': len(assets) != len(ASSETS)}


class Graph:
    def __init__(self, client):
        self.client = client
        self.cache = {}
        self.blobs = set()

    def closure(self, root, visiting=None):
        if root in self.cache:
            return self.cache[root]
        visiting = set() if visiting is None else visiting
        if root in visiting or len(visiting) > 20 or len(self.cache) > 10000:
            raise ValueError('Invalid or unbounded OCI dependency graph')
        visiting = visiting | {root}
        package, digest = root
        manifest = self.client.manifest(package, digest)
        kind = manifest.get('mediaType')
        result = {root}
        if kind in INDEX_TYPES:
            children = manifest.get('manifests')
            if not isinstance(children, list) or not children:
                raise ValueError('OCI index has no image manifests')
            for child in children:
                value = child.get('digest')
                if not isinstance(value, str) or not DIGEST.fullmatch(value):
                    raise ValueError('Malformed OCI child digest')
                result |= self.closure((package, value), visiting)
        elif kind in MANIFEST_TYPES:
            if not isinstance(manifest.get('config'), dict) or not isinstance(manifest.get('layers'), list):
                raise ValueError('Incomplete OCI image manifest')
            for blob in [manifest['config'], *manifest['layers']]:
                value = blob.get('digest')
                if not isinstance(value, str) or not DIGEST.fullmatch(value):
                    raise ValueError('Malformed OCI blob digest')
                key = (package, value)
                if key not in self.blobs:
                    self.client.blob_available(*key)
                    self.blobs.add(key)
        else:
            raise ValueError('Unsupported OCI manifest media type')
        self.cache[root] = result
        return result


def plan_retention(client, state, now=None):
    protection = protected_state(state, client.repository, now)
    rows = client.releases()
    # The exact protected baseline is a public metadata record whose assets are
    # baseline.json/archived infrastructure, not an eight-image runtime bundle.
    # Every other semver release still requires the full normal validation.
    managed = [r for r in rows if isinstance(r.get('tag_name'), str)
               and r['tag_name'] != protection['baseline_release'] and RELEASE_ID.fullmatch(r['tag_name'])]
    if len({r['tag_name'] for r in managed}) != len(managed):
        raise ValueError('Duplicate managed release tags')
    if any(r.get('draft') is not False or not r.get('published_at') for r in managed):
        raise ValueError('A managed release is still being published; retention must wait')
    releases = [load_release(client, row) for row in managed]
    releases.sort(key=lambda r: (utc(r['published_at']), r['tag']), reverse=True)
    tags = {r['tag'] for r in releases}
    missing = set(protection['protected_releases']) - tags - {protection['baseline_release']}
    if missing:
        raise ValueError('Protected published releases are missing: ' + ', '.join(sorted(missing)))
    keep = {r['tag'] for r in releases[:5]} | set(protection['protected_releases'])
    keep |= {r['tag'] for r in releases if r['has_extra_assets']}
    obsolete = tags - keep
    graph = Graph(client)
    protected_nodes, old_nodes = set(), set()
    for release in releases:
        target = protected_nodes if release['tag'] in keep else old_nodes
        for reference in release['images'].values():
            target.update(graph.closure(image_parts(reference, client.repository)))
    for reference in protection['protected_images']:
        protected_nodes.update(graph.closure(image_parts(reference, client.repository)))
    inventories = {}
    if releases or protection['protected_images']:
        for service in SERVICES:
            package = 'unimeow-' + service
            inventories[package] = version_inventory(client.versions(package))
            present = {r['digest'] for r in inventories[package]}
            if any(p == package and d not in present for p, d in protected_nodes | old_nodes):
                raise ValueError('A release image is missing from the package version inventory')
            for version in inventories[package]:
                node = (package, version['digest'])
                # Unknown roots and foreign/baseline/new tags are never cleanup candidates.
                # Protect their complete graph as they may share untagged children with an old image.
                if node not in old_nodes or not set(version['tags']).issubset(obsolete):
                    protected_nodes.update(graph.closure(node))
    removals = []
    for package, versions in inventories.items():
        for version in versions:
            node = (package, version['digest'])
            if node in old_nodes and node not in protected_nodes:
                removals.append({'package': package, **version})
    result = {
        'format': 1, 'repository': client.repository, 'policy': 'newest-five-complete-plus-protected',
        'protection': protection,
        'retained_releases': sorted(keep),
        'delete_releases': [{'tag': r['tag'], 'id': r['id']} for r in releases if r['tag'] in obsolete],
        'delete_package_versions': sorted(removals, key=lambda r: (r['package'], r['id'])),
        'protected_manifest_nodes': sorted([list(node) for node in protected_nodes]),
        'release_assets': {r['tag']: r['assets_sha256'] for r in releases},
        'release_inventory': release_inventory(rows, protection['baseline_release']), 'package_inventory': inventories,
        'baseline_policy': 'Exact protected baseline identity preserved locally and publicly; never removed by this program',
        'tag_policy': 'Git tags and shared retained digest aliases are preserved',
    }
    result['plan_sha256'] = checksum(canonical(result))
    return result


def apply_plan(client, plan, state_provider, confirm_plan, journal_path):
    if confirm_plan != plan['plan_sha256']:
        raise ValueError('Apply requires the exact reviewed plan SHA256')
    initial = protected_state(state_provider(), client.repository)
    if initial != plan['protection']:
        raise ValueError('Protected server state changed after planning')
    # Re-download every retained bundle and every OCI manifest/blob before the first DELETE.
    fresh = plan_retention(client, state_provider())
    if fresh['plan_sha256'] != confirm_plan:
        raise ValueError('Release or registry state changed after planning; review a new plan')
    expected_releases = list(plan['release_inventory'])
    expected_versions = {key: list(value) for key, value in plan['package_inventory'].items()}
    journal_path = Path(journal_path)
    journal_path.parent.mkdir(parents=True, exist_ok=True)
    with journal_path.open('x', encoding='utf-8') as journal:
        journal_path.chmod(0o600)
        def record(event, **fields):
            journal.write(json.dumps({'at': datetime.now(timezone.utc).isoformat(), 'event': event, **fields}) + '\n')
            journal.flush()
            os.fsync(journal.fileno())

        record('reviewed_plan', plan=plan)
        if os.name == 'posix':
            directory = os.open(journal_path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)

        def unchanged():
            if protected_state(state_provider(), client.repository) != plan['protection']:
                raise ValueError('Server protection changed during retention; stopped')
            if release_inventory(client.releases(), plan['protection']['baseline_release']) != expected_releases:
                raise ValueError('Release publication changed during retention; stopped')
            for package, expected in expected_versions.items():
                if version_inventory(client.versions(package)) != expected:
                    raise ValueError('Registry changed during retention; stopped')

        try:
            # Remove obsolete release records first: no public release should promise images
            # after those images are removed. The durable plan records any orphan cleanup if
            # an API failure interrupts the remaining version removals. Never blind-retry DELETE.
            for release in plan['delete_releases']:
                unchanged()
                record('deleting_release', **release)
                client.delete_release(release['id'])
                expected_releases = [r for r in expected_releases if r['id'] != release['id']]
                record('deleted_release', **release)
            for version in plan['delete_package_versions']:
                unchanged()
                record('deleting_package_version', **version)
                client.delete_version(version['package'], version['id'])
                expected_versions[version['package']] = [r for r in expected_versions[version['package']]
                                                         if r['id'] != version['id']]
                record('deleted_package_version', **version)
            record('complete')
        except BaseException as error:
            record('stopped', error_type=type(error).__name__)
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--state', required=True, type=Path, help='Fresh sanitized state fetched from the server under its deployment lock')
    parser.add_argument('--output', type=Path, help='Save the read-only plan as JSON')
    parser.add_argument('--apply', action='store_true', help='Execute exactly the reviewed, revalidated plan')
    parser.add_argument('--confirm-plan', help='SHA256 of a previously reviewed plan')
    parser.add_argument('--journal', type=Path, help='New durable JSONL file; required for apply')
    args = parser.parse_args(argv)
    if args.apply and (not args.confirm_plan or not args.journal):
        parser.error('--apply requires --confirm-plan and a new --journal path')
    client = GitHub(args.repository, os.environ.get('GH_TOKEN'))
    state_provider = lambda: json.loads(args.state.read_text(encoding='utf-8'))
    plan = plan_retention(client, state_provider())
    data = json.dumps(plan, indent=2) + '\n'
    if args.output:
        args.output.write_text(data, encoding='utf-8')
    print(data, end='')
    if args.apply:
        apply_plan(client, plan, state_provider, args.confirm_plan, args.journal)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError) as error:
        print('Retention stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
