"""Failure injection for the isolated upgrade path; never contacts Docker."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import upgrade_rehearsal as upgrade
from release import APPROVED_MINIO_IMAGE, SERVICES
PRIVATE_COMMAND = upgrade.command


class UpgradeRehearsalTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.project = 'unimeow-restore-20260926192039'
        self.work = self.base / 'rehearsals' / self.project
        self.work.mkdir(parents=True)
        self.release = self.base / 'releases/v1.0.0'
        (self.release / 'ops').mkdir(parents=True)
        (self.release / 'ops/smoke.py').write_text('# synthetic smoke checker')
        baseline = self.base / 'baseline'
        baseline.mkdir()
        self.images = {s: f'ghcr.io/example/unimeow-{s}@sha256:' + 'a' * 64 for s in SERVICES}
        self.manifest = {'release_id': 'v1.0.0', 'commit': 'b' * 40, 'architecture': 'linux/amd64',
                         'images': self.images, 'migration_services': list(upgrade.MIGRATORS),
                         'infrastructure_images': {'minio': APPROVED_MINIO_IMAGE}}
        (self.release / 'release.json').write_text(json.dumps(self.manifest))
        self.baseline_ids = {s: 'old-id-' + s for s in SERVICES}
        (baseline / 'manifest.json').write_text(json.dumps({'release_id': 'baseline-20260926',
            'images': {s: {'image_id': value} for s, value in self.baseline_ids.items()}}))
        self.state = {'status': 'healthy', 'project': 'production', 'current_release': 'baseline-20260926',
                      'restore_verified': True, 'rehearsal_project': self.project,
                      'rehearsal_path': str(self.work), 'baseline_path': str(baseline), 'backup': 'original-backup'}
        (self.base / 'state.json').write_text(json.dumps(self.state))
        names = list(SERVICES) + sorted(upgrade.INFRA)
        self.old = {'services': {s: {'image': 'baseline/' + s, 'environment': {'SECRET': 'a$b'},
                    'ports': [{'target': 80, 'published': '8080'}], 'networks': {'internal': None}}
                    for s in names}, 'volumes': {'data': {'name': 'clone-data'}},
                    'networks': {'internal': {}}}
        self.old['services']['postgres-user']['volumes'] = [
            {'type': 'volume', 'source': 'data', 'target': '/var/lib/postgresql/data'}]
        (self.work / 'compose.private.json').write_text(json.dumps(self.old))
        baseline_config = copy.deepcopy(self.old)
        baseline_config['volumes']['data']['name'] = 'original-data'
        (baseline / 'compose.private.json').write_text(json.dumps(baseline_config))
        self.new = copy.deepcopy(self.old)
        self.new['volumes'] = {'new_data_alias': {'name': 'original-data'}}
        self.new['services']['postgres-user']['volumes'][0]['source'] = 'new_data_alias'
        self.new['services'].update({s: {'image': image, 'environment': {}, 'networks': {'internal': None}}
                                    for s, image in self.images.items()})
        self.new['services']['gateway-proxy'] = {'image': 'nginx@sha256:synthetic', 'environment': {}}
        self.new['services']['minio']['image'] = APPROVED_MINIO_IMAGE
        for name, app in upgrade.MIGRATORS.items():
            self.new['services'][name] = {'image': self.images[app], 'environment': {}, 'command': ['migrate']}
        self.original = [self.container('original', s, 'old-id-' + s, True) for s in names]
        self.clone = {s: self.container('clone', s, 'old-id-' + s, False) for s in names}
        self.calls = []
        self.fail = None
        self.readonly_calls = []
        self.patches = [patch.object(upgrade, 'BASE', self.base),
                        patch.object(upgrade, 'command', self.command),
                        patch.object(upgrade, 'inspect_project', self.inspect),
                        patch.object(upgrade, 'wait_healthy', lambda *_: None),
                        patch.object(upgrade, 'smoke', self.smoke)]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def container(self, prefix, name, image, running):
        return {'Id': prefix + '-' + name, 'Image': image,
                'Config': {'Labels': {upgrade.LABEL: name, 'com.docker.compose.project':
                                     'production' if prefix == 'original' else self.project}},
                'Mounts': [{'Type': 'volume', 'Name': 'original-data' if prefix == 'original' else 'clone-data'}],
                'State': {'Running': running, 'Pid': 123},
                'NetworkSettings': {'Networks': {'isolated': {'IPAddress': '172.31.0.5'}}}}

    def inspect(self, project):
        return self.original if project == 'production' else list(self.clone.values())

    def command(self, *args):
        self.calls.append(args)
        if self.fail and self.fail(args):
            raise RuntimeError('Injected failure')
        if args[:3] == ('docker', 'image', 'inspect'):
            ref = args[3]
            if ref.startswith('baseline/'):
                identity = 'old-id-' + ref.split('/')[1]
            elif ref == APPROVED_MINIO_IMAGE:
                identity = 'new-id-minio'
            else:
                identity = 'new-id-' + next((s for s, image in self.images.items() if image == ref), 'gateway-proxy')
            return json.dumps([{'Id': identity}])
        if args[:3] == ('docker', 'volume', 'inspect'):
            return json.dumps([{'Labels': {'unimeow.restore': self.project}}])
        if args[:3] == ('docker', 'network', 'ls'):
            return ''
        if args[:2] == ('docker', 'compose') and 'up' in args:
            name = args[-1]
            old = 'baseline.private.json' in args[5]
            self.clone[name] = self.container('clone', name, ('old-id-' if old else 'new-id-') + name, True)
        if '--snapshot' in args:
            Path(args[-1]).write_text('{}')
        if args[:2] == ('docker', 'inspect'):
            return json.dumps(self.original)
        if args[:2] == ('docker', 'stop'):
            for name, item in self.clone.items():
                if item['Id'] in args:
                    item['State']['Running'] = False
        return ''

    def smoke(self, project, script, snapshot, read_only):
        self.readonly_calls.append(read_only)
        self.assertEqual(project, self.project)
        self.assertEqual(script, self.release / 'ops/smoke.py')
        self.assertTrue(snapshot.is_file())
        self.assertEqual(self.clone['minio']['Image'], 'new-id-minio')

    def saved_state(self):
        return json.loads((self.base / 'state.json').read_text())

    def test_exact_images_migrate_twice_before_baseline_then_new_release_and_restore_original(self):
        proof = upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertEqual(self.readonly_calls, [True, False, True])
        self.assertTrue(proof['verified'])
        self.assertTrue(proof['old_code_compatible'])
        self.assertEqual(proof['baseline_release'], 'baseline-20260926')
        self.assertEqual(self.saved_state()['upgrade_rehearsals']['v1.0.0']['release_commit'], 'b' * 40)
        self.assertEqual(self.saved_state()['current_release'], 'baseline-20260926')
        self.assertEqual(proof['infrastructure_images'], {'minio': APPROVED_MINIO_IMAGE})
        self.assertEqual(proof['infrastructure_image_ids'], {'minio': 'new-id-minio'})
        migrations = [a for a in self.calls if 'run' in a and a[-1] in upgrade.MIGRATORS]
        self.assertEqual([a[-1] for a in migrations], list(upgrade.MIGRATORS) * 2)
        baseline_start = next(i for i, a in enumerate(self.calls) if 'up' in a and a[-1] == 'eureka-server')
        self.assertTrue(all(self.calls.index(a) < baseline_start for a in migrations))
        minio_upgrade = next(i for i, a in enumerate(self.calls) if 'up' in a and a[-1] == 'minio'
                             and 'release.private.json' in a[5])
        self.assertLess(max(self.calls.index(a) for a in migrations), minio_upgrade)
        self.assertLess(minio_upgrade, baseline_start)
        self.assertFalse(any('up' in a and a[-1] == 'minio' and 'baseline.private.json' in a[5]
                             for a in self.calls))
        other_infra_upgrades = [a[-1] for a in self.calls if 'up' in a and a[-1] in upgrade.INFRA - {'minio'}
                                and 'release.private.json' in a[5]]
        self.assertEqual(other_infra_upgrades, [])
        starts = [a[-1] for a in self.calls if a[:2] == ('docker', 'start')]
        self.assertEqual(set(starts), {c['Id'] for c in self.original})
        self.assertLess(starts.index('original-kafka'), starts.index('original-eureka-server'))
        self.assertLess(starts.index('original-eureka-server'), starts.index('original-api-gateway'))
        self.assertFalse((self.base / 'maintenance').exists())
        self.assertFalse(any(token in ('down', 'build', 'rm', 'prune') for a in self.calls for token in a))

    def test_migration_failure_restores_original_without_success_proof(self):
        self.fail = lambda a: a[-1] == 'post-migrate'
        with self.assertRaisesRegex(RuntimeError, 'original production recovered'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertNotIn('upgrade_rehearsals', self.saved_state())
        self.assertFalse((self.base / 'maintenance').exists())
        starts = [a[-1] for a in self.calls if a[:2] == ('docker', 'start')]
        self.assertEqual(set(starts), {c['Id'] for c in self.original})
        self.assertFalse(self.readonly_calls)

    def test_clone_cleanup_error_still_attempts_every_original_restart_and_keeps_gate(self):
        def fail(args):
            return args[:2] == ('docker', 'stop') and self.readonly_calls and any(x.startswith('clone-') for x in args)
        self.fail = fail
        with self.assertRaisesRegex(RuntimeError, 'recovery incomplete'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        starts = [a[-1] for a in self.calls if a[:2] == ('docker', 'start')]
        self.assertEqual(set(starts), {c['Id'] for c in self.original})
        self.assertTrue((self.base / 'maintenance').exists())
        self.assertNotIn('upgrade_rehearsals', self.saved_state())

    def test_failed_original_start_does_not_prevent_other_recovery_attempts(self):
        self.fail = lambda a: a[:2] == ('docker', 'start') and a[-1] == 'original-postgres-user'
        with self.assertRaisesRegex(RuntimeError, 'recovery incomplete'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertIn(('docker', 'start', 'original-api-gateway'), self.calls)
        self.assertTrue((self.base / 'maintenance').exists())
        self.assertNotIn('upgrade_rehearsals', self.saved_state())

    def test_prod_volume_collision_fails_before_any_stop(self):
        self.old['volumes']['data']['name'] = 'original-data'
        (self.work / 'compose.private.json').write_text(json.dumps(self.old))
        with self.assertRaisesRegex(RuntimeError, 'distinct'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertFalse(any(a[:2] == ('docker', 'stop') for a in self.calls))

    def test_mutable_baseline_tag_change_fails_before_maintenance(self):
        self.baseline_ids['frontend'] = 'wrong-id'
        path = self.base / 'baseline/manifest.json'
        manifest = json.loads(path.read_text())
        manifest['images']['frontend']['image_id'] = 'wrong-id'
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(RuntimeError, 'identity changed'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertFalse((self.base / 'maintenance').exists())

    def test_isolation_retains_jwt_and_literal_secrets_but_removes_external_access(self):
        original = copy.deepcopy(self.old)
        config = upgrade.isolated(self.old, self.project, {'data': 'clone-data'})
        self.assertEqual(self.old, original)
        self.assertTrue(config['networks']['internal']['internal'])
        self.assertEqual(config['volumes']['data'], {'name': 'clone-data', 'external': True})
        for name, item in config['services'].items():
            self.assertNotIn('ports', item)
            if name in SERVICES and name != 'frontend':
                self.assertEqual(item['mem_limit'], '512m')
                self.assertIn('-Xmx256m', item['environment']['JAVA_TOOL_OPTIONS'])
        self.assertEqual(upgrade.compose_literal(config)['services']['api-gateway']['environment']['SECRET'], 'a$$b')

    def test_writable_host_bind_rejected(self):
        self.old['services']['postgres-user']['volumes'] = [
            {'type': 'bind', 'source': '/production', 'target': '/data'}]
        with self.assertRaisesRegex(RuntimeError, 'writable host paths'):
            upgrade.isolated(self.old, self.project, {'data': 'clone-data'})

    def test_release_aliases_are_mapped_by_actual_volume_identity(self):
        baseline = {'volumes': {'saved_original': {'name': 'actual-original'}}}
        config = {'volumes': {'new_alias': {'name': 'actual-original'}},
                  'services': {'db': {'volumes': [{'type': 'volume', 'source': 'new_alias', 'target': '/data'}]}}}
        mapped = upgrade.map_release_volumes(config, baseline, {'saved_original': 'restored-private'})
        self.assertEqual(mapped['services']['db']['volumes'][0]['source'], 'saved_original')
        self.assertEqual(mapped['volumes'], {'saved_original': {'name': 'restored-private'}})
        self.assertEqual(config['services']['db']['volumes'][0]['source'], 'new_alias')
        config['volumes']['new_alias']['name'] = 'unknown-production-volume'
        with self.assertRaisesRegex(RuntimeError, 'not in the saved baseline'):
            upgrade.map_release_volumes(config, baseline, {'saved_original': 'restored-private'})

    def test_explicit_interruption_recovery_uses_saved_ids_and_does_not_claim_verification(self):
        directory = self.work / 'upgrade-v1.0.0'
        directory.mkdir()
        (directory / 'original-containers.private.json').write_text(json.dumps(self.original))
        (directory / 'journal.private.json').write_text(json.dumps({
            'phase': 'running', 'production_project': 'production', 'clone_project': self.project}))
        (self.base / 'maintenance').touch()
        upgrade.recover(directory)
        self.assertFalse((self.base / 'maintenance').exists())
        self.assertEqual(json.loads((directory / 'journal.private.json').read_text())['phase'], 'recovered')
        self.assertNotIn('upgrade_rehearsals', self.saved_state())
        starts = [a[-1] for a in self.calls if a[:2] == ('docker', 'start')]
        self.assertEqual(set(starts), {c['Id'] for c in self.original})

    def test_failed_command_retains_private_diagnostics_without_secret_in_exception(self):
        diagnostics = self.work / 'diagnostics'
        diagnostics.mkdir(mode=0o700)
        with patch.object(upgrade, 'DIAGNOSTICS', diagnostics), patch.object(upgrade.subprocess, 'run') as run:
            run.return_value.returncode = 1
            run.return_value.stdout = 'synthetic-private-value'
            run.return_value.stderr = 'private-diagnostic'
            with self.assertRaises(RuntimeError) as failure:
                PRIVATE_COMMAND('docker', 'synthetic-operation')
        self.assertNotIn('synthetic-private-value', str(failure.exception))
        files = list(diagnostics.glob('*.private.json'))
        self.assertEqual(len(files), 1)
        self.assertEqual(files[0].stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(files[0].read_text())['stderr'], 'private-diagnostic')

    def test_existing_external_network_is_rejected_before_original_stop(self):
        def network_command(*args):
            if args[:3] == ('docker', 'network', 'ls'):
                return self.project + '_internal\n'
            if args[:3] == ('docker', 'network', 'inspect'):
                return json.dumps([{'Internal': False}])
            return self.command(*args)
        with patch.object(upgrade, 'command', network_command):
            with self.assertRaisesRegex(RuntimeError, 'permits external traffic'):
                upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertFalse(any(a[:2] == ('docker', 'stop') for a in self.calls))

    def test_minio_image_mismatch_is_rejected_before_maintenance(self):
        self.new['services']['minio']['image'] = 'unapproved/minio:latest'
        with self.assertRaisesRegex(RuntimeError, 'Infrastructure Compose image differs'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertFalse((self.base / 'maintenance').exists())
        self.assertFalse(any(a[:2] == ('docker', 'stop') for a in self.calls))

    def test_minio_upgrade_failure_restores_exact_original_containers_without_proof(self):
        self.fail = lambda a: 'up' in a and a[-1] == 'minio' and 'release.private.json' in a[5]
        with self.assertRaisesRegex(RuntimeError, 'original production recovered'):
            upgrade.rehearse(self.release, resolved_config=self.new)
        self.assertFalse(self.readonly_calls)
        self.assertNotIn('upgrade_rehearsals', self.saved_state())
        self.assertIn(('docker', 'start', 'original-minio'), self.calls)
        self.assertEqual(next(c for c in self.original if upgrade.service(c) == 'minio')['Image'], 'old-id-minio')
        self.assertFalse((self.base / 'maintenance').exists())


if __name__ == '__main__':
    unittest.main()
