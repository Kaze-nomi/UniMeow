import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parents[1]))
import rollback


class RollbackRecoveryTests(unittest.TestCase):
    def test_interrupted_success_opens_gate_only_after_readiness_and_read_smoke(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);(base/'maintenance').touch()
            (base/'state.json').write_text(json.dumps({'status':'healthy','current_release':'baseline-20260926','project':'test'}))
            active=[{'Id':name,'State':{'Running':True},'Config':{'Labels':{'com.docker.compose.service':name}}}
                    for name in set(rollback.SERVICES)|rollback.INFRA]
            def smoke(*args):
                self.assertTrue((base/'maintenance').exists())
                self.assertIn('--read-only',args)
            with patch.object(rollback,'BASE',base), patch.object(rollback,'inspect_project',return_value=active), \
                 patch.object(rollback,'wait_healthy') as readiness, patch.object(rollback,'run',side_effect=smoke), patch.object(rollback,'log'):
                rollback.rollback('baseline-20260926')
                readiness.assert_called_once()
            self.assertFalse((base/'maintenance').exists())

    def test_failed_retry_smoke_never_opens_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);(base/'maintenance').touch()
            (base/'state.json').write_text(json.dumps({'status':'healthy','current_release':'baseline-20260926','project':'test'}))
            active=[{'Id':name,'State':{'Running':True},'Config':{'Labels':{'com.docker.compose.service':name}}}
                    for name in set(rollback.SERVICES)|rollback.INFRA]
            with patch.object(rollback,'BASE',base), patch.object(rollback,'inspect_project',return_value=active), \
                 patch.object(rollback,'wait_healthy'), patch.object(rollback,'run',side_effect=RuntimeError('unavailable')):
                with self.assertRaisesRegex(RuntimeError,'unavailable'):rollback.rollback('baseline-20260926')
            self.assertTrue((base/'maintenance').exists())

    def test_unverified_or_different_schema_target_cannot_authorize_rollback(self):
        state={'current_release':'v1.0.0','upgrade_rehearsals':{'v1.0.0':{
            'verified':True,'old_code_compatible':True,'baseline_release':'baseline-20260926'}}}
        self.assertEqual(rollback.compatible_rehearsal(state,'baseline-20260926'),'v1.0.0')
        with self.assertRaises(RuntimeError):rollback.compatible_rehearsal(state,'v0.9.0')
        state['target_release']='v2.0.0'
        with self.assertRaises(RuntimeError):rollback.compatible_rehearsal(state,'baseline-20260926')

    def test_failed_rollback_retry_reuses_its_original_fingerprint(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);(base/'config').mkdir();baseline=base/'baseline-20260926';baseline.mkdir()
            snapshot=base/'config/rollback-data-before.json';snapshot.write_text('{"users":{"old":"original"},"posts":{}}')
            services={s:{'image':'saved-'+s,'environment':{}} for s in set(rollback.SERVICES)|rollback.INFRA}
            config={'services':services,'volumes':{'saved':{'name':'existing-data'}}}
            (baseline/'compose.private.json').write_text(json.dumps(config))
            (baseline/'manifest.json').write_text(json.dumps({'release_id':baseline.name,
                'images':{s:{'image_id':'saved-'+s} for s in services}}))
            (base/'config/installation.json').write_text('{"volumes":["existing-data"]}')
            state={'status':'rollback_failed','current_release':'v1.0.0','project':'test',
                'baseline_path':str(baseline),'rollback_from':'v1.0.0','rollback_target':baseline.name,
                'rollback_code_started':True,'rollback_snapshot_sha256':hashlib.sha256(snapshot.read_bytes()).hexdigest(),
                'upgrade_rehearsals':{'v1.0.0':{'verified':True,'old_code_compatible':True,'baseline_release':baseline.name}}}
            (base/'state.json').write_text(json.dumps(state))
            current=[{'Id':s,'Config':{'Labels':{'com.docker.compose.service':s}}} for s in services]
            def command(*args,**kwargs):
                self.assertNotIn('--snapshot',args,'A retry must not replace the original fingerprint')
                if args[:3]==('docker','image','inspect'):return json.dumps([{'Id':args[3]}])
                if '--compare-snapshot' in args:
                    self.assertEqual(json.loads(snapshot.read_text())['users']['old'],'original')
                return ''
            def transition(data,status,**fields):data.update(status=status,**fields)
            with patch.object(rollback,'BASE',base), patch.object(rollback,'inspect_project',return_value=current), \
                 patch.object(rollback,'stop_applications'), patch.object(rollback,'wait_healthy'), \
                 patch.object(rollback,'transition',side_effect=transition), patch.object(rollback,'log'), \
                 patch.object(rollback,'run',side_effect=command):
                rollback.rollback(baseline.name)
            self.assertFalse((base/'maintenance').exists())


if __name__=='__main__':unittest.main()
