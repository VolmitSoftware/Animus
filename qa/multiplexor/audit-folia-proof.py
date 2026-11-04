#!/usr/bin/env python3
"""Read-only, artifact-bound audit of the limited Folia stationary preview.

This is separate from the accepted production/runtime audit. A complete matrix
can still be partial when a required live gate is unverified. No older report is
used as a fallback, and unit races cannot supply live evidence. Every invocation
writes a new immutable report, including partial and failed attempts.
"""
import argparse
import difflib
from datetime import datetime, timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import sys
import zipfile

from folia_acceptance import GATES, OUTSIDE_SCOPE, SCOPE, assess_gates

# Reuse the frozen runtime auditor's file, native artifact, build, deployed hash,
# loopback-port and log checks without changing or invoking its matrix policy.
_spec = importlib.util.spec_from_file_location('animus_runtime_audit', Path(__file__).with_name('audit-runtime.py'))
runtime = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(runtime)
require, read_json, digest, lexists = runtime.require, runtime.read_json, runtime.digest, runtime.lexists
TARGETS = {'26.1.2': (8, 'STABLE'), '26.2': (7, 'BETA'), '26.3': (6, 'ALPHA')}
PHASES = ('main', 'bounds', 'shutdown')
DISABLED = {
    'general-disabled:runtime': 'NPC runtime unavailable on Folia',
    'general-disabled:proof': 'Packet proof unavailable on Folia',
    # The console cannot control a viewer body. The native player receipt below
    # separately proves that an eligible viewer cannot enter remote control.
    'general-disabled:console-control': 'Proof presentation requires a player viewer',
    'general-disabled:combat': 'Combat unavailable:',
    'general-disabled:navigation': 'unavailable on Folia',
}
REQUIRED = {
    'ownership': ['separate-region-ownership', 'owner-attack-event'],
    'virtual-presentation': ['private-wire-bodies', 'signed-texture-payload'],
    'authoritative-interaction': ['owner-attack-event', 'server-ray-ignores-forged-point',
        'off-ray-forged-point-rejected', 'walked-overreach-rejected', 'occluded-ray-rejected',
        'cancellable-owner-event', 'interaction-rate-bounded', 'foreign-viewer-ids-inert',
        'preview-does-not-damage-viewer'],
    'viewer-lifecycle': ['cross-region-teleport-retirement', 'quit-reconnect-generation-isolation',
        'permission-revocation-retirement', 'death-respawn-retirement', 'world-change-retirement',
        'visible-nearby-teleport-retirement', 'visible-same-teleport-retirement',
        'reentrant-same-position-teleport-retirement'],
    'unavailable-terrain': ['unsupported-ground-refused-before-spawn', 'unavailable-footprint-refused-no-load',
        'unowned-footprint-refused-no-load'],
    'queued-cancellation': ['queued-spawn-clear-before-owner-admission', 'reentrant-clear-revokes-authority',
        'reentrant-replacement-isolated', 'queued-skin-clear-before-completion',
        'pending-spawn-teleport-before-owner-admission', 'pending-same-position-teleport-retirement'],
    'bounds': ['eight-viewers-ninth-refused', 'lifetime-admission-ceiling', 'command-callback-bound',
        'concurrent-skin-read-bound', 'slow-netty-retirement-bound'],
    'retirement-shutdown': ['explicit-clear-entity-and-profile-cleanup', 'scheduler-retirement-cleanup',
        'plugin-disable-pending-work-cleanup', 'disabled-late-completions-inert',
        'no-live-wire-bodies-or-profiles-after-cleanup'],
    'general-disabled': [*DISABLED, 'general-control-disabled-for-viewer'],
}
PHASE_REQUIRED = {'main': {'retired-id-inert'},
                  'bounds': {'stalled-watch-retirement-handler-cleanup', 'retired-transport-handlers-zero'},
                  'shutdown': set()}
MAPPING_REVIEW = {
    'approvedBy': 'parent implementation review',
    'scope': 'Documented stationary-preview scenarios only; no exhaustive Folia ownership certification',
    'retirement': 'Official plugin disable with pending owner callbacks, skin work and stalled transport invokes AnimusPlugin.onDisable, which closes the Folia service before shutting down the file executor. This supersedes a separate direct service.close prerequisite, while late-completion, wire/profile cleanup and managed-stop checks remain required.',
    'terrain': 'The actual spawn command is refused on an owned, loaded footprint before delivery and succeeds after delivery. A separate lower-level production admission call rejects a remote unowned footprint, with zero observed chunk events and bounded locked ticket-count snapshots. These are distinct evidence components.',
}
UNVERIFIED_EDGES = ['Actual spawn command at an unowned terrain boundary',
                    'Partially owned four-chunk footprint',
                    'Absence of transient tickets between the recorded snapshots']


