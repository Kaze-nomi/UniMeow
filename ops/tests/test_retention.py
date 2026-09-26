import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
spec = importlib.util.spec_from_file_location('retention', Path(__file__).parents[1] / 'retention.py')
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)


def digest(value):
    return 'sha256:' + hashlib.sha256(value.encode()).hexdigest()


def state(current='v1.0.8', previous='v1.0.2'):
    return {'repository': 'Kaze-nomi/UniMeow', 'status': 'healthy',
            'current_release': current, 'previous_release': previous,
            'baseline_release': 'baseline-20260926', 'baseline_local_verified': True,
            'observed_at': datetime.now(timezone.utc).isoformat()}


class FakeGitHub:
    repository = 'Kaze-nomi/UniMeow'

    def __init__(self, count=8):
        self.rows = []
        self.assets = {}
        self.files = {}
        self.images = {}
        self.package_versions = {'unimeow-' + s: [] for s in retention.SERVICES}
        self.deleted = []
        self.manifest_reads = []
        self.blob_reads = []
        self.next_id = 100
        for number in range(1, count + 1):
            self.add_release(number)

    def add_release(self, number):
        tag = f'v1.0.{number}'
        images = {}
        for service in retention.SERVICES:
            package = 'unimeow-' + service
            image_digest = digest(package + '-' + tag)
            images[service] = f'ghcr.io/kaze-nomi/{package}@{image_digest}'
            self.add_image(package, image_digest, [tag])
        manifest = {'format': 1, 'release_id': tag, 'commit': 'a' * 40, 'architecture': 'linux/amd64',
                    'migration_services': ['user-migrate', 'post-migrate', 'notification-migrate'], 'images': images}
        files = {'release.json': json.dumps(manifest).encode(), 'runtime.tar.gz': b'synthetic-runtime-bundle',
                 'release.env': ''.join(f'{s.upper().replace("-", "_")}_IMAGE={i}\n' for s, i in images.items()).encode()}
        files['SHA256SUMS'] = ''.join(f'{retention.checksum(data)}  {name}\n' for name, data in files.items()).encode()
        self.rows.append({'id': number, 'tag_name': tag, 'draft': False,
                          'published_at': f'2026-09-{number:02d}T00:00:00Z', 'updated_at': f'2026-09-{number:02d}T00:00:00Z'})
        self.assets[number] = []
        for name, data in files.items():
            self.next_id += 1
            self.assets[number].append({'id': self.next_id, 'name': name, 'state': 'uploaded', 'size': len(data)})
            self.files[self.next_id] = data

    def add_image(self, package, image_digest, tags, children=None):
        self.next_id += 1
        self.package_versions[package].append({'id': self.next_id, 'name': image_digest,
                                              'metadata': {'container': {'tags': tags}}})
        self.images[package, image_digest] = (
            {'mediaType': 'application/vnd.oci.image.index.v1+json',
             'manifests': [{'digest': value} for value in children]}
            if children is not None else
            {'mediaType': 'application/vnd.oci.image.manifest.v1+json',
             'config': {'digest': digest('config')}, 'layers': [{'digest': digest('layer')}]})

    def rewrite_manifest(self, release_id, change):
        files = {a['name']: self.files[a['id']] for a in self.assets[release_id]}
        manifest = json.loads(files['release.json'])
        change(manifest)
        files['release.json'] = json.dumps(manifest).encode()
        files['release.env'] = ''.join(f'{s.upper().replace("-", "_")}_IMAGE={i}\n' for s, i in manifest['images'].items()).encode()
        files['SHA256SUMS'] = ''.join(f'{retention.checksum(files[name])}  {name}\n'
                                      for name in ['release.json', 'release.env', 'runtime.tar.gz']).encode()
        for asset in self.assets[release_id]:
            asset['size'] = len(files[asset['name']])
            self.files[asset['id']] = files[asset['name']]

    def releases(self):
        return copy.deepcopy(self.rows)

    def release_assets(self, release_id):
        return copy.deepcopy(self.assets[release_id])

    def asset(self, asset_id):
        return self.files[asset_id]

    def versions(self, package):
        return copy.deepcopy(self.package_versions[package])

    def manifest(self, package, image_digest):
        self.manifest_reads.append((package, image_digest))
        if (package, image_digest) not in self.images:
            raise RuntimeError('Image unavailable')
        return copy.deepcopy(self.images[package, image_digest])

    def blob_available(self, package, image_digest):
        self.blob_reads.append((package, image_digest))

    def delete_version(self, package, version_id):
        self.deleted.append(('version', package, version_id))
        self.package_versions[package] = [r for r in self.package_versions[package] if r['id'] != version_id]

    def delete_release(self, release_id):
        self.deleted.append(('release', release_id))
        self.rows = [r for r in self.rows if r['id'] != release_id]


