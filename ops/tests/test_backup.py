import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup', Path(__file__).parents[1] / 'backup.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
linux_only = unittest.skipUnless(os.name == 'posix', 'Run real durability checks in the isolated Linux container')


class BackupSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def fixture(self, path):
        path.mkdir()
        (path / 'volumes').mkdir()
        for name, entry, content in [('volumes/v.tar', 'synthetic-data', b'original synthetic data'),
                                     ('config.tar', 'production.env', b'APP_ENV=synthetic\n')]:
            with tarfile.open(path / name, 'w') as archive:
                member = tarfile.TarInfo(entry)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
        (path / 'containers.private.json').write_text('[{"Id":"synthetic-container"}]')
        manifest = {'version': 1, 'volumes': {'v': '/synthetic-original'},
                    'files': {name: backup.sha256(path / name)
                              for name in ['volumes/v.tar', 'config.tar', 'containers.private.json']}}
        (path / 'manifest.json').write_text(json.dumps(manifest))
        (path / 'COMPLETE').write_text('verified\n')
        return path

    def pair(self):
        current = self.fixture(self.root / 'backup-current')
        previous = self.fixture(self.root / 'backup-previous')
        (self.root / 'current').symlink_to(previous.name)
        self.intent(current, previous)
        return current, previous

    def intent(self, current, previous, phase='prepared'):
        backup.atomic_json(self.root / 'rotation.json', {
            'current': str(current), 'previous': str(previous) if previous else None, 'phase': phase})

    def assert_completed(self, current, previous):
        self.assertEqual((self.root / 'current').resolve(), current.resolve())
        self.assertFalse(previous.exists())
        self.assertEqual(json.loads((self.root / 'rotation.json').read_text())['phase'], 'complete')
        backup.validate_backup(current)

    def test_complete_fixture_validates_real_tar_streams(self):
        path = self.fixture(self.root / 'backup-valid')
        self.assertEqual(set(backup.validate_backup(path)['files']),
                         {'volumes/v.tar', 'config.tar', 'containers.private.json'})

    def test_corrupt_archive_never_validates(self):
        path = self.fixture(self.root / 'backup-old')
        (path / 'volumes/v.tar').write_bytes(b'corrupt')
        with self.assertRaisesRegex(RuntimeError, 'Checksum mismatch'):
            backup.validate_backup(path)

    def test_checksum_of_invalid_tar_is_not_sufficient(self):
        path = self.fixture(self.root / 'backup-old')
        (path / 'volumes/v.tar').write_bytes(b'not a tar archive')
        manifest = json.loads((path / 'manifest.json').read_text())
        manifest['files']['volumes/v.tar'] = backup.sha256(path / 'volumes/v.tar')
        (path / 'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaises(subprocess.CalledProcessError):
            backup.validate_backup(path)

    def test_missing_volume_coverage_or_complete_marker_never_validates(self):
        path = self.fixture(self.root / 'backup-old')
        manifest = json.loads((path / 'manifest.json').read_text())
        manifest['files'].pop('volumes/v.tar')
        (path / 'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(RuntimeError, 'does not cover'):
            backup.validate_backup(path)
        (path / 'COMPLETE').unlink()
        with self.assertRaisesRegex(RuntimeError, 'incomplete'):
            backup.validate_backup(path)

    def test_unsafe_volume_name_never_validates(self):
        path = self.fixture(self.root / 'backup-old')
        manifest = json.loads((path / 'manifest.json').read_text())
        manifest['volumes'] = {'../production': '/synthetic'}
        (path / 'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(RuntimeError, 'Unsafe backup volume name'):
            backup.validate_backup(path)

    def test_retirement_cannot_target_current_or_production(self):
        current = self.fixture(self.root / 'backup-current')
        for previous in [current, self.root / 'production-volume', self.root.parent]:
            with self.assertRaisesRegex(RuntimeError, 'unsafe'):
                backup.retire_previous(self.root, current, previous)
        self.assertTrue((current / 'volumes/v.tar').exists())

    @linux_only
    def test_only_verified_previous_backup_is_retired(self):
        current, previous = self.pair()
        unrelated = self.root / 'unrelated'
        unrelated.mkdir()
        backup.retire_previous(self.root, current, previous)
        self.assertFalse(previous.exists())
        self.assertTrue(current.exists())
        self.assertTrue(unrelated.exists())

    @linux_only
    def test_recovery_after_durable_intent_before_pointer_switch(self):
        current, previous = self.pair()
        self.assertEqual((self.root / 'current').resolve(), previous)
        backup.recover_rotation(self.root)
        self.assert_completed(current, previous)
        backup.recover_rotation(self.root)
        self.assert_completed(current, previous)

    @linux_only
    def test_recovery_after_pointer_switch_before_previous_retirement(self):
        current, previous = self.pair()
        (self.root / 'current').unlink()
        (self.root / 'current').symlink_to(current.name)
        backup.recover_rotation(self.root)
        self.assert_completed(current, previous)

    @linux_only
    def test_partial_previous_deletion_resumes_without_revalidating_partial_archive(self):
        current, previous = self.pair()

        def interrupted_retirement(path):
            self.assertEqual(json.loads((self.root / 'rotation.json').read_text())['phase'], 'retiring')
            self.assertEqual((self.root / 'current').resolve(), current)
            backup.validate_backup(current)
            (Path(path) / 'volumes/v.tar').unlink()
            raise OSError('synthetic power loss during retirement')

        with patch.object(backup.shutil, 'rmtree', side_effect=interrupted_retirement):
            with self.assertRaisesRegex(OSError, 'power loss'):
                backup.recover_rotation(self.root)
        self.assertTrue(previous.exists())
        self.assertFalse((previous / 'volumes/v.tar').exists())
        backup.recover_rotation(self.root)
        self.assert_completed(current, previous)

    @linux_only
    def test_recovery_after_retirement_before_complete_journal_write(self):
        current, previous = self.pair()
        original_atomic = backup.atomic_json

        def interrupted_complete(path, value):
            if value.get('phase') == 'complete':
                raise OSError('synthetic power loss before complete journal write')
            original_atomic(path, value)

        with patch.object(backup, 'atomic_json', side_effect=interrupted_complete):
            with self.assertRaisesRegex(OSError, 'power loss'):
                backup.recover_rotation(self.root)
        self.assertFalse(previous.exists())
        backup.recover_rotation(self.root)
        self.assert_completed(current, previous)

    @linux_only
    def test_corrupt_new_copy_preserves_pointer_and_previous_complete_copy(self):
        current, previous = self.pair()
        (current / 'config.tar').write_bytes(b'corrupt')
        with self.assertRaisesRegex(RuntimeError, 'Checksum mismatch'):
            backup.recover_rotation(self.root)
        self.assertEqual((self.root / 'current').resolve(), previous)
        backup.validate_backup(previous)

    @linux_only
    def test_first_backup_prepared_intent_recovers_without_previous_copy(self):
        current = self.fixture(self.root / 'backup-current')
        self.intent(current, None)
        backup.recover_rotation(self.root)
        self.assertEqual((self.root / 'current').resolve(), current)
        self.assertEqual(json.loads((self.root / 'rotation.json').read_text())['phase'], 'complete')

    @linux_only
    def test_current_backup_path_symlink_rejected_before_old_copy_removed(self):
        current, previous = self.pair()
        alias = self.root / 'backup-alias'
        alias.symlink_to(current, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            backup.retire_previous(self.root, alias, previous)
        self.assertTrue(previous.exists())
        self.intent(alias, previous)
        with self.assertRaises(RuntimeError):
            backup.recover_rotation(self.root)
        self.assertTrue(previous.exists())

    @linux_only
    def test_current_backup_outside_root_rejected_before_retirement(self):
        managed = self.root / 'managed'
        managed.mkdir()
        previous = self.fixture(managed / 'backup-previous')
        external = self.fixture(self.root / 'backup-external')
        with self.assertRaises(RuntimeError):
            backup.retire_previous(managed, external, previous)
        self.assertTrue(previous.exists())
        self.assertTrue(external.exists())

    @linux_only
    def test_previous_symlink_never_deletes_a_different_backup(self):
        current, previous = self.pair()
        unrelated = self.fixture(self.root / 'backup-unrelated')
        alias = self.root / 'backup-alias'
        alias.symlink_to(unrelated, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            backup.retire_previous(self.root, current, alias)
        self.assertTrue(unrelated.exists())

    def test_insufficient_space_preserves_previous_before_copying(self):
        previous = self.fixture(self.root / 'backup-previous')
        with patch.object(backup, 'volumes_for', return_value={'v': '/volume'}), \
             patch.object(backup, 'run', side_effect=['', '100 /volume']), \
             patch.object(backup.shutil, 'disk_usage', return_value=type('Usage', (), {'free': 1})()):
            with self.assertRaisesRegex(RuntimeError, 'Insufficient free space'):
                backup.create_backup(self.root, 'test', 'v1', [], [], self.root)
        self.assertTrue((previous / 'COMPLETE').exists())
        self.assertEqual([p.name for p in self.root.iterdir()], ['backup-previous'])

    def test_running_writer_blocks_backup(self):
        with patch.object(backup, 'volumes_for', return_value={'v': '/volume'}), \
             patch.object(backup, 'run', side_effect=['container-id', json.dumps([{'Name': 'database', 'Mounts': [{'Name': 'v'}]}])]):
            with self.assertRaisesRegex(RuntimeError, 'writer is still running'):
                backup.create_backup(self.root, 'test', 'v1', [], [], self.root)


if __name__ == '__main__':
    unittest.main()
