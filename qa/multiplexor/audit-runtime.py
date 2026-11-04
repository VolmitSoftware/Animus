#!/usr/bin/env python3
"""Strict, read-only final runtime audit. Never starts servers or rewrites evidence.

Select an explicit successful build and its runtime SHA-256. The latest report on
each target must qualify; an older pass is never used as a fallback.
The output directory must not exist. Every invocation, including failure, writes
its own immutable audit.json. This is point-in-time verification, not a lock on
future server starts or file edits. Folia coverage is dormant file lifecycle only.
"""
import argparse
import ast
import difflib
from datetime import datetime, timezone
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sys
import zipfile

VERSIONS = ('26.1.2', '26.2', '26.3')
PHASES = ('prepare', 'recover-position', 'verify', 'combat', 'control', 'movement')
DESCRIPTION = 'A Manager for all things NPC.'
BAD_LOG = re.compile(r'\bERROR\b|\b\w*Exception\b|moved\s+(?:too\s+quickly|wrongly)|invalid\s+(?:player|vehicle)\s+movement|flying is not enabled', re.I)
HASH = re.compile(r'[0-9a-f]{64}')
UNSPECIFIED = object()
BAD_BYTES = 'durable checkpoint restores active UUID and revision while preserving invalid bytes and edit conflicts'
REPAIRED = 'restoring exact valid bytes and reloading clears recovery quarantine and permits edits'
INLINE = 'invalid inline navigation file is rejected on reload while its active body and last-valid checkpoint remain available'
SCENARIOS = {'prepare': 'runtime-client.mjs', 'recover-position': 'runtime-client.mjs', 'verify': 'runtime-client.mjs',
             'combat': 'combat-client.mjs', 'control': 'control-client.mjs', 'movement': 'movement-client.mjs'}


class AuditError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise AuditError(message)


def read_json(path):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, f'Duplicate JSON key {key} in {path}')
            value[key] = item
        return value
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def lexists(path):
    return os.path.lexists(path)


def closed_port(port):
    require(type(port) is int and 0 < port < 65536, f'Invalid owned port: {port}')
    with socket.socket() as sock:
        sock.settimeout(1)
        result = sock.connect_ex(('127.0.0.1', port))
    # Timeout/unreachable is not positive evidence of a closed loopback listener.
    require(result == errno.ECONNREFUSED, f'Owned port {port} is open or cannot be verified closed (errno={result})')
    return True


def packaged_description(path):
    with zipfile.ZipFile(path) as jar:
        require(jar.namelist().count('plugin.yml') == 1, f'Exactly one plugin.yml required: {path}')
        text = jar.read('plugin.yml').decode('utf8')
    values = re.findall(r'^description:\s*(.*?)\s*$', text, re.M)
    require(len(values) == 1, f'Exactly one top-level description required: {path}')
    value = values[0]
    if value.startswith('"'):
        value = json.loads(value)
    elif value.startswith("'") and value.endswith("'"):
        value = value[1:-1].replace("''", "'")
    require(value == DESCRIPTION, f'Packaged description differs: {value!r}')
    return value


def phase_structure(report):
    phases = report.get('phases', [])
    require([p.get('phase') for p in phases] == list(PHASES), f'Exact complete phase sequence required: {list(PHASES)}')
    for phase in phases:
        label = phase['phase']
        require(type(phase.get('exitCode')) is int and phase['exitCode'] == 0, f'{label}: exitCode must be 0')
        require(phase.get('timedOut') is False, f'{label}: timedOut must be false')
        client = phase.get('clientResult', {})
        require(client.get('status') == 'passed', f'{label}: client did not pass')
        checks = client.get('checks')
        require(isinstance(checks, list) and checks, f'{label}: nonempty checks required')
        require(all(isinstance(c, dict) and isinstance(c.get('name'), str) and c['name'].strip() for c in checks), f'{label}: malformed checks')
        require(all(c.get('status', 'passed') == 'passed' for c in checks), f'{label}: failed check')
        require(not client.get('error'), f'{label}: client error present')
    return phases


