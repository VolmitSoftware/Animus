#!/usr/bin/env python3
"""Independently audit the latest exact-build manual behavior attempt on all nine targets."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import zipfile
import multiplexor_workspace as mw

spec = importlib.util.spec_from_file_location('behavior_import_audit', Path(__file__).with_name('audit-import-applications.py'))
imports = importlib.util.module_from_spec(spec); spec.loader.exec_module(imports)
base = imports.base
require, read, digest = base.require, base.read_json, base.digest
SCOPE = 'explicit manual definition-bound JSON behavior execution of module capabilities; built-in world actions, automatic triggers, preview, privileged grants and durable execution stacks are not exercised'
PHASES = ['cold-disable', 'manual', 'restart-and-disable']
CHECKS = ['cold-immediate-disable', 'module-schema-staging', 'readonly-file-validation', 'manual-dormant-start',
          'typed-arguments-and-conditions', 'sequence-repeat-routine-parallel', 'logical-final-wait',
          'resolved-schema-refusal', 'unavailable-capability', 'execution-budgets', 'parallel-write-conflict',
          'one-active-execution-per-npc', 'manual-cancel-raw-retention', 'explicit-release-quiescence',
          'unchanged-reload-preserves-execution', 'invalid-reload-retains-program', 'same-revision-conflict',
          'accepted-program-replacement', 'source-removal-and-restore', 'definition-revision-cancellation',
          'definition-despawn-intent', 'definition-delete-cancellation', 'invocation-capacity-after-cancel',
          'managed-restart-no-stack-resume', 'actual-plugin-disable', 'cleanup-and-provenance']
FROZEN_WRAPPER = '72d33c176a349aeebe306276328d92304eff1aa505215baf54139c3e678985b7'
DIAGNOSTIC_STATUS_GATES = {'logical-final-wait', 'manual-cancel-raw-retention', 'explicit-release-quiescence', 'invocation-capacity-after-cancel'}
ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
UUID = re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}')
CONFIG = '''packetProofEnabled = false
[npcs]
enabled = false
[modules]
enabled = true
maximumInvocations = 32
invocationTimeoutMillis = 60000
deliveryBudgetMillis = 250
[behaviors]
enabled = true
maximumOperations = 32
operationsPerTick = 8
tickMillis = 50
deliveryBudgetMillis = 250
[behaviors.engine]
executions = 16
fibers = 32
inFlight = 4
workPerTick = 128
totalSteps = 128
lifetimeTicks = 2000
callDepth = 8
traceEntries = 128
historyEntries = 64
'''


def clean(text): return ANSI.sub('', text)


def counter(text, key):
    found = re.search(r'\b' + re.escape(key) + r'=([0-9]+)\b', text or '')
    require(found is not None, 'Missing actual status counter: ' + key)
    return int(found[1])


def execution(line):
    found = re.search(r'Behavior execution=([0-9a-f-]{36}) \| (.*)', clean(line))
    require(found is not None and UUID.fullmatch(found[1]), 'Invalid execution receipt')
    parts = [item.split('=', 1) for item in found[2].split(' | ')]
    require(all(len(item) == 2 for item in parts) and len({item[0] for item in parts}) == len(parts), 'Malformed execution fields')
    result = dict(parts); result['execution'] = found[1]
    for key in ('npc-revision', 'behavior-revision', 'steps'): result[key] = int(result[key])
    require(UUID.fullmatch(result['npc-uuid']) and base.HASH.fullmatch(result['source-hash']), 'Invalid captured source identity')
    return result


def json_rows(text, prefix):
    return [json.loads(line.split(prefix, 1)[1]) for line in clean(text).splitlines() if prefix in line]


def latest_attempt(directory):
    attempts = sorted((p for p in directory.glob('behaviors-*') if p.is_dir() and re.fullmatch(r'behaviors-[0-9]+-[0-9]+', p.name)),
                      key=lambda p: tuple(map(int, p.name.split('-')[1:])))
    return attempts, attempts[-1] / 'result.json' if attempts else None


def target_name(project, version):
    return base.target_name(project, version).replace('animus-baseline-', 'animus-behaviors-', 1)


def diagnostic_status(value):
    """Exact schema1 wrapper quirk: four status fields contain evidence, never a pass flag."""
    bools = {'cold', 'disabled', 'serviceClosed', 'catalogWorkPending', 'coordinatorPresent', 'coordinatorStopped', 'moduleStopped', 'fileQuiescent'}
    require(type(value) is dict and set(value) == bools | {'fileFailure', 'behaviorThreads', 'runtime', 'modules', 'files'}, 'Wrong diagnostic status object schema')
    require(all(type(value[k]) is bool for k in bools) and value['cold'] is False and value['disabled'] is False and value['serviceClosed'] is False and value['coordinatorPresent'] is True, 'Wrong live diagnostic authority state')
    require(value['fileFailure'] is None and type(value['behaviorThreads']) is int and 1 <= value['behaviorThreads'] <= 3, 'Wrong diagnostic thread/failure fields')
    require(all(type(value[k]) is str and value[k].startswith('Status[') and value[k].endswith(']') for k in ('runtime', 'modules', 'files')), 'Actual status record strings required')
    for name in ('tick', 'admittedOperations', 'queuedOperations', 'queuedDeliveries', 'activeDeliveries', 'callsInFlight', 'retiredCalls', 'activeExecutions'):
        counter(value['runtime'], name)
    counter(value['modules'], 'handlersOutstanding'); counter(value['files'], 'admitted')


def structure(report):
    require(report.get('schemaVersion') == 1 and report.get('runner') == 'ServerMultiplexor' and report.get('scope') == SCOPE, 'Wrong behavior runner/schema/scope')
    require(report.get('status') == 'pass' and [c.get('id') for c in report.get('checks', [])] == CHECKS, 'Exact 26 ordered completed checks required')
    require(report.get('sources', {}).get('qa/multiplexor/behaviors.py') == FROZEN_WRAPPER, 'This report profile requires the exact reviewed frozen wrapper')
    for gate in report['checks']:
        if gate['id'] in DIAGNOSTIC_STATUS_GATES: diagnostic_status(gate.get('status'))
        else: require(gate.get('status') == 'passed', 'Non-diagnostic gate must have literal passed status')
    require([x.get('phase') for x in report.get('phases', [])] == PHASES and [x.get('phase') for x in report.get('stops', [])] == PHASES, 'Three actual ordered managed phases required')
    require(report.get('serverPortClosed') is True and all(x.get('serverPortClosed') is True for x in report['stops']), 'Owned ports remain open')
    require(report.get('outstandingGates') == [] and all(report.get(key) == [] for key in ('remainingDefinitions', 'remainingCheckpoints', 'remainingBehaviors', 'logFailures')), 'Missing gates or incomplete cleanup')
    require(report.get('plannedCleanupAuthorizedByScenario') is True and report.get('offlineCleanup', {}).get('planned') is True, 'Emergency offline cleanup cannot pass')
    require(not any(report.get(k) for k in ('error', 'artifactError', 'cleanupError', 'cleanupErrors', 'runningPhase', 'sourceError')), 'Attempt records a failure')
    ids = report.get('ownedNpcIds', []); npc = report.get('npcId', '')
    require(re.fullmatch(r'behavior-qa-[0-9]+-[0-9]+', npc) and ids == [npc, npc + '-control'] + [npc + '-cap' + str(i) for i in range(5)], 'Unexpected synthetic identity scope')


def quiescence(value, cold):
    for key in ('disabled', 'serviceClosed', 'coordinatorPresent', 'coordinatorStopped', 'moduleStopped', 'fileQuiescent'):
        require(value.get(key) is True, 'Actual shutdown not complete: ' + key)
    require(value.get('catalogWorkPending') is False and value.get('fileFailure') is None and value.get('behaviorThreads') == 0, 'Catalog/file/thread cleanup incomplete')
    require(value.get('cold') is cold, 'Cold/warm observation mismatch')
    if cold:
        require(all(value.get(k) is None for k in ('runtime', 'modules', 'files')), 'Cold test warmed execution/status paths')
    else:
        idle(value)
        require(counter(value['runtime'], 'admittedOperations') == 0 and counter(value['runtime'], 'queuedDeliveries') == 0
                and counter(value['runtime'], 'activeDeliveries') == 0, 'Behavior result delivery still retained')


def idle(value):
    for key in ('callsInFlight', 'retiredCalls', 'activeExecutions'): require(counter(value['runtime'], key) == 0, 'Interpreter is not idle: ' + key)
    require(counter(value['modules'], 'handlersOutstanding') == 0, 'Raw module handlers remain')


def retention(value, count):
    for key in ('callsInFlight', 'retiredCalls'): require(counter(value['runtime'], key) == count, 'Cancelled call capacity released prematurely')
    require(counter(value['modules'], 'handlersOutstanding') == count, 'Raw handler retention differs')


def budget_results(values):
    require(isinstance(values, list) and len(values) == 2 and len({item['execution'] for item in values}) == 2, 'Both distinct actual budget executions required')
    for item, reason, behavior in zip(values, ('routine-depth', 'execution-step-budget'), ('recursive', 'step-budget')):
        require(item['state'] == 'BUDGET_EXHAUSTED' and item['reason'] == reason and item['behavior'] == 'qabehavior:' + behavior, 'Wrong execution budget failure')


def restart_files(report, snapshots):
    source = 'npcs/qa/' + report['npcId'] + '.json'
    expected = {source, 'state/npc-definitions/last-valid/' + source, 'behaviors/qabehavior/hold.json'}
    require(set(report['beforeRestart']) == set(snapshots['before-restart']) == expected, 'Restart requires exact source, checkpoint and behavior snapshots')
    require({name: imports.text_hash(raw.decode()) for name, raw in snapshots['before-restart'].items()} == report['beforeRestart'], 'Restart file hashes differ from archived snapshots')
    require(snapshots['before-restart'] == {name: snapshots['offline-owned-cleanup'][name] for name in expected}, 'Restart/disable changed valid source/checkpoint bytes')


def logical_wait(ledger, gate):
    identity = gate['terminal']['execution']; due = gate['dueTick']
    running = [row for row, value in ledger.executions if value['execution'] == identity and value['state'] == 'RUNNING'
               and 'Behavior wait: tick:' + str(due) in row['output']]
    terminal = [row for row, value in ledger.executions if value == gate['terminal']]
    require(running and terminal, 'Final wait lacks its actual running/deadline and terminal observations')
    before, after = running[0]['logOffset'], terminal[0]['logOffset']; require(before < after, 'Final wait observation order differs')
    statuses = [(row, value) for row in ledger.rows if row['phase'] == 'manual' for value in json_rows(row['receipt'], 'Behavior QA status ')]
    require(any(before < row['logOffset'] < after and counter(value['runtime'], 'tick') < due for row, value in statuses), 'No pre-deadline status within this execution interval')
    require(any(row['logOffset'] > after and value == gate['status'] and counter(value['runtime'], 'tick') >= due for row, value in statuses), 'No matching post-terminal deadline observation')


def cancellation_after(ledger, command, npc, npc_uuid, revision):
    admissions = [row for row in ledger.rows if row['phase'] == 'manual' and row['logOffset'] < command['logOffset']
                  and row['command'] == f'animus behaviors run {npc} qabehavior:hold' and 'Behavior run: OK | execution=' in row['receipt']]
    require(admissions, 'Mutation lacks a prior owned hold admission'); prior = admissions[-1]
    identity = re.search(r'execution=(' + UUID.pattern + ')', prior['receipt'])[1]
    require(any(prior['logOffset'] < row['logOffset'] < command['logOffset'] and value.get('pending') == [{'npc': npc_uuid, 'cancelled': False}]
                for row in ledger.rows if row['phase'] == 'manual' for value in json_rows(row['receipt'], '; result ')), 'No live owned raw hold observed before mutation')
    following = [(row, value) for row, value in ledger.executions if row['phase'] == 'manual' and row['logOffset'] > command['logOffset']]
    require(following, 'Mutation lacks terminal observation'); value = following[0][1]
    require(value['execution'] == identity and value['state'] == 'CANCELLED' and value['npc'] == npc and value['npc-uuid'] == npc_uuid and value['npc-revision'] == revision, 'Definition mutation cancelled a different lease or captured revision')


def basic_events(snapshot, npc):
    require(snapshot.get('dropped') == 0 and snapshot.get('pending') == [], 'Fixture event loss/unfinished work')
    events = snapshot['events']; require(len(events) == 12 and all(type(e['sequence']) is int for e in events) and [e['sequence'] for e in events] == list(range(1, 13)), 'Exact event sequence missing')
    require(all(e['npc'] == npc for e in events), 'Handler observed a different NPC UUID')
    conditions = [e['arguments'] for e in events if e['kind'] == 'condition']
    records = [e['arguments'] for e in events if e['kind'] == 'record']
    require(conditions == [{'result': True}, {'result': False}] and all(type(c['result']) is bool for c in conditions) and len(records) == 10, 'Typed branch results differ')
    tags = [r['tag'] for r in records]
    require(tags[:7] == ['start', 'bound', 'true', 'false', 'loop', 'loop', 'loop'] and set(tags[7:9]) == {'branch-a', 'branch-b'} and tags[9] == 'after', 'Sequence/routine/repeat/join ordering differs')
    require(records[1] == {'tag': 'bound', 'longValue': 9223372036854775807, 'decimalValue': 1.25, 'booleanValue': True, 'stringValue': 'Garde Étoile'}, 'Typed argument binding changed')
    require(all(type(records[1][key]) is kind for key, kind in [('longValue', int), ('decimalValue', float), ('booleanValue', bool), ('stringValue', str)]), 'Handler JSON argument types differ')
    return tags


def audit_log_line(line, phase):
    line = clean(line)
    if base.BAD_LOG.search(line) or re.search(r'\bSEVERE\b|\b\w+(?:Error|Exception)\b|^\s+at [\w.$]+\(', line):
        # Exact public module-list row observed on the managed Paper preflight. The
        # lifecycle logger reports schema-keyword-unavailable without an exception.
        allowed = phase != 'cold-disable' and re.fullmatch(
            r'\[[0-9]{2}:[0-9]{2}:[0-9]{2}\] \[(?:Server thread|Folia Region Scheduler Thread #[0-9]+)/INFO\]: \[\] '
            r'qabehaviorbad \[qabehaviorbad\.jar\] \| FAILED \| enable-failed: SchemaException', line)
        require(allowed, 'Unexpected server diagnostic: ' + line)


class Ledger:
    """Bind recorded receipts to their original raw-character offsets, never a later matching line."""
    def __init__(self, logs, rows):
        self.logs, self.rows = logs, rows
        require(rows, 'No original console receipts')
        previous = dict.fromkeys(PHASES, -1)
        for row in rows:
            phase, offset = row['phase'], row['logOffset']
            require(phase in PHASES and type(offset) is int and offset >= previous[phase] and offset >= 0, 'Invalid/out-of-order original log offset')
            previous[phase] = offset
            output, receipt = row['output'], row['receipt']
            require(isinstance(output, str) and output and isinstance(receipt, str) and receipt in output, 'Missing receipt output')
            require(clean(logs[phase][offset:]).startswith(output), 'Recorded command output differs at its original log offset')
        self.executions = [(row, execution(row['receipt'])) for row in rows if 'Behavior execution=' in row['receipt']]

    def find(self, command, phase='manual'):
        found = [r for r in self.rows if r['phase'] == phase and r['command'] == command]
        require(found, 'Missing actual command: ' + command)
        return found

    def observed(self, value, prefix='Behavior QA status ', phase='manual'):
        require(any(value == candidate for row in self.rows if row['phase'] == phase
                    for candidate in json_rows(row['receipt'], prefix)), 'Reported object has no matching original command receipt')
        return value

    def inspect(self, value, state=None, phase='manual'):
        require(any(row['phase'] == phase and item == value for row, item in self.executions), 'Reported execution differs from original inspect')
        if state is not None: require(value['state'] == state, 'Wrong execution terminal state')
        return value

    def action(self, npc, name, phase='manual'):
        rows = self.find(f'animus modules action qabehaviorctl:{name} {npc}-control {{}}', phase)
        return [(row, json.loads(row['receipt'].split('; result ', 1)[1])) for row in rows if 'qabehaviorctl:' + name + ': OK; result ' in row['receipt']]

    def action_observed(self, npc, name, value, phase='manual'):
        require(any(observed == value for _, observed in self.action(npc, name, phase)), 'Fixture result not present in original console receipt')


class BehaviorAuditor(imports.ImportAuditor):
    def bind_sources(self, manifest_path, status_path):
        result = self.bind_snapshot(manifest_path, status_path)
        manifest = read(manifest_path)['Animus']['files']
        self.frozen = {name: h for name, h in manifest.items() if name.startswith('qa/behaviors/') and '__pycache__' not in Path(name).parts and not name.endswith('.pyc')
                       or name in ('qa/multiplexor/behaviors.py', 'qa/multiplexor/managed-runtime.py')}
        require(len(self.frozen) >= 20 and 'qa/behaviors/acceptance.json' in self.frozen, 'Behavior harness absent from build capture')
        artifacts = [a for j in read(status_path)['runs'][0]['jobs'] if j['project'] == 'animus' for a in j.get('artifacts', []) if a.get('role') == 'api']
        require(len(artifacts) == 1, 'One standalone API artifact required')
        self.api = artifacts[0]; self.file(self.api['file'], self.api['sha256'], 'standalone-api-artifact')
        require({g['id'] for g in read(self.repo / 'qa/behaviors/acceptance.json')['gates']} == set(CHECKS), 'Reviewed acceptance gates differ')
        return result

    def fixtures(self, folder, data, report):
        self.file(folder / 'modules-build.json', kind='fixture-build'); self.file(folder / 'observer-build.json', kind='fixture-build')
        modules, observer = read(folder / 'modules-build.json'), read(folder / 'observer-build.json')
        require(modules['status'] == 'compiled-only' and modules['apiSha256'] == report['apiSha256'] == self.api['sha256'], 'Fixture API differs from selected build')
        require(modules['builderSha256'] == self.frozen['qa/behaviors/build-fixtures.py'], 'Module builder source differs')
        require(len(modules['modules']) == 4 and {x['id'] for x in modules['modules']} == {'qabprobe', 'qabehavior', 'qabehaviorctl', 'qabehaviorbad'}, 'Wrong trusted fixture set')
        expected = {m['file']: m['sha256'] for m in modules['modules']}
        require(report['initialModuleHashes'] == report['finalModuleHashes'] == expected, 'Trusted module bytes changed')
        require({p.name for p in (data / 'modules').glob('*.jar')} == set(expected), 'Actual installed module set differs')
        for module in modules['modules']:
            require(module['file'] == module['id'] + '.jar', 'Unsafe fixture filename')
            archive = data / 'modules' / module['file']; self.file(archive, module['sha256'], 'deployed-behavior-module')
            with zipfile.ZipFile(archive) as jar:
                require(json.loads(jar.read('animus-module.json')) == module['manifest'], 'Trusted module manifest differs')
                require(all(not name.startswith('art/arcane/animus/') for name in jar.namelist()), 'Fixture bundled private/public Animus classes')
            for source, h in module['sources'].items(): require(self.frozen.get(source) == h, 'Fixture source not captured')
            classpath = module['compileClasspath']; require(classpath and classpath[0]['sha256'] == self.api['sha256'], 'External fixture did not compile against selected API')
            expected_extra = [expected['qabprobe.jar']] if module['id'] in ('qabehavior', 'qabehaviorctl') else []
            require([p['sha256'] for p in classpath[1:]] == expected_extra, 'Unexpected fixture private compile dependency')
            for item in classpath: self.file(item['file'], item['sha256'], 'fixture-compile-input')
        observer_names = ['qa/behaviors/build-plugin-fixture.py', 'qa/behaviors/fixture/plugin.yml', 'qa/behaviors/fixture/src/art/arcane/animus/qa/BehaviorFixture.java']
        require(observer['sources'] == {s: self.frozen[s] for s in observer_names} and observer['pluginSha256'] == self.artifact_hash, 'Observer source/artifact binding differs')
        self.file(observer['spigotApi'], observer['spigotApiSha256'], 'observer-compile-api')
        require(report['plugins'] == sorted([{'file': self.artifact_name, 'sha256': self.artifact_hash}, {'file': 'AnimusBehaviorFixture.jar', 'sha256': observer['fixtureSha256']}], key=lambda v: v['file']), 'Unexpected plugin set')
        expected_sources = {name.removeprefix('qa/behaviors/fixtures/valid/'): h for name, h in self.frozen.items() if name.startswith('qa/behaviors/fixtures/valid/')}
        require(report['initialBehaviorHashes'] == {'behaviors/' + name: h for name, h in expected_sources.items()} and len(expected_sources) == 10, 'Initial behavior source bytes differ')
        return modules

    def logs(self, folder, report):
        logs = {}
        for started, stopped in zip(report['phases'], report['stops']):
            phase = started['phase']; path = folder / (phase + '-server.log')
            require(Path(stopped['log']) == path, 'Phase log escaped its immutable attempt')
            self.file(path, stopped['logSha256'], 'behavior-phase-log'); logs[phase] = path.read_text()
            require(started['ready'] in logs[phase] and 'Done (' in started['ready'], 'Missing actual server-ready line')
            for line in logs[phase].splitlines(): audit_log_line(line, phase)
        final = folder / 'final-server.log'; require(Path(report['finalServerLog']) == final, 'Final log path differs')
        self.file(final, report['finalServerLogSha256'], 'final-server-log')
        require(final.read_text() == logs['restart-and-disable'], 'Final log differs from last actual stop')
        return logs

    def snapshots(self, folder, data, report):
        result = {}
        allowed = {f'npcs/qa/{npc}.json' for npc in report['ownedNpcIds']}
        allowed |= {'state/npc-definitions/last-valid/' + name for name in allowed}
        allowed |= {'behaviors/' + name.split('/fixtures/', 1)[1].split('/', 1)[1] for name in self.frozen if '/fixtures/' in name and name.endswith('.json')}
        for group in report['snapshots']:
            label = group['label']; require(label not in result and re.fullmatch('[a-z0-9-]+', label), 'Duplicate/unsafe snapshot label')
            files = {}; result[label] = files
            for item in group['files']:
                source = item['source']; require(source in allowed and source not in files, 'Unowned/duplicate snapshot path')
                if item.get('missing'): files[source] = None; continue
                archive = folder / imports.relative(item['archive'])
                require(archive.parent == folder / 'snapshots' / label, 'Snapshot archive escaped label directory')
                self.file(archive, item['sha256'], 'behavior-source-snapshot'); files[source] = archive.read_bytes()
            for source, raw in files.items():
                if raw is None or not source.startswith('npcs/'): continue
                document = json.loads(raw); require(document['id'] in report['ownedNpcIds'], 'Snapshot belongs to other definition')
                pointer_raw = files.get('state/npc-definitions/last-valid/' + source)
                require(pointer_raw is not None, 'Definition snapshot lacks its last-valid pointer')
                pointer = json.loads(pointer_raw)
                require(pointer['schemaVersion'] == 1 and pointer['path'] == source and pointer['sourceHash'] == imports.text_hash(raw.decode()), 'Checkpoint source token mismatch')
                obj = self.data_file(data, 'state/objects/npc-definitions/' + pointer['objectHash'] + '.json')
                require(digest(obj) == pointer['objectHash'] and read(obj) == document, 'Checkpoint object mismatch')
        for required in ('initial-definition', 'invalid-validation', 'malformed-behavior', 'same-revision-conflict', 'definition-edit', 'before-despawn', 'after-despawn', 'before-restart', 'offline-owned-cleanup'):
            require(required in result, 'Missing phase snapshot: ' + required)
        return result

    def semantics(self, report, logs, ledger, snapshots, modules):
        gates = {g['id']: g for g in report['checks']}; npc = report['npcId']; source = f'npcs/qa/{npc}.json'
        manual, restart, cold = (clean(logs[p]) for p in ('manual', 'restart-and-disable', 'cold-disable'))
        def observed_status(value, phase='manual'): return ledger.observed(value, phase=phase)
        def inspected(value, state=None, phase='manual'): return ledger.inspect(value, state, phase)
        def cancelled(value):
            if isinstance(value, str): identity = value
            else: identity = value['execution']
            require(any(row['phase'] == 'manual' and v['execution'] == identity and v['state'] == 'CANCELLED' for row, v in ledger.executions), 'Missing actual cancellation terminal report')
        def retained(item, count=1):
            require(len(item['pending']) == count and all(p['cancelled'] is True for p in item['pending']), 'Module token was not cancelled')
            require(any(value.get('pending') == item['pending'] for _, value in ledger.action(npc, 'inspect')), 'Pending token report not bound to fixture receipt')
            retention(observed_status(item['status']), count)
        def released(item, count=1):
            require(item['release'] == {'released': count, 'cancelled': count}, 'Raw release did not observe cancellation')
            ledger.action_observed(npc, 'release_all', item['release']); idle(observed_status(item['status']))

        # Original cold receipts must contain no execution/status preparation before official disable.
        cold_gate = gates['cold-immediate-disable']; disabled = cold_gate['disable']
        require(disabled in json_rows(cold, 'Behavior QA disabled '), 'Cold disable receipt not in original log')
        require(disabled.get('cold') is True and disabled.get('enabled') is False and disabled.get('serviceClosed') is True
                and disabled.get('coordinatorBefore') is True and disabled.get('before') is None, 'Cold startup ownership/disable differs')
        require(type(disabled.get('durationNanos')) is int and 0 <= disabled['durationNanos'] < 2_000_000_000, 'Cold disable blocked')
        quiescence(ledger.observed(cold_gate['final'], phase='cold-disable'), True)
        require(all(r['command'] == 'behaviorqa status' for r in ledger.rows if r['phase'] == 'cold-disable')
                and 'Animus behavior QA enabled' not in cold and 'Animus behavior QA event' not in cold, 'Cold path warmed or initialized fixtures')

        listing = ledger.find('animus modules list')[0]['output']; require('Module list: 4;' in listing, 'Missing startup module inventory')
        for item in modules['modules']:
            require(item['id'] + ' [' + item['file'] + '] | ' + item['expectedStartup'] + ' |' in listing, 'Actual module state differs: ' + item['id'])
        capabilities = ledger.find('animus modules capabilities')[0]['output']
        require('Module capabilities: 8; built-in: 19;' in capabilities and 'qabehaviorbad:staged' not in capabilities and 'qabehaviorbad:unsupported' not in capabilities, 'Failed schema registration leaked')
        original = json.loads(snapshots['initial-definition'][source]); require(original['spawned'] is False and original['uuid'] == report['npcUuid'] and original['revision'] == report['initialRevision'], 'Initial dormant definition differs')
        validation = ledger.find('animus behaviors validate')[0]
        require('Behaviors validate: INVALID |' in validation['receipt'] and 'published=false' in validation['receipt'], 'Validation applied or accepted invalid files')
        generation = gates['readonly-file-validation']['acceptedGeneration']
        require(counter(validation['receipt'], 'generation') == generation, 'Validation changed accepted generation')
        for name in ('unknown-node', 'repeat-limit', 'parallel-limit', 'persistent-scope'):
            require('Behavior diagnostic: behaviors/qabehavior/' + name + '.json |' in gates['readonly-file-validation']['diagnostics'], 'Missing intentional compiler diagnostic')
        require(gates['readonly-file-validation']['diagnostics'] in clean(logs['manual'][validation['logOffset']:]), 'Validation diagnostics absent at command observation')
        for name, raw in snapshots['invalid-validation'].items():
            fixture = 'qa/behaviors/fixtures/' + ('valid/' if name in report['initialBehaviorHashes'] else 'invalid/') + name.removeprefix('behaviors/')
            require(imports.text_hash(raw.decode()) == self.frozen[fixture], 'Read-only validation changed source bytes')

        first = inspected(gates['manual-dormant-start']['execution'], 'COMPLETED')
        require(first['npc'] == npc and first['npc-uuid'] == original['uuid'] and first['npc-revision'] == original['revision']
                and first['behavior'] == 'qabehavior:basic' and first['behavior-revision'] == 1
                and first['source-hash'] == report['initialBehaviorHashes']['behaviors/qabehavior/basic.json'], 'Manual captured identity/source differs')
        events = gates['typed-arguments-and-conditions']['events']; ledger.action_observed(npc, 'inspect', events)
        require(basic_events(events, original['uuid']) == gates['sequence-repeat-routine-parallel']['tags'], 'Join/typed gate differs from actual handler events')
        log_events = json_rows(manual, 'Animus behavior QA event ')
        require(log_events[:12] == events['events'], 'Handler event log differs from inspect results')

        wait = gates['logical-final-wait']; final = inspected(wait['terminal'], 'COMPLETED')
        require(final['behavior'] == 'qabehavior:final-wait' and type(wait['dueTick']) is int, 'Wrong final-wait execution')
        logical_wait(ledger, wait)
        refusals = gates['resolved-schema-refusal']['refusals']; require([r['behavior'] for r in refusals] == ['bad-argument', 'bad-binding'], 'Both resolved schema refusal cases required')
        for item in refusals:
            value = inspected(item['execution'], 'FAILED'); require(value['behavior'] == 'qabehavior:' + item['behavior'], 'Schema refusal wrong program')
            require(clean(item['diagnostic']) in manual and 'Behavior diagnostic: execution |' in clean(item['diagnostic']) and 'longValue' in item['diagnostic'], 'Resolved field diagnostic missing')
            require(not any(e['npc'] == value['npc-uuid'] and e['kind'] == 'record' for e in self.events_between(ledger, logs, value['execution'])), 'Invalid arguments entered handler')
        require(any('Behavior run: UNAVAILABLE' in r['receipt'] for r in ledger.find(f'animus behaviors run {npc} qabehavior:missing')), 'Missing capability was admitted')
        budget_results(gates['execution-budgets']['executions'])
        for item in gates['execution-budgets']['executions']: inspected(item, 'BUDGET_EXHAUSTED')
        conflict = inspected(gates['parallel-write-conflict']['execution'], 'FAILED'); require(conflict['reason'].startswith('parallel-write-conflict:'), 'Parallel state conflict was not rejected')
        active = gates['one-active-execution-per-npc']['execution']; require(any(v['execution'] == active and v['state'] == 'RUNNING' for _, v in ledger.executions), 'First execution replaced')
        require(any('Behavior run: BUSY' in r['receipt'] for r in ledger.find(f'animus behaviors run {npc} qabehavior:basic')), 'Second NPC execution was not rejected')
        cancelled(active); retained(gates['manual-cancel-raw-retention']); released(gates['explicit-release-quiescence'])

        # Every explicit reload must publish its observation; rejected/no-op scans cannot prove retention.
        reloads = ledger.find('animus behaviors reload'); require(len(reloads) == 6, 'Expected six revision/removal observations')
        require(all('Behaviors reload: APPLIED |' in row['receipt'] for row in reloads), 'Rejected scan substituted for behavior reload evidence')
        require([counter(row['receipt'], 'generation') for row in reloads] == list(range(generation + 1, generation + 7)), 'Catalog generation did not advance exactly once per scan')
        old = inspected(gates['unchanged-reload-preserves-execution']['execution'], 'RUNNING')
        for index in range(3):
            after = reloads[index]['logOffset']; before = reloads[index + 1]['logOffset']
            require(any(row['phase'] == 'manual' and after < row['logOffset'] < before and value == old for row, value in ledger.executions), 'Reload retention lacks an inspect in its own observation interval')
        for gate_id, state, code, label, hash_key in [('invalid-reload-retains-program', 'INVALID', 'behavior-json-invalid', 'malformed-behavior', 'invalidSha256'),
                                                    ('same-revision-conflict', 'CONFLICT', 'behavior-revision-conflict', 'same-revision-conflict', 'changedSha256')]:
            gate = gates[gate_id]; require(inspected(gate['execution'], 'RUNNING') == old, 'Invalid/conflicting edit changed active source')
            line = clean(gate['entry']); require(line in manual and '| state=' + state + ' | available=true | accepted-revision=' + str(old['behavior-revision']) + ' |' in line and '| source-hash=' + old['source-hash'] in line, 'Retained catalog state differs')
            require(clean(gate['diagnostic']) in manual and '| ' + code + ' |' in gate['diagnostic'], 'Exact invalid/conflict diagnostic absent')
            raw = snapshots[label]['behaviors/qabehavior/hold.json']; require(imports.text_hash(raw.decode()) == gate[hash_key] != old['source-hash'], 'Invalid bytes were repaired or source hash fabricated')
        replace = gates['accepted-program-replacement']; fresh = inspected(replace['current'], 'RUNNING')
        require(replace['previous'] == old and fresh['execution'] != old['execution'] and fresh['behavior-revision'] == old['behavior-revision'] + 1 and fresh['source-hash'] != old['source-hash'], 'Valid replacement did not change captured revision/source')
        cancelled(old); retained(replace['cancellation']); released(replace['release'])
        restored = inspected(gates['source-removal-and-restore']['previous'], 'RUNNING')
        require(restored['source-hash'] == fresh['source-hash'] == gates['source-removal-and-restore']['sourceSha256'], 'Restored accepted source differs')
        require('Behavior qabehavior:hold | path=behaviors/qabehavior/hold.json | state=MISSING | available=false' in manual, 'Missing source stayed available')
        require(any('Behavior run: UNAVAILABLE' in r['receipt'] for r in ledger.find(f'animus behaviors run {npc} qabehavior:hold')), 'Missing source admitted a run')
        cancelled(fresh); cancelled(restored)

        edited = json.loads(snapshots['definition-edit'][source]); before = json.loads(snapshots['before-despawn'][source]); after = json.loads(snapshots['after-despawn'][source])
        edit_gate = gates['definition-revision-cancellation']; require(edit_gate['before'] == original['revision'] and edit_gate['after'] == edited['revision'] == original['revision'] + 1 and edited['name'] == 'Behavior Edited' and edited['uuid'] == original['uuid'], 'Definition edit identity/revision differs')
        released(edit_gate['release'])
        despawn = gates['definition-despawn-intent']; require(despawn['before'] == before and despawn['after'] == after and despawn['physicalNpcAssertion'] is False, 'Despawn definition evidence differs')
        require(before['spawned'] is True and after['spawned'] is False and after['revision'] == before['revision'] + 1 and before['uuid'] == after['uuid'] == original['uuid'], 'Despawn intent did not publish new authority')
        released(despawn['release'])
        deletion = gates['definition-delete-cancellation']; persisted = json.loads(snapshots['before-restart'][source])
        require(deletion['oldUuid'] == original['uuid'] and deletion['recreatedUuid'] == persisted['uuid'] != original['uuid'] and persisted['revision'] == 1, 'Delete/recreate retained stale identity')
        require(any(re.search(r'Deleted [^;]*; tx ', r['receipt']) for r in ledger.find('animus delete ' + npc)), 'No actual definition deletion command')
        for command, document in [('animus edit ' + npc + ' {"name":"Behavior Edited"}', original), ('animus despawn ' + npc, before), ('animus delete ' + npc, after)]:
            cancellation_after(ledger, ledger.find(command)[0], npc, document['uuid'], document['revision'])

        capacity = gates['invocation-capacity-after-cancel']; retained(capacity['retained'], 4); retention(observed_status(capacity['status']), 4); idle(observed_status(capacity['final']))
        blocked = capacity['blocked']; require(any(v['execution'] == blocked and v['state'] == 'RUNNING' and 'Behavior wait: capacity:invocation' in row['output'] for row, v in ledger.executions), 'Fifth invocation did not wait behind retained calls')
        cancelled(blocked); ledger.action_observed(npc, 'release_all', {'released': 4, 'cancelled': 4})
        require(any(len(value.get('events', [])) == 8 and sum(e['kind'] == 'hold-enter' for e in value['events']) == 4 and sum(e['kind'] == 'hold-release' for e in value['events']) == 4 for _, value in ledger.action(npc, 'inspect')), 'Cancelled capacity allowed extra raw callbacks')

        restart_gate = gates['managed-restart-no-stack-resume']; require(restart_gate['priorExecution'] == report['interruptedExecution'] and restart_gate['freshExecution'] != restart_gate['priorExecution'], 'Transient execution identity resumed')
        idle(observed_status(restart_gate['startup'], 'restart-and-disable'))
        prior = ledger.find('animus behaviors inspect ' + report['interruptedExecution'], 'restart-and-disable')[0]
        require('Behavior inspect: NOT_FOUND |' in prior['receipt'], 'Interrupted execution resumed after restart')
        restart_files(report, snapshots)
        shutdown = gates['actual-plugin-disable']; disabled = shutdown['disable']
        require(disabled in json_rows(restart, 'Behavior QA disabled ') and disabled['cold'] is False and disabled['enabled'] is False and disabled['serviceClosed'] is True, 'Missing actual warm plugin disable')
        require(type(disabled['durationNanos']) is int and 0 <= disabled['durationNanos'] < 2_000_000_000 and counter(disabled['before'], 'callsInFlight') == 1 and counter(disabled['before'], 'activeExecutions') == 1, 'Warm disable blocked or lacked active work')
        quiescence(observed_status(shutdown['final'], 'restart-and-disable'), False)
        require('Behavior QA post-disable run=UNAVAILABLE' in restart and 'Animus behavior QA dependency disable releases {"released":1,"cancelled":1}' in restart, 'Shutdown failed to revoke/release raw work')
        shutdown_events = json_rows(restart, 'Animus behavior QA event ')
        require([e['kind'] for e in shutdown_events] == ['hold-enter', 'hold-release'] and all(e['npc'] == persisted['uuid'] for e in shutdown_events) and shutdown_events[-1]['arguments'] == {'cancelled': True}, 'Cancelled stack continued during shutdown')

    @staticmethod
    def events_between(ledger, logs, identity):
        starts = [r for r in ledger.rows if 'Behavior run: OK | execution=' + identity in r['receipt']]
        ends = [r for r, value in ledger.executions if value['execution'] == identity and value['state'] != 'RUNNING']
        require(len(starts) == 1 and ends, 'Execution lacks actual admission/terminal bounds')
        return json_rows(logs[starts[0]['phase']][starts[0]['logOffset']:ends[0]['logOffset'] + len(ends[0]['output'])], 'Animus behavior QA event ')

    def durable_transitions(self, data, report, snapshots, ledger):
        npc = report['npcId']; path = f'npcs/qa/{npc}.json'
        journals = {}
        for row in ledger.rows:
            match = re.search(r'\btransaction (' + UUID.pattern + ')', row['receipt'])
            if match: journals[match[1]] = self.journal(data, match[1])
        require(journals, 'Actual mutation transaction receipts absent')
        initial = snapshots['initial-definition'][path].decode(); edited = snapshots['definition-edit'][path].decode()
        before = snapshots['before-despawn'][path].decode(); after = snapshots['after-despawn'][path].decode()
        recreated = snapshots['before-restart'][path].decode()
        pairs = [journal[path] for journal in journals.values() if path in journal]
        for pair in [(None, initial), (initial, edited), (before, after), (after, None), (None, recreated)]:
            require(pair in pairs, 'Actual journal does not establish required durable definition transition')
        for identity in report['ownedNpcIds'][2:]:
            key = f'npcs/qa/{identity}.json'; changes = [j[key] for j in journals.values() if key in j]
            require(len(changes) == 2 and sum(old is None and new is not None for old, new in changes) == 1 and sum(old is not None and new is None for old, new in changes) == 1, 'Capacity definition cleanup lacks create/delete journal pair')

    def cleanup(self, folder, data, report, snapshots):
        require(report.get('finalProvenanceProblems') == [], 'Harness inputs changed during the attempt')
        expected_archives = {'fixtures/' + p['file']: p['sha256'] for p in report['plugins'] if p['file'] != self.artifact_name}
        expected_archives.update({'fixtures/' + name: h for name, h in report['initialModuleHashes'].items()})
        require(report['archivedFixtures'] == expected_archives, 'Fixture archives are missing or unexpected')
        for path, h in report['archivedFixtures'].items(): self.file(folder / imports.relative(path), h, 'archived-exact-fixture')
        require(len(report['inputFiles']) == 7, 'Original fixture input inventory differs')
        for path, h in report['inputFiles'].items(): self.file(path, h, 'original-fixture-input')
        owned = report['ownedFiles']; require(owned and set(report['offlineCleanup']['files']) == set(snapshots['offline-owned-cleanup']), 'Cleanup receipt differs from archived final source set')
        expected = {f'npcs/qa/{npc}.json' for npc in report['ownedNpcIds']}
        expected |= {'state/npc-definitions/last-valid/' + path for path in expected}
        expected |= {'behaviors/' + name.split('/fixtures/', 1)[1].split('/', 1)[1] for name in self.frozen if '/fixtures/' in name and name.endswith('.json')}
        require(set(owned) == expected, 'Exact owned source/pointer inventory differs')
        absent = []
        for path in owned:
            target = data / imports.relative(path); require(not base.lexists(target), 'Owned source/pointer remains: ' + path); absent.append(str(target))
        for source, expected_hash in report['initialBehaviorHashes'].items():
            raw = snapshots['offline-owned-cleanup'][source]
            if source == 'behaviors/qabehavior/hold.json': expected_hash = report['beforeRestart'][source]
            require(imports.text_hash(raw.decode()) == expected_hash, 'Final behavior source changed beyond the declared revision workflow')
        for directory in ('npcs', 'state/npc-definitions/last-valid/npcs', 'behaviors'):
            root = data / directory; require(not root.is_symlink() and not list(root.rglob('*.json')), 'Unowned or unclean JSON tree: ' + directory)
        config = data / 'config.toml'; self.file(config, kind='plugin-config')
        require(config.read_text() == CONFIG, 'Exact synthetic TOML/world/module/behavior budgets differ')
        return absent

    def target(self, path, project, version):
        self.file(path, kind='behavior-attempt'); report = read(path); structure(report); folder = path.parent
        name = target_name(project, version); require(folder.parent.name == name, 'Wrong attempt target directory')
        require(report['pluginSha256'] == self.artifact_hash, 'Behavior attempt uses another artifact')
        instance = self.deployed(report, project, version, name); data = instance / 'plugins/Animus'
        require(report['deployedPlugins'] == report['plugins'] and report['finalServerSha256'] == report['serverSha256'], 'Actual deployment changed')
        pinned_path = mw.evidence_root(self.workspace) / base.target_name(project, version) / 'runtime.json'
        self.file(pinned_path, kind='pinned-server-reference'); pinned = read(pinned_path)
        require((report['serverBuild'], report['serverChannel'], report['serverSha256']) == (pinned['build'], pinned['channel'], pinned['serverSha256']), 'Official exact server binding differs')
        self.sources(folder, report); modules = self.fixtures(folder, data, report)
        logs = self.logs(folder, report); ledger = Ledger(logs, report['commands']); snapshots = self.snapshots(folder, data, report)
        self.semantics(report, logs, ledger, snapshots, modules); self.durable_transitions(data, report, snapshots, ledger)
        absent = self.cleanup(folder, data, report, snapshots)
        transcript = folder / 'multiplexor-commands.json'; self.file(transcript, kind='managed-transcript'); commands = read(transcript)
        retries = base.managed_commands(commands)
        require(sum(c['command'] == ['runtime', 'start', name, '--no-console'] for c in commands) == 3, 'Three real managed starts required')
        require(sum(c['command'] == ['runtime', 'stop', name, '--graceful'] for c in commands) == 5, 'Prepare/three phase/final managed stops required')
        require(any(c['command'] == ['projects', 'deploy', self.run_id, '--to', name, '--target', 'animus:bukkit', '--json'] for c in commands), 'Exact selected build was not deployed')
        return {'project': project, 'minecraftVersion': version, 'report': str(path), 'port': report['port'], 'checks': len(CHECKS),
                'commands': len(ledger.rows), 'managedLockRetries': retries, 'phases': PHASES, 'serverBuild': report['serverBuild'],
                'serverChannel': report['serverChannel'], 'serverSha256': report['serverSha256'], 'absentNow': absent}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=mw.default_workspace())
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    for name in ('build-status', 'source-snapshot', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--instance-prefix', choices=['animus-behaviors'], required=True)
    args = parser.parse_args(argv); require(not base.lexists(args.output), 'Choose a fresh immutable audit directory'); args.output.mkdir(parents=True)
    audit = BehaviorAuditor(args.workspace.resolve(), args.repo.resolve(), Path('/unused-native-codecs'))
    result = {'schemaVersion': 1, 'scope': SCOPE, 'instancePrefix': args.instance_prefix, 'status': 'failed',
              'reportProfile': {'schemaVersion': 1, 'wrapperSha256': FROZEN_WRAPPER, 'diagnosticStatusGates': sorted(DIAGNOSTIC_STATUS_GATES),
                                'note': 'These four frozen status fields are diagnostic objects, not pass flags; every gate is re-evaluated from original evidence. No reports normalized.'},
              'startedAt': datetime.now(timezone.utc).isoformat(), 'targets': [], 'attempts': [], 'errors': [], 'pending': [],
              'limitations': ['Manual module/data interpreter only; no platform actions, automatic triggers, privileged commands or durable execution stacks.',
                              'Trusted Java remains cooperative; bounded accounting does not preempt blocked handlers.',
                              'Observed filesystem revisions/restart are not a power-loss durability test.']}
    caught = (base.AuditError, OSError, ValueError, KeyError, TypeError, IndexError, AttributeError, zipfile.BadZipFile, StopIteration)
    try:
        audit.archive_auditors(args.output, [Path(__file__), Path(imports.__file__), Path(base.__file__)])
        result['build'] = audit.build(args.build_status.resolve(), args.artifact_sha256)
        result['sourceSnapshot'] = audit.bind_sources(args.source_snapshot.resolve(), args.build_status.resolve()); result['apiArtifact'] = audit.api
        for project in ('paper', 'spigot', 'folia'):
            for version in base.VERSIONS:
                directory = mw.evidence_root(audit.workspace) / target_name(project, version)
                attempts, selected = latest_attempt(directory)
                for attempt in attempts:
                    path = attempt / 'result.json'; item = {'report': str(path), 'status': 'pending'}
                    if path.exists(): audit.file(path, kind='preserved-behavior-attempt'); item.update({k: read(path).get(k) for k in ('status', 'buildRun', 'error')})
                    result['attempts'].append(item)
                if selected is None or not selected.exists(): result['pending'].append({'project': project, 'version': version}); continue
                try: result['targets'].append(audit.target(selected, project, version))
                except caught as error: result['errors'].append({'project': project, 'version': version, 'reason': str(error)})
        for path, item in audit.files.items():
            require(digest(path) == item['sha256'], 'Evidence changed during audit: ' + path)
            if 'linkTarget' in item: require(os.readlink(path) == item['linkTarget'] and str(Path(path).resolve()) == item['resolvedPath'], 'Server link changed')
            else: require(not Path(path).is_symlink(), 'Evidence became a symlink')
        for target in result['targets']:
            base.closed_port(target['port'])
            for path in target['absentNow']: require(not base.lexists(path), 'Owned source reappeared')
        result['status'] = 'failed' if result['errors'] else 'passed' if len(result['targets']) == 9 else 'partial'
    except caught as error: result['errors'].append({'target': 'matrix', 'reason': str(error)})
    result['files'] = audit.files; result['completedAt'] = datetime.now(timezone.utc).isoformat()
    result['counts'] = {'targetsVerified': len(result['targets']), 'groupedBehaviorChecks': sum(t['checks'] for t in result['targets']),
                        'failedAttemptsPreserved': sum(t['status'] == 'failed' for t in result['attempts'])}
    target = args.output / 'audit.json'
    with target.open('x') as stream: json.dump(result, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    for path in args.output.rglob('*'): os.chmod(path, 0o555 if path.is_dir() else 0o444)
    os.chmod(args.output, 0o555)
    print(json.dumps({'status': result['status'], 'audit': str(target.resolve()), 'sha256': digest(target), 'counts': result['counts'], 'errors': result['errors']}))
    return 0 if result['status'] == 'passed' else 2 if result['status'] == 'partial' else 1


if __name__ == '__main__': sys.exit(main())


