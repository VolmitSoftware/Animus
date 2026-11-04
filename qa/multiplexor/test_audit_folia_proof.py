"""Negative coverage/provenance cases for the narrow Folia proof audit."""
import copy
import difflib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('folia_audit_tested', Path(__file__).with_name('audit-folia-proof.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def phase(name='main'):
    return {'phase': name, 'exitCode': 0, 'timedOut': False, 'serverPortClosed': True, 'stopSeconds': 1.5,
            'clientResult': {'phase': name, 'scope': audit.SCOPE, 'status': 'passed',
                             'checks': [{'id': 'one-live-check', 'status': 'passed'}]}}


class FoliaPhaseTest(unittest.TestCase):
    def test_diagnostic_without_phases_cannot_qualify(self):
        with self.assertRaises(audit.runtime.AuditError):
            audit.phase_structure({'status': 'pass', 'checks': ['diagnostic only']})

    def test_single_phase_is_valid_evidence_but_not_complete_matrix_policy(self):
        phases = audit.phase_structure({'phases': [phase()]})
        self.assertNotEqual(list(audit.PHASES), [p['phase'] for p in phases])

    def test_ordered_subsets_and_exact_three_phase_order(self):
        self.assertEqual(['main', 'bounds'], [p['phase'] for p in audit.phase_structure({'phases': [phase(), phase('bounds')]})])
        complete = [phase(), phase('bounds'), phase('shutdown')]
        self.assertEqual(list(audit.PHASES), [p['phase'] for p in audit.phase_structure({'phases': complete})])
        self.assertEqual(['shutdown'], [p['phase'] for p in audit.phase_structure({'phases': [phase('shutdown')]})])
        for values in [[phase(), phase()], [phase('bounds'), phase()], [phase('production')]]:
            with self.subTest(values=values), self.assertRaises(audit.runtime.AuditError):
                audit.phase_structure({'phases': values})

    def test_failed_timeout_missing_cleanup_and_bool_exit_are_rejected(self):
        for key, value in [('exitCode', 1), ('exitCode', False), ('timedOut', True), ('timedOut', None),
                           ('serverPortClosed', False), ('stopSeconds', float('nan'))]:
            record = phase(); record[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(audit.runtime.AuditError):
                audit.phase_structure({'phases': [record]})

    def test_scope_cannot_claim_production_or_graphical_runtime(self):
        record = phase(); record['clientResult']['scope'] = 'general Folia runtime'
        with self.assertRaises(audit.runtime.AuditError): audit.phase_structure({'phases': [record]})

    def test_empty_failed_and_duplicate_checks_rejected(self):
        for checks in [[], [{'id': 'a', 'status': 'failed'}], [{'id': 'a', 'status': 'passed'}] * 2]:
            record = phase(); record['clientResult']['checks'] = checks
            with self.subTest(checks=checks), self.assertRaises(audit.runtime.AuditError):
                audit.phase_structure({'phases': [record]})

    def test_source_traversal_rejected(self):
        for path in ['/outside/file', '../outside', 'qa/../../outside', '']:
            with self.subTest(path=path), self.assertRaises(audit.runtime.AuditError): audit.relative_source(path)


class FoliaNativeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo, self.native = self.root / 'repo', self.root / 'native'
        source_paths = {
            'jarSha256': self.native / '26.3/minecraft-extracted_server.jar',
            'clientSourceSha256': self.repo / 'qa/protocol-client/NativeProtocolClient.java',
            'adapterSourceSha256': self.repo / 'qa/protocol-client/v26_3/ProtocolRevision.java',
            'scenarioSourceSha256': self.repo / 'qa/folia-proof/client.mjs',
            'harnessSourceSha256': self.repo / 'qa/protocol-client/client.mjs',
        }
        codec = {'adapter': 'v26_3'}
        for key, path in source_paths.items():
            path.parent.mkdir(parents=True, exist_ok=True); path.write_text('synthetic provenance ' + key)
            codec[key] = audit.digest(path)
        self.record = phase()
        self.output = self.root / 'native-result'; self.output.mkdir()
        self.client = self.record['clientResult']
        self.client.update(event='result', version='26.3', port=65430, nativeCodec=codec, resultDir=str(self.output))
        self.transcript = self.root / 'main.ndjson'
        self.record['transcript'] = str(self.transcript)
        (self.output / 'wire.ndjson').write_text('{"event":"synthetic-wire"}\n')
        self.write_receipts()

    def write_receipts(self):
        self.transcript.write_text(json.dumps({'event': 'check', **self.client['checks'][0]}) + '\n' + json.dumps(self.client) + '\n')
        self.record['transcriptSha256'] = audit.digest(self.transcript)
        (self.output / 'result.json').write_text(json.dumps(self.client))

    def verifier(self):
        return audit.FoliaAuditor(self.root / 'workspace', self.repo, self.native)

    def test_exact_archive_and_source_hashes_verified(self):
        result = self.verifier().native(self.record, '26.3', 65430)
        self.assertEqual(1, result['checks'])

    def test_modified_scenario_rejected_even_when_record_says_passed(self):
        (self.repo / 'qa/folia-proof/client.mjs').write_text('changed assertion')
        with self.assertRaisesRegex(audit.runtime.AuditError, 'Hash mismatch'):
            self.verifier().native(self.record, '26.3', 65430)

    def test_transcript_hash_cannot_be_replaced_by_summary_pass(self):
        self.transcript.write_text(self.transcript.read_text() + 'modified\n')
        with self.assertRaisesRegex(audit.runtime.AuditError, 'Hash mismatch'):
            self.verifier().native(self.record, '26.3', 65430)

    def test_added_final_check_without_receipt_rejected(self):
        self.client['checks'].append({'id': 'fabricated', 'status': 'passed'})
        self.write_receipts()
        with self.assertRaisesRegex(audit.runtime.AuditError, 'check stream differs'):
            self.verifier().native(self.record, '26.3', 65430)

    def test_archived_result_mismatch_rejected(self):
        changed = copy.deepcopy(self.client); changed['checks'][0]['id'] = 'different assertion'
        (self.output / 'result.json').write_text(json.dumps(changed))
        with self.assertRaisesRegex(audit.runtime.AuditError, 'archived native result differs'):
            self.verifier().native(self.record, '26.3', 65430)

    def test_wrong_exact_adapter_rejected(self):
        self.client['nativeCodec']['adapter'] = 'v26_1_26_2'; self.write_receipts()
        with self.assertRaisesRegex(audit.runtime.AuditError, 'wrong exact native adapter'):
            self.verifier().native(self.record, '26.3', 65430)


if __name__ == '__main__': unittest.main()


class SourceUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.repo=self.root/'repo'
        self.reader=audit.FoliaAuditor(self.root,self.repo,self.root,10);self.reader.run_id='selected';self.reader.artifact_hash='a'*64;self.reader.frozen_sources={}
        self.snapshot=self.root/'snapshot.json';self.snapshot.write_text('{}')
        self.manifest={'schemaVersion':1,'scope':audit.SOURCE_UPDATE_SCOPE,'buildRun':'selected','pluginSha256':'a'*64,'sourceSnapshotSha256':audit.digest(self.snapshot),'approvedBy':'parent implementation review','approvedAt':'now','purpose':'Explicit fixture release after disable','changes':[]}
        for index,name in enumerate(sorted(audit.SOURCE_UPDATE_PATHS)):
            current=self.repo/name;current.parent.mkdir(parents=True,exist_ok=True);current.write_text('after\n')
            before=self.root/f'before-{index}';before.write_text('before\n');after=self.root/f'after-{index}';after.write_text('after\n');patch=self.root/f'diff-{index}'
            patch.write_text(''.join(difflib.unified_diff(before.read_text().splitlines(keepends=True),after.read_text().splitlines(keepends=True),fromfile='a/'+name,tofile='b/'+name)))
            self.reader.frozen_sources[name]=audit.digest(before)
            self.manifest['changes'].append({'path':name,'beforeArchive':str(before),'afterArchive':str(after),'diffArchive':str(patch),'beforeSha256':audit.digest(before),'afterSha256':audit.digest(after),'diffSha256':audit.digest(patch)})
        fixture=self.root/'fixture.jar';fixture.write_bytes(b'synthetic fixture')
        receipt=self.root/'fixture-build.json';java=next(c for c in self.manifest['changes'] if c['path'].endswith('.java'))
        receipt.write_text(json.dumps({'pluginSha256':'a'*64,'fixtureSha256':audit.digest(fixture),'sources':{java['path']:java['afterSha256']}}))
        self.manifest['fixture']={'artifact':str(fixture),'sha256':audit.digest(fixture),'buildReceipt':str(receipt),'buildReceiptSha256':audit.digest(receipt)}
    def invoke(self,manifest=None):
        path=self.root/'review.json';path.write_text(json.dumps(manifest or self.manifest));return self.reader.configure_source_update(path,self.snapshot)
    def test_exact_named_two_file_review_and_fixture_binding(self):
        result=self.invoke();self.assertEqual(result['buildRun'],'selected');self.assertEqual(self.reader.source_update_fixture,self.manifest['fixture']['sha256'])
    def test_wrong_build_artifact_snapshot_or_unapproved_manifest_rejected(self):
        for key,value in [('buildRun','old'),('pluginSha256','b'*64),('sourceSnapshotSha256','0'*64),('approvedBy','pending')]:
            manifest=copy.deepcopy(self.manifest);manifest[key]=value
            with self.assertRaises(audit.runtime.AuditError):self.invoke(manifest)
    def test_no_unlisted_source_or_noop_update(self):
        manifest=copy.deepcopy(self.manifest);manifest['changes'][0]['path']='core/Production.java'
        with self.assertRaises(audit.runtime.AuditError):self.invoke(manifest)
        manifest=copy.deepcopy(self.manifest);manifest['changes'][0]['beforeSha256']='0'*64
        with self.assertRaises(audit.runtime.AuditError):self.invoke(manifest)
    def test_changed_archives_diff_or_current_bytes_rejected(self):
        change=self.manifest['changes'][0];Path(change['diffArchive']).write_text('unreviewed patch')
        change['diffSha256']=audit.digest(Path(change['diffArchive']))
        with self.assertRaises(audit.runtime.AuditError):self.invoke()
    def test_current_after_bytes_must_match(self):
        (self.repo/self.manifest['changes'][0]['path']).write_text('additional unreviewed edit')
        with self.assertRaises(audit.runtime.AuditError):self.invoke()
    def test_release_is_not_a_substitute_for_real_lane_cleanup(self):
        check={'explicitReleaseAfterDisable':True,'heldReceipt':'Folia QA file status io-held=1 runningMutations=1 closed=true quiescent=false',
               'drainedReceipt':'Folia QA file status io-held=0 admitted=0 runningMutations=0 queuedEngineNotifications=0 cleanupState=SUCCEEDED quiescent=true closed=true'}
        audit.cooperative_shutdown_receipt(check)
        for old,new in [('io-held=0','io-held=1'),('cleanupState=SUCCEEDED','cleanupState=QUEUED'),('quiescent=true','quiescent=false'),('closed=true','closed=false')]:
            with self.assertRaises(audit.runtime.AuditError):audit.cooperative_shutdown_receipt({**check,'drainedReceipt':check['drainedReceipt'].replace(old,new)})
        with self.assertRaises(audit.runtime.AuditError):audit.cooperative_shutdown_receipt({**check,'explicitReleaseAfterDisable':False})
