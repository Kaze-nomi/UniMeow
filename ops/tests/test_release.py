import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('release',Path(__file__).parents[1]/'release.py')
release=importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseValidationTests(unittest.TestCase):
    def valid(self):
        return {'release_id':'v1.2.3','commit':'a'*40,'architecture':'linux/amd64','migration_services':['user-migrate','post-migrate','notification-migrate'],
            'images':{s:f'ghcr.io/kaze-nomi/unimeow-{s}@sha256:'+('b'*64) for s in release.SERVICES}}

    def test_full_release(self):
        self.assertEqual(release.validate(self.valid())['release_id'],'v1.2.3')

    def test_shell_syntax_and_paths_are_rejected(self):
        for name in ['../../prod','v1.2.3;id','$(id)','main','v1.2.3\nfoo']:
            m=self.valid();m['release_id']=name
            with self.assertRaises(ValueError):release.validate(m)

    def test_partial_release_or_mutable_tag_is_rejected(self):
        m=self.valid();m['images'].pop('frontend')
        with self.assertRaises(ValueError):release.validate(m)
        m=self.valid();m['images']['frontend']='ghcr.io/kaze-nomi/unimeow-frontend:latest'
        with self.assertRaises(ValueError):release.validate(m)