def phase_structure(report):
    phases = report.get('phases')
    require(isinstance(phases, list) and phases, 'At least one completed native phase required')
    require(all(isinstance(p, dict) for p in phases), 'Malformed phase record')
    names = [p.get('phase') for p in phases]
    require(all(name in PHASES for name in names) and len(set(names)) == len(names)
            and names == sorted(names, key=PHASES.index), 'Unsupported, duplicate or reordered Folia phases')
    for phase in phases:
        label, client = phase['phase'], phase.get('clientResult', {})
        require(isinstance(client, dict), label + ': malformed client result')
        require(type(phase.get('exitCode')) is int and phase['exitCode'] == 0, label + ': nonzero or missing exitCode')
        require(phase.get('timedOut') is False, label + ': timedOut must be false')
        require(phase.get('serverPortClosed') is True, label + ': owned server was not closed')
        elapsed = phase.get('stopSeconds')
        require(type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed >= 0, label + ': stop duration missing')
        require(client.get('status') == 'passed' and not client.get('error'), label + ': native client did not pass')
        require(client.get('phase') == label and client.get('scope') == SCOPE, label + ': native scope or phase mismatch')
        checks = client.get('checks')
        require(isinstance(checks, list) and checks, label + ': nonempty native check receipts required')
        require(all(isinstance(c, dict) and isinstance(c.get('id'), str) and c['id'].strip()
                    and c.get('status') == 'passed' for c in checks), label + ': malformed or failed check')
        require(len({c['id'] for c in checks}) == len(checks), label + ': duplicate check ID')
    return phases


def cooperative_shutdown_receipt(check):
    require(check.get('explicitReleaseAfterDisable') is True, 'Explicit release after actual plugin disable was not recorded')
    values = {}
    for key in ['heldReceipt', 'drainedReceipt']:
        receipt = check.get(key)
        require(isinstance(receipt, str) and 'Folia QA file status ' in receipt, 'Missing observed file-lane receipt')
        pairs = re.findall(r'([A-Za-z][A-Za-z-]*)=([A-Za-z_0-9]+)', receipt)
        require(len(dict(pairs)) == len(pairs), 'Duplicate file-lane receipt fields')
        values[key] = dict(pairs)
    require(all(values['heldReceipt'].get(k) == v for k, v in {'io-held':'1','runningMutations':'1','closed':'true','quiescent':'false'}.items()), 'Shutdown did not retain the running fixture hold')
    require(all(values['drainedReceipt'].get(k) == v for k, v in {'io-held':'0','admitted':'0','runningMutations':'0','queuedEngineNotifications':'0','cleanupState':'SUCCEEDED','quiescent':'true','closed':'true'}.items()), 'Released file lane did not reach genuine cleanup and quiescence')


def relative_source(value):
    require(isinstance(value, str), 'Source path must be a string')
    path = Path(value)
    require(not path.is_absolute() and path.parts and '..' not in path.parts and '.' not in path.parts,
            'Unsafe source path: ' + value)
    return path


SOURCE_UPDATE_PATHS = {'qa/folia-proof/client.mjs', 'qa/folia-proof/fixture/src/art/arcane/animus/qa/FoliaFixture.java'}
SOURCE_UPDATE_SCOPE = 'Folia QA explicit release and cooperative file-lane cleanup after actual plugin disable'


