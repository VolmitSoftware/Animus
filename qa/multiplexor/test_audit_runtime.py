"""Negative audit fixtures are synthetic and never start a Minecraft process."""
import copy
import difflib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('audit-runtime.py').resolve()
spec = importlib.util.spec_from_file_location('audit_runtime', SCRIPT)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + '\n' if not isinstance(value, str) else value)
    return path


class RuntimeAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='animus-runtime-audit-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo, self.workspace, self.native = (self.root / key for key in ['repo', 'workspace', 'native'])
        self.binary = self.root / 'Animus.jar'
        with zipfile.ZipFile(self.binary, 'w') as jar:
            jar.writestr('plugin.yml', 'description: "A Manager for all things NPC."\n')
        self.sha = audit.digest(self.binary)
        self.status = write(self.root / 'build.json', {'runs': [{'id': 'selected', 'status': 'succeeded', 'jobs': [
            {'status': 'succeeded', 'exitCode': 0, 'project': 'animus', 'target': 'bukkit', 'artifacts': [
                {'role': 'runtime', 'file': str(self.binary), 'sha256': self.sha, 'minecraftVersions': list(audit.VERSIONS)}]}]}]})
        for source in ['qa/multiplexor/runtime-npcs.py', 'qa/multiplexor/managed-runtime.py', 'qa/protocol-client/client.mjs',
                       'qa/protocol-client/NativeProtocolClient.java', 'qa/protocol-client/v26_3/ProtocolRevision.java',
                       'qa/protocol-client/v26_1_26_2/ProtocolRevision.java', 'qa/fixture/test.java',
                       *{'qa/' + name for name in audit.SCENARIOS.values()}]:
            write(self.repo / source, 'synthetic source ' + source)
        for version in audit.VERSIONS:
            write(self.native / version / 'minecraft-extracted_server.jar', 'synthetic native jar ' + version)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); self.port = sock.getsockname()[1]
        self.paths = {}
        for project in ['paper', 'spigot', 'folia']:
            for version in audit.VERSIONS:
                self.target(project, version)
        self.output_count = 0

    def target(self, project, version):
        name = f'animus-{project}-{version.replace(".", "-")}'
        instance = self.workspace / 'consumers/plugin-consumers/instances' / name
        evidence = self.workspace / 'animus-evidence' / name
        write(instance / 'server.jar', name)
        write(instance / 'server.properties', f'server-ip=127.0.0.1\nserver-port={self.port}\n')
        plugin = instance / 'plugins' / self.binary.name
        plugin.parent.mkdir(parents=True, exist_ok=True); plugin.write_bytes(self.binary.read_bytes())
        report = {'project': project, 'minecraftVersion': version, 'buildRun': 'selected', 'status': 'pass',
                  'serverSha256': audit.digest(instance / 'server.jar'), 'port': self.port, 'serverPortClosed': True,
                  'plugins': [{'file': self.binary.name, 'sha256': self.sha}]}
        if project == 'folia':
            report.update({key: True for key in ['liveInlineRecoveryVerified', 'recoveryVerified', 'ownedDefinitionAbsent', 'ownedCheckpointAbsent']})
            report.update({'recoveryField': '$.modules.animus:navigation', 'npcId': 'managed-test', 'build': 1, 'channel': 'SYNTHETIC',
                           'checks': ['Packet proof unavailable on Folia', 'NPC runtime unavailable on Folia', 'Combat unavailable:',
                                      'Navigation unavailable', 'Conflict:', 'NPC definition recovery:']})
            report['recoveryValidSha256'] = audit.digest(write(evidence / 'recovery-valid.json', {'valid': True}))
            report['recoveryInvalidSha256'] = audit.digest(write(evidence / 'recovery-invalid.json', {'invalid': True}))
            write(evidence / 'cycle-1.log', 'clean first cycle')
            write(evidence / 'cycle-2.log', 'managed-test $.modules.animus:navigation original file unchanged')
            path = write(evidence / 'runtime.json', report)
        else:
            folder = evidence / 'production-100'
            fixture = write(instance / 'plugins/Fixture.jar', 'synthetic fixture')
            report['plugins'].append({'file': fixture.name, 'sha256': audit.digest(fixture)})
            write(folder / 'fixture-build.json', {'pluginSha256': self.sha, 'fixtureSha256': audit.digest(fixture),
                  'sources': {'qa/fixture/test.java': audit.digest(self.repo / 'qa/fixture/test.java')}})
            report['wrapperSha256'] = audit.digest(self.repo / 'qa/multiplexor/runtime-npcs.py')
            write(folder / 'wrapper-source.py', (self.repo / 'qa/multiplexor/runtime-npcs.py').read_text())
            report.update({'runtimeNpcId': 'runtime-test', 'combatNpcId': 'combat-test', 'movementNpcId': 'movement-test', 'phases': []})
            for label, field, invalid_name in [('offlineRecovery', '$.position.x', 'invalid-offline-npc.json'),
                                               ('inlineRecovery', '$.modules.animus:navigation', 'npc-state.json.inline-invalid.json')]:
                valid = write(folder / (label + '-valid.json'), {'valid': label})
                invalid = write(folder / invalid_name, {'invalid': label})
                report[label] = {'field': field, 'validBackup': str(valid), 'validSha256': audit.digest(valid), 'invalidSha256': audit.digest(invalid)}
            report['recoveryWarnings'] = [f'runtime-test {report[label]["field"]} retained last valid revision; original file unchanged' for label in ['offlineRecovery', 'inlineRecovery']]
            ids = ['runtime-test' + suffix for suffix in ['', '-world', '-type', '-name']] + ['combat-test', 'movement-test', 'movement-test-unloaded']
            report['ownedDefinitionCleanup'] = [{'id': npc_id, 'sourceAbsent': True, 'checkpointAbsent': True} for npc_id in ids]
            revision = 'v26_3' if version == '26.3' else 'v26_1_26_2'
            for index, label in enumerate(audit.PHASES):
                result_dir = folder / ('native-' + label)
                codec = {'adapter': revision,
                         'jarSha256': audit.digest(self.native / version / 'minecraft-extracted_server.jar'),
                         'clientSourceSha256': audit.digest(self.repo / 'qa/protocol-client/NativeProtocolClient.java'),
                         'adapterSourceSha256': audit.digest(self.repo / 'qa/protocol-client' / revision / 'ProtocolRevision.java'),
                         'scenarioSourceSha256': audit.digest(self.repo / 'qa' / audit.SCENARIOS[label]),
                         'harnessSourceSha256': audit.digest(self.repo / 'qa/protocol-client/client.mjs')}
                client = {'event': 'result', 'status': 'passed', 'checks': [{'name': 'synthetic check'}], 'version': version,
                          'port': self.port, 'nativeCodec': codec, 'resultDir': str(result_dir)}
                if index < 3:
                    client.update({'phase': 'prepare' if index == 0 else 'verify', 'recoveryOnly': index == 1,
                                   'state': {'id': 'runtime-test', 'group': 'runtimeqa', 'uuid': 'synthetic-uuid', 'revision': index + 1}})
                    if index:
                        recovery = report['offlineRecovery' if index == 1 else 'inlineRecovery']
                        client['checks'] += [{'name': audit.BAD_BYTES, 'invalidSha256': recovery['invalidSha256']}, {'name': audit.REPAIRED}]
                        client['state']['recovery'] = report['inlineRecovery']
                    if index == 1: client['checks'].append({'name': audit.INLINE})
                write(result_dir / 'result.json', client)
                write(result_dir / 'wire.ndjson', '{}\n')
                transcript = write(folder / (label + '-client.ndjson'), json.dumps(client) + '\n')
                report['phases'].append({'phase': label, 'exitCode': 0, 'timedOut': False, 'clientResult': client,
                                        'transcript': str(transcript), 'transcriptSha256': audit.digest(transcript)})
            for name in ['prepare-server.log', 'recover-position-server.log', 'final-server.log']: write(folder / name, 'clean log')
            path = write(folder / 'result.json', report)
        self.paths[project, version] = path

    def test_explicit_round_never_uses_existing_legacy_instances(self):
        selected = audit.Auditor(self.workspace, self.repo, self.native, 10)
        selected.build(self.status, self.sha)
        report = audit.read_json(self.paths['paper', '26.3'])
        with self.assertRaisesRegex(audit.AuditError, 'animus-r10-paper-26-3'):
            selected.deployed(report, 'paper', '26.3')
        legacy = audit.Auditor(self.workspace, self.repo, self.native)
        legacy.build(self.status, self.sha)
        self.assertTrue(legacy.deployed(report, 'paper', '26.3').exists())

    def test_isolated_round_rejects_truncated_native_check_inventory(self):
        selected = audit.Auditor(self.workspace, self.repo, self.native, 10)
        selected.build(self.status, self.sha)
        with self.assertRaisesRegex(audit.AuditError, '80-check'):
            selected.production(self.paths['paper', '26.3'], 'paper', '26.3')

    def invoke(self, expected=0, reason=None, reviewed=None):
        self.output_count += 1
        output = self.root / ('audit-' + str(self.output_count))
        command = [sys.executable, str(SCRIPT), '--workspace', str(self.workspace), '--repo', str(self.repo),
                   '--native-root', str(self.native), '--build-status', str(self.status), '--artifact-sha256', self.sha, '--output', str(output)]
        if reviewed: command += ['--reviewed-harness', str(reviewed)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        report = json.loads((output / 'audit.json').read_text())
        self.assertEqual('passed' if expected == 0 else 'failed', report['status'])
        if reason: self.assertIn(reason, json.dumps(report['errors']))
        self.assertEqual(0, (output / 'audit.json').stat().st_mode & 0o222)
        return report, command

    def mutate(self, project, version, callback):
        path = self.paths[project, version]
        report = json.loads(path.read_text()); callback(report); write(path, report)

    def test_complete_synthetic_matrix_passes_and_output_is_not_reused(self):
        report, command = self.invoke()
        self.assertEqual(6, report['counts']['productionTargets'])
        self.assertEqual(3, report['counts']['foliaTargets'])
        before = Path(command[-1]) / 'audit.json'; checksum = audit.digest(before)
        retry = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, retry.returncode); self.assertIn('already exists', retry.stderr)
        self.assertEqual(checksum, audit.digest(before))

    def test_diagnostic_success_does_not_count_as_complete_and_no_old_pass_fallback(self):
        old = self.paths['paper', '26.3']; value = json.loads(old.read_text()); value['phases'] = value['phases'][-1:]
        write(old.parent.parent / 'production-101/result.json', value)
        self.invoke(1, 'Exact complete phase sequence required')

    def test_running_newest_attempt_blocks_older_success(self):
        (self.paths['paper', '26.3'].parent.parent / 'production-101').mkdir()
        self.invoke(1, 'Missing or symlink file')

    def test_phase_failures_and_empty_checks_rejected(self):
        path = self.paths['paper', '26.3']; original = json.loads(path.read_text())
        for key, value, reason in [('timedOut', True, 'timedOut'), ('exitCode', 1, 'exitCode'), ('exitCode', False, 'exitCode')]:
            with self.subTest(key=key, value=value):
                changed = copy.deepcopy(original); changed['phases'][0][key] = value; write(path, changed)
                self.invoke(1, reason)
        changed = copy.deepcopy(original); changed['phases'][0]['clientResult']['checks'] = []; write(path, changed)
        self.invoke(1, 'nonempty checks')

    def test_bad_bytes_receipt_and_second_warning_required(self):
        path = self.paths['paper', '26.3']; original = json.loads(path.read_text())
        changed = copy.deepcopy(original); changed['phases'][1]['clientResult']['checks'][1]['invalidSha256'] = '0' * 64; write(path, changed)
        self.invoke(1, 'bad-bytes receipt')
        changed = copy.deepcopy(original); changed['recoveryWarnings'].pop(); write(path, changed)
        self.invoke(1, 'Exactly two recoveryWarnings')

    def test_current_deployed_bytes_and_description_are_verified(self):
        instance = self.workspace / 'consumers/plugin-consumers/instances/animus-paper-26-3'
        write(instance / 'server.jar', 'changed server')
        self.invoke(1, 'Hash mismatch')
        with zipfile.ZipFile(self.binary, 'w') as jar: jar.writestr('plugin.yml', 'description: Wrong\n')
        with self.assertRaisesRegex(audit.AuditError, 'Packaged description differs'): audit.packaged_description(self.binary)

    def test_managed_server_symlink_records_and_verifies_resolved_bytes(self):
        instance = self.workspace / 'consumers/plugin-consumers/instances/animus-paper-26-3'
        deployed = instance / 'server.jar'; target = instance / 'pinned-server.jar'
        deployed.rename(target); deployed.symlink_to(target.name)
        report, _ = self.invoke()
        entry = report['files'][str(deployed.resolve().parent / 'server.jar')]
        self.assertEqual(target.name, entry['linkTarget'])
        self.assertEqual(str(target.resolve()), entry['resolvedPath'])
        write(target, 'altered resolved server')
        self.invoke(1, 'Hash mismatch')
        target.unlink()
        self.invoke(1, 'Missing or symlink file')

    def test_report_missing_hash_does_not_disable_verification(self):
        self.mutate('paper', '26.3', lambda d: d.pop('serverSha256'))
        self.invoke(1, 'Hash mismatch')

    def test_live_port_cannot_be_certified_closed(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', self.port)); listener.listen()
            self.invoke(1, 'open or cannot be verified closed')

    def test_actual_source_and_broken_pointer_symlink_cannot_hide_behind_receipt(self):
        root = self.workspace / 'consumers/plugin-consumers/instances/animus-paper-26-3/plugins/Animus'
        source = write(root / 'npcs/runtimeqa/runtime-test.json', '{}')
        self.invoke(1, 'Owned source or checkpoint still exists')
        source.unlink()
        pointer = root / 'state/npc-definitions/last-valid/npcs/runtimeqa/runtime-test.json'
        pointer.parent.mkdir(parents=True); pointer.symlink_to('nonexistent')
        self.invoke(1, 'Owned source or checkpoint still exists')

    def test_archived_logs_fail_on_errors_exceptions_and_motion_warnings(self):
        log = self.paths['paper', '26.3'].parent / 'final-server.log'
        for message in ['[ERROR] failure', 'java.lang.IllegalStateException: failure', 'player moved too quickly!', 'player moved wrongly!']:
            with self.subTest(message=message):
                write(log, message); self.invoke(1, 'Forbidden server log diagnostic')

    def test_failed_attempts_remain_intact_and_inventoried(self):
        old = self.paths['paper', '26.3'].parent.parent / 'production-99/result.json'
        write(old, {'status': 'failed', 'buildRun': 'selected', 'error': 'preserved synthetic failure'})
        checksum = audit.digest(old)
        report, _ = self.invoke()
        self.assertEqual(1, report['counts']['failedAttemptsPreserved']); self.assertEqual(checksum, audit.digest(old))

    def test_folia_must_supply_live_inline_and_restart_recovery(self):
        self.mutate('folia', '26.3', lambda d: d.pop('liveInlineRecoveryVerified'))
        self.invoke(1, 'liveInlineRecoveryVerified')

    def test_prepare_wrapper_annotation_is_exactly_the_verified_offline_recovery(self):
        self.mutate('paper', '26.3', lambda d: d['phases'][0]['clientResult']['state'].update({'recovery': d['offlineRecovery']}))
        report, _ = self.invoke()
        paper = next(r for r in report['production'] if r['project'] == 'paper' and r['version'] == '26.3')
        self.assertEqual('$.position.x', paper['phases'][0]['wrapperStateAnnotations']['state.recovery']['field'])
        self.mutate('paper', '26.3', lambda d: d['phases'][0]['clientResult']['state']['recovery'].update({'field': 'unreviewed'}))
        self.invoke(1, 'unexpected wrapper recovery annotation')

    def reviewed_wrapper(self, extra_code=''):
        relative = 'qa/multiplexor/runtime-npcs.py'
        before = "def run(session, log):\n    console(session, log, 'time set day', r'.*Set .*time.*')\n    assert True\n"
        after = before.replace("r'.*Set .*time.*'", "r'.*(Set .*time|Clock .*already at time marker).*'") + extra_code
        old_sha = audit.hashlib.sha256(before.encode()).hexdigest()
        for (project, _), path in self.paths.items():
            if project == 'folia': continue
            write(path.parent / 'wrapper-source.py', before)
            report = json.loads(path.read_text()); report['wrapperSha256'] = old_sha; write(path, report)
        current = write(self.repo / relative, after)
        manifest = {'schemaVersion': 1, 'buildRun': 'selected', 'artifactSha256': self.sha, 'path': relative,
                    'currentSha256': audit.digest(current),
                    'review': {'approvedBy': 'synthetic-test', 'approvedAt': '1970-01-01T00:00:00Z',
                               'purpose': 'Synthetic receipt-only review fixture', 'scope': 'console-receipt-pattern-only'},
                    'alternatives': [{'sha256': old_sha, 'purpose': 'Recognize unchanged daytime receipt',
                                      'unifiedDiff': ''.join(difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True), fromfile='a/' + relative, tofile='b/' + relative)),
                                      'receiptChanges': [{'command': 'time set day', 'before': '.*Set .*time.*', 'after': '.*(Set .*time|Clock .*already at time marker).*'}]}]}
        return write(self.root / 'reviewed-harness.json', manifest)

    def test_reviewed_receipt_alternative_is_explicit_and_archived(self):
        reviewed = self.reviewed_wrapper()
        self.invoke(1, 'explicit reviewed harness manifest required')
        report, _ = self.invoke(reviewed=reviewed)
        self.assertEqual(6, len(report['acceptedHarnessVersions']))
        self.assertTrue(all(item['mode'] == 'reviewed-receipt-only' for item in report['acceptedHarnessVersions']))
        self.assertEqual(audit.digest(reviewed), report['files'][str(reviewed.resolve())]['sha256'])

    def test_unreviewed_archived_version_rejected_even_with_manifest(self):
        reviewed = self.reviewed_wrapper()
        path = self.paths['paper', '26.3']
        wrapper = path.parent / 'wrapper-source.py'
        write(wrapper, wrapper.read_text() + '# unreviewed version\n')
        self.mutate('paper', '26.3', lambda value: value.update({'wrapperSha256': audit.digest(wrapper)}))
        self.invoke(1, 'not explicitly reviewed', reviewed)

    def test_reviewed_diff_must_match_exact_archived_bytes(self):
        reviewed = self.reviewed_wrapper()
        manifest = json.loads(reviewed.read_text()); manifest['alternatives'][0]['unifiedDiff'] += '# extra text\n'; write(reviewed, manifest)
        self.invoke(1, 'exact diff mismatch', reviewed)

    def test_review_manifest_cannot_approve_non_receipt_code_changes(self):
        reviewed = self.reviewed_wrapper('assert False\n')
        self.invoke(1, 'outside the approved console receipt patterns', reviewed)

    def test_review_is_bound_to_build_artifact_and_current_hash(self):
        reviewed = self.reviewed_wrapper()
        original = json.loads(reviewed.read_text())
        wrong = copy.deepcopy(original); wrong['artifactSha256'] = '0' * 64; write(reviewed, wrong)
        self.invoke(1, 'not bound to this build', reviewed)
        write(reviewed, original)
        current = self.repo / original['path']; write(current, current.read_text() + '# changed since review\n')
        self.invoke(1, 'Hash mismatch', reviewed)

    def test_changed_native_harness_rejected(self):
        write(self.repo / 'qa/protocol-client/client.mjs', 'changed harness')
        self.invoke(1, 'Hash mismatch')


if __name__ == '__main__':
    unittest.main()
