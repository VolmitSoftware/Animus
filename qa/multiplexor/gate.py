#!/usr/bin/env python3
"""Milestone gate: run every applicable scenario set on the nine exact Paper/Spigot/Folia targets.

Fabric, NeoForge and Forge targets (for example --targets fabric-26.3) run only the live set, with the
mod jar given by --mod-jars and no Bukkit fixture; their instances are named animus-mod-<loader>-<version>.

Uses one successful Multiplexor build run. Fixtures are rebuilt against that exact artifact.
Each target runs its sets one after another, so at most --workers servers run at once.
Instances are named animus-gate-[set-]<platform>-<version>.
"""
import argparse, concurrent.futures, hashlib, json, subprocess, threading, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECTS = ['paper', 'spigot', 'folia']
MOD_PROJECTS = ['fabric', 'neoforge', 'forge']
VERSIONS = ['26.1.2', '26.2', '26.3']
SETS = ['live', 'native', 'modules', 'imports', 'behaviors']
SKIN = 'consumers/plugin-consumers/instances/animus-r10-paper-26-3/plugins/Animus/skins/d140b4361c456ad426b436dd35d097786be4fe57d7618a67a4d07d044add6696.json'

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--binary', type=Path, default=ROOT.parent / 'ServerMultiplexor/multiplexor')
p.add_argument('--workspace', type=Path, required=True)
p.add_argument('--build-home', type=Path, required=True)
p.add_argument('--build-run', required=True)
p.add_argument('--output', type=Path, required=True, help='New directory for logs and state')
p.add_argument('--targets', help='Comma list like paper-26.3,folia-26.2,fabric-26.3; default the nine Bukkit targets')
p.add_argument('--mod-jars', type=Path, default=ROOT / 'build/distributions', help='Directory holding the Fabric, NeoForge and Forge jars')
p.add_argument('--sets', default=','.join(SETS))
p.add_argument('--workers', type=int, default=3)
a = p.parse_args()
assert 1 <= a.workers <= 3, 'At most three servers at once'
a.workspace, a.build_home, a.binary = a.workspace.resolve(), a.build_home.resolve(), a.binary.resolve()
assert '.qa' in a.workspace.parts, 'Dedicated QA workspace required'
sets = a.sets.split(',')
assert set(sets) <= set(SETS), sets
a.output.mkdir(parents=True)
sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
instances = a.workspace / 'consumers/plugin-consumers/instances'


def environment():
    import os
    env = dict(os.environ)
    env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'
    env['MULTIPLEXOR_BUILD_HOME'] = str(a.build_home)
    return env


status = subprocess.run([str(a.binary), '--root', str(a.workspace), 'projects', 'status', a.build_run, '--json'], capture_output=True, text=True, env=environment(), check=True)
run_record = json.loads(status.stdout)['runs'][0]
assert run_record['status'] == 'succeeded', run_record['status']
artifacts = [item for job in run_record['jobs'] for item in job['artifacts']]
plugin = next(Path(item['file']) for item in artifacts if item['role'] == 'runtime')
api = next(Path(item['file']) for item in artifacts if item['role'] == 'api')
plugin_sha = sha(plugin)
assert plugin_sha == next(item['sha256'] for item in artifacts if item['role'] == 'runtime')


def build_fixtures() -> dict[str, str]:
    cache = a.workspace / f'gate-fixtures-{plugin_sha[:16]}.json'
    if cache.exists():
        paths = json.loads(cache.read_text())
        if all(Path(path).exists() for path in paths.values()):
            return paths
    out = a.output / 'fixtures'
    out.mkdir()
    spigot_api = instances / 'animus-spigot-26-1-2/bundler/libraries/spigot-api-26.1.2-R0.1-SNAPSHOT.jar'
    folia_libraries = instances / 'animus-folia-26-3/libraries'
    commands = {
        'modules': ['python3', 'qa/modules/build-fixtures.py', '--api-jar', str(api), '--output', str(out / 'modules')],
        'reference': ['python3', 'examples/modules/reference/build.py', '--api-jar', str(api), '--output', str(out / 'reference')],
        'moduleObserver': ['python3', 'qa/modules/build-plugin-fixture.py', '--spigot-api', str(spigot_api), '--plugin', str(plugin)],
        'behaviorModules': ['python3', 'qa/behaviors/build-fixtures.py', '--api-jar', str(api)],
        'behaviorObserver': ['python3', 'qa/behaviors/build-plugin-fixture.py', '--spigot-api', str(spigot_api), '--plugin', str(plugin)],
        'native': ['python3', 'qa/build-fixture.py', '--java-home', '/opt/homebrew/opt/openjdk@25', '--spigot-api', str(spigot_api), '--plugin', str(plugin)],
        'folia': ['python3', 'qa/folia-proof/build-fixture.py', '--libraries', str(folia_libraries), '--plugin', str(plugin)],
    }
    outputs: dict[str, str] = {}
    for key, command in commands.items():
        done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
        (out / f'{key}.log').write_text(done.stdout + done.stderr)
        assert done.returncode == 0, f'{key} fixture build failed: {out / (key + ".log")}'
        outputs[key] = done.stdout.strip().splitlines()[-1] if done.stdout.strip() else ''
    native = ROOT / '.qa/event-fixture/AnimusProofFixture.jar'
    paths = {
        'modules': str(out / 'modules'),
        'reference': str(out / 'reference/animus-reference-1.0.0.jar'),
        'moduleObserver': json.loads(outputs['moduleObserver'])['fixture'],
        'behaviorModules': json.loads(outputs['behaviorModules'])['build'],
        'behaviorObserver': json.loads(outputs['behaviorObserver'])['fixture'],
        'native': str(native),
        'folia': json.loads(outputs['folia'])['fixture'],
        'skin': str(a.workspace / SKIN),
    }
    for key, path in paths.items():
        assert Path(path).exists(), key
    cache.write_text(json.dumps(paths, indent=2) + '\n')
    return paths