class FoliaAuditor(runtime.Auditor):
    def configure_source_update(self, path, snapshot):
        require(self.frozen_sources is not None, 'Reviewed update requires the selected build source capture')
        self.file(path, kind='reviewed-qa-source-manifest'); update = read_json(path)
        require(update.get('schemaVersion') == 1 and update.get('scope') == SOURCE_UPDATE_SCOPE, 'Unknown QA source update scope')
        require(update.get('buildRun') == self.run_id and update.get('pluginSha256') == self.artifact_hash and
                update.get('sourceSnapshotSha256') == digest(snapshot), 'QA update is bound to a different build/artifact/capture')
        require(update.get('approvedBy') == 'parent implementation review' and
                all(isinstance(update.get(k), str) and update[k].strip() for k in ['approvedAt', 'purpose']), 'Explicit named review approval required')
        changes = update.get('changes', [])
        require(len(changes) == 2 and {c.get('path') for c in changes} == SOURCE_UPDATE_PATHS, 'Only the two reviewed Folia QA sources may change')
        amended = dict(self.frozen_sources)
        for change in changes:
            name = change['path']; require(change.get('beforeSha256') == self.frozen_sources[name], 'Before source is not the captured build source')
            before, after, patch = (Path(change[k]) for k in ['beforeArchive', 'afterArchive', 'diffArchive'])
            self.file(before, change['beforeSha256'], 'reviewed-before-source'); self.file(after, change.get('afterSha256'), 'reviewed-after-source')
            self.file(patch, change.get('diffSha256'), 'reviewed-source-diff')
            require(change['beforeSha256'] != change['afterSha256'], 'Source supplement contains a no-op change')
            expected = ''.join(difflib.unified_diff(before.read_text().splitlines(keepends=True), after.read_text().splitlines(keepends=True),
                                                   fromfile='a/' + name, tofile='b/' + name))
            require(patch.read_text() == expected, 'Reviewed source diff does not match exact before/after archives')
            current = self.repo / name
            require(current.is_file() and not current.is_symlink() and digest(current) == change['afterSha256'], 'Current QA source differs from reviewed after bytes')
            amended[name] = change['afterSha256']
        fixture = update.get('fixture', {})
        self.file(Path(fixture['artifact']), fixture.get('sha256'), 'reviewed-fixture-artifact')
        self.file(Path(fixture['buildReceipt']), fixture.get('buildReceiptSha256'), 'reviewed-fixture-build')
        receipt = read_json(fixture['buildReceipt'])
        require(receipt.get('pluginSha256') == self.artifact_hash and receipt.get('fixtureSha256') == fixture['sha256'], 'Reviewed fixture not bound to plugin and artifact')
        require(receipt.get('sources') and all(amended.get(name) == h for name, h in receipt['sources'].items()) and
                'qa/folia-proof/fixture/src/art/arcane/animus/qa/FoliaFixture.java' in receipt['sources'], 'Fixture sources exceed reviewed update/capture')
        self.frozen_sources = amended
        for name in SOURCE_UPDATE_PATHS: self.file(self.repo / name, amended[name], 'reviewed-current-qa-source')
        self.source_update_fixture = fixture['sha256']
        return {'manifest': str(path), 'sha256': digest(path), **update,
                'meaning': 'Changed QA shutdown setup on the same plugin artifact; failed earlier attempts remain failed.'}

    def native(self, phase, version, port):
        client, label = phase['clientResult'], phase['phase']
        require(client.get('version') == version and client.get('port') == port, label + ': native endpoint mismatch')
        transcript = Path(phase['transcript'])
        self.file(transcript, phase.get('transcriptSha256'), 'stationary-native-transcript')
        entries = []
        for line in transcript.read_text().splitlines():
            try: entry = json.loads(line)
            except ValueError: continue
            if isinstance(entry, dict): entries.append(entry)
        results = [e for e in entries if e.get('event') == 'result']
        require(results == [client], label + ': exactly one matching immutable native result required')
        emitted = [{k: v for k, v in e.items() if k != 'event'} for e in entries if e.get('event') == 'check']
        require(emitted == client['checks'], label + ': native check stream differs from final check list')
        revision = 'v26_3' if version == '26.3' else 'v26_1_26_2'
        codec = client.get('nativeCodec', {})
        require(codec.get('adapter') == revision, label + ': wrong exact native adapter')
        expected = {
            'jarSha256': self.native_root / version / 'minecraft-extracted_server.jar',
            'clientSourceSha256': self.repo / 'qa/protocol-client/NativeProtocolClient.java',
            'adapterSourceSha256': self.repo / 'qa/protocol-client' / revision / 'ProtocolRevision.java',
            'scenarioSourceSha256': self.repo / 'qa/folia-proof/client.mjs',
            'harnessSourceSha256': self.repo / 'qa/protocol-client/client.mjs',
        }
        for key, path in expected.items(): self.file(path, codec.get(key), 'stationary-native-codec')
        result_dir = Path(client['resultDir'])
        self.file(result_dir / 'result.json', kind='stationary-native-result')
        require(read_json(result_dir / 'result.json') == client, label + ': archived native result differs')
        self.file(result_dir / 'wire.ndjson', kind='stationary-native-wire')
        for stderr in result_dir.glob('*.stderr.log'):
            self.file(stderr, kind='stationary-native-stderr')
            require(not runtime.BAD_LOG.search(stderr.read_text(errors='replace')), label + ': native stderr failure')
        return {'phase': label, 'checks': len(client['checks']), 'checkIds': [c['id'] for c in client['checks']],
                'result': str(result_dir / 'result.json'), 'nativeCodec': codec}

    def console_receipts(self, phase):
        label, receipts = phase['phase'], phase.get('disabledReceipts')
        require(isinstance(receipts, list) and len(receipts) == len(DISABLED), label + ': disabled receipts missing')
        for (key, text), receipt in zip(DISABLED.items(), receipts):
            require(isinstance(receipt, str) and text in receipt, label + ': missing ' + key)
        require('Folia' in receipts[3] and 'Navigation ' in receipts[4], label + ': combat/navigation target ambiguity')
        ready, cleanup, fixture = (phase.get(k, '') for k in ('readyReceipt', 'cleanupReceipt', 'fixtureReceipt'))
        require('Folia stationary proof ready' in ready and 'admitted=0/8' in ready, label + ': initial zero admission receipt missing')
        state_marker = 'Folia stationary proof unavailable: closed' if label == 'shutdown' else 'Folia stationary proof ready'
        for marker in (state_marker, 'admitted=0/8', 'pending-spawns=0/8', 'command-work=0/8', 'skin-reads=0/2'):
            require(marker in cleanup, label + ': cleanup did not establish ' + marker)
        for marker in ('Folia QA counters', 'failures=0 ', 'scheduled=0 ', 'states=0'):
            require(marker in fixture, label + ': fixture cleanup did not establish ' + marker)
        for counter in ('owners', 'rectangles'):
            match = re.search(r'\b' + counter + r'=(\d+)\b', fixture)
            require(match and int(match[1]) > 0, label + ': no fixture ownership checks')
        log = Path(phase['serverLog'])
        self.file(log, phase.get('serverLogSha256'), 'stationary-server-log')
        self.logs([log])
        text = log.read_text(errors='replace')
        for receipt in [*receipts, ready, cleanup, fixture]:
            require(receipt in text, label + ': console receipt absent from archived server log')
        return set(DISABLED)

    def target(self, path, version):
        self.file(path, kind='stationary-report')
        report, folder = read_json(path), path.parent
        require(report.get('schemaVersion') == 1 and report.get('scope') == SCOPE, 'Stationary scope/schema mismatch')
        require(report.get('status') in ('pass', 'passed', 'partial'), 'Attempt did not complete successfully')
        require(report.get('generalRuntimeEnabled') is False and report.get('graphicalAppearance') == 'unverified',
                'Report claims unsupported general runtime or graphical scope')
        build, channel = TARGETS[version]
        require(report.get('serverBuild') == build and report.get('serverChannel') == channel, 'Exact Folia build/channel mismatch')
        require(report.get('pluginSha256') == self.artifact_hash, 'Explicit plugin SHA differs from selected artifact')
        # The inherited deploy verifier checks hashes, exact plugin set and closed
        # loopback endpoint. Coverage status is assessed independently below.
        self.deployed({**report, 'status': 'pass'}, 'folia', version)
        phases = phase_structure(report)
        self.file(folder / 'wrapper-source.py', report.get('wrapperSha256'), 'stationary-wrapper')
        self.file(self.repo / 'qa/multiplexor/folia-proof.py', report.get('wrapperSha256'), 'current-stationary-wrapper')
        self.file(folder / 'fixture-build.json', kind='stationary-fixture-build')
        fixture = read_json(folder / 'fixture-build.json')
        if hasattr(self, 'source_update_fixture'): require(fixture.get('fixtureSha256') == self.source_update_fixture, 'Report fixture differs from explicitly reviewed QA supplement')
        require(fixture.get('pluginSha256') == self.artifact_hash and fixture.get('fixtureSha256') == report.get('fixtureSha256'),
                'Fixture provenance does not match selected artifact and report')
        require(any(p.get('file') == 'AnimusFoliaFixture.jar' and p.get('sha256') == fixture['fixtureSha256'] for p in report['plugins']),
                'Separate Folia fixture is not deployed as recorded')
        self.file(Path(fixture['foliaApi']), fixture.get('foliaApiSha256'), 'fixture-compile-api')
        require(isinstance(fixture.get('sources'), dict) and fixture['sources'], 'Fixture source hashes missing')
        sources = report.get('sources')
        require(isinstance(sources, dict), 'Archived harness source map missing')
        revision = 'v26_3' if version == '26.3' else 'v26_1_26_2'
        required = set(fixture['sources']) | {'qa/folia-proof/client.mjs', 'qa/protocol-client/client.mjs',
            'qa/protocol-client/NativeProtocolClient.java', 'qa/protocol-client/' + revision + '/ProtocolRevision.java'}
        require(set(sources) == required, 'Archived source inventory does not exactly match fixture/native harness')
        for name, expected in sources.items():
            relative = relative_source(name)
            self.file(folder / 'sources' / relative, expected, 'archived-stationary-source')
            self.file(self.repo / relative, expected, 'current-stationary-source')
            if name in fixture['sources']: require(fixture['sources'][name] == expected, 'Fixture and wrapper source hashes differ')
        verified, details = set(), []
        for phase in phases:
            phase_ids = {c['id'] for c in phase['clientResult']['checks']}
            if hasattr(self, 'source_update_fixture') and phase['phase'] == 'shutdown':
                receipts = [c for c in phase['clientResult']['checks'] if c['id'] == 'disabled-late-completions-inert']
                require(len(receipts) == 1, 'Reviewed shutdown requires one cooperative cleanup receipt')
                cooperative_shutdown_receipt(receipts[0])
            require({'native-exact-codec', 'general-control-disabled-for-viewer',
                     'no-live-wire-bodies-or-profiles-after-cleanup'} | PHASE_REQUIRED[phase['phase']] <= phase_ids,
                    'Each phase requires exact native codec, disabled viewer control and final wire/profile cleanup')
            require(Path(phase['transcript']).absolute() == (folder / (phase['phase'] + '-client.ndjson')).absolute(),
                    'Native transcript must belong to this attempt and phase')
            require(Path(phase['serverLog']).absolute() == (folder / (phase['phase'] + '-server.log')).absolute(),
                    'Server log must belong to this attempt and phase')
            details.append(self.native(phase, version, report['port']))
            verified.update(details[-1]['checkIds'])
            verified.update(self.console_receipts(phase))
        gates = report.get('gates')
        require(isinstance(gates, list) and all(isinstance(g, dict) for g in gates), 'Malformed gate declarations')
        for gate in gates:
            require(set(gate.get('evidence', [])) <= verified, 'Gate cites evidence absent from verified receipts')
        coverage = assess_gates(gates, verified, REQUIRED)
        coverage['unverifiedEdges'] = list(UNVERIFIED_EDGES)
        complete = [p['phase'] for p in phases] == list(PHASES)
        if not complete and coverage['status'] == 'passed': coverage['status'] = 'partial'
        self.logs([folder / 'final-server.log'])
        self.file(folder / 'commands.json', kind='multiplexor-commands')
        return {'report': str(path), 'version': version, 'build': build, 'channel': channel,
                'port': report['port'], 'portClosedNow': True, 'serverSha256': report['serverSha256'],
                'phasesComplete': complete, 'phases': details, 'coverage': coverage,
                'status': coverage['status'], 'checks': sum(p['checks'] for p in details)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=Path('.qa/multiplexor-workspace'))
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--native-root', type=Path, default=Path.home() / '.gradle/caches/fabric-loom')
    parser.add_argument('--build-status', type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--source-snapshot', type=Path, help='Required for an isolated evidence round')
    parser.add_argument('--reviewed-source-update', type=Path, help='Explicit exact two-file Folia QA shutdown supplement; no general mismatch waiver')
    parser.add_argument('--round', type=int, help='Explicit isolated evidence round; no older instance fallback')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if args.round is not None and not args.source_snapshot: parser.error('--round requires --source-snapshot')
    if not runtime.HASH.fullmatch(args.artifact_sha256): parser.error('Lowercase artifact SHA-256 required')
    if lexists(args.output): parser.error('Output already exists; choose a new immutable directory')
    args.output.mkdir(parents=True, exist_ok=False)
    audit = FoliaAuditor(args.workspace.resolve(), args.repo.resolve(), args.native_root.resolve(), args.round)
    result = {'schemaVersion': 1, 'round': args.round, 'scope': SCOPE, 'outsideScope': list(OUTSIDE_SCOPE), 'status': 'failed',
        'startedAt': datetime.now(timezone.utc).isoformat(), 'build': None, 'targets': [], 'pending': [],
        'attempts': [], 'errors': [], 'requiredLiveChecks': REQUIRED,
        'mappingReview': MAPPING_REVIEW, 'unverifiedStationaryEdges': list(UNVERIFIED_EDGES)}
    caught = (runtime.AuditError, OSError, ValueError, KeyError, TypeError, AttributeError, zipfile.BadZipFile)
    try:
        audit.archive_auditors(args.output, [Path(__file__), Path(__file__).with_name('folia_acceptance.py'), Path(runtime.__file__)])
        result['build'] = audit.build(args.build_status.resolve(), args.artifact_sha256)
        if args.source_snapshot: result['sourceSnapshot'] = audit.bind_snapshot(args.source_snapshot.resolve(), args.build_status.resolve())
        if args.reviewed_source_update:
            require(args.source_snapshot is not None, 'QA update requires --source-snapshot')
            result['reviewedSourceUpdate'] = audit.configure_source_update(args.reviewed_source_update.resolve(), args.source_snapshot.resolve())
        for version in TARGETS:
            target = audit.workspace / 'animus-evidence' / runtime.target_name('folia', version, args.round)
            attempts = sorted((p for p in target.glob('folia-proof-*') if p.is_dir() and p.name.removeprefix('folia-proof-').isdigit()),
                              key=lambda p: int(p.name.removeprefix('folia-proof-')))
            for folder in attempts:
                path = folder / 'result.json'
                entry = {'path': str(path), 'status': 'pending'}
                if path.exists():
                    try:
                        audit.file(path, kind='stationary-attempt')
                        attempt = read_json(path)
                        entry.update({key: attempt.get(key) for key in ['status', 'buildRun', 'error']})
                    except caught as error: entry.update({'status': 'unreadable', 'error': str(error)})
                result['attempts'].append(entry)
            if not attempts or not (attempts[-1] / 'result.json').exists():
                result['pending'].append({'version': version, 'reason': 'Latest stationary attempt is missing or still running'})
                continue
            try: result['targets'].append(audit.target(attempts[-1] / 'result.json', version))
            except caught as error: result['errors'].append({'target': version, 'reason': str(error)})
        for path, recorded in audit.files.items():
            require(digest(path) == recorded['sha256'], 'File changed during audit: ' + path)
            if 'linkTarget' in recorded:
                require(Path(path).is_symlink() and os.readlink(path) == recorded['linkTarget'] and
                        str(Path(path).resolve(strict=True)) == recorded['resolvedPath'], 'File link changed during audit: ' + path)
            else: require(not Path(path).is_symlink(), 'File became a symlink during audit: ' + path)
        for target in result['targets']: runtime.closed_port(target['port'])
        if result['errors'] or any(t['status'] == 'failed' for t in result['targets']): result['status'] = 'failed'
        elif len(result['targets']) == 3 and all(t['status'] == 'passed' and t['phasesComplete'] for t in result['targets']): result['status'] = 'passed'
        else: result['status'] = 'partial'
    except caught as error: result['errors'].append({'target': 'matrix', 'reason': str(error)})
    result['completedAt'] = datetime.now(timezone.utc).isoformat()
    result['files'] = audit.files
    result['counts'] = {'targetsVerified': len(result['targets']), 'nativeChecks': sum(t['checks'] for t in result['targets']),
        'failedAttemptsPreserved': sum(a['status'] == 'failed' for a in result['attempts'])}
    output = args.output / 'audit.json'
    with output.open('x') as stream:
        json.dump(result, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    os.chmod(output, 0o444); os.chmod(args.output, 0o555)
    print(json.dumps({'status': result['status'], 'audit': str(output.resolve()), 'sha256': digest(output),
                      'counts': result['counts'], 'errors': result['errors'], 'pending': result['pending']}))
    return 0 if result['status'] == 'passed' else 2 if result['status'] == 'partial' else 1


if __name__ == '__main__':
    sys.exit(main())
