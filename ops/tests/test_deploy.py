"""Deployment failure injection; all files and containers are synthetic."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0,str(Path(__file__).parents[1]))
import deploy
from release import SERVICES, APPROVED_MINIO_IMAGE


class DeploymentRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name);(self.base/'config').mkdir()
        self.backups=self.base/'backups';self.backups.mkdir()
        self.original=self.backups/'backup-original';self.original.mkdir()
        self.fresh=self.backups/'backup-fresh';self.fresh.mkdir()
        (self.backups/'current').symlink_to(self.original.name,target_is_directory=True)
        self.directory=self.base/'releases/v1.0.0';self.directory.mkdir(parents=True)
        images={s:f'ghcr.io/kaze-nomi/unimeow-{s}@sha256:'+('a'*64) for s in SERVICES}
        self.manifest={'release_id':'v1.0.0','commit':'b'*40,'architecture':'linux/amd64','images':images,
                       'infrastructure_images':{'minio':APPROVED_MINIO_IMAGE},
                       'migration_services':['user-migrate','post-migrate','notification-migrate']}
        (self.directory/'release.json').write_text(json.dumps(self.manifest))
        self.initial={'status':'healthy','project':'test','current_release':'baseline-20260926',
                      'restore_verified':True,'baseline_path':str(self.base/'baseline-20260926'),'backup':str(self.original),
                      'upgrade_rehearsals':{'v1.0.0':{'verified':True,'old_code_compatible':True,
                        'release_commit':self.manifest['commit'],'images':images,
                        'infrastructure_images':self.manifest['infrastructure_images']}}}
        self.save_state(self.initial)
        (self.base/'config/installation.json').write_text(json.dumps({'volumes':['existing-data']}))
        self.services={s:{'image':images.get(s,'infra-image-'+s),'environment':{}}
                       for s in set(SERVICES)|deploy.INFRA|{'gateway-proxy'}}
        self.services['api-gateway']['environment']={'APP_SECURITY_SECURE_COOKIE':'true','APP_SECURITY_OAUTH2_SUCCESS_REDIRECT':'https://example.test/'}
        self.services['frontend']['environment']={'FRONTEND_API_BASE':'https://example.test','MINIO_PUBLIC_URL':'https://media.example.test'}
        self.containers=[{'Id':'container-'+s,'Image':self.services[s]['image'],
                          'State':{'Running':True,'Health':{'Status':'healthy'}},
                          'Config':{'Labels':{'com.docker.compose.service':s}}} for s in self.services]
        self.services['minio']['image']=APPROVED_MINIO_IMAGE
        for migrator,app in deploy.MIGRATORS.items():self.services[migrator]={'image':images[app],'environment':{}}
        self.config={'services':self.services,'volumes':{'data':{'name':'existing-data'}}}
        self.calls=[];self.fail=None;self.after_smoke=None
        self.healthy=Mock();self.validate=Mock(return_value=True);self.rotation=Mock()
        self.drained=Mock(side_effect=lambda project,require_active=True,stable_samples=2:
                          self.calls.append(('drain',project,require_active,stable_samples)))
        self.backup=Mock(side_effect=self.complete_backup)
        for name,value in [('BASE',self.base),('BACKUPS',self.backups),('run',self.run_command),
                           ('inspect_project',lambda _:copy.deepcopy(self.containers)),('wait_healthy',self.healthy),
                           ('validate_backup',self.validate),('recover_rotation',self.rotation),
                           ('drain',self.drained),('create_backup',self.backup)]:
            p=patch.object(deploy,name,value);p.start();self.addCleanup(p.stop)

    def save_state(self,value):(self.base/'state.json').write_text(json.dumps(value))
    def state(self):return json.loads((self.base/'state.json').read_text())
    def container(self,service):return next(c for c in self.containers if c['Config']['Labels']['com.docker.compose.service']==service)

    def complete_backup(self,*args):
        self.calls.append(('backup',))
        (self.backups/'current').unlink();(self.backups/'current').symlink_to(self.fresh.name,target_is_directory=True)
        return self.fresh

    def run_command(self,*args,capture=False):
        self.calls.append(args)
        if self.fail and self.fail(args):raise RuntimeError('injected failure')
        if args[-3:]==('config','--format','json'):return json.dumps(self.config)
        if args[:3]==('docker','image','inspect'):return json.dumps([{'Id':args[3]}])
        if args[:2] in [('docker','stop'),('docker','start')]:
            for container in self.containers:
                if container['Id'] in args:container['State']['Running']=args[1]=='start'
        if args[:2]==('docker','compose') and 'up' in args:
            for container in self.containers:
                service=container['Config']['Labels']['com.docker.compose.service']
                if service==args[-1]:
                    container['State']={'Running':True,'Health':{'Status':'healthy'}}
                    container['Image']=self.services[service]['image']
        if args[0]=='python3' and '--mode' in args and '--read-only' not in args and self.after_smoke:self.after_smoke()
        return ''

    def test_failed_migration_keeps_good_backup_and_retry_does_not_resnapshot(self):
        self.fail=lambda args:args[-1]=='post-migrate'
        with self.assertRaisesRegex(RuntimeError,'injected'):deploy.deploy(self.directory)
        self.assertEqual(self.state()['status'],'failed');self.assertEqual(self.state()['backup'],str(self.fresh))
        self.assertEqual(self.state()['current_release'],'baseline-20260926')
        self.assertTrue((self.base/'maintenance').exists());self.assertEqual(self.backup.call_count,1)
        self.fail=None;deploy.deploy(self.directory)
        self.assertEqual(self.backup.call_count,1);self.assertEqual(self.state()['current_release'],'v1.0.0')
        self.assertFalse((self.base/'maintenance').exists())

    def test_backup_failure_restarts_original_containers_without_migrating(self):
        self.backup.side_effect=RuntimeError('backup failed')
        with self.assertRaisesRegex(RuntimeError,'backup failed'):deploy.deploy(self.directory)
        self.assertEqual(self.state()['status'],'healthy');self.assertEqual(self.state()['backup'],str(self.original))
        self.assertFalse((self.base/'maintenance').exists())
        self.assertFalse(any(args[-1] in self.manifest['migration_services'] for args in self.calls))
        started=[args[2] for args in self.calls if args[:2]==('docker','start')]
        self.assertLess(started.index('container-eureka-server'),started.index('container-api-gateway'))

    def test_rotated_backup_failure_reconciles_state_before_opening_traffic(self):
        def interrupted(*args):
            self.complete_backup(*args);self.original.rmdir();raise RuntimeError('retirement interrupted')
        self.backup.side_effect=interrupted
        with self.assertRaisesRegex(RuntimeError,'retirement interrupted'):deploy.deploy(self.directory)
        self.rotation.assert_called_with(self.backups);self.validate.assert_called_with(self.fresh)
        self.assertEqual(self.state()['backup'],str(self.fresh));self.assertEqual(self.state()['status'],'healthy')
        self.assertFalse((self.base/'maintenance').exists())

    def test_unverifiable_rotated_backup_blocks_recovery_and_keeps_gate_closed(self):
        self.backup.side_effect=RuntimeError('backup failed');self.validate.side_effect=RuntimeError('current archive corrupt')
        with self.assertRaisesRegex(RuntimeError,'maintenance remains closed'):deploy.deploy(self.directory)
        self.assertEqual(self.state()['status'],'backup_recovery_failed');self.assertEqual(self.state()['target_release'],'v1.0.0')
        self.assertTrue((self.base/'maintenance').exists())
        self.assertFalse(any(args[-1] in self.manifest['migration_services'] for args in self.calls))
        self.validate.side_effect=None;self.backup.side_effect=self.complete_backup
        deploy.deploy(self.directory)
        self.assertEqual(self.backup.call_count,2);self.assertEqual(self.state()['status'],'healthy')

    def test_rotation_recovery_error_never_reopens_traffic(self):
        self.backup.side_effect=RuntimeError('backup failed');self.rotation.side_effect=RuntimeError('partial retirement blocked')
        with self.assertRaisesRegex(RuntimeError,'maintenance remains closed'):deploy.deploy(self.directory)
        self.assertEqual(self.state()['status'],'backup_recovery_failed');self.assertTrue((self.base/'maintenance').exists())

    def test_wrong_application_or_migrator_image_is_rejected_before_any_mutation(self):
        for service in [*SERVICES,*deploy.MIGRATORS,'minio']:
            original=self.services[service]['image'];self.services[service]['image']='wrong-image'
            with self.subTest(service=service),self.assertRaisesRegex(RuntimeError,'Resolved image differs'):deploy.deploy(self.directory)
            self.services[service]['image']=original
        self.assertFalse(any(args[:2] in [('docker','pull'),('docker','stop')] for args in self.calls))
        self.backup.assert_not_called();self.assertFalse((self.base/'maintenance').exists())

    def test_ingress_drains_then_core_stops_before_final_drain_and_snapshot(self):
        deploy.deploy(self.directory)
        stages=[i for i,args in enumerate(self.calls) if args[0]=='drain']
        stops=[(i,args) for i,args in enumerate(self.calls) if args[:2]==('docker','stop')]
        self.assertEqual(len(stages),2);self.assertEqual(set(stops[0][1][4:]),{'container-'+s for s in deploy.INGRESS})
        self.assertLess(stops[0][0],stages[0]);self.assertTrue(all(stages[0]<i<stages[1] for i,args in stops[1:3]))
        self.assertEqual(self.calls[stages[1]],('drain','test',False,1))
        snapshot=next(i for i,args in enumerate(self.calls) if '--snapshot' in args)
        self.assertLess(stages[1],snapshot);self.assertLess(snapshot,stops[-1][0])

    def test_drain_failure_never_snapshots_pending_work_or_runs_migrations(self):
        self.drained.side_effect=RuntimeError('pending asynchronous work')
        with self.assertRaisesRegex(RuntimeError,'pending asynchronous work'):deploy.deploy(self.directory)
        self.backup.assert_not_called()
        self.assertFalse(any('--snapshot' in args or args[-1] in deploy.MIGRATORS for args in self.calls))
        self.assertEqual(self.state()['status'],'healthy');self.assertFalse((self.base/'maintenance').exists())

    def test_all_seventeen_services_are_verified_together(self):
        deploy.deploy(self.directory)
        self.assertEqual(set(self.healthy.call_args.args[0]),{'container-'+s for s in deploy.INFRA|set(deploy.APPLICATIONS)})

    def test_minio_upgrade_is_pulled_before_gate_and_started_only_after_backup(self):
        deploy.deploy(self.directory)
        pull=next(i for i,args in enumerate(self.calls) if args[:2]==('docker','pull') and args[-1]==APPROVED_MINIO_IMAGE)
        first_stop=next(i for i,args in enumerate(self.calls) if args[:2]==('docker','stop'))
        backup=next(i for i,args in enumerate(self.calls) if args[0]=='backup')
        start=next(i for i,args in enumerate(self.calls) if args[:2]==('docker','compose') and 'up' in args and args[-1]=='minio')
        self.assertLess(pull,first_stop);self.assertLess(backup,start)
        self.assertEqual(self.container('minio')['Image'],APPROVED_MINIO_IMAGE)

    def test_any_other_infrastructure_change_is_rejected_before_gate(self):
        self.services['redis']['image']='different-redis-image'
        with self.assertRaisesRegex(RuntimeError,'cannot change infrastructure versions: redis'):
            deploy.deploy(self.directory)
        self.backup.assert_not_called();self.assertFalse((self.base/'maintenance').exists())

    def test_failed_minio_upgrade_preserves_fresh_backup_and_closed_gate(self):
        self.fail=lambda args:args[:2]==('docker','compose') and 'up' in args and args[-1]=='minio'
        with self.assertRaisesRegex(RuntimeError,'injected'):deploy.deploy(self.directory)
        self.assertEqual(self.state()['backup'],str(self.fresh))
        self.assertEqual(self.state()['status'],'failed');self.assertTrue((self.base/'maintenance').exists())
        self.assertFalse(any(args[-1] in deploy.MIGRATORS for args in self.calls))

    def test_missing_duplicate_unhealthy_stopped_or_wrong_image_blocks_traffic(self):
        initial=copy.deepcopy(self.containers)
        for defect in ['missing','duplicate','unhealthy','stopped','wrong-image']:
            self.containers=copy.deepcopy(initial);self.save_state(self.initial)
            def break_service():
                target=self.container('media-service')
                if defect=='missing':self.containers.remove(target)
                elif defect=='duplicate':
                    clone=copy.deepcopy(target);clone['Id']='unexpected-duplicate';self.containers.append(clone)
                elif defect=='unhealthy':target['State']['Health']['Status']='unhealthy'
                elif defect=='stopped':target['State']['Running']=False
                else:target['Image']='wrong-running-image'
            self.after_smoke=break_service
            with self.subTest(defect=defect),self.assertRaisesRegex(RuntimeError,'installed|Installed|Running image'):deploy.deploy(self.directory)
            self.assertTrue((self.base/'maintenance').exists());self.assertEqual(self.state()['status'],'failed')

    def test_final_check_rejects_container_replacement_during_health_wait(self):
        self.healthy.side_effect=lambda _:self.container('post-service').update(Id='replaced-container')
        with self.assertRaisesRegex(RuntimeError,'identities changed'):deploy.verify_installed('test',self.config)

    def test_late_log_failure_preserves_verified_state_for_idempotent_retry(self):
        real_log=deploy.log
        def fail_last(event,**fields):
            if event=='traffic_opened':raise RuntimeError('late log failure')
            real_log(event,**fields)
        with patch.object(deploy,'log',side_effect=fail_last):
            with self.assertRaisesRegex(RuntimeError,'late log failure'):deploy.deploy(self.directory)
        self.assertEqual(self.state()['status'],'healthy');self.assertEqual(self.state()['previous_release'],'baseline-20260926')
        deploy.deploy(self.directory);self.assertEqual(self.backup.call_count,1)

    def test_backup_contains_previous_runtime_and_state_without_image_archive(self):
        deploy.deploy(self.directory);paths=self.backup.call_args.args[4]
        for relative in ['state.json','baseline-20260926/compose.private.json','baseline-20260926/manifest.json']:
            self.assertIn(str(self.base/relative),paths)
        self.assertFalse(any('images.tar' in path for path in paths))
        state={'current_release':'v1.0.0','runtime_path':str(self.directory)}
        self.assertIn(str(self.directory),deploy.configuration_paths(state))
        state['runtime_path']=str(self.base/'unexpected')
        with self.assertRaisesRegex(RuntimeError,'not canonical'):deploy.configuration_paths(state)


if __name__=='__main__':unittest.main()