def recovery_receipts(report, phases):
    warnings = report.get('recoveryWarnings')
    require(isinstance(warnings, list) and len(warnings) == 2, 'Exactly two recoveryWarnings required')
    for index, (label, field) in enumerate((('offlineRecovery', '$.position.x'), ('inlineRecovery', '$.modules.animus:navigation'))):
        recovery = report.get(label, {})
        require(recovery.get('field') == field, f'{label}: wrong recovery field')
        for key in ('validSha256', 'invalidSha256'):
            require(isinstance(recovery.get(key), str) and HASH.fullmatch(recovery[key]), f'{label}: missing {key}')
        require(recovery['validSha256'] != recovery['invalidSha256'], f'{label}: invalid fixture equals valid bytes')
        warning = warnings[index]
        require(isinstance(warning, str) and field in warning and report['runtimeNpcId'] in warning
                and 'retained last valid revision' in warning and 'original file unchanged' in warning,
                f'{label}: recovery warning does not identify retained source')
        checks = phases[index + 1]['clientResult']['checks']
        receipts = [c for c in checks if c['name'] == BAD_BYTES]
        require(len(receipts) == 1 and receipts[0].get('invalidSha256') == recovery['invalidSha256'], f'{label}: bad-bytes receipt missing or mismatched')
        require(any(c['name'] == REPAIRED for c in checks), f'{label}: repair receipt missing')
    require(any(c['name'] == INLINE for c in phases[1]['clientResult']['checks']), 'Live inline rejection receipt missing')
    clients = [p['clientResult'] for p in phases[:3]]
    require([p.get('phase') for p in clients] == ['prepare', 'verify', 'verify'], 'Incorrect client lifecycle phases')
    require([p.get('recoveryOnly') for p in clients] == [False, True, False], 'Incorrect recovery-only phase flags')
    states = [p.get('state', {}) for p in clients]
    for state in states:
        require(state.get('id') == report['runtimeNpcId'] and state.get('group') == 'runtimeqa', 'Lifecycle state identity mismatch')
    require(len({s.get('uuid') for s in states}) == 1 and states[0].get('uuid'), 'Lifecycle UUID changed')
    revisions = [s.get('revision') for s in states]
    require(all(type(r) is int for r in revisions) and revisions[0] < revisions[1] < revisions[2], 'Recovery revisions did not advance')
    require(states[1].get('recovery') == report['inlineRecovery'] and states[2].get('recovery') == report['inlineRecovery'], 'Inline recovery state mismatch')


def verify_receipt_only_change(before, after, relative_path, alternative):
    """Verify the reviewed exact diff and allow only named console receipt regex edits."""
    expected_diff = ''.join(difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True),
                                               fromfile='a/' + relative_path, tofile='b/' + relative_path))
    require(expected_diff and alternative.get('unifiedDiff') == expected_diff, 'Reviewed wrapper exact diff mismatch')
    changes = alternative.get('receiptChanges')
    require(isinstance(changes, list) and changes, 'Reviewed wrapper needs explicit receipt changes')
    require(all(isinstance(c, dict) and all(isinstance(c.get(k), str) and c[k] for k in ['command', 'before', 'after'])
                and c['before'] != c['after'] for c in changes), 'Malformed reviewed receipt change')
    require(len({(c['command'], c['before']) for c in changes}) == len(changes), 'Duplicate reviewed receipt change')
    try:
        old_tree, new_tree = ast.parse(before), ast.parse(after)
    except SyntaxError as error:
        raise AuditError(f'Reviewed wrapper is not valid Python: {error}') from error
    matches = [0] * len(changes)
    for node in ast.walk(old_tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == 'console'
                and len(node.args) == 4 and not node.keywords and isinstance(node.args[2], ast.Constant)
                and isinstance(node.args[3], ast.Constant)):
            continue
        for index, change in enumerate(changes):
            if node.args[2].value == change['command'] and node.args[3].value == change['before']:
                node.args[3].value = change['after']; matches[index] += 1
                break
    require(all(count == 1 for count in matches), 'Each reviewed console receipt change must occur exactly once')
    require(ast.dump(old_tree, include_attributes=False) == ast.dump(new_tree, include_attributes=False),
            'Reviewed wrapper changes code outside the approved console receipt patterns')
    return {'diffSha256': hashlib.sha256(expected_diff.encode()).hexdigest(), 'receiptChanges': changes}


