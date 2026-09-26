"""Failure injection at deployment boundaries; no real Docker or production access."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parents[1]))
import deploy
from release import SERVICES


class DeploymentRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name)
        (self.base/'config').mkdir()
        self.directory=self.base/'releases/v1.0.0';self.directory.mkdir(parents=True)
        images={s:f'ghcr.io/kaze-nomi/unimeow-{s}@sha256:'+('a'*64) for s in SERVICES}
        self.manifest={'release_id':'v1.0.0','commit':'b'*40,'architecture':'linux/amd64','images':images,
                       'migration_services':['user-migrate','post-migrate','notification-migrate']}
        (self.directory/'release.json').write_text(json.dumps(self.manifest))
        self.initial={'status':'healthy','project':'test','current_release':'baseline-20260926',
                      'restore_verified':True,'baseline_path':str(self.base/'baseline'), 'backup':'original-good'}
        (self.base/'state.json').write_text(json.dumps(self.initial))
        (self.base/'config/installation.json').write_text(json.dumps({'volumes':['existing-data']}))
        self.services={s:{'image':images.get(s,'infra-image-'+s),'environment':{}} for s in set(SERVICES)|deploy.INFRA|{'gateway-proxy'}}
        self.services['api-gateway']['environment']={'APP_SECURITY_SECURE_COOKIE':'true','APP_SECURITY_OAUTH2_SUCCESS_REDIRECT':'https://example.test/'}
        self.services['frontend']['environment']={'FRONTEND_API_BASE':'https://example.test','MINIO_PUBLIC_URL':'https://media.example.test'}
        self.config={'services':self.services,'volumes':{'data':{'name':'existing-data'}}}
        self.containers=[{'Id':'container-'+s,'Image':self.services[s]['image'],
                          'Config':{'Labels':{'com.docker.compose.service':s}}} for s in self.services]
        self.calls=[];self.fail=None
        self.stack=[]
        for name,value in [('BASE',self.base),('run',self.run_command),('inspect_project',lambda _:self.containers),
                           ('wait_healthy',lambda *_:None),('validate_backup',lambda _:True),('recover_rotation',lambda _:None)]:
            p=patch.object(deploy,name,value);p.start();self.addCleanup(p.stop)
        self.backup=patch.object(deploy,'create_backup',return_value=self.base/'fresh-good').start()
        self.addCleanup(patch.stopall)

    def run_command(self,*args,capture=False):
        self.calls.append(args)
        if self.fail and self.fail(args):raise RuntimeError('injected failure')
        if args[-3:]==('config','--format','json'):return json.dumps(self.config)
        if args[:3]==('docker','image','inspect'):return json.dumps([{'Id':args[3]}])
        return ''

    def state(self):return json.loads((self.base/'state.json').read_text())

    def test_failed_migration_keeps_original_good_backup_and_retry_does_not_resnapshot(self):
        self.fail=lambda args:args[-1]=='post-migrate'
        with self.assertRaisesRegex(RuntimeError,'injected'):
            deploy.deploy(self.directory)
        failed=self.state()
        self.assertEqual(failed['status'],'failed')
        self.assertEqual(failed['current_release'],'baseline-20260926')
        self.assertEqual(failed['backup'],str(self.base/'fresh-good'))
        self.assertTrue((self.base/'maintenance').exists())
        self.assertEqual(self.backup.call_count,1)
        self.fail=None
        deploy.deploy(self.directory)
        self.assertEqual(self.backup.call_count,1)
        self.assertEqual(self.state()['current_release'],'v1.0.0')
        self.assertFalse((self.base/'maintenance').exists())

    def test_backup_failure_restarts_original_containers_without_migrating(self):
        self.backup.side_effect=RuntimeError('backup failed')
        with self.assertRaisesRegex(RuntimeError,'backup failed'):
            deploy.deploy(self.directory)
        self.assertEqual(self.state()['status'],'healthy')
        self.assertEqual(self.state()['backup'],'original-good')
        self.assertFalse((self.base/'maintenance').exists())
        self.assertFalse(any(args[-1] in self.manifest['migration_services'] for args in self.calls))
        started=[args[2] for args in self.calls if args[:2]==('docker','start')]
        self.assertIn('container-api-gateway',started)
        self.assertLess(started.index('container-eureka-server'),started.index('container-api-gateway'))

    def test_fingerprints_are_captured_after_application_writers_stop(self):
        deploy.deploy(self.directory)
        snapshot=next(i for i,a in enumerate(self.calls) if '--snapshot' in a)
        stops=[i for i,a in enumerate(self.calls) if a[:2]==('docker','stop')]
        self.assertEqual(len([i for i in stops if i<snapshot]),2)
        self.assertTrue(any(i>snapshot for i in stops))


if __name__=='__main__':unittest.main()
