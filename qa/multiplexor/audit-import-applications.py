#!/usr/bin/env python3
"""Strict read-only audit of synthetic import data workflows, not native gameplay.

Select an explicit successful build, its source manifest and artifact hash. The
latest attempt on each exact target must pass; old passes never hide failures.
Each invocation preserves a new immutable audit, including incomplete results.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import sys
import uuid
import zipfile
import multiplexor_workspace as mw

spec = importlib.util.spec_from_file_location('import_runtime_audit', Path(__file__).with_name('audit-runtime.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
require, read, digest = base.require, base.read_json, base.digest
SCOPE = 'synthetic import application commands, persistence, checkpoints and rollback; no rendering or private fixture'
PHASES = ['prepare', 'rollback-after-restart', 'empty-restart']
CHECKS = ['preview-identity-and-source-preservation', 'stage-idempotent-without-definition-or-checkpoint-mutation',
          'apply-dormant-combat-disabled-checkpoints-and-repeat', 'duplicate-deterministic-identities-and-resolved-reference-remap',
          'default-stage-rejects-collisions', 'stale-application-rejects-whole-batch-and-keeps-edit',
          'reviewed-replacement-increments-revisions-and-adds-record', 'actual-restart-retains-applied-definitions-and-checkpoints',
          'replacement-rollback-after-restart-restores-exact-bytes', 'reviewed-application-can-reapply-after-rollback',
          'copy-rollback-preserves-original-definitions', 'initial-application-rollback-removes-sources-and-checkpoints',
          'legacy-default-apply-repeat-and-rollback-unchanged', 'second-restart-does-not-resurrect-rolled-back-records']
SOURCES = ['qa/multiplexor/import-applications.py', 'qa/multiplexor/managed-runtime.py',
           'qa/import-applications/assertions.py', 'qa/import-applications/fixtures/baseline.json',
           'qa/import-applications/fixtures/updated.json']
POINTER = 'state/npc-definitions/last-valid/'
UUID = re.compile(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}')


def text_hash(text):
    return hashlib.sha256(text.encode()).hexdigest()


def relative(value):
    require(isinstance(value, str) and value, 'Missing relative evidence path')
    path = Path(value)
    require(not path.is_absolute() and '..' not in path.parts and str(path) == value, 'Unsafe relative evidence path')
    return path


def structure(report):
    require(report.get('schemaVersion') == 1 and report.get('runner') == 'ServerMultiplexor', 'Wrong runner/schema')
    require(report.get('scope') == SCOPE and report.get('status') == 'pass', 'A complete passing data-workflow report is required')
    require(report.get('checks') == [{'id': key, 'status': 'passed'} for key in CHECKS], 'Exact ordered 14 passing checks required')
    require([p.get('phase') for p in report.get('phases', [])] == PHASES, 'Three complete ordered starts required')
    require([p.get('after') for p in report.get('restarts', [])] == PHASES, 'All three owned stops required')
    require(report.get('serverPortClosed') is True and report.get('remainingDefinitions') == []
            and report.get('remainingCheckpoints') == [] and report.get('logFailures') == [], 'Incomplete final cleanup')
    require(not any(report.get(key) for key in ['error', 'definitionCleanupError', 'cleanupError', 'artifactError', 'runningPhase']),
            'Failure or unfinished work is recorded')
    for stop in report['restarts']:
        require(stop.get('serverPortClosed') is True, 'Phase port was not closed')
        seconds = stop.get('seconds')
        require(type(seconds) in (int, float) and math.isfinite(seconds) and seconds >= 0, 'Invalid stop duration')


def latest_attempt(directory):
    attempts = sorted((p for p in directory.glob('application-*') if p.is_dir() and
                       re.fullmatch(r'application-[0-9]+-[0-9]+', p.name)),
                      key=lambda p: tuple(map(int, p.name.split('-')[1:])))
    return attempts, attempts[-1] / 'result.json' if attempts else None


class ImportAuditor(base.Auditor):
    def bind_sources(self, manifest_path, build_status):
        self.file(manifest_path, kind='source-manifest')
        manifest = read(manifest_path)
        run = read(build_status)['runs'][0]
        for project in run['projects']:
            info = manifest[project['name']]
            require(text_hash(json.dumps(info['files'], sort_keys=True)) == info['snapshotSha256'], 'Source manifest aggregate mismatch')
            captured = Path(project['sourceRoot']) / project['relativePath']
            for name, expected in info['files'].items():
                self.file(captured / relative(name), expected, 'captured-build-source')
        self.frozen = {name: manifest['Animus']['files'][name] for name in SOURCES}
        return {'path': str(manifest_path), 'snapshotSha256': {k: v['snapshotSha256'] for k, v in manifest.items()}}

    def sources(self, folder, report):
        require(report.get('sources') == self.frozen, 'Harness/helpers/fixtures differ from selected build snapshot')
        for name, expected in self.frozen.items():
            self.file(folder / 'sources' / name, expected, 'archived-import-harness')
            self.file(self.repo / name, expected, 'current-import-harness')

    def data_file(self, data, path):
        target = data / relative(path)
        for parent in [target, *target.parents]:
            require(not parent.is_symlink(), 'Linked retained import data is forbidden')
            if parent == data: break
        self.file(target, kind='retained-import-data')
        return target

    def plan(self, data, import_id, application_id):
        require(base.HASH.fullmatch(import_id) and base.HASH.fullmatch(application_id), 'Invalid application identity')
        path = self.data_file(data, f'imports/{import_id}/applications/{application_id}/plan.json')
        require(digest(path) == application_id, 'Plan content-address mismatch')
        plan = read(path)
        require(plan['schemaVersion'] == 1 and plan['importId'] == import_id, 'Plan identity/schema mismatch')
        source_report = self.data_file(data, f'imports/{import_id}/report.json')
        require(plan['sourceReportSha256'] == digest(source_report), 'Plan is not bound to the preserved source report')
        candidates = {}
        for target, descriptor in plan['documents'].items():
            require(re.fullmatch(r'(npcs/[a-z0-9_-]+/[a-z0-9_-]+|skins/[a-f0-9]{64})\.json', target), 'Invalid candidate target')
            obj_hash = descriptor['objectHash']
            require(base.HASH.fullmatch(obj_hash), 'Invalid candidate object hash')
            obj = self.data_file(data, f'state/objects/import-applications/{obj_hash}.json')
            require(digest(obj) == obj_hash and read(obj)['schemaVersion'] == 1, 'Candidate object mismatch')
            content = read(obj)['document']; require(isinstance(content, str), 'Candidate must contain raw JSON text')
            candidates[target] = content
            if target.startswith('npcs/'):
                document = json.loads(content); self.dormant(document)
                require(target == f"npcs/{document['group']}/{document['id']}.json", 'Candidate identity/path mismatch')
        require(sum(k.startswith('npcs/') for k in candidates) == plan['records'], 'Plan record count mismatch')
        require(not base.lexists(data / f'imports/{import_id}/applications/{application_id}/receipt.json'), 'Rolled-back or rejected application receipt remains')
        return plan, candidates

    @staticmethod
    def dormant(document):
        require(isinstance(document.get('spawned'), bool), 'Imported definition has no spawned flag')
        combat = document.get('modules', {}).get('animus:combat')
        if combat is not None:
            require(combat.get('enabled') is True and 'reviewAccepted' not in combat, 'Imported combat is not on as converted')

    def snapshot(self, data, values, count):
        require(isinstance(values, dict) and len(values) == count * 2, 'Incomplete definition/checkpoint snapshot')
        definitions = {k: v for k, v in values.items() if k.startswith('npcs/')}
        require(len(definitions) == count and set(values) == set(definitions) | {POINTER + k for k in definitions}, 'Snapshot path pairing mismatch')
        for path, content in definitions.items():
            relative(path); document = json.loads(content); self.dormant(document)
            require(path == f"npcs/{document['group']}/{document['id']}.json" and document['position']['world'] == 'world', 'Snapshot identity/world mismatch')
            pointer = json.loads(values[POINTER + path]); h = pointer['objectHash']
            require(pointer['schemaVersion'] == 1 and pointer['path'] == path and pointer['sourceHash'] == text_hash(content), 'Snapshot recovery pointer mismatch')
            require(base.HASH.fullmatch(h), 'Invalid checkpoint hash')
            obj = self.data_file(data, f'state/objects/npc-definitions/{h}.json')
            require(digest(obj) == h and read(obj) == document, 'Checkpoint object does not match snapshot')
        return definitions

    def journal(self, data, transaction):
        require(isinstance(transaction, str) and UUID.fullmatch(transaction), 'Invalid transaction receipt')
        journal = read(self.data_file(data, f'backups/{transaction}.json'))
        require(journal['id'] == transaction and 0 < len(journal['entries']) <= 10000, 'Journal identity/entry bound mismatch')
        result = {}; total = 0
        for entry in journal['entries']:
            path = entry['path']; relative(path); require(path not in result, 'Duplicate journal target')
            sides = []
            for side in ['before', 'after']:
                if journal.get('schemaVersion', 1) == 1: value = entry[side]
                else:
                    require(journal['schemaVersion'] == 2, 'Unknown journal schema')
                    h = entry[side + 'Object']; value = None
                    if h is not None:
                        require(base.HASH.fullmatch(h), 'Invalid compact journal reference')
                        obj = self.data_file(data, f'state/objects/transaction-data/{h}.json')
                        require(digest(obj) == h, 'Compact journal payload changed'); value = obj.read_text()
                require(value is None or isinstance(value, str), 'Invalid journal payload')
                total += len(value.encode()) if value is not None else 0
                require(total <= 256 * 1024 * 1024, 'Journal payload budget exceeded')
                sides.append(value)
            result[path] = tuple(sides)
        return result

    @staticmethod
    def transition(journal, before, after):
        observed = dict(before)
        for path, (old, new) in journal.items():
            if not (path.startswith('npcs/') or path.startswith(POINTER)): continue
            require(observed.get(path) == old, 'Journal before-state differs from archived snapshot')
            if new is None: observed.pop(path, None)
            else: observed[path] = new
        require(observed == after, 'Journal after-state differs from archived snapshot')

    def state(self, folder, data, report):
        self.file(folder / 'scenario-state.json', kind='archived-scenario-state'); state = read(folder / 'scenario-state.json')
        baseline, updated = state['baselineImport'], state['updatedImport']
        require(not base.lexists(data / f'imports/{baseline}/receipt.json'), 'Legacy application receipt remains after rollback')
        for kind, identity in [('baseline', baseline), ('updated', updated)]:
            require(base.HASH.fullmatch(identity), 'Invalid source import identity')
            self.file(folder / f'{kind}-input.json', kind='synthetic-input')
            encoded = (folder / f'{kind}-input.json').read_bytes(); fixture = read(self.repo / f'qa/import-applications/fixtures/{kind}.json')
            fixture['_qaRun'] = folder.name.removeprefix('application-')
            require(json.loads(encoded) == fixture, 'Run input differs from frozen synthetic fixture')
            filename = f'qa_{kind}_{fixture["_qaRun"]}.yml'
            require(state['inputFiles'][kind] == filename and not base.lexists(data / 'imports/inbox' / filename), 'Owned input was not cleaned')
            source = self.data_file(data, f'imports/{identity}/source.yml')
            require(source.read_bytes() == encoded, 'Original source archive changed')
            summary = read(self.data_file(data, f'imports/{identity}/report.json'))
            require(summary['sourceSha256'] == hashlib.sha256(encoded).hexdigest() and summary['importId'] == identity, 'Source report binding mismatch')
            require(summary['records'] == summary['valid'] == len(fixture['npc']) and summary['skins'] == 1, 'Source report count mismatch')
            for legacy, record in fixture['npc'].items():
                require(read(self.data_file(data, f'imports/{identity}/records/{legacy}.json')) == record, 'Original record archive changed')
            for path, expected in summary['documents'].items():
                require(digest(self.data_file(data, f'imports/{identity}/{path}')) == expected, 'Converted archive document changed')
        plans = {}
        for key, identity in [('baseApplication', baseline), ('copyApplication', baseline), ('staleApplication', updated), ('replacementApplication', updated)]:
            plans[key] = self.plan(data, identity, state[key])
        require([(plans[k][0]['mode'], plans[k][0]['records'], plans[k][0]['replacements']) for k in plans]
                == [('REJECT', 3, 0), ('DUPLICATE', 3, 0), ('REPLACE', 4, 3), ('REPLACE', 4, 3)], 'Unexpected projection modes/counts')
        b = self.snapshot(data, state['baselineSnapshot'], 3)
        before = self.snapshot(data, state['beforeReplacement'], 6)
        after = self.snapshot(data, state['afterReplacement'], 7)
        require(all(b[k] == v for k, v in plans['baseApplication'][1].items() if k.startswith('npcs/')), 'Baseline snapshot differs from reviewed plan')
        require(all(after[k] == v for k, v in plans['replacementApplication'][1].items() if k.startswith('npcs/')), 'Applied replacement differs from reviewed plan')
        copy_plan, copies = plans['copyApplication']; original = read(data / f'imports/{baseline}/report.json')
        for legacy, original_uuid in original['idMap'].items():
            expected_uuid = str(uuid.UUID(bytes=hashlib.md5(f'animus:import-copy:v1:{baseline}:qa:{original_uuid}'.encode()).digest(), version=3))
            path = f'npcs/copy-qa/citizens-{legacy}-qa.json'; value = json.loads(copies[path])
            require(value['uuid'] == copy_plan['uuidMap'][original_uuid] == expected_uuid and value['revision'] == 1, 'Duplicate identity differs')
            require(before[path] == after[path] == copies[path], 'Duplicate bytes changed during replacement')
            source = read(data / f'imports/{baseline}/npcs/world/citizens-{legacy}.json')
            require(value['modules'] == source['modules'], 'Opaque module configuration changed')
            refs = value['migration']['references']; require(len(refs) == len(source['migration']['references']), 'Reference count changed')
            for old, new in zip(source['migration']['references'], refs):
                expected = dict(old)
                if old['status'] == 'resolved': expected.update(npcUuid=copy_plan['uuidMap'][old['npcUuid']], npcId=old['npcId'] + '-qa')
                require(new == expected, 'Duplicate reference mapping changed')
        for path, content in after.items():
            if path.startswith('npcs/world/'):
                revision = json.loads(before[path])['revision'] + 1 if path in before else 1
                require(json.loads(content)['revision'] == revision, 'Replacement revision does not advance correctly')
        require(json.loads(before['npcs/world/citizens-900001.json'])['name'] == 'QAConcurrent', 'Concurrent edit snapshot missing')
        self.transition(self.journal(data, state['baseTransaction']), {}, state['baselineSnapshot'])
        copied = dict(state['baselineSnapshot']); copied.update({k: v for k, v in state['beforeReplacement'].items() if '/copy-qa/' in k})
        self.transition(self.journal(data, state['copyTransaction']), state['baselineSnapshot'], copied)
        self.transition(self.journal(data, state['editTransaction']), copied, state['beforeReplacement'])
        self.transition(self.journal(data, state['replacementTransaction']), state['beforeReplacement'], state['afterReplacement'])
        return state

    def commands(self, folder, data, report, state, logs):
        rows = report.get('commands'); require(isinstance(rows, list) and len(rows) == 32, 'Full 32-command scenario required')
        b, u = state['baselineImport'], state['updatedImport']; app = state['baseApplication']; cp = state['copyApplication']; stale = state['staleApplication']; fresh = state['replacementApplication']
        transaction = lambda i: 'already-applied' if 'Already installed; nothing changed' in rows[i]['receipt'] else re.search(r'\btx ([a-f0-9-]{36})\b', rows[i]['receipt'])[1]
        expected = ['animus status', *[f"animus import preview {state['inputFiles']['baseline']} worlds=qa_legacy:world"] * 2,
            *[f'animus import stage {b} mode=reject'] * 2, *[f'animus import plan {b} {app}'] * 2,
            *[f'animus import stage {b} mode=duplicate copy=qa'] * 2, *[f'animus import plan {b} {cp}'] * 2,
            f"animus import preview {state['inputFiles']['updated']} worlds=qa_legacy:world", f'animus import stage {u}', f'animus import stage {u} mode=replace',
            'animus edit citizens-900001 {"name":"QAConcurrent"}', f'animus import plan {u} {stale}', f'animus import stage {u} mode=replace',
            *[f'animus import plan {u} {fresh}'] * 2, 'animus status', f"animus rollback {state['replacementTransaction']}",
            f'animus import plan {u} {fresh}', f'animus rollback {transaction(21)}', f"animus rollback {state['copyTransaction']}",
            f"animus rollback {state['editTransaction']}", f"animus rollback {state['baseTransaction']}",
            *[f'animus import apply {b}'] * 2, f'animus rollback {transaction(26)}', 'animus validate', 'animus status', 'animus status']
        require([r.get('command') for r in rows] == expected, 'Command order/identity differs from frozen scenario')
        for i, row in enumerate(rows):
            phase = 0 if i < 19 else 1 if i < 30 else 2
            require(isinstance(row.get('receipt'), str) and row['receipt'] in logs[phase], 'Command receipt missing from its actual phase log')
        for i, identity in [(1, b), (2, b), (11, u)]:
            require('Preview ' + identity in rows[i]['receipt'], 'Preview receipt differs from scenario identity')
        for i, identity in [(3, app), (4, app), (7, cp), (8, cp), (13, stale), (16, fresh)]:
            require('Application ' + identity in rows[i]['receipt'], 'Application receipt differs from reviewed plan identity')
        for i in [6, 10, 18, 27]: require(transaction(i) == 'already-applied', 'Repeat application was not idempotent')
        for i, key in [(5, 'baseTransaction'), (9, 'copyTransaction'), (14, 'editTransaction'), (17, 'replacementTransaction')]:
            require(transaction(i) == state[key], 'State transaction differs from command receipt')
        require('NPC collision:' in rows[12]['receipt'] and ('Conflict:' in rows[15]['receipt'] or 'Application no longer matches' in rows[15]['receipt']), 'Collision/conflict receipt absent')
        require('All definitions valid; 0 saved' in rows[29]['receipt'] and 'NPC definitions: 0' in rows[31]['receipt'], 'Empty final registry receipt missing')
        for i in [20, 22, 23, 24, 25, 28]:
            old = self.journal(data, rows[i]['command'].split()[-1]); inverse = self.journal(data, transaction(i))
            for path, (before, after) in old.items(): require(inverse.get(path) == (after, before), 'Rollback journal is not the exact inverse')
            for path, (_, after) in inverse.items():
                if path not in old:
                    require(path.startswith(POINTER) and path.removeprefix(POINTER) in old and old[path.removeprefix(POINTER)][0] is None and after is None,
                            'Unexpected extra rollback mutation')
        self.transition(self.journal(data, transaction(21)), state['beforeReplacement'], state['afterReplacement'])
        return len(rows)

    def target(self, path, project, version):
        self.file(path, kind='import-attempt'); report = read(path); structure(report); folder = path.parent
        require(report['project'] == project and report['minecraftVersion'] == version and report['buildRun'] == self.run_id
                and report['pluginSha256'] == self.artifact_hash, 'Wrong target/build/artifact')
        self.sources(folder, report)
        name = base.target_name(project, version, self.round_number, "import"); instance = self.workspace / 'consumers/plugin-consumers/instances' / name; data = instance / 'plugins/Animus'
        reference = mw.evidence_root(self.workspace) / mw.baseline_name(project, version) / 'runtime.json'
        self.file(reference, kind='pinned-server-reference'); pinned = read(reference)
        require((report['serverBuild'], report['serverChannel'], report['serverSha256']) == (pinned['build'], pinned['channel'], pinned['serverSha256']), 'Pinned exact server build/channel/hash differs')
        require(report['finalServerSha256'] == report['serverSha256'], 'Server changed during run')
        self.file(instance / 'server.jar', report['serverSha256'], 'deployed-server', allow_symlink=True)
        expected = [{'file': self.artifact_name, 'sha256': self.artifact_hash}]
        require(report['plugins'] == report['deployedPlugins'] == expected and {p.name for p in (instance / 'plugins').glob('*.jar')} == {self.artifact_name}, 'Plugin set/hash mismatch')
        self.file(instance / 'plugins' / self.artifact_name, self.artifact_hash, 'deployed-plugin'); base.packaged_description(instance / 'plugins' / self.artifact_name)
        self.file(instance / 'server.properties', kind='owned-server-properties')
        props = dict(line.split('=', 1) for line in (instance / 'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
        require(props['server-ip'] == '127.0.0.1' and props['server-port'] == str(report['port']), 'Owned endpoint changed'); base.closed_port(report['port'])
        for prefix in ['npcs', POINTER + 'npcs']:
            root = data / prefix
            require(not root.is_symlink(), 'Linked owned cleanup directory')
            if root.exists():
                require(not any(p.is_symlink() or p.is_file() for p in root.rglob('*')), 'Owned definitions/checkpoints remain')
        require(not base.lexists(data / 'state/pending.json'), 'Pending transaction remains')
        logs = []
        for phase, stop in zip(report['phases'], report['restarts']):
            log = folder / (phase['phase'] + '-server.log')
            require(Path(stop['log']).absolute() == log.absolute(), 'Phase log outside attempt')
            self.file(log, stop['logSha256'], 'phase-server-log'); self.logs([log]); text = log.read_text()
            require(phase['ready'] in text and 'Done (' in phase['ready'], 'Server startup receipt absent')
            require(f'Runtime stopped (graceful): {name}' in stop['receipt'], 'Managed stop receipt missing')
            logs.append(text)
        final = folder / 'final-server.log'; require(Path(report['finalServerLog']).absolute() == final.absolute(), 'Final log outside attempt')
        self.file(final, report['finalServerLogSha256'], 'final-server-log'); self.logs([final]); require(final.read_text() == logs[-1], 'Final stopped log differs')
        self.file(folder / 'multiplexor-commands.json', kind='managed-command-transcript'); managed = read(folder / 'multiplexor-commands.json')
        managed_retries = base.managed_commands(managed)
        starts = [r for r in managed if r['command'] == ['runtime', 'start', name, '--no-console']]
        stops = [r for r in managed if r['command'] == ['runtime', 'stop', name, '--graceful']]
        require(len(starts) == 3 and len(stops) == 5 and all('tmux session:' in r['stdout'] for r in starts), 'Actual runner three-start/five-stop lifecycle absent')
        require(any(r['command'] == ['projects', 'deploy', self.run_id, '--to', name, '--target', 'animus:bukkit', '--json'] for r in managed), 'Actual artifact deployment missing')
        require('state:        stopped' in report['runtimeAfter'] and 'server pid:   none' in report['runtimeAfter'], 'Managed runtime is not recorded stopped')
        state = self.state(folder, data, report); count = self.commands(folder, data, report, state, logs)
        return {'project': project, 'version': version, 'serverBuild': report['serverBuild'], 'channel': report['serverChannel'],
                'managedLockRetries': managed_retries, 'report': str(path), 'port': report['port'], 'portClosedNow': True, 'checks': len(CHECKS), 'commands': count,
                'starts': 3, 'interveningRestarts': 2, 'status': 'passed'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=mw.default_workspace())
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--build-status', type=Path, required=True)
    parser.add_argument('--source-snapshot', type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--round', type=int, help='Explicit isolated evidence round; no older instance fallback')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if not base.HASH.fullmatch(args.artifact_sha256): parser.error('Explicit lowercase artifact SHA-256 required')
    if base.lexists(args.output): parser.error('Choose a new immutable output directory')
    args.output.mkdir(parents=True)
    audit = ImportAuditor(args.workspace.resolve(), args.repo.resolve(), Path('/unused-native-codecs'), args.round)
    result = {'schemaVersion': 1, 'round': args.round, 'scope': SCOPE, 'status': 'failed', 'startedAt': datetime.now(timezone.utc).isoformat(),
              'targets': [], 'attempts': [], 'errors': [], 'pending': [], 'limitations': [
                  'Data-workflow assertions and console lifecycle only; no native protocol client or graphical appearance test.',
                  'Synthetic public fixtures; private 495-record validation and broader runtime gates have separate evidence.',
                  'Observed restarts and rollback are not a power-loss/crash injection test.',
                  'Point-in-time verification of retained archives, deployment and closed ports.']}
    caught = (base.AuditError, OSError, ValueError, KeyError, TypeError, AttributeError, AssertionError, zipfile.BadZipFile, IndexError)
    try:
        audit.archive_auditors(args.output, [Path(__file__), Path(base.__file__)])
        result['build'] = audit.build(args.build_status.resolve(), args.artifact_sha256)
        result['sourceSnapshot'] = audit.bind_sources(args.source_snapshot.resolve(), args.build_status.resolve())
        for project in ['paper', 'spigot', 'folia']:
            for version in base.VERSIONS:
                directory = mw.evidence_root(audit.workspace) / base.target_name(project, version, args.round, "import")
                attempts, selected = latest_attempt(directory)
                for folder in attempts:
                    path = folder / 'result.json'; entry = {'report': str(path), 'status': 'pending'}
                    if path.exists():
                        audit.file(path, kind='preserved-import-attempt'); value = read(path)
                        entry.update({k: value.get(k) for k in ['status', 'buildRun', 'error']})
                    result['attempts'].append(entry)
                if selected is None or not selected.exists():
                    result['pending'].append({'project': project, 'version': version, 'reason': 'Latest attempt missing/incomplete'}); continue
                try: result['targets'].append(audit.target(selected, project, version))
                except caught as error: result['errors'].append({'project': project, 'version': version, 'reason': str(error)})
        for path, record in audit.files.items():
            require(digest(path) == record['sha256'], 'Evidence changed during audit: ' + path)
            if 'linkTarget' in record: require(os.readlink(path) == record['linkTarget'] and str(Path(path).resolve()) == record['resolvedPath'], 'Server link changed')
            else: require(not Path(path).is_symlink(), 'Evidence became a symlink')
        for target in result['targets']: base.closed_port(target['port'])
        result['status'] = 'failed' if result['errors'] else 'passed' if len(result['targets']) == 9 else 'partial'
    except caught as error: result['errors'].append({'target': 'matrix', 'reason': str(error)})
    result['completedAt'] = datetime.now(timezone.utc).isoformat(); result['files'] = audit.files
    result['counts'] = {'targetsVerified': len(result['targets']), 'groupedDataChecks': sum(t['checks'] for t in result['targets']),
                        'failedAttemptsPreserved': sum(t['status'] == 'failed' for t in result['attempts'])}
    output = args.output / 'audit.json'
    with output.open('x') as stream: json.dump(result, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    os.chmod(output, 0o444); os.chmod(args.output, 0o555)
    print(json.dumps({'status': result['status'], 'audit': str(output.resolve()), 'sha256': digest(output), 'counts': result['counts'], 'errors': result['errors']}))
    return 0 if result['status'] == 'passed' else 2 if result['status'] == 'partial' else 1


if __name__ == '__main__': sys.exit(main())