def target_name(project, version, round_number=None, kind=None):
    require(project in ('paper', 'spigot', 'folia') and version in VERSIONS, 'Unsupported exact target')
    require(round_number is None or type(round_number) is int and 1 <= round_number <= 1000000, 'Invalid evidence round')
    require(kind in (None, 'import', 'modules'), 'Invalid evidence category')
    prefix = 'animus' + (f'-r{round_number}' if round_number is not None else '')
    return prefix + (f'-{kind}' if kind else '') + f'-{project}-{version.replace(".", "-")}'


def managed_commands(rows):
    """Accept only completed operations; retain exact lock-contention retries."""
    retries = []
    for index, row in enumerate(rows):
        require(type(row.get('exitCode')) is int, 'Invalid managed command exit code')
        if row['exitCode'] == 0:
            continue
        require(row['exitCode'] == 1 and row.get('stdout') == '' and row.get('stderr') in (
            '[ERROR] Bad state: Another network command is running. Retry after it finishes.\n',
            '[ERROR] Bad state: Another project operation owns the workspace. Retry after it finishes.\n'),
            'Managed runner command failed')
        following = index + 1
        while following < len(rows) and rows[following].get('command') == row.get('command') and rows[following].get('exitCode') != 0:
            following += 1
        require(following < len(rows) and rows[following].get('command') == row.get('command') and rows[following].get('exitCode') == 0,
                'Lock-contention attempt lacks an immediately retried successful operation')
        retries.append({'index': index, 'successfulIndex': following, 'command': row['command'], 'stderr': row['stderr']})
    return retries