class RetentionTests(unittest.TestCase):
    def test_last_five_plus_current_previous_and_baseline(self):
        api = FakeGitHub()
        plan = retention.plan_retention(api, state())
        self.assertEqual(plan['retained_releases'], ['baseline-20260926', 'v1.0.2', 'v1.0.4', 'v1.0.5', 'v1.0.6', 'v1.0.7', 'v1.0.8'])
        self.assertEqual({r['tag'] for r in plan['delete_releases']}, {'v1.0.1', 'v1.0.3'})
        self.assertEqual(len(plan['delete_package_versions']), 16)
        self.assertEqual(api.deleted, [])
        self.assertEqual(len(api.manifest_reads), 64)
        self.assertGreater(len(api.blob_reads), 0)

    def test_old_current_is_protected_even_outside_newest_five(self):
        plan = retention.plan_retention(FakeGitHub(), state('v1.0.1', 'v1.0.2'))
        self.assertEqual([r['tag'] for r in plan['delete_releases']], ['v1.0.3'])

    def test_fewer_than_five_never_deletes(self):
        plan = retention.plan_retention(FakeGitHub(2), state('v1.0.2', 'v1.0.1'))
        self.assertEqual(plan['delete_releases'], [])
        self.assertEqual(plan['delete_package_versions'], [])

    def test_baseline_only_has_no_registry_dependency(self):
        plan = retention.plan_retention(FakeGitHub(0), state('baseline-20260926', None))
        self.assertEqual(plan['retained_releases'], ['baseline-20260926'])

    def test_shared_digest_survives_obsolete_release_removal(self):
        api = FakeGitHub()
        package = 'unimeow-frontend'
        shared = digest(package + '-v1.0.1')
        api.rewrite_manifest(8, lambda m: m['images'].update(frontend=f'ghcr.io/kaze-nomi/{package}@{shared}'))
        api.package_versions[package][0]['metadata']['container']['tags'].append('v1.0.8')
        plan = retention.plan_retention(api, state())
        self.assertNotIn(shared, [v['digest'] for v in plan['delete_package_versions']])
        self.assertIn([package, shared], plan['protected_manifest_nodes'])

    def test_shared_untagged_child_survives_a_retained_index(self):
        api = FakeGitHub()
        package = 'unimeow-frontend'
        child = digest('shared-child')
        api.add_image(package, child, [])
        for number in [1, 8]:
            api.images[package, digest(package + f'-v1.0.{number}')] = {
                'mediaType': 'application/vnd.oci.image.index.v1+json', 'manifests': [{'digest': child}]}
        plan = retention.plan_retention(api, state())
        self.assertNotIn(child, [v['digest'] for v in plan['delete_package_versions']])

    def test_unknown_untagged_parent_protects_its_old_child(self):
        api = FakeGitHub()
        package = 'unimeow-frontend'
        child = digest(package + '-v1.0.1')
        api.add_image(package, digest('unknown-parent'), [], [child])
        plan = retention.plan_retention(api, state())
        self.assertNotIn(child, [v['digest'] for v in plan['delete_package_versions']])

    def test_unique_old_index_children_can_be_retired(self):
        api = FakeGitHub()
        package = 'unimeow-frontend'
        child = digest('old-child')
        api.add_image(package, child, [])
        api.images[package, digest(package + '-v1.0.1')] = {
            'mediaType': 'application/vnd.oci.image.index.v1+json', 'manifests': [{'digest': child}]}
        plan = retention.plan_retention(api, state())
        self.assertIn(child, [v['digest'] for v in plan['delete_package_versions']])

    def test_foreign_and_baseline_tags_preserve_versions(self):
        api = FakeGitHub()
        package = 'unimeow-frontend'
        api.package_versions[package][0]['metadata']['container']['tags'].append('baseline-20260926')
        api.package_versions[package][2]['metadata']['container']['tags'].append('developer-test')
        api.rows.append({'id': 9999, 'tag_name': 'baseline-20260926'})
        api.rows.append({'id': 9998, 'tag_name': 'my-unmanaged-release'})
        plan = retention.plan_retention(api, state())
        self.assertFalse(any(v['package'] == package for v in plan['delete_package_versions']))
        self.assertFalse(any(r['id'] in {9999, 9998} for r in plan['delete_releases']))

    def test_missing_protected_release_blocks_every_removal(self):
        api = FakeGitHub()
        with self.assertRaisesRegex(ValueError, 'Protected published releases are missing'):
            retention.plan_retention(api, state(previous='v0.0.1'))
        self.assertEqual(api.deleted, [])

    def test_malformed_or_stale_state_blocks_every_removal(self):
        for field in ['current_release', 'previous_release', 'baseline_release', 'observed_at']:
            value = state()
            value.pop(field)
            with self.subTest(field=field), self.assertRaises(ValueError):
                retention.plan_retention(FakeGitHub(), value)
        for patch_value in [
            {'status': 'migrating'}, {'baseline_local_verified': False}, {'repository': 'other/project'},
            {'previous_release': None}, {'current_release': '../../prod'},
            {'observed_at': (datetime.now(timezone.utc) - timedelta(minutes=6)).isoformat()},
            {'observed_at': (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()},
        ]:
            with self.subTest(value=patch_value), self.assertRaises(ValueError):
                retention.plan_retention(FakeGitHub(), {**state(), **patch_value})

    def test_partial_release_and_draft_block_cleanup(self):
        api = FakeGitHub()
        api.assets[1] = [a for a in api.assets[1] if a['name'] != 'runtime.tar.gz']
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            retention.plan_retention(api, state())
        api = FakeGitHub()
        api.rows[0]['draft'] = True
        with self.assertRaisesRegex(ValueError, 'still being published'):
            retention.plan_retention(api, state())

    def test_malicious_manifest_owner_and_image_paths_are_rejected(self):
        for image in ['ghcr.io/attacker/unimeow-frontend@' + digest('x'),
                      'https://example.org/token', 'ghcr.io/kaze-nomi/unimeow-frontend:latest']:
            api = FakeGitHub()
            api.rewrite_manifest(1, lambda m: m['images'].update(frontend=image))
            with self.subTest(image=image), self.assertRaises(ValueError):
                retention.plan_retention(api, state())
        api = FakeGitHub()
        api.rewrite_manifest(1, lambda m: m['images'].pop('frontend'))
        with self.assertRaises(ValueError):
            retention.plan_retention(api, state())

    def test_checksum_tampering_blocks_cleanup(self):
        api = FakeGitHub()
        asset = next(a for a in api.assets[8] if a['name'] == 'runtime.tar.gz')
        api.files[asset['id']] += b'tampered'
        with self.assertRaisesRegex(ValueError, 'checksum or size mismatch'):
            retention.plan_retention(api, state())

    def test_missing_retained_image_or_blob_blocks_cleanup(self):
        api = FakeGitHub()
        del api.images['unimeow-frontend', digest('unimeow-frontend-v1.0.8')]
        with self.assertRaisesRegex(RuntimeError, 'Image unavailable'):
            retention.plan_retention(api, state())
        api = FakeGitHub()
        with patch.object(api, 'blob_available', side_effect=RuntimeError('Missing blob')):
            with self.assertRaisesRegex(RuntimeError, 'Missing blob'):
                retention.plan_retention(api, state())
        self.assertEqual(api.deleted, [])

    def test_dependency_cycle_rejected(self):
        api = FakeGitHub()
        package = 'unimeow-frontend'
        root = digest(package + '-v1.0.8')
        api.images[package, root] = {'mediaType': 'application/vnd.oci.image.index.v1+json', 'manifests': [{'digest': root}]}
        with self.assertRaisesRegex(ValueError, 'dependency graph'):
            retention.plan_retention(api, state())

    def test_apply_requires_matching_reviewed_plan(self):
        api = FakeGitHub()
        value = state()
        plan = retention.plan_retention(api, value)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'reviewed plan'):
                retention.apply_plan(api, plan, lambda: value, 'wrong', Path(directory) / 'log.jsonl')
        self.assertEqual(api.deleted, [])

    def test_apply_revalidates_all_assets_before_deleting(self):
        api = FakeGitHub()
        value = state()
        plan = retention.plan_retention(api, value)
        asset = next(a for a in api.assets[8] if a['name'] == 'runtime.tar.gz')
        api.files[asset['id']] = b'gone'
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                retention.apply_plan(api, plan, lambda: value, plan['plan_sha256'], Path(directory) / 'log.jsonl')
        self.assertEqual(api.deleted, [])

    def test_apply_does_not_delete_after_server_or_registry_changes(self):
        api = FakeGitHub()
        value = state()
        plan = retention.plan_retention(api, value)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'server state changed'):
                retention.apply_plan(api, plan, lambda: state(previous='v1.0.1'), plan['plan_sha256'], Path(directory) / 'a.jsonl')
            api.package_versions['unimeow-frontend'][0]['metadata']['container']['tags'].append('new-protected-tag')
            with self.assertRaisesRegex(ValueError, 'state changed'):
                retention.apply_plan(api, plan, lambda: value, plan['plan_sha256'], Path(directory) / 'b.jsonl')
        self.assertEqual(api.deleted, [])

    def test_explicit_apply_removes_only_reviewed_set_and_journals_it(self):
        api = FakeGitHub()
        value = state()
        plan = retention.plan_retention(api, value)
        with tempfile.TemporaryDirectory() as directory:
            journal = Path(directory) / 'log.jsonl'
            retention.apply_plan(api, plan, lambda: value, plan['plan_sha256'], journal)
            events = [json.loads(line)['event'] for line in journal.read_text().splitlines()]
        self.assertEqual(len(api.deleted), 18)
        self.assertEqual(api.deleted[:2], [('release', 3), ('release', 1)])
        self.assertEqual(events[0], 'reviewed_plan')
        self.assertEqual(events[-1], 'complete')

    def test_dry_run_cli_never_calls_delete(self):
        api = FakeGitHub()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            path.write_text(json.dumps(state()))
            with patch.object(retention, 'GitHub', return_value=api), patch('builtins.print'):
                self.assertEqual(retention.main(['--repository', api.repository, '--state', str(path)]), 0)
        self.assertEqual(api.deleted, [])

    def test_foreign_redirect_rejected_and_cross_host_auth_stripped(self):
        request = retention.urllib.request.Request('https://api.github.com/repos/test/test/assets/1', headers={'Authorization': 'Bearer secret'})
        handler = retention.SafeRedirect()
        with self.assertRaises(ValueError):
            handler.redirect_request(request, None, 302, '', {}, 'https://attacker.invalid/object')
        redirected = handler.redirect_request(request, None, 302, '', {}, 'https://release-assets.githubusercontent.com/object')
        self.assertFalse(redirected.has_header('Authorization'))


if __name__ == '__main__':
    unittest.main()
