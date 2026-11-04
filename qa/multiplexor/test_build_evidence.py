"""Synthetic provenance failures must never produce an apparently verified evidence directory."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('build-evidence.py').resolve()


class BuildEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='animus-evidence-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / 'cache'
        self.cache.mkdir()
        self.output = self.root / 'verified'
        projects = []
        manifest = {}
        for name in ['Animus', 'VolmLib']:
            source = self.root / 'captured' / name / 'source.txt'
            source.parent.mkdir(parents=True)
            source.write_text('synthetic test source')
            manifest[name] = {'files': {'source.txt': hashlib.sha256(source.read_bytes()).hexdigest()}}
            projects.append({'name': name, 'sourceRoot': str(self.root / 'captured'), 'relativePath': name})
        self.snapshot = self.root / 'snapshot.json'
        self.snapshot.write_text(json.dumps(manifest))
        jar = self.root / 'plugin.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('plugin.yml', "description: 'Synthetic fixture'\n")
        self.record = {'id': 'synthetic-run', 'status': 'succeeded', 'projects': projects, 'sourceHashes': {}, 'jobs': []}
        self.logs = {}
        for module, key in [('core', 'a' * 32), ('bukkit', 'b' * 32), ('converter', None)]:
            log = self.root / (module + '.log')
            text = '> Task :' + module + ':test' + (' NO-SOURCE' if module == 'converter' else '') + '\n'
            if module == 'bukkit':
                text += '> Task :bukkit:compileSpigot26_1_2\n> Task :bukkit:compileSpigot26_2 FROM-CACHE\n> Task :bukkit:compileSpigot26_3 UP-TO-DATE\n'
            log.write_text(text + 'BUILD SUCCESSFUL\n')
            self.logs[module] = log
            artifacts = [] if module != 'bukkit' else [{'file': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'role': 'runtime'}]
            self.record['jobs'].append({'key': 'animus:' + module, 'project': 'animus', 'target': module,
                                       'status': 'succeeded', 'logPath': str(log), 'artifacts': artifacts,
                                       'startedAt': '1970-01-01T00:00:04Z', 'finishedAt': '1970-01-01T00:00:06Z'})
            if key:
                self.write_cache(module, key)
        self.status = self.root / 'status.json'
        self.status.write_text(json.dumps(self.record))

    def write_cache(self, module, key, duplicate=False):
        with tarfile.open(self.cache / key, 'w:gz') as archive:
            files = {'METADATA': f'identity=\\:{module}\\:test\nbuildCacheKey={key}\ncreationTime=5000\n',
                     'reports/TEST-Example.xml': '<testsuite tests="1" failures="0" errors="0" skipped="0"/>'}
            if duplicate:
                files['other/TEST-Example.xml'] = files['reports/TEST-Example.xml']
            for name, text in files.items():
                data = text.encode()
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))

    def run_collector(self):
        return subprocess.run([sys.executable, str(SCRIPT), '--build-status', str(self.status),
                               '--source-snapshot', str(self.snapshot), '--cache-dir', str(self.cache),
                               '--output', str(self.output)], capture_output=True, text=True)

    def rejected(self, reason):
        result = self.run_collector()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stderr)
        self.assertFalse(self.output.exists())

    def test_executed_origins_and_api_reuse_outcomes_are_explicit(self):
        result = self.run_collector()
        self.assertEqual(0, result.returncode, result.stderr)
        summary = json.loads((self.output / 'summary.json').read_text())
        self.assertEqual({'core', 'bukkit'}, set(summary['tests']))
        self.assertEqual({'26.1.2': 'EXECUTED', '26.2': 'FROM-CACHE', '26.3': 'UP-TO-DATE'}, summary['apiCompileOutcomes'])

    def test_core_no_source_is_not_a_test_pass(self):
        self.logs['core'].write_text('> Task :core:test NO-SOURCE\nBUILD SUCCESSFUL\n')
        self.rejected('a real test task and result are required')

    def test_missing_core_job_cannot_produce_complete_evidence(self):
        self.record['jobs'] = self.record['jobs'][1:]
        self.status.write_text(json.dumps(self.record))
        self.rejected('Both core and Bukkit test results are required')

    def test_skipped_and_no_source_api_compile_are_rejected(self):
        original = self.logs['bukkit'].read_text()
        for outcome in ['SKIPPED', 'NO-SOURCE']:
            with self.subTest(outcome=outcome):
                self.logs['bukkit'].write_text(original.replace('compileSpigot26_2 FROM-CACHE', 'compileSpigot26_2 ' + outcome))
                self.rejected('Exact API compilation did not run or reuse compiled output')

    def test_duplicate_xml_basename_cannot_inflate_counts_or_overwrite_output(self):
        self.write_cache('bukkit', 'b' * 32, duplicate=True)
        self.rejected('Duplicate test XML basename')

    def test_cached_task_needs_verified_prior_lineage(self):
        self.logs['core'].write_text('> Task :core:test FROM-CACHE\nBUILD SUCCESSFUL\n')
        self.rejected('require one proven cache origin')

    def test_source_changes_after_capture_are_rejected(self):
        (self.root / 'captured/Animus/source.txt').write_text('changed')
        self.rejected('Captured input differs from manifest')

    def api_artifact(self, extra=None):
        jar = self.root / 'api.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('art/arcane/animus/api/AnimusApi.class', b'synthetic class bytes')
            archive.writestr('art/arcane/animus/api/TrustedModule.class', b'synthetic class bytes')
            for name, content in (extra or {}).items():
                archive.writestr(name, content)
        self.record['jobs'][0]['artifacts'] = [{'file': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'role': 'api'}]
        self.status.write_text(json.dumps(self.record))

    def test_public_api_artifact_is_recorded_separately_from_runtime_metadata(self):
        self.api_artifact()
        result = self.run_collector()
        self.assertEqual(0, result.returncode, result.stderr)
        artifacts = json.loads((self.output / 'summary.json').read_text())['artifacts']
        api = next(value for value in artifacts if value['role'] == 'api')
        self.assertEqual(2, api['publicClassCount'])
        self.assertNotIn('description', api)
        self.assertEqual('Synthetic fixture', next(value for value in artifacts if value['role'] == 'runtime')['description'])

    def test_private_class_in_api_artifact_is_rejected(self):
        self.api_artifact({'art/arcane/animus/core/Hidden.class': b'synthetic class bytes'})
        self.rejected('API artifact must contain only the stable public API classes')

    def test_plugin_metadata_in_api_artifact_is_rejected(self):
        self.api_artifact({'plugin.yml': b'description: not an API'})
        self.rejected('API artifact must not contain platform plugin metadata')

    def test_cached_test_lineage_changes_when_public_api_source_changes(self):
        spec = importlib.util.spec_from_file_location('build_evidence', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        before = {'Animus': {'files': {'animus-api/src/main/java/Public.java': 'old', 'core/src/main/java/Core.java': 'same'}}}
        after = {'Animus': {'files': {'animus-api/src/main/java/Public.java': 'new', 'core/src/main/java/Core.java': 'same'}}}
        for target in ['core', 'bukkit']:
            self.assertNotEqual(module.inputs(before, target), module.inputs(after, target))


if __name__ == '__main__':
    unittest.main()
