#!/usr/bin/env python3
"""Preserve exact Multiplexor test outputs from its Gradle task cache after sandbox cleanup.

Executed tasks require one matching origin within that job's execution window.
FROM-CACHE tasks require a previous verified evidence record and unchanged module,
build configuration and pinned dependency inputs. No local checkout XML is read.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def milliseconds(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp() * 1000


def metadata(archive):
    member = archive.getmember('METADATA')
    if member.size > 16384:
        raise ValueError('Oversized task origin metadata')
    raw = archive.extractfile(member).read()
    parsed = {}
    for line in raw.decode().splitlines():
        if not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            parsed[key] = value.replace('\\:', ':')
    return raw, parsed


def inputs(manifest, module):
    prefixes = ('animus-api/', 'core/', 'gradle/') + (('animus-bukkit-api/', 'bukkit/') if module == 'bukkit' else ())
    root_files = {'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat'}
    return {name: {path: sha for path, sha in project['files'].items()
                   if name != 'Animus' or path in root_files or path.startswith(prefixes)}
            for name, project in manifest.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-status', type=Path, required=True)
    parser.add_argument('--source-snapshot', type=Path, required=True)
    parser.add_argument('--cache-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--reuse-from', type=Path, action='append', default=[])
    args = parser.parse_args()
    record = json.loads(args.build_status.read_text())
    runs = record.get('runs', [record])
    if len(runs) != 1 or runs[0]['status'] != 'succeeded':
        parser.error('Exactly one successful build is required')
    if args.output.exists():
        parser.error('Use a new evidence directory; previous evidence is immutable')
    run = runs[0]
    manifest = json.loads(args.source_snapshot.read_text())
    # Verify the per-file manifest against the actual captured input, not today's working checkout.
    for project in run['projects']:
        name = project['name']
        captured = Path(project['sourceRoot']) / project['relativePath']
        for relative, expected in manifest[name]['files'].items():
            path = captured / relative
            if not path.is_file() or digest(path) != expected:
                raise ValueError(f'Captured input differs from manifest: {name}/{relative}')
    previous = [json.loads(path.read_text()) for path in args.reuse_from]
    summary = {'schema': 1, 'verifiedAt': datetime.now(timezone.utc).isoformat(),
               'runId': run['id'], 'status': run['status'],
               'collectorSha256': digest(Path(__file__)),
               'buildRecord': str(args.build_status.resolve()), 'buildRecordSha256': digest(args.build_status),
               'sourceManifest': str(args.source_snapshot.resolve()), 'sourceManifestSha256': digest(args.source_snapshot),
               'sourceHashes': run['sourceHashes'], 'tests': {}, 'logs': {}, 'artifacts': [],
               'limitations': ['Converter has no separate test sources when its log records NO-SOURCE.',
                               'Executed XML origins match task identity and the recorded job execution window.',
                               'Cached test results are identified by a prior verified cache archive and unchanged relevant source/build/dependency inputs.',
                               'The current log exposes FROM-CACHE, not its cache key. Cached association uses matching inputs and the prior archive; it is not a directly observed current Gradle cache key.',
                               'Build evidence does not establish live gameplay or performance.']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.build-evidence-', dir=args.output.parent) as temporary:
        stage = Path(temporary)
        for job in run['jobs']:
            if job['project'] != 'animus':
                continue
            module = job['target']
            log = Path(job['logPath'])
            text = log.read_text()
            if job['status'] != 'succeeded' or 'BUILD SUCCESSFUL' not in text:
                raise ValueError(f'Build job did not succeed: {job["key"]}')
            summary['logs'][module] = {'path': str(log), 'sha256': digest(log)}
            for artifact in job.get('artifacts', []):
                jar = Path(artifact['file'])
                if digest(jar) != artifact['sha256']:
                    raise ValueError('Artifact hash mismatch')
                with zipfile.ZipFile(jar) as archive:
                    if artifact.get('role') == 'api':
                        classes = [entry.filename for entry in archive.infolist() if entry.filename.endswith('.class')]
                        public = 'art/arcane/animus/api/'
                        if (len(classes) != len(set(classes)) or not classes
                                or any(not name.startswith(public) for name in classes)
                                or not {public + 'AnimusApi.class', public + 'TrustedModule.class'}.issubset(classes)):
                            raise ValueError('API artifact must contain only the stable public API classes')
                        if any(name in archive.namelist() for name in ['plugin.yml', 'paper-plugin.yml', 'fabric.mod.json']):
                            raise ValueError('API artifact must not contain platform plugin metadata')
                        public_hashes = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in sorted(classes)}
                        summary['artifacts'].append(artifact | {'publicClassCount': len(classes), 'publicClasses': public_hashes,
                            'scope': 'standalone API artifact provenance; packaged API/JDK dependency verification is separate'})
                    elif artifact.get('role') == 'runtime':
                        plugin = archive.read('plugin.yml')
                        description = re.search(r'^description:\s*(.*)$', plugin.decode(), re.M).group(1).strip("'\"")
                        (stage / 'packaged-plugin.yml').write_bytes(plugin)
                        summary['artifacts'].append(artifact | {'description': description, 'pluginYmlSha256': hashlib.sha256(plugin).hexdigest()})
                    else:
                        raise ValueError('Unsupported Animus artifact role: ' + str(artifact.get('role')))
            task = re.search(r'^> Task :' + re.escape(module) + r':test(?: ([A-Z-]+))?$', text, re.M)
            if not task or task.group(1) == 'NO-SOURCE':
                if module in ('core', 'bukkit'):
                    raise ValueError(f'{module}: a real test task and result are required')
                continue
            outcome = task.group(1) or 'EXECUTED'
            candidates = []
            origin_run = run['id']
            if outcome == 'FROM-CACHE':
                for prior in previous:
                    if prior.get('status') != 'succeeded' or module not in prior['tests']:
                        continue
                    prior_manifest = Path(prior['sourceManifest'])
                    if digest(prior_manifest) != prior['sourceManifestSha256']:
                        raise ValueError('Prior source manifest changed')
                    if inputs(manifest, module) != inputs(json.loads(prior_manifest.read_text()), module):
                        continue
                    result = prior['tests'][module]
                    if result.get('failures', 0) or result.get('errors', 0):
                        continue
                    cache = args.cache_dir / result['originCacheKey']
                    if digest(cache) != result['originCacheArchiveSha256']:
                        raise ValueError('Prior cache archive changed')
                    candidates = [cache]
                    origin_run = result.get('originRunId', prior['runId'])
                    break
            elif outcome == 'EXECUTED':
                for cache in args.cache_dir.iterdir():
                    if not cache.is_file() or not re.fullmatch(r'[a-f0-9]{32,64}', cache.name):
                        continue
                    with tarfile.open(cache, 'r:*') as archive:
                        _, origin = metadata(archive)
                    if origin.get('identity') == f':{module}:test' and milliseconds(job['startedAt']) <= int(origin['creationTime']) <= milliseconds(job['finishedAt']):
                        candidates.append(cache)
            else:
                raise ValueError(f'Unsupported test outcome {outcome}; do not infer counts')
            if len(candidates) != 1:
                raise ValueError(f'{module}: require one proven cache origin, found {len(candidates)}; cached tasks need --reuse-from')
            cache = candidates[0]
            destination = stage / module
            destination.mkdir()
            counts = {key: 0 for key in ['tests', 'failures', 'errors', 'skipped']}
            files = []
            xml_names = set()
            with tarfile.open(cache, 'r:*') as archive:
                raw, origin = metadata(archive)
                if origin.get('identity') != f':{module}:test' or origin.get('buildCacheKey') != cache.name:
                    raise ValueError('Task origin identity mismatch')
                (destination / 'origin.properties').write_bytes(raw)
                for member in archive.getmembers():
                    name = Path(member.name).name
                    if not name.startswith('TEST-') or not name.endswith('.xml'):
                        continue
                    if name in xml_names:
                        raise ValueError(f'Duplicate test XML basename: {name}')
                    xml_names.add(name)
                    if not member.isfile() or member.size > 8 * 1024 * 1024:
                        raise ValueError('Invalid test XML cache entry')
                    data = archive.extractfile(member).read()
                    suite = ET.fromstring(data)
                    for key in counts:
                        counts[key] += int(suite.attrib.get(key, 0))
                    (destination / name).write_bytes(data)
                    files.append({'file': str((args.output / module / name).resolve()), 'sha256': hashlib.sha256(data).hexdigest()})
            if not files or counts['failures'] or counts['errors']:
                raise ValueError('Test origin contains no passing XML or records test failures')
            summary['tests'][module] = counts | {'taskOutcome': outcome, 'originRunId': origin_run,
                                                'originCacheKey': cache.name, 'originCacheArchiveSha256': digest(cache),
                                                'origin': origin, 'suiteFiles': len(files), 'files': files}
        if not {'core', 'bukkit'}.issubset(summary['tests']):
            raise ValueError('Both core and Bukkit test results are required')
        bukkit = Path(next(job['logPath'] for job in run['jobs'] if job['key'] == 'animus:bukkit')).read_text()
        summary['exactSpigotApiCompiles'] = []
        summary['apiCompileOutcomes'] = {}
        for version in ['26.1.2', '26.2', '26.3']:
            task = 'compileSpigot' + version.replace('.', '_')
            match = re.search(r'^> Task :bukkit:' + task + r'(?: ([A-Z-]+))?$', bukkit, re.M)
            if not match:
                raise ValueError(f'Missing exact API compilation task: {version}')
            outcome = match.group(1) or 'EXECUTED'
            if outcome not in ('EXECUTED', 'FROM-CACHE', 'UP-TO-DATE'):
                raise ValueError(f'Exact API compilation did not run or reuse compiled output: {version} {outcome}')
            summary['exactSpigotApiCompiles'].append(version)
            summary['apiCompileOutcomes'][version] = outcome
        (stage / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        stage.rename(args.output)
    print(json.dumps({'runId': run['id'], 'tests': {module: {k: data[k] for k in ['tests', 'failures', 'errors', 'skipped', 'taskOutcome']} for module, data in summary['tests'].items()}, 'evidence': str(args.output / 'summary.json')}))


if __name__ == '__main__':
    main()
