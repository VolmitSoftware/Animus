import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('behavior_audit', Path(__file__).with_name('audit-behaviors.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


def report():
    npc = 'behavior-qa-123-9'
    return {'schemaVersion': 1, 'runner': 'ServerMultiplexor', 'scope': m.SCOPE, 'status': 'pass',
            'checks': [{'id': name, 'status': 'passed'} for name in m.CHECKS],
            'phases': [{'phase': phase} for phase in m.PHASES], 'stops': [{'phase': phase, 'serverPortClosed': True} for phase in m.PHASES],
            'serverPortClosed': True, 'outstandingGates': [], 'remainingDefinitions': [], 'remainingCheckpoints': [], 'remainingBehaviors': [], 'logFailures': [],
            'plannedCleanupAuthorizedByScenario': True, 'offlineCleanup': {'planned': True},
            'npcId': npc, 'ownedNpcIds': [npc, npc + '-control'] + [npc + '-cap' + str(i) for i in range(5)]}


def status(cold=False):
    value = {'cold': cold, 'disabled': True, 'serviceClosed': True, 'coordinatorPresent': True,
             'coordinatorStopped': True, 'moduleStopped': True, 'fileQuiescent': True,
             'catalogWorkPending': False, 'fileFailure': None, 'behaviorThreads': 0}
    value.update(runtime=None if cold else 'Status[admittedOperations=0, queuedDeliveries=0, activeDeliveries=0, callsInFlight=0, retiredCalls=0, activeExecutions=0, tick=99]',
                 modules=None if cold else 'Status[handlersOutstanding=0]', files=None if cold else 'Status[quiescent=true]')
    return value


def events():
    tags = ['start', 'bound', 'true', 'false', 'loop', 'loop', 'loop', 'branch-a', 'branch-b', 'after']
    rows = [{'npc': 'npc', 'kind': 'record', 'arguments': {'tag': tag}} for tag in tags]
    rows[1]['arguments'].update(longValue=9223372036854775807, decimalValue=1.25, booleanValue=True, stringValue='Garde Étoile')
    rows.insert(2, {'npc': 'npc', 'kind': 'condition', 'arguments': {'result': True}})
    rows.insert(4, {'npc': 'npc', 'kind': 'condition', 'arguments': {'result': False}})
    for index, row in enumerate(rows, 1): row['sequence'] = index
    return {'dropped': 0, 'pending': [], 'events': rows}


class BehaviorAuditTest(unittest.TestCase):
    def test_complete_structure(self): m.structure(report())

    def test_gate_flags_do_not_allow_missing_reordered_or_failed_checks(self):
        for mutation in [lambda r: r['checks'].pop(), lambda r: r['checks'].reverse(), lambda r: r['checks'][3].update(status='failed')]:
            value = report(); mutation(value)
            with self.assertRaises(m.base.AuditError): m.structure(value)

    def test_incomplete_phase_or_emergency_cleanup_cannot_pass(self):
        for key, value in [('remainingBehaviors', ['orphan']), ('remainingCheckpoints', ['pointer']), ('offlineCleanup', {'planned': False}),
                           ('plannedCleanupAuthorizedByScenario', False), ('serverPortClosed', False), ('error', 'earlier failure'), ('outstandingGates', ['missing'])]:
            candidate = report(); candidate[key] = value
            with self.assertRaises(m.base.AuditError): m.structure(candidate)
        for key in ['phases', 'stops']:
            candidate = report(); candidate[key].pop()
            with self.assertRaises(m.base.AuditError): m.structure(candidate)

    def test_latest_attempt_never_hides_failure_or_incomplete_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); old = root / 'behaviors-1-9'; old.mkdir(); (old / 'result.json').write_text(json.dumps(report()))
            newer = root / 'behaviors-2-1'; newer.mkdir()
            attempts, selected = m.latest_attempt(root); self.assertEqual(selected, newer / 'result.json'); self.assertEqual(len(attempts), 2)
            self.assertFalse(selected.exists()); failed = report(); failed['status'] = 'failed'; selected.write_text(json.dumps(failed))
            with self.assertRaises(m.base.AuditError): m.structure(m.read(m.latest_attempt(root)[1]))

    def test_original_raw_offset_and_ansi_are_bound(self):
        text = 'old\n\x1b[32m[12:00] receipt\x1b[m\n'
        row = {'phase': 'manual', 'command': 'command', 'logOffset': 4, 'output': '[12:00] receipt\n', 'receipt': '[12:00] receipt'}
        ledger = m.Ledger(dict.fromkeys(m.PHASES, text), [row]); self.assertEqual(ledger.find('command'), [row])
        for mutate in [lambda r: r.update(logOffset=0), lambda r: r.update(output='receipt\n'), lambda r: r.update(receipt='invented'), lambda r: r.update(logOffset=True)]:
            bad = dict(row); mutate(bad)
            with self.assertRaises(m.base.AuditError): m.Ledger(dict.fromkeys(m.PHASES, text), [bad])

    def test_later_matching_receipt_cannot_replace_wrong_original_offset(self):
        text = 'first\nsecond\nfirst\n'; row = {'phase': 'manual', 'command': 'x', 'logOffset': 6, 'output': 'first\n', 'receipt': 'first'}
        with self.assertRaises(m.base.AuditError): m.Ledger(dict.fromkeys(m.PHASES, text), [row])

    def test_original_offsets_must_be_monotonic_per_phase(self):
        text = 'one\ntwo\n'; rows = [{'phase': 'manual', 'command': 'two', 'logOffset': 4, 'output': 'two\n', 'receipt': 'two'},
                                    {'phase': 'manual', 'command': 'one', 'logOffset': 0, 'output': 'one\n', 'receipt': 'one'}]
        with self.assertRaises(m.base.AuditError): m.Ledger(dict.fromkeys(m.PHASES, text), rows)

    def test_reported_json_needs_original_receipt(self):
        text = '[12:00] Behavior QA status {"value":1}\n'; row = {'phase': 'manual', 'command': 'behaviorqa status', 'logOffset': 0, 'output': text, 'receipt': text.strip()}
        ledger = m.Ledger(dict.fromkeys(m.PHASES, text), [row]); ledger.observed({'value': 1})
        with self.assertRaises(m.base.AuditError): ledger.observed({'value': 2})

    def test_execution_parser_checks_identity_fields(self):
        uuid = '11111111-1111-1111-1111-111111111111'
        line = f'Behavior execution={uuid} | state=COMPLETED | npc=test | npc-uuid={uuid} | npc-revision=1 | behavior=qa:test | behavior-revision=2 | source-hash=' + 'a' * 64 + ' | steps=3 | reason=completed'
        parsed = m.execution(line); self.assertEqual(parsed['steps'], 3); self.assertEqual(parsed['behavior-revision'], 2)
        for candidate in [line + ' | steps=9', line.replace('npc-uuid=' + uuid, 'npc-uuid=invalid'), line.replace('a' * 64, 'short')]:
            with self.assertRaises(m.base.AuditError): m.execution(candidate)

    def test_cold_and_warm_quiescence_are_distinct(self):
        m.quiescence(status(True), True); m.quiescence(status(False), False)
        warmed = status(True); warmed['runtime'] = 'some status'
        with self.assertRaises(m.base.AuditError): m.quiescence(warmed, True)
        with self.assertRaises(m.base.AuditError): m.quiescence(status(True), False)

    def test_every_shutdown_ownership_and_drain_gate_is_required(self):
        for key in ('disabled', 'serviceClosed', 'coordinatorPresent', 'coordinatorStopped', 'moduleStopped', 'fileQuiescent'):
            value = status(); value[key] = False
            with self.assertRaises(m.base.AuditError): m.quiescence(value, False)
        for key, alternative in [('catalogWorkPending', True), ('fileFailure', 'IOException'), ('behaviorThreads', 1)]:
            value = status(); value[key] = alternative
            with self.assertRaises(m.base.AuditError): m.quiescence(value, False)

    def test_terminal_result_does_not_hide_raw_or_result_delivery_retention(self):
        for key in ('callsInFlight', 'retiredCalls', 'activeExecutions', 'admittedOperations', 'queuedDeliveries', 'activeDeliveries'):
            value = status(); value['runtime'] = value['runtime'].replace(key + '=0', key + '=1')
            with self.assertRaises(m.base.AuditError): m.quiescence(value, False)
        value = status(); value['modules'] = 'Status[handlersOutstanding=1]'
        with self.assertRaises(m.base.AuditError): m.quiescence(value, False)

    def test_retention_requires_engine_and_actual_raw_handler_counts(self):
        value = {'runtime': 'Status[callsInFlight=4, retiredCalls=4]', 'modules': 'Status[handlersOutstanding=4]'}; m.retention(value, 4)
        for bad in [{'runtime': 'Status[callsInFlight=4, retiredCalls=3]', 'modules': value['modules']}, {'runtime': value['runtime'], 'modules': 'Status[handlersOutstanding=0]'}]:
            with self.assertRaises(m.base.AuditError): m.retention(bad, 4)

    def test_both_distinct_budget_executions_required(self):
        values = [{'execution': 'first', 'state': 'BUDGET_EXHAUSTED', 'reason': 'routine-depth', 'behavior': 'qabehavior:recursive'},
                  {'execution': 'second', 'state': 'BUDGET_EXHAUSTED', 'reason': 'execution-step-budget', 'behavior': 'qabehavior:step-budget'}]
        m.budget_results(values)
        for invalid in [[], values[:1], values + values, [values[0], {**values[1], 'execution': 'first'}], [values[0], {**values[1], 'state': 'COMPLETED'}]]:
            with self.assertRaises(m.base.AuditError): m.budget_results(invalid)

    def test_restart_requires_exact_three_archived_and_unchanged_sources(self):
        source = 'npcs/qa/example.json'; files = {source: b'definition', 'state/npc-definitions/last-valid/' + source: b'pointer', 'behaviors/qabehavior/hold.json': b'behavior'}
        value = {'npcId': 'example', 'beforeRestart': {name: m.imports.text_hash(raw.decode()) for name, raw in files.items()}}
        snapshots = {'before-restart': dict(files), 'offline-owned-cleanup': dict(files)}; m.restart_files(value, snapshots)
        for missing in files:
            partial = copy.deepcopy(value); images = copy.deepcopy(snapshots); partial['beforeRestart'].pop(missing); images['before-restart'].pop(missing)
            with self.assertRaises(m.base.AuditError): m.restart_files(partial, images)
        images = copy.deepcopy(snapshots); images['offline-owned-cleanup'][source] = b'changed'
        with self.assertRaises(m.base.AuditError): m.restart_files(value, images)

    def test_typed_events_and_parallel_join_are_recomputed(self):
        value = events(); self.assertEqual(m.basic_events(value, 'npc')[-1], 'after')
        for mutate in [lambda x: x.update(dropped=1), lambda x: x['events'][1]['arguments'].update(longValue=9223372036854775806),
                       lambda x: x['events'][0].update(npc='foreign'), lambda x: x['events'][3]['arguments'].update(tag='after'),
                       lambda x: x['events'][2]['arguments'].update(result=False), lambda x: x['events'][2]['arguments'].update(result=1),
                       lambda x: x['events'][1]['arguments'].update(booleanValue=1), lambda x: x['events'][0].update(sequence=True), lambda x: x['events'].pop()]:
            bad = copy.deepcopy(value); mutate(bad)
            with self.assertRaises(m.base.AuditError): m.basic_events(bad, 'npc')

    def test_both_parallel_branch_orders_allowed_before_join(self):
        value = events(); records = [r for r in value['events'] if r['kind'] == 'record']
        records[7]['arguments']['tag'], records[8]['arguments']['tag'] = records[8]['arguments']['tag'], records[7]['arguments']['tag']
        m.basic_events(value, 'npc')

    def test_only_exact_synthetic_schema_failure_is_allowed(self):
        for thread in ['Server thread', 'Folia Region Scheduler Thread #12']:
            line = '[12:00:00] [' + thread + '/INFO]: [] qabehaviorbad [qabehaviorbad.jar] | FAILED | enable-failed: SchemaException'
            m.audit_log_line(line, 'manual'); m.audit_log_line(line, 'restart-and-disable')
            with self.assertRaises(m.base.AuditError): m.audit_log_line(line, 'cold-disable')

    def test_schema_failure_exception_level_or_owner_changes_are_rejected(self):
        line = '[12:00:00] [Server thread/INFO]: [] qabehaviorbad [qabehaviorbad.jar] | FAILED | enable-failed: SchemaException'
        for bad in [line.replace('INFO', 'ERROR'), line.replace('qabehaviorbad', 'qabehavior'), line.replace('SchemaException', 'NoClassDefFoundError'),
                    line.replace('SchemaException', 'IllegalStateException'), 'UnrelatedException ' + line, line.replace('[]', '[UnrelatedException]'),
                    '\tat engine.Owner.run(Owner.java:1)', 'Exception in thread owner', 'java.lang.AssertionError: lost lease']:
            with self.assertRaises(m.base.AuditError): m.audit_log_line(bad, 'manual')

    def test_frozen_harness_requires_exact_archive_and_current_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); repo = root / 'repo'; attempt = root / 'attempt'; name = 'qa/behaviors/acceptance.json'
            (repo / name).parent.mkdir(parents=True); (attempt / 'sources' / name).parent.mkdir(parents=True)
            (repo / name).write_text('{}'); (attempt / 'sources' / name).write_text('{}')
            audit = m.BehaviorAuditor(root, repo, root); audit.frozen = {name: m.digest(repo / name)}; record = {'sources': audit.frozen}
            audit.sources(attempt, record); (attempt / 'sources' / name).write_text('{"changed":true}')
            with self.assertRaises(m.base.AuditError): audit.sources(attempt, record)

    def test_counter_requires_exact_nonnegative_named_field(self):
        self.assertEqual(m.counter('Status[callsInFlight=12, other=1]', 'callsInFlight'), 12)
        for text in ['othercallsInFlight=12', 'callsInFlight=-1', 'callsInFlight=true', 'callsInFlight=']:
            with self.assertRaises(m.base.AuditError): m.counter(text, 'callsInFlight')


if __name__ == '__main__': unittest.main()
