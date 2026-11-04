"""Negative provenance and completeness checks for synthetic import auditing."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('import_audit_tested', Path(__file__).with_name('audit-import-applications.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def report():
    return {'schemaVersion': 1, 'runner': 'ServerMultiplexor', 'scope': audit.SCOPE, 'status': 'pass',
            'checks': [{'id': key, 'status': 'passed'} for key in audit.CHECKS],
            'phases': [{'phase': name} for name in audit.PHASES],
            'restarts': [{'after': name, 'seconds': 1.0, 'serverPortClosed': True} for name in audit.PHASES],
            'serverPortClosed': True, 'remainingDefinitions': [], 'remainingCheckpoints': [], 'logFailures': []}


class CompletenessTest(unittest.TestCase):
    def test_exact_complete_report(self): audit.structure(report())

    def test_no_phases_or_checks_cannot_qualify(self):
        for key in ['phases', 'checks', 'restarts']:
            value = report(); value[key] = []
            with self.subTest(key=key), self.assertRaises(audit.base.AuditError): audit.structure(value)

    def test_partial_reordered_duplicate_and_failed_checks_rejected(self):
        values = [report() for _ in range(4)]
        values[0]['checks'].pop(); values[1]['checks'].reverse()
        values[2]['checks'][1] = values[2]['checks'][0]; values[3]['checks'][0]['status'] = 'failed'
        for value in values:
            with self.subTest(checks=value['checks']), self.assertRaises(audit.base.AuditError): audit.structure(value)

    def test_cleanup_failures_and_open_port_rejected(self):
        for key, value in [('remainingDefinitions', ['npcs/test.json']), ('remainingCheckpoints', ['pointer.json']),
                           ('serverPortClosed', False), ('logFailures', ['ERROR']), ('definitionCleanupError', 'failure')]:
            item = report(); item[key] = value
            with self.subTest(key=key), self.assertRaises(audit.base.AuditError): audit.structure(item)

    def test_no_gameplay_relabel_or_failed_attempt(self):
        for key, value in [('scope', 'native gameplay'), ('status', 'failed'), ('runningPhase', 'prepare')]:
            item = report(); item[key] = value
            with self.subTest(key=key), self.assertRaises(audit.base.AuditError): audit.structure(item)

    def test_invalid_stop_proof(self):
        for key, value in [('seconds', float('nan')), ('seconds', True), ('serverPortClosed', False)]:
            item = report(); item['restarts'][0][key] = value
            with self.subTest(key=key), self.assertRaises(audit.base.AuditError): audit.structure(item)

    def test_newer_failed_or_incomplete_attempt_never_falls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = root / 'application-1-1'; old.mkdir(); (old / 'result.json').write_text(json.dumps(report()))
            latest = root / 'application-2-1'; latest.mkdir()
            attempts, selected = audit.latest_attempt(root)
            self.assertEqual(selected, latest / 'result.json'); self.assertFalse(selected.exists())
            failed = report(); failed['status'] = 'failed'; selected.write_text(json.dumps(failed))
            self.assertEqual(audit.latest_attempt(root)[1], selected)
            with self.assertRaises(audit.base.AuditError): audit.structure(audit.read(selected))


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.repo = self.root / 'repo'; self.folder = self.root / 'attempt'; self.data = self.root / 'data'
        self.collector = audit.ImportAuditor(self.root, self.repo, self.root)
        self.collector.frozen = {}
        for name in audit.SOURCES:
            current = self.repo / name; archive = self.folder / 'sources' / name
            current.parent.mkdir(parents=True, exist_ok=True); archive.parent.mkdir(parents=True, exist_ok=True)
            current.write_text('frozen ' + name); archive.write_bytes(current.read_bytes())
            self.collector.frozen[name] = audit.digest(current)
        self.report = {'sources': dict(self.collector.frozen)}

    def test_matching_harness_archives_pass(self): self.collector.sources(self.folder, self.report)

    def test_unreviewed_current_helper_change_rejected(self):
        (self.repo / audit.SOURCES[1]).write_text('changed helper')
        with self.assertRaises(audit.base.AuditError): self.collector.sources(self.folder, self.report)

    def test_archived_fixture_tampering_rejected(self):
        (self.folder / 'sources' / audit.SOURCES[-1]).write_text('changed fixture')
        with self.assertRaises(audit.base.AuditError): self.collector.sources(self.folder, self.report)

    def test_forged_matching_current_and_archive_cannot_replace_frozen_source(self):
        name = audit.SOURCES[0]
        for path in [self.repo / name, self.folder / 'sources' / name]: path.write_text('different wrapper')
        self.report['sources'][name] = audit.digest(self.repo / name)
        with self.assertRaises(audit.base.AuditError): self.collector.sources(self.folder, self.report)

    def test_retained_data_symlink_and_traversal_rejected(self):
        self.data.mkdir(); outside = self.root / 'outside'; outside.write_text('{}')
        (self.data / 'linked.json').symlink_to(outside)
        for name in ['../outside', '/outside', 'linked.json']:
            with self.subTest(name=name), self.assertRaises(audit.base.AuditError): self.collector.data_file(self.data, name)

    def test_snapshot_requires_exact_checkpoint_source_and_object_hashes(self):
        path = 'npcs/test/example.json'; text = json.dumps({'id': 'example', 'group': 'test', 'spawned': False, 'position': {'world': 'world'}})
        h = audit.text_hash(text); obj = self.data / f'state/objects/npc-definitions/{h}.json'; obj.parent.mkdir(parents=True); obj.write_text(text)
        pointer = {'schemaVersion': 1, 'path': path, 'sourceHash': h, 'objectHash': h}
        values = {path: text, audit.POINTER + path: json.dumps(pointer)}
        self.collector.snapshot(self.data, values, 1)
        pointer['sourceHash'] = '0' * 64; values[audit.POINTER + path] = json.dumps(pointer)
        with self.assertRaises(audit.base.AuditError): self.collector.snapshot(self.data, values, 1)

    def test_checkpoint_payload_change_rejected(self):
        path = 'npcs/test/example.json'; text = json.dumps({'id': 'example', 'group': 'test', 'spawned': False, 'position': {'world': 'world'}})
        h = audit.text_hash(text); obj = self.data / f'state/objects/npc-definitions/{h}.json'; obj.parent.mkdir(parents=True); obj.write_text('{}')
        values = {path: text, audit.POINTER + path: json.dumps({'schemaVersion': 1, 'path': path, 'sourceHash': h, 'objectHash': h})}
        with self.assertRaises(audit.base.AuditError): self.collector.snapshot(self.data, values, 1)

    def test_snapshot_journal_requires_exact_before_and_after_bytes(self):
        self.collector.transition({'npcs/test/a.json': ('old', 'new')}, {'npcs/test/a.json': 'old'}, {'npcs/test/a.json': 'new'})
        for before, after in [({'npcs/test/a.json': 'tampered'}, {'npcs/test/a.json': 'new'}), ({'npcs/test/a.json': 'old'}, {})]:
            with self.assertRaises(audit.base.AuditError): self.collector.transition({'npcs/test/a.json': ('old', 'new')}, before, after)

    def test_mutated_plan_hash_rejected(self):
        identity = '1' * 64; plan = self.data / f'imports/{identity}/applications/{identity}/plan.json'; plan.parent.mkdir(parents=True); plan.write_text('{}')
        with self.assertRaises(audit.base.AuditError): self.collector.plan(self.data, identity, identity)


if __name__ == '__main__': unittest.main()