class Auditor:
    def __init__(self, workspace, repo, native_root, round_number=None):
        self.workspace, self.repo, self.native_root = workspace, repo, native_root
        target_name("paper", VERSIONS[0], round_number)
        self.round_number = round_number
        self.files = {}
        self.run_id = self.artifact_hash = self.artifact_name = None
        self.frozen_sources = None
        self.reviewed_harness = None
        self.accepted_harness_versions = []

    def file(self, path, expected=UNSPECIFIED, kind='evidence', allow_symlink=False):
        path = Path(path).absolute()
        require(path.is_file() and (allow_symlink or not path.is_symlink()), f'Missing or symlink file: {path}')
        actual = digest(path)
        if self.frozen_sources is not None and path.is_relative_to(self.repo):
            relative = str(path.relative_to(self.repo))
            if relative in self.frozen_sources and kind not in ('current-auditor-source', 'auditor', 'shared-auditor'):
                require(actual == self.frozen_sources[relative], f'Source differs from selected build capture: {relative}')
        if expected is not UNSPECIFIED:
            require(isinstance(expected, str) and HASH.fullmatch(expected) and actual == expected, f'Hash mismatch: {path}')
        old = self.files.get(str(path))
        require(old is None or old['sha256'] == actual, f'File changed during audit: {path}')
        recorded = {'sha256': actual, 'bytes': path.stat().st_size, 'kind': kind}
        if path.is_symlink():
            recorded.update({'linkTarget': os.readlink(path), 'resolvedPath': str(path.resolve(strict=True))})
        require(old is None or old.get('linkTarget') == recorded.get('linkTarget') and old.get('resolvedPath') == recorded.get('resolvedPath'),
                f'File link changed during audit: {path}')
        self.files[str(path)] = recorded
        return actual

    def archive_auditors(self, output, paths):
        for path in paths:
            source = Path(path).resolve()
            self.file(source, kind='current-auditor-source')
            target = output / 'auditor-sources' / source.name
            target.parent.mkdir(exist_ok=True)
            shutil.copy2(source, target)
            self.file(target, digest(source), 'archived-auditor-source')
            os.chmod(target, 0o444)
        os.chmod(output / 'auditor-sources', 0o555)

    def bind_snapshot(self, manifest_path, status_path):
        self.file(manifest_path, kind='source-manifest')
        manifest = read_json(manifest_path)
        run = read_json(status_path)['runs'][0]
        for project in run['projects']:
            info = manifest[project['name']]
            require(hashlib.sha256(json.dumps(info['files'], sort_keys=True).encode()).hexdigest() == info['snapshotSha256'], 'Source manifest aggregate mismatch')
            captured = Path(project['sourceRoot']) / project['relativePath']
            for name, expected in info['files'].items():
                relative = Path(name)
                require(not relative.is_absolute() and '..' not in relative.parts, 'Unsafe capture path')
                self.file(captured / relative, expected, 'captured-build-source')
        self.frozen_sources = manifest['Animus']['files']
        return {'path': str(manifest_path), 'snapshotSha256': {k: v['snapshotSha256'] for k, v in manifest.items()}}

    def build(self, status_path, expected_hash):
        self.file(status_path, kind='build-status')
        record = read_json(status_path)
        runs = record.get('runs', [record])
        require(len(runs) == 1, 'Select exactly one successful build status')
        run = runs[0]
        require(run.get('status') == 'succeeded' and run.get('id'), 'Selected build must have succeeded')
        jobs = run.get('jobs', [])
        require(jobs and all(j.get('status') == 'succeeded' and j.get('exitCode') == 0 for j in jobs), 'Selected build contains unsuccessful jobs')
        artifacts = [a for j in jobs if j.get('project') == 'animus' and j.get('target') == 'bukkit'
                     for a in j.get('artifacts', []) if a.get('role') == 'runtime' and a.get('sha256') == expected_hash]
        require(len(artifacts) == 1, 'Explicit SHA-256 must select exactly one Animus Bukkit runtime artifact')
        artifact = artifacts[0]
        self.file(artifact['file'], expected_hash, 'runtime-artifact')
        require(set(artifact.get('minecraftVersions', [])) == set(VERSIONS), 'Artifact does not declare all three exact targets')
        self.run_id, self.artifact_hash, self.artifact_name = run['id'], expected_hash, Path(artifact['file']).name
        return {'id': self.run_id, 'record': str(status_path), 'artifact': artifact, 'description': packaged_description(artifact['file'])}

    def configure_reviewed_harness(self, path):
        self.file(path, kind='reviewed-harness-manifest')
        manifest = read_json(path)
        require(manifest.get('schemaVersion') == 1, 'Unsupported reviewed harness manifest')
        require(manifest.get('buildRun') == self.run_id and manifest.get('artifactSha256') == self.artifact_hash,
                'Reviewed harness manifest is not bound to this build and artifact')
        require(manifest.get('path') == 'qa/multiplexor/runtime-npcs.py', 'Only the production runtime wrapper may have reviewed alternatives')
        review = manifest.get('review', {})
        require(review.get('scope') == 'console-receipt-pattern-only' and
                all(isinstance(review.get(key), str) and review[key].strip() for key in ['approvedBy', 'approvedAt', 'purpose']),
                'Explicit console-receipt-only review metadata required')
        alternatives = manifest.get('alternatives')
        require(isinstance(alternatives, list) and alternatives, 'Reviewed harness alternatives missing')
        require(all(isinstance(a, dict) and isinstance(a.get('sha256'), str) and HASH.fullmatch(a['sha256'])
                    and isinstance(a.get('purpose'), str) and a['purpose'].strip() for a in alternatives), 'Malformed reviewed harness alternative')
        require(len({a['sha256'] for a in alternatives}) == len(alternatives), 'Duplicate reviewed harness alternative')
        self.file(self.repo / manifest['path'], manifest.get('currentSha256'), 'current-runtime-wrapper')
        self.reviewed_harness = manifest
        return {'manifest': str(path), 'review': review, 'currentSha256': manifest['currentSha256']}

    def wrapper(self, archived, expected_hash):
        archived_hash = self.file(archived, expected_hash, 'runtime-wrapper')
        relative = 'qa/multiplexor/runtime-npcs.py'
        current = self.repo / relative
        current_hash = self.file(current, kind='current-runtime-wrapper')
        if archived_hash == current_hash:
            return {'mode': 'exact-current', 'sha256': current_hash}
        manifest = self.reviewed_harness
        require(manifest is not None, 'Wrapper differs from current source; explicit reviewed harness manifest required')
        require(current_hash == manifest['currentSha256'], 'Current wrapper changed after review')
        alternatives = [a for a in manifest['alternatives'] if a['sha256'] == archived_hash]
        require(len(alternatives) == 1, 'Archived wrapper version is not explicitly reviewed')
        alternative = alternatives[0]
        verified = verify_receipt_only_change(archived.read_text(), current.read_text(), relative, alternative)
        accepted = {'mode': 'reviewed-receipt-only', 'archived': str(archived), 'sha256': archived_hash,
                    'currentSha256': current_hash, 'purpose': alternative['purpose'], **verified}
        self.accepted_harness_versions.append(accepted)
        return accepted

    def logs(self, paths):
        records = []
        for path in paths:
            self.file(path, kind='server-log')
            matches = [{'line': i, 'text': line[:1000]} for i, line in enumerate(path.read_text(errors='replace').splitlines(), 1) if BAD_LOG.search(line)]
            require(not matches, f'Forbidden server log diagnostic in {path}: {matches[:5]}')
            records.append(str(path))
        return records

    def deployed(self, report, project, version, instance_name=None):
        require(report.get('status') == 'pass', 'Runtime report status must be pass')
        require(report.get('project') == project and report.get('minecraftVersion') == version, 'Report target mismatch')
        require(report.get('buildRun') == self.run_id, 'Report is from a different build run')
        require(report.get('serverPortClosed') is True, 'Recorded owned port was not closed')
        require(not any(report.get(key) for key in ['error', 'cleanupError', 'cleanupErrors', 'emergencyCleanup', 'arenaCleanupError']), 'Report contains an error or emergency cleanup')
        instance = self.workspace / 'consumers/plugin-consumers/instances' / (instance_name or target_name(project, version, self.round_number))
        self.file(instance / 'server.jar', report.get('serverSha256'), 'deployed-server', allow_symlink=True)
        plugins = report.get('plugins', [])
        require(plugins and len({p.get('file') for p in plugins}) == len(plugins), 'Missing or duplicate plugin receipt')
        require({p['file'] for p in plugins} == {p.name for p in (instance / 'plugins').glob('*.jar')}, 'Deployed plugin set differs from report')
        require(sum(p.get('file') == self.artifact_name and p.get('sha256') == self.artifact_hash for p in plugins) == 1, 'Report does not contain selected plugin artifact')
        for plugin in plugins:
            require(Path(plugin['file']).name == plugin['file'], 'Invalid plugin filename')
            self.file(instance / 'plugins' / plugin['file'], plugin.get('sha256'), 'deployed-plugin')
        packaged_description(instance / 'plugins' / self.artifact_name)
        props = dict(line.split('=', 1) for line in (instance / 'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
        self.file(instance / 'server.properties', kind='server-properties')
        require(props.get('server-ip') == '127.0.0.1' and props.get('server-port') == str(report.get('port')), 'Current owned loopback endpoint differs from report')
        closed_port(report['port'])
        return instance

    def absent(self, data, group, npc_id):
        require(isinstance(npc_id, str) and re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', npc_id), 'Unsafe/missing owned NPC ID')
        relative = Path('npcs') / group / f'{npc_id}.json'
        paths = [data / relative, data / 'state/npc-definitions/last-valid' / relative]
        for path in paths:
            require(not lexists(path), f'Owned source or checkpoint still exists: {path}')
        return [str(p) for p in paths]

    def native_phase(self, phase, version, port, offline_recovery):
        label, client = phase['phase'], phase['clientResult']
        require(client.get('version') == version and client.get('port') == port, f'{label}: native endpoint/version mismatch')
        transcript = Path(phase['transcript'])
        self.file(transcript, phase.get('transcriptSha256'), 'client-transcript')
        results = []
        for line in transcript.read_text().splitlines():
            try: entry = json.loads(line)
            except ValueError: continue
            if isinstance(entry, dict) and entry.get('event') == 'result': results.append(entry)
        require(len(results) == 1, f'{label}: exactly one transcript result required')
        original_client = results[0]
        annotations = {}
        comparable = client
        # runtime-npcs.py assigns state = clientResult['state'], then stages the
        # offline edit after prepare exits. Only this verified wrapper annotation
        # may differ from the immutable original client receipt.
        if label == 'prepare' and 'recovery' not in original_client.get('state', {}) and 'recovery' in client.get('state', {}):
            require(client['state']['recovery'] == offline_recovery, 'prepare: unexpected wrapper recovery annotation')
            comparable = {**client, 'state': {k: v for k, v in client['state'].items() if k != 'recovery'}}
            annotations['state.recovery'] = offline_recovery
        require(original_client == comparable, f'{label}: transcript final result does not match report')
        revision = 'v26_3' if version == '26.3' else 'v26_1_26_2'
        codec = client.get('nativeCodec', {})
        require(codec.get('adapter') == revision, f'{label}: incorrect native adapter')
        sources = {
            'jarSha256': self.native_root / version / 'minecraft-extracted_server.jar',
            'clientSourceSha256': self.repo / 'qa/protocol-client/NativeProtocolClient.java',
            'adapterSourceSha256': self.repo / 'qa/protocol-client' / revision / 'ProtocolRevision.java',
            'scenarioSourceSha256': self.repo / 'qa' / SCENARIOS[label],
            'harnessSourceSha256': self.repo / 'qa/protocol-client/client.mjs',
        }
        for key, path in sources.items():
            require(key in codec, f'{label}: missing native provenance {key}')
            self.file(path, codec[key], 'native-harness')
        result_dir = Path(client.get('resultDir') or client['evidence'])
        self.file(result_dir / 'result.json', kind='client-result')
        native_result = read_json(result_dir / 'result.json')
        require({k: v for k, v in native_result.items() if k != 'event'} == {k: v for k, v in original_client.items() if k != 'event'}, f'{label}: native archived result differs')
        self.file(result_dir / 'wire.ndjson', kind='native-wire')
        for path in result_dir.glob('*.stderr.log'):
            self.file(path, kind='native-stderr')
            require(not BAD_LOG.search(path.read_text(errors='replace')), f'{label}: native stderr contains failure: {path}')
        return {'phase': label, 'checks': len(client['checks']), 'nativeCodec': codec, 'result': str(result_dir / 'result.json'),
                'wrapperStateAnnotations': annotations}

    def production(self, path, project, version):
        self.file(path, kind='production-report')
        report = read_json(path)
        phases = phase_structure(report)
        if self.round_number is not None:
            require([len(p['clientResult']['checks']) for p in phases] == [12, 6, 14, 14, 18, 16], 'Complete isolated-round 80-check suite required')
            require(all(len({c['name'] for c in p['clientResult']['checks']}) == len(p['clientResult']['checks']) for p in phases), 'Duplicate native checks')
        recovery_receipts(report, phases)
        instance = self.deployed(report, project, version)
        folder = path.parent
        wrapper = self.wrapper(folder / 'wrapper-source.py', report.get('wrapperSha256'))
        fixture = folder / 'fixture-build.json'
        self.file(fixture, kind='fixture-build')
        receipt = read_json(fixture)
        require(receipt.get('pluginSha256') == self.artifact_hash, 'Fixture was built against a different plugin artifact')
        require(any(p.get('sha256') == receipt.get('fixtureSha256') for p in report['plugins']), 'Fixture build does not match deployed fixture')
        require(receipt.get('sources'), 'Fixture source provenance missing')
        for source, expected in receipt['sources'].items():
            self.file(self.repo / source, expected, 'fixture-source')
        for label, invalid in [('offlineRecovery', folder / 'invalid-offline-npc.json'), ('inlineRecovery', folder / 'npc-state.json.inline-invalid.json')]:
            recovery = report[label]
            self.file(Path(recovery['validBackup']), recovery['validSha256'], 'recovery-valid')
            self.file(invalid, recovery['invalidSha256'], 'recovery-invalid')
        groups = [('runtimeqa', report['runtimeNpcId'] + suffix) for suffix in ['', '-world', '-type', '-name']]
        groups += [('combatqa', report['combatNpcId'])]
        groups += [('movementqa', report['movementNpcId'] + suffix) for suffix in ['', '-unloaded']]
        cleanup = report.get('ownedDefinitionCleanup', [])
        require(len(cleanup) == len(groups) and {item.get('id') for item in cleanup} == {npc_id for _, npc_id in groups}, 'Owned definition cleanup must enumerate every synthetic NPC')
        require(all(c.get('sourceAbsent') is True and c.get('checkpointAbsent') is True and not c.get('emergencySourceRemoved') and not c.get('emergencyCheckpointRemoved') for c in cleanup), 'Source/checkpoint cleanup receipt failed')
        data = instance / 'plugins/Animus'
        absent = [p for group, npc_id in groups for p in self.absent(data, group, npc_id)]
        route = data / 'behaviors/routes' / f'{report["movementNpcId"]}.json'
        require(not lexists(route), f'Owned route remains: {route}')
        absent.append(str(route))
        details = [self.native_phase(p, version, report['port'], report['offlineRecovery']) for p in phases]
        logs = self.logs([folder / 'prepare-server.log', folder / 'recover-position-server.log', folder / 'final-server.log'])
        return {'report': str(path), 'project': project, 'version': version, 'status': 'pass', 'phases': details,
                'checks': sum(p['checks'] for p in details), 'port': report['port'], 'portClosedNow': True,
                'absentNow': absent, 'logs': logs, 'serverSha256': report['serverSha256'], 'wrapper': wrapper}

    def folia(self, path, version):
        self.file(path, kind='folia-report')
        report = read_json(path)
        instance = self.deployed(report, 'folia', version)
        for flag in ['liveInlineRecoveryVerified', 'recoveryVerified', 'ownedDefinitionAbsent', 'ownedCheckpointAbsent']:
            require(report.get(flag) is True, f'Folia {flag} must be true')
        require(report.get('recoveryField') == '$.modules.animus:navigation', 'Folia inline recovery field missing')
        require(isinstance(report.get('checks'), list) and report['checks'] and all(isinstance(c, str) and c.strip() for c in report['checks']), 'Folia checks missing')
        for marker in ['Packet proof unavailable on Folia', 'NPC runtime unavailable on Folia', 'Combat unavailable:', 'Navigation ', 'Conflict:', 'NPC definition recovery:']:
            require(any(marker in c for c in report['checks']), f'Folia required capability/recovery receipt missing: {marker}')
        self.file(path.parent / 'recovery-valid.json', report.get('recoveryValidSha256'), 'recovery-valid')
        self.file(path.parent / 'recovery-invalid.json', report.get('recoveryInvalidSha256'), 'recovery-invalid')
        require(report['recoveryValidSha256'] != report['recoveryInvalidSha256'], 'Folia invalid bytes equal valid bytes')
        absent = self.absent(instance / 'plugins/Animus', 'qa', report['npcId'])
        logs = self.logs([path.parent / 'cycle-1.log', path.parent / 'cycle-2.log'])
        second = (path.parent / 'cycle-2.log').read_text(errors='replace')
        require(report['npcId'] in second and 'original file unchanged' in second and report['recoveryField'] in second, 'Folia restart recovery log missing')
        return {'report': str(path), 'project': 'folia', 'version': version, 'status': 'pass', 'checks': len(report['checks']),
                'port': report['port'], 'portClosedNow': True, 'absentNow': absent, 'logs': logs,
                'serverSha256': report['serverSha256'], 'build': report['build'], 'channel': report['channel']}

    def inventory(self, target):
        records = []
        paths = list(target.glob('production-*/result.json')) + list(target.glob('native-*/result.json')) + list(target.glob('prior-runtime-*.json')) + list(target.glob('lifecycle-history/*/runtime.json'))
        for path in sorted(paths):
            try:
                self.file(path, kind='attempt-report')
                report = read_json(path)
                records.append({'path': str(path), 'status': report.get('status'), 'buildRun': report.get('buildRun'),
                                'phases': [p.get('phase') for p in report.get('phases', [])], 'error': report.get('error')})
            except (OSError, ValueError, AuditError) as error:
                records.append({'path': str(path), 'status': 'unreadable', 'error': str(error)})
        return records


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=Path('.qa/multiplexor-workspace'))
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--native-root', type=Path, default=Path.home() / '.gradle/caches/fabric-loom')
    parser.add_argument('--build-status', type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--source-snapshot', type=Path, help='Required for an isolated evidence round')
    parser.add_argument('--reviewed-harness', type=Path, help='Explicit build-bound review of exact archived wrapper receipt-only alternatives')
    parser.add_argument('--round', type=int, help='Explicit isolated evidence round; never falls back to older instance names')
    parser.add_argument('--production-only', action='store_true', help='Require exactly six native production suites; Folia stationary scope is audited separately')
    parser.add_argument('--output', type=Path, required=True, help='New immutable audit directory; must not exist')
    args = parser.parse_args(argv)
    if args.round is not None and not args.source_snapshot: parser.error('--round requires --source-snapshot')
    if not HASH.fullmatch(args.artifact_sha256): parser.error('--artifact-sha256 must be a lowercase SHA-256')
    if lexists(args.output): parser.error('--output already exists; choose a new audit directory')
    args.output.mkdir(parents=True, exist_ok=False)
    audit = Auditor(args.workspace.resolve(), args.repo.resolve(), args.native_root.resolve(), args.round)
    result = {'schemaVersion': 1, 'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'failed',
              'round': args.round, 'productionOnly': args.production_only, 'build': None, 'production': [], 'folia': [], 'attempts': [], 'errors': [],
              'scope': 'Six complete native-codec production suites and three dormant Folia lifecycle suites; no graphical, riding, Folia rendering or performance claim.'}
    if args.production_only:
        result['scope'] = 'Six complete native-codec production suites; Folia stationary proof is a separate scope. No graphical, riding or performance claim.'
    try:
        audit.archive_auditors(args.output, [Path(__file__)])
        result['build'] = audit.build(args.build_status.resolve(), args.artifact_sha256)
        if args.source_snapshot: result['sourceSnapshot'] = audit.bind_snapshot(args.source_snapshot.resolve(), args.build_status.resolve())
        audit.file(audit.repo / 'qa/multiplexor/managed-runtime.py', kind='current-lifecycle-harness')
        if args.reviewed_harness:
            result['reviewedHarness'] = audit.configure_reviewed_harness(args.reviewed_harness.resolve())
        for project in (('paper', 'spigot') if args.production_only else ('paper', 'spigot', 'folia')):
            for version in VERSIONS:
                target = audit.workspace / 'animus-evidence' / target_name(project, version, args.round)
                attempts = audit.inventory(target)
                result['attempts'].extend(attempts)
                try:
                    if project == 'folia':
                        result['folia'].append(audit.folia(target / 'runtime.json', version))
                    else:
                        current = [p for p in target.glob('production-*') if p.is_dir() and p.name.removeprefix('production-').isdigit()]
                        require(current, f'No production report for selected build on {project} {version}')
                        selected = max(current, key=lambda p: int(p.name.removeprefix('production-')))
                        # No fallback if the newest attempt is partial, failed, from another build, or still running.
                        result['production'].append(audit.production(selected / 'result.json', project, version))
                except (AuditError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
                    result['errors'].append({'target': f'{project} {version}', 'reason': str(error)})
        require(len(result['production']) == 6 and len(result['folia']) == (0 if args.production_only else 3), 'Complete explicitly selected production/lifecycle matrix not yet verified')
        require(not result['errors'], 'One or more targets failed audit')
        # Detect changed report/harness/deployed bytes before issuing final evidence.
        for path, recorded in audit.files.items():
            require(digest(path) == recorded['sha256'], f'File changed during audit: {path}')
            if 'linkTarget' in recorded:
                require(Path(path).is_symlink() and os.readlink(path) == recorded['linkTarget']
                        and str(Path(path).resolve(strict=True)) == recorded['resolvedPath'], f'File link changed during audit: {path}')
            else:
                require(not Path(path).is_symlink(), f'File became a symlink during audit: {path}')
        for entry in result['production'] + result['folia']:
            closed_port(entry['port'])
            for path in entry['absentNow']: require(not lexists(path), f'Owned file reappeared during audit: {path}')
        result['status'] = 'passed'
    except (AuditError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        result['errors'].append({'target': 'matrix', 'reason': str(error)})
    result['completedAt'] = datetime.now(timezone.utc).isoformat()
    result['files'] = audit.files
    result['acceptedHarnessVersions'] = audit.accepted_harness_versions
    result['counts'] = {'productionTargets': len(result['production']), 'foliaTargets': len(result['folia']),
                        'nativeChecks': sum(p['checks'] for p in result['production']),
                        'foliaChecks': sum(p['checks'] for p in result['folia']),
                        'failedAttemptsPreserved': sum(a.get('status') == 'failed' for a in result['attempts'])}
    output = args.output / 'audit.json'
    with output.open('x') as stream:
        json.dump(result, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    os.chmod(output, 0o444)
    os.chmod(args.output, 0o555)
    print(json.dumps({'status': result['status'], 'audit': str(output.resolve()), 'sha256': digest(output), 'counts': result['counts'], 'errors': result['errors']}))
    return 0 if result['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