fixtures = build_fixtures()
base = ['--binary', str(a.binary), '--workspace', str(a.workspace), '--build-home', str(a.build_home), '--build-run', a.build_run]


def commands(project: str, version: str) -> list[tuple[str, list[str]]]:
    dashed = version.replace('.', '-')
    jobs: list[tuple[str, list[str]]] = []
    if project in MOD_PROJECTS:
        if 'live' not in sets:
            return jobs
        label = {'fabric': 'Fabric', 'neoforge': 'NeoForge', 'forge': 'Forge'}[project]
        jars = sorted(a.mod_jars.glob(f'* [[]{label}[]] {version}+*.jar'))
        assert len(jars) == 1, f'expected one {label} {version} jar in {a.mod_jars}: {jars}'
        jobs.append(('live', ['node', 'qa/multiplexor/live-features.mjs', '--project', project, '--version', version, '--instance-name', f'animus-mod-{project}-{dashed}',
                              '--results', str(a.output / f'live-{project}-{version}'), '--workspace', str(a.workspace), '--binary', str(a.binary),
                              '--build-home', str(a.build_home), '--plugin', str(jars[0]), '--skin-file', fixtures['skin']]))
        return jobs
    if 'live' in sets:
        live = ['node', 'qa/multiplexor/live-features.mjs', '--project', project, '--version', version, '--instance-name', f'animus-gate-live-{project}-{dashed}',
                '--results', str(a.output / f'live-{project}-{version}'), '--workspace', str(a.workspace), '--binary', str(a.binary),
                '--build-home', str(a.build_home), '--plugin', str(plugin), '--skin-file', fixtures['skin']]
        if project != 'folia':
            live += ['--fixture', fixtures['native']]
        jobs.append(('live', live))
    if 'native' in sets:
        name = f'animus-gate-{project}-{dashed}'
        prepare = [] if (instances / name).exists() else ['python3', 'qa/multiplexor/prepare-recheck.py', *base[:6], '--round', 'gate', '--project', project, '--version', version]
        if project == 'folia':
            native = ['python3', 'qa/multiplexor/folia-proof.py', *base, '--version', version, '--instance-name', name, '--fixture', fixtures['folia'], '--skin-file', fixtures['skin'], '--phase', 'all']
        else:
            native = ['python3', 'qa/multiplexor/runtime-npcs.py', *base, '--project', project, '--version', version, '--instance-name', name, '--fixture', fixtures['native'],
                      '--skin-file', fixtures['skin'], '--combat', '--control', '--movement', '--inline-recovery']
        if prepare:
            jobs.append(('prepare', prepare))
        jobs.append(('native', native))
    if 'modules' in sets:
        jobs.append(('modules', ['python3', 'qa/multiplexor/modules.py', *base, '--artifact-sha256', plugin_sha, '--project', project, '--version', version,
                                 '--instance-name', f'animus-gate-modules-{project}-{dashed}', '--module-fixtures', fixtures['modules'],
                                 '--reference', fixtures['reference'], '--observer', fixtures['moduleObserver']]))
    if 'imports' in sets:
        jobs.append(('imports', ['python3', 'qa/multiplexor/import-applications.py', *base, '--artifact-sha256', plugin_sha, '--project', project, '--version', version,
                                 '--instance-name', f'animus-gate-import-{project}-{dashed}']))
    if 'behaviors' in sets:
        jobs.append(('behaviors', ['python3', 'qa/multiplexor/behaviors.py', *base, '--artifact-sha256', plugin_sha, '--project', project, '--version', version,
                                   '--instance-name', f'animus-gate-behaviors-{project}-{dashed}', '--module-fixtures', fixtures['behaviorModules'],
                                   '--observer', fixtures['behaviorObserver']]))
    return jobs


targets = [(project, version) for project in PROJECTS for version in VERSIONS]
if a.targets:
    wanted = set(a.targets.split(','))
    targets = [(project, version) for project, version in targets + [(m, v) for m in MOD_PROJECTS for v in VERSIONS] if f'{project}-{version}' in wanted]
lock = threading.Lock()
results: list[dict[str, object]] = []


def save() -> None:
    temp = a.output / 'state.tmp'
    temp.write_text(json.dumps({'buildRun': a.build_run, 'pluginSha256': plugin_sha, 'fixtures': fixtures, 'results': results}, indent=2) + '\n')
    temp.replace(a.output / 'state.json')


def target(item: tuple[str, str]) -> None:
    project, version = item
    for label, command in commands(project, version):
        log = a.output / f'{project}-{version}-{label}.log'
        started = time.time()
        with log.open('w') as stream:
            code = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, env=environment()).returncode
        row = {'project': project, 'version': version, 'set': label, 'exitCode': code, 'seconds': round(time.time() - started), 'log': str(log)}
        with lock:
            results.append(row)
            save()
        print(json.dumps(row), flush=True)
        if label == 'prepare' and code:
            return


save()
with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as pool:
    list(pool.map(target, targets))
failed = [row for row in results if row['exitCode']]
(a.output / 'result.json').write_text(json.dumps({'status': 'FAILED' if failed else 'PASS', 'results': results}, indent=2) + '\n')
raise SystemExit(1 if failed else 0)
