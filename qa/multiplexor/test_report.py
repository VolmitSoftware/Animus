import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('evidence_report',Path(__file__).with_name('report.py'))
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)

class ReportTest(unittest.TestCase):
    def test_build_artifact_round_and_scope_must_all_match(self):
        value={'target':'animus-r10-paper-26-3','buildRun':'run10','artifactSha256':'new'}
        self.assertTrue(r.selected(value,'run10',{'new'},10,'production'))
        for changed in [{'buildRun':'run9'},{'artifactSha256':'old'},{'target':'animus-paper-26-3'},{'target':'animus-r9-paper-26-3'},{'target':'animus-r10-import-paper-26-3'}]:
            self.assertFalse(r.selected(value|changed,'run10',{'new'},10,'production'))
        module=value|{'target':'animus-modules-folia-26-2'}
        self.assertTrue(r.selected(module,'run10',{'new'},10,'modules'))
        self.assertFalse(r.selected(module,'run10',{'new'},10,'production'))
    def test_prepared_descriptors_are_never_lifecycle_passes(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'runtime.json';path.write_text(json.dumps({'status':'prepared','scope':'Fresh isolated regression instance preparation only; no server startup or runtime validation','port':1234}))
            with patch.object(r,'closed',return_value=True):category,entry=r.descriptor(path)
            self.assertEqual(category,'provisioning');self.assertFalse(entry['certifiesRuntime']);self.assertEqual(entry['status'],'prepared')
    def test_newest_pending_attempt_never_falls_back(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);old=folder/'production-9';old.mkdir();(old/'result.json').write_text('{"status":"pass"}')
            new=folder/'production-10';new.mkdir()
            self.assertEqual(r.latest(folder,'production'),new/'result.json')
            self.assertFalse(r.latest(folder,'production').exists())

if __name__=='__main__':unittest.main()
