import copy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import retention_entry as entry
from test_retention import FakeGitHub


class EntrypointArgumentsTests(unittest.TestCase):
    def test_plan_requires_no_confirmation_and_apply_exact_hash(self):
        self.assertEqual(entry.arguments(['plan']), ('plan', None))
        self.assertEqual(entry.arguments(['maintain']), ('maintain', None))
        self.assertEqual(entry.arguments(['apply', 'a' * 64]), ('apply', 'a' * 64))
        for args in [[], ['apply'], ['plan', 'a' * 64], ['apply', '../release'], ['apply', 'A' * 64], ['apply', 'a' * 64, 'extra']]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                entry.arguments(args)

    def test_lifecycle_workflows_share_one_non_canceling_group(self):
        root = Path(__file__).parents[2]
        for name in ['release', 'deploy', 'rollback', 'retention']:
            text = (root / '.github/workflows' / (name + '.yml')).read_text()
            self.assertIn('group: production-release-lifecycle', text)
            self.assertIn('cancel-in-progress: false', text)
        workflow = (root / '.github/workflows/retention.yml').read_text()
        self.assertIn('default: plan', workflow)
        self.assertIn('sudo -n /usr/local/sbin/unimeow-retention', workflow)
        self.assertNotIn('scp ', workflow)
        self.assertIn('workflows: [Release]', workflow)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", workflow)
        self.assertIn('github.event.workflow_run.head_repository.full_name == github.repository', workflow)


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'Requires Linux root-owned synthetic files and flock')
class ServerRetentionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.baseline_id = 'baseline-20260926'
        self.baseline = self.base / self.baseline_id
        self.baseline.mkdir(mode=0o700)
        (self.base / 'config').mkdir(mode=0o700)
        (self.base / 'deployment.lock').touch(mode=0o600)
        self.api = FakeGitHub()
        images = {s: {'image_id': 'sha256:' + hashlib.sha256(s.encode()).hexdigest(),
                      'local_reference': 'unimeow-baseline/' + s + ':20260926'} for s in entry.SERVICES}
        self.tar(self.baseline / 'images.tar', {'manifest.json': b'[]'})
        self.tar(self.baseline / 'configuration.private.tar', {
            'root/UniMeow/.env': b'SYNTHETIC_ONLY=true', 'root/UniMeow/docker-compose.yml': b'services: {}',
            'root/UniMeow/Monitoring/test.yml': b'', 'etc/nginx/test.conf': b'', 'etc/letsencrypt/test.pem': b''})
        digest = hashlib.sha256((self.baseline / 'images.tar').read_bytes()).hexdigest()
        self.write(self.baseline / 'manifest.json', {'release_id': self.baseline_id, 'images': images,
                                                   'images_archive_sha256': digest})
        (self.baseline / 'COMPLETE').write_text(digest + '\n')
        self.write(self.baseline / 'compose.private.json', {'services': {
            s: {'image': v['local_reference'], 'pull_policy': 'never'} for s, v in images.items()}})
        self.write(self.baseline / 'images.private.json', [])
        self.write(self.baseline / 'containers.private.json', [])
        self.write(self.base / 'config/installation.json', {'created_from_release': self.baseline_id, 'project': 'unimeow'})
        self.state = {'status': 'healthy', 'restore_verified': True, 'current_release': self.baseline_id,
                      'project': 'unimeow', 'baseline_path': str(self.baseline)}
        self.save_state()

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))
        path.chmod(0o600)

    def tar(self, path, files):
        with tarfile.open(path, 'w') as archive:
            for name, data in files.items():
                item = tarfile.TarInfo(name)
                item.size = len(data)
                archive.addfile(item, io.BytesIO(data))

    def save_state(self):
        self.write(self.base / 'state.json', self.state)

    def installed(self, current=8, previous=2):
        for number in [current, previous]:
            asset = next(a for a in self.api.assets[number] if a['name'] == 'release.json')
            self.write(self.base / 'releases' / f'v1.0.{number}' / 'release.json', json.loads(self.api.asset(asset['id'])))
        self.state.update(current_release=f'v1.0.{current}', previous_release=f'v1.0.{previous}')
        self.save_state()

    def execute(self, mode='plan', reviewed=None):
        return entry.execute(self.base, mode, reviewed, 'synthetic_token', lambda repo, token: self.api)

    def test_default_plan_verifies_baseline_and_never_mutates_registry(self):
        result = self.execute()
        self.assertEqual(result['result'], 'read-only-plan')
        self.assertIn(self.baseline_id, result['plan']['retained_releases'])
        self.assertEqual(self.api.deleted, [])
        self.assertFalse((self.base / 'retention').exists())
        self.assertNotIn('SYNTHETIC_ONLY', json.dumps(result))

    def public_baseline(self, value='v1.0.0'):
        path = self.base / 'config/installation.json'
        installation = json.loads(path.read_text())
        installation['public_baseline_release'] = value
        self.write(path, installation)

    def test_public_baseline_mapping_keeps_private_archive_identity_and_state_unchanged(self):
        self.public_baseline()
        self.api.add_baseline()
        original = (self.base / 'state.json').read_bytes()
        provider = entry.ServerState(self.base)
        value = provider()
        self.assertEqual(value['baseline_release'], 'v1.0.0')
        self.assertEqual(value['current_release'], 'v1.0.0')
        self.assertIsNone(value['previous_release'])
        self.assertEqual(provider.baseline_id, self.baseline_id)
        result = self.execute()
        self.assertIn('v1.0.0', result['plan']['retained_releases'])
        self.assertNotIn(self.baseline_id, result['plan']['retained_releases'])
        self.assertEqual((self.base / 'state.json').read_bytes(), original)
        self.assertTrue((self.baseline / 'images.tar').is_file())

    def test_previous_internal_baseline_is_public_while_managed_current_images_are_protected(self):
        self.installed()
        self.state['previous_release'] = self.baseline_id
        self.save_state()
        self.public_baseline()
        value = entry.ServerState(self.base)()
        self.assertEqual(value['current_release'], 'v1.0.8')
        self.assertEqual(value['previous_release'], 'v1.0.0')
        self.assertEqual(len(value['protected_images']), 8)
        self.assertEqual(json.loads((self.base / 'state.json').read_text())['previous_release'], self.baseline_id)

    def test_public_baseline_cannot_collide_with_a_managed_installed_release(self):
        self.public_baseline()
        self.state.update(current_release='v1.0.0', previous_release=self.baseline_id)
        self.save_state()
        with self.assertRaisesRegex(ValueError, 'collides'):
            entry.ServerState(self.base)()

    def test_public_naming_never_bypasses_private_baseline_verification(self):
        self.public_baseline()
        path = self.baseline / 'manifest.json'
        manifest = json.loads(path.read_text())
        manifest['release_id'] = 'v1.0.0'
        self.write(path, manifest)
        with self.assertRaisesRegex(ValueError, 'baseline identity'):
            entry.ServerState(self.base)()

    def test_invalid_public_baseline_identifier_is_rejected(self):
        for value in ['../v1.0.0', 'latest', '', None]:
            self.public_baseline(value)
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'valid public baseline'):
                entry.ServerState(self.base)()

    def test_installed_current_and_previous_exact_images_are_protected(self):
        self.installed()
        state = entry.ServerState(self.base)()
        self.assertEqual(len(state['protected_images']), 16)
        self.assertLess((datetime.now(timezone.utc) - datetime.fromisoformat(state['observed_at'])).total_seconds(), 5)
        result = self.execute()
        self.assertNotIn('v1.0.2', {r['tag'] for r in result['plan']['delete_releases']})

    def test_changed_baseline_stops_provider_after_initial_verification(self):
        provider = entry.ServerState(self.base)
        provider()
        with (self.baseline / 'images.tar').open('ab') as archive:
            archive.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'baseline changed'):
            provider()

    def test_baseline_hash_corruption_stops_before_any_api_call(self):
        (self.baseline / 'images.tar').write_bytes(b'corrupt')
        factory = unittest.mock.Mock()
        with self.assertRaisesRegex(ValueError, 'archive failed'):
            entry.execute(self.base, 'plan', None, 'synthetic_token', factory)
        factory.assert_not_called()

    def test_incomplete_private_configuration_is_rejected(self):
        self.tar(self.baseline / 'configuration.private.tar', {'root/UniMeow/.env': b'test'})
        with self.assertRaisesRegex(ValueError, 'configuration archive is incomplete'):
            self.execute()
        self.assertEqual(self.api.deleted, [])

    def test_unhealthy_unverified_or_maintenance_state_stops(self):
        original = copy.deepcopy(self.state)
        for changes in [{'status': 'migrating'}, {'restore_verified': False}, {'current_release': 'v1.0.8'}]:
            self.state = {**original, **changes}
            self.save_state()
            with self.subTest(changes=changes), self.assertRaises((ValueError, OSError)):
                self.execute()
        self.state = original
        self.save_state()
        (self.base / 'maintenance').touch()
        with self.assertRaisesRegex(ValueError, 'maintenance'):
            self.execute()
        self.assertEqual(self.api.deleted, [])

    def test_symlink_or_writable_metadata_is_rejected(self):
        target = self.base / 'state.json'
        real = self.base / 'saved.json'
        target.rename(real)
        target.symlink_to(real)
        with self.assertRaisesRegex(ValueError, 'symlinks'):
            self.execute()
        target.unlink()
        real.rename(target)
        target.chmod(0o666)
        with self.assertRaisesRegex(ValueError, 'root-owned'):
            self.execute()

    def test_existing_deployment_lock_prevents_even_planning(self):
        with entry.deployment_lock(self.base):
            with self.assertRaisesRegex(RuntimeError, 'holds the server lock'):
                self.execute()
        self.assertEqual(self.api.manifest_reads, [])

    def test_apply_holds_same_lock_for_every_removal_and_durably_journals(self):
        import fcntl
        plan = self.execute()['plan']
        deletes = self.api.delete_release, self.api.delete_version
        def locked_delete(action, *args):
            with (self.base / 'deployment.lock').open('a') as probe:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return action(*args)
        with patch.object(self.api, 'delete_release', side_effect=lambda *args: locked_delete(deletes[0], *args)), \
                patch.object(self.api, 'delete_version', side_effect=lambda *args: locked_delete(deletes[1], *args)):
            result = self.execute('apply', plan['plan_sha256'])
        self.assertEqual(result['result'], 'applied')
        journals = list((self.base / 'retention').glob('*.jsonl'))
        self.assertEqual(len(journals), 1)
        records = [json.loads(line) for line in journals[0].read_text().splitlines()]
        self.assertEqual(records[0]['event'], 'reviewed_plan')
        self.assertEqual(records[-1]['event'], 'complete')
        self.assertEqual(journals[0].stat().st_mode & 0o077, 0)
        with entry.deployment_lock(self.base):
            pass

    def test_apply_rejects_changed_plan_or_server_state_without_deletion(self):
        plan = self.execute()['plan']
        self.installed()
        with self.assertRaisesRegex(ValueError, 'reviewed plan SHA256'):
            self.execute('apply', plan['plan_sha256'])
        self.assertEqual(self.api.deleted, [])

    def test_automatic_maintenance_uses_fresh_plan_and_protects_installed_releases(self):
        self.installed()
        result = self.execute('maintain')
        self.assertEqual(result['result'], 'applied')
        self.assertEqual({row[1] for row in self.api.deleted if row[0] == 'release'}, {1, 3})
        self.assertNotIn(2, {row[1] for row in self.api.deleted if row[0] == 'release'})
        self.assertTrue((self.baseline / 'images.tar').is_file())

    def test_state_is_reread_between_deletes_and_stops_on_external_mutation(self):
        plan = self.execute()['plan']
        original = self.api.delete_release
        def delete_and_change(release_id):
            original(release_id)
            self.state['status'] = 'migrating'
            self.save_state()
        with patch.object(self.api, 'delete_release', side_effect=delete_and_change):
            with self.assertRaisesRegex(ValueError, 'healthy'):
                self.execute('apply', plan['plan_sha256'])
        self.assertEqual(len(self.api.deleted), 1)
        journal = next((self.base / 'retention').glob('*.jsonl'))
        self.assertEqual(json.loads(journal.read_text().splitlines()[-1])['event'], 'stopped')


if __name__ == '__main__':
    unittest.main()
