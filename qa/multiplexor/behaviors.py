#!/usr/bin/env python3
"""Three managed starts for explicit manual, definition-bound JSON behaviors.

Never starts Java directly. All fixtures are public synthetic data, all world-facing
NPC services are disabled, and no automatic trigger or durable execution is claimed.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import socket
import time
import multiplexor_workspace as mw

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'qa/behaviors'
HELPERS = runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')))
run, wait_log, console = (HELPERS[name] for name in ('run', 'wait_log', 'console'))
ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
read = lambda path: json.loads(path.read_text())
encode = lambda value: (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def no_symlinks(path):
    """Reject aliases before reading, replacing or deleting owned fixture data."""
    path = path.absolute()
    for component in [*reversed(path.parents), path]:
        assert not component.is_symlink(), f"Symlink outside QA ownership: {component}"
    return path


def counter(text, name):
    found = re.search(r'\b' + re.escape(name) + r'=([0-9]+)\b', text or '')
    assert found, (name, text)
    return int(found[1])


def json_receipt(line, prefix):
    clean = ANSI.sub('', line)
    return json.loads(clean.split(prefix, 1)[1])


def execution_receipt(line):
    clean = ANSI.sub('', line)
    match = re.search(r'Behavior execution=([0-9a-f-]{36}) \| (.*)', clean)
    assert match, line
    result = {'execution': match[1]}
    result.update(part.split('=', 1) for part in match[2].split(' | '))
    for key in ('npc-revision', 'behavior-revision', 'steps'):
        result[key] = int(result[key])
    return result


def basic_events(snapshot, npc_uuid):
    assert snapshot['dropped'] == 0 and not snapshot['pending']
    events = snapshot['events']
    assert all(item['npc'] == npc_uuid for item in events)
    assert [item['sequence'] for item in events] == list(range(1, 13))
    assert collections.Counter(item['kind'] for item in events) == {'record': 10, 'condition': 2}
    assert [item['arguments'] for item in events if item['kind'] == 'condition'] == [{'result': True}, {'result': False}]
    records = [item['arguments'] for item in events if item['kind'] == 'record']
    tags = [item['tag'] for item in records]
    assert tags[:4] == ['start', 'bound', 'true', 'false']
    assert tags[4:7] == ['loop'] * 3 and set(tags[7:9]) == {'branch-a', 'branch-b'} and tags[9] == 'after'
    assert type(records[1]['longValue']) is int and type(records[1]['decimalValue']) is float
    assert type(records[1]['booleanValue']) is bool and type(records[1]['stringValue']) is str
    assert records[1] == {'tag': 'bound', 'longValue': 9223372036854775807, 'decimalValue': 1.25, 'booleanValue': True, 'stringValue': 'Garde Étoile'}
    return tags


class Scenario:
    def __init__(self, args):
        self.a = args
        self.a.binary = args.binary.resolve()
        self.a.workspace = args.workspace.resolve()
        self.a.transcript = []
        assert re.fullmatch('[0-9a-f]{64}', args.artifact_sha256)
        self.fixtures = read(args.module_fixtures / 'build.json')
        self.observer = read(args.observer.parent / 'build.json')
        assert self.fixtures['status'] == 'compiled-only'
        assert self.observer['fixtureSha256'] == sha(args.observer)
        assert self.observer['pluginSha256'] == args.artifact_sha256
        self.name = f'animus-behaviors-{args.project}-{args.version.replace(".", "-")}'
        if args.instance_name:
            assert re.fullmatch(r'animus-gate-behaviors-' + args.project + '-' + re.escape(args.version.replace('.', '-')), args.instance_name), 'Instance name must bind requested platform/version'
            self.name = args.instance_name
        baseline_name = mw.baseline_name(args.project, args.version)
        self.instances = args.workspace / 'consumers/plugin-consumers/instances'
        self.baseline = self.instances / baseline_name
        self.instance = self.instances / self.name
        self.pinned = read(mw.evidence_root(args.workspace) / baseline_name / 'runtime.json')
        assert sha(self.baseline / 'server.jar') == self.pinned['serverSha256']
        self.token = f'{time.time_ns()}-{os.getpid()}'
        self.evidence = mw.evidence_root(args.workspace) / self.name / f'behaviors-{self.token}'
        no_symlinks(self.evidence); self.evidence.mkdir(parents=True)
        self.data = self.instance / 'plugins/Animus'
        self.log = self.instance / 'logs/latest.log'
        self.npc_id = 'behavior-qa-' + self.token
        self.control_id = self.npc_id + '-control'
        self.owned_ids = [self.npc_id, self.control_id] + [self.npc_id + '-cap' + str(i) for i in range(5)]
        self.session = None
        self.started = self.owned = self.disabled = self.ownership_ready = False
        self.owned_files = {}
        self.catalog_generation = 0
        self.report = {
            'schemaVersion': 1, 'runner': 'ServerMultiplexor',
            'scope': 'explicit manual definition-bound JSON behavior execution of module capabilities; built-in world actions, automatic triggers, preview, privileged grants and durable execution stacks are not exercised',
            'project': args.project, 'minecraftVersion': args.version,
            'serverBuild': self.pinned['build'], 'serverChannel': self.pinned['channel'],
            'serverSha256': self.pinned['serverSha256'], 'buildRun': args.build_run,
            'pluginSha256': args.artifact_sha256, 'apiSha256': self.fixtures['apiSha256'],
            'status': 'preparing', 'npcId': self.npc_id, 'ownedNpcIds': self.owned_ids,
            'checks': [], 'commands': [], 'phases': [], 'stops': [], 'snapshots': [], 'ownedFiles': self.owned_files,
            'cleanupPolicy': 'after actual disable and observed quiescence, remove only exact receipt-owned sources/checkpoints offline after managed stop; immutable objects/backups retained',
        }
        sources = [Path(__file__).resolve(), Path(__file__).with_name('managed-runtime.py').resolve()]
        sources += [p for p in HERE.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc']
        self.report['sources'] = {str(p.relative_to(ROOT)): sha(p) for p in sorted(set(sources))}
        for source in sorted(set(sources)):
            target = self.evidence / 'sources' / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        (self.evidence / 'modules-build.json').write_bytes(encode(self.fixtures))
        (self.evidence / 'observer-build.json').write_bytes(encode(self.observer))
        inputs = [args.module_fixtures / 'build.json', args.observer, args.observer.parent / 'build.json']
        inputs += [args.module_fixtures / item['file'] for item in self.fixtures['modules']]
        self.report['inputFiles'] = {str(path.resolve()): sha(path) for path in inputs}
        fixture_archive = self.evidence / 'fixtures'; fixture_archive.mkdir()
        for path in inputs:
            if path.suffix == '.jar': shutil.copy2(path, fixture_archive / path.name)
        self.report['archivedFixtures'] = {str(path.relative_to(self.evidence)): sha(path) for path in fixture_archive.iterdir()}
        for record in [self.observer, *self.fixtures['modules']]:
            assert all(sha(ROOT / name) == digest for name, digest in record['sources'].items())
        self.save()

    def save(self):
        path = self.evidence / 'result.json'
        temp = path.with_suffix('.json.tmp')
        no_symlinks(temp); no_symlinks(path)
        temp.write_bytes(encode(self.report)); temp.replace(path)
        (self.evidence / 'multiplexor-commands.json').write_bytes(encode(self.a.transcript))

    def check(self, identifier, **details):
        assert identifier not in [item['id'] for item in self.report['checks']]
        self.report['checks'].append({'id': identifier, 'status': 'passed', 'phase': self.report.get('runningPhase'), **details})
        self.save(); print(json.dumps({'check': identifier, 'instance': self.name}), flush=True)

    def command(self, text, pattern):
        offset = len(self.log.read_text(errors='replace')) if self.log.exists() else 0
        receipt = console(self.session, self.log, text, pattern)
        # A command may have bounded diagnostic rows after its first receipt. They
        # remain in the phase log; semantic assertions poll for their actual rows.
        item = {'phase': self.report.get('runningPhase'), 'command': text, 'receipt': ANSI.sub('', receipt), 'logOffset': offset}
        self.report['commands'].append(item)
        item['output'] = ANSI.sub('', self.log.read_text(errors='replace')[offset:])
        self.save()
        return item['receipt']

    def output(self):
        item = self.report['commands'][-1]
        item['output'] = ANSI.sub('', self.log.read_text(errors='replace')[item['logOffset']:])
        self.save()
        return item['output']

    def until(self, function, predicate, timeout=20):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            last = function()
            if predicate(last):
                return last
            time.sleep(.1)
        raise AssertionError(f'Bounded observation timed out: {last}')

    def reserve(self, path):
        assert self.ownership_ready, 'Data ownership not established'
        no_symlinks(path)
        relative = str(path.relative_to(self.data))
        assert not path.exists(), f'Refusing preexisting fixture data: {path}'
        self.owned_files.setdefault(relative, {'reservedBeforeCreation': True, 'hashes': []})
        self.save()

    def owned_write(self, path, content):
        no_symlinks(path)
        relative = str(path.relative_to(self.data))
        if relative not in self.owned_files: self.reserve(path)
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
        self.owned_files[relative]['hashes'].append(sha(path)); self.save()

    def owned_delete(self, path):
        assert str(path.relative_to(self.data)) in self.owned_files
        no_symlinks(path); path.unlink()

    def source(self, identity=None):
        return self.data / 'npcs/qa' / ((identity or self.npc_id) + '.json')

    def checkpoint(self, identity):
        return self.data / 'state/npc-definitions/last-valid/npcs/qa' / (identity + '.json')

    def behavior(self, name):
        return self.data / 'behaviors/qabehavior' / (name + '.json')

    def snapshot(self, label, paths):
        records = []
        for index, path in enumerate(paths):
            no_symlinks(path)
            if path.exists():
                target = self.evidence / 'snapshots' / label / str(index)
                no_symlinks(target); target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
                records.append({'source': str(path.relative_to(self.data)), 'archive': str(target.relative_to(self.evidence)), 'sha256': sha(target)})
            else:
                records.append({'source': str(path.relative_to(self.data)), 'missing': True})
        self.report['snapshots'].append({'label': label, 'files': records}); self.save()
        return records

    def status(self):
        return json_receipt(self.command('behaviorqa status', r'.*Behavior QA status \{.*'), 'Behavior QA status ')

    def observe(self):
        return json_receipt(self.command('behaviorqa observe', r'.*Behavior QA observed \{.*'), 'Behavior QA observed ')

    def quiescent(self, value):
        return value['disabled'] and value['serviceClosed'] and not value['catalogWorkPending'] and value['coordinatorStopped'] and value['moduleStopped'] and value['fileQuiescent'] and value['fileFailure'] is None and value['behaviorThreads'] == 0

    def idle(self):
        return self.until(self.status, lambda s: counter(s['runtime'], 'callsInFlight') == 0 and counter(s['runtime'], 'retiredCalls') == 0 and counter(s['runtime'], 'activeExecutions') == 0 and counter(s['modules'], 'handlersOutstanding') == 0)

    def action(self, name, identity=None):
        key = 'qabehaviorctl:' + name
        line = self.command(f'animus modules action {key} {identity or self.control_id} {{}}', rf'.*Module {re.escape(key)}: OK; result \{{.*')
        return json_receipt(line, '; result ')

    def create(self, identity):
        self.reserve(self.source(identity)); self.reserve(self.checkpoint(identity))
        self.command(f'animus create {identity} minecraft:player world 0 61 3 qa "Behavior QA"', rf'.*Created dormant {re.escape(identity)};.*')
        saved = read(self.source(identity))
        for path in (self.source(identity), self.checkpoint(identity)):
            no_symlinks(path); self.owned_files[str(path.relative_to(self.data))]['hashes'].append(sha(path))
        assert saved['spawned'] is False
        return saved

    def run_behavior(self, name, identity=None, expected='OK'):
        identity = identity or self.npc_id
        receipt = self.command(f'animus behaviors run {identity} qabehavior:{name}', rf'.*Behavior run: {expected}(?: \|.*)?')
        if expected != 'OK':
            return receipt
        return re.search(r'execution=([0-9a-f-]{36})', receipt)[1]

    def inspect(self, execution):
        line = self.command('animus behaviors inspect ' + execution, rf'.*Behavior execution={execution} \|.*')
        return execution_receipt(line)

    def terminal(self, execution, expected, timeout=20):
        value = self.until(lambda: self.inspect(execution), lambda value: value['state'] != 'RUNNING', timeout)
        assert value['state'] == expected, value
        return value

    def cancel(self, execution):
        self.command('animus behaviors cancel ' + execution, rf'.*Behavior cancel: OK \| execution={execution} \| cancelled=true.*')
        return self.terminal(execution, 'CANCELLED')

    def hold(self, identity=None):
        identity = identity or self.npc_id
        execution = self.run_behavior('hold', identity)
        npc_uuid = read(self.source(identity))['uuid']
        held = self.until(lambda: self.action('inspect'), lambda p: any(x['npc'] == npc_uuid for x in p['pending']))
        assert any(x == {'npc': npc_uuid, 'cancelled': False} for x in held['pending'])
        return execution

    def cancelled_hold(self, execution, expected_count=1):
        self.terminal(execution, 'CANCELLED')
        pending = self.until(lambda: self.action('inspect'), lambda p: len(p['pending']) == expected_count and all(x['cancelled'] for x in p['pending']))
        state = self.until(self.status, lambda s: counter(s['runtime'], 'retiredCalls') == expected_count and counter(s['modules'], 'handlersOutstanding') == expected_count)
        assert counter(state['runtime'], 'callsInFlight') == expected_count
        return {'pending': pending['pending'], 'status': state}

    def release(self, expected=1):
        result = self.action('release_all')
        assert result == {'released': expected, 'cancelled': expected}, result
        state = self.idle()
        snapshot = self.action('inspect')
        assert not snapshot['pending'] and snapshot['dropped'] == 0
        assert not any(x['kind'] == 'record' for x in snapshot['events']), snapshot
        return {'release': result, 'status': state}

    def reload(self):
        receipt = self.command('animus behaviors reload', r'.*Behaviors reload: (?:APPLIED|REJECTED) \|.*')
        assert 'Behaviors reload: APPLIED |' in receipt, receipt
        generation = int(re.search(r'generation=(\d+)', receipt)[1])
        assert generation == self.catalog_generation + 1, (self.catalog_generation, receipt)
        self.catalog_generation = generation
        return receipt

    def catalog_entry(self, name, state, available, revision, digest):
        receipt = self.command('animus behaviors list', rf'.*Behavior qabehavior:{re.escape(name)} \|.*')
        assert f'| state={state} | available={str(available).lower()} | accepted-revision={revision} |' in receipt
        assert f'| source-hash={digest}' in receipt
        return receipt

    def configuration(self):
        (self.data / 'config.toml').write_text('''statusMessage = "Behavior QA"
packetProofEnabled = false
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
''')

    def fixture_mode(self, mode):
        directory = self.instance / 'plugins/AnimusBehaviorFixture'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'config.toml').write_text(f'mode = "{mode}"\n')

    def prepare(self):
        a = self.a
        if not self.instance.exists():
            run(a, 'server', 'create', self.name, '--jar', str(self.baseline / 'server.jar'), '--type', a.project, '--mc', a.version, '--isolated')
        assert run(a, 'instance', 'isolated', self.name).strip() == 'true'
        self.owned = True
        run(a, 'runtime', 'stop', self.name, '--graceful')
        assert sha(self.instance / 'server.jar') == self.report['serverSha256']
        no_symlinks(self.data)
        for relative in ('npcs', 'state/npc-definitions/last-valid/npcs', 'behaviors'):
            directory = no_symlinks(self.data / relative)
            for path in directory.rglob('*'): no_symlinks(path)
            assert not list((self.data / relative).rglob('*.json')), f'Unexpected existing data: {relative}'
        self.ownership_ready = True
        run(a, 'runtime', 'settings', 'set-java', '/opt/homebrew/opt/openjdk@25/bin/java', '--instance', self.name)
        run(a, 'runtime', 'settings', 'set-heap', '1G', '--instance', self.name)
        run(a, 'runtime', 'settings', 'set-preset', 'vanilla', '--instance', self.name)
        run(a, 'gameplay', 'prepare', self.name)
        self.report['port'] = HELPERS['free_port']()
        run(a, 'instance', 'port', self.name, str(self.report['port']))
        shutil.copy2(self.baseline / 'eula.txt', self.instance / 'eula.txt')
        assert 'eula=true' in (self.instance / 'eula.txt').read_text().lower()
        self.data.mkdir(parents=True, exist_ok=True); self.configuration(); self.fixture_mode('cold')
        properties = self.instance / 'server.properties'
        values = {'server-ip': '127.0.0.1', 'online-mode': 'false', 'white-list': 'true', 'difficulty': 'peaceful', 'pause-when-empty-seconds': '-1', 'view-distance': '2', 'simulation-distance': '2', 'max-players': '1', 'spawn-protection': '0', 'level-type': 'minecraft:flat', 'generate-structures': 'false', 'generator-settings': json.dumps({'layers': [{'block': 'minecraft:bedrock', 'height': 1}, {'block': 'minecraft:stone', 'height': 59}, {'block': 'minecraft:grass_block', 'height': 1}], 'biome': 'minecraft:plains'}, separators=(',', ':'))}
        properties.write_text('\n'.join(line for line in properties.read_text().splitlines() if line.partition('=')[0] not in values) + '\n' + '\n'.join(k + '=' + v for k, v in values.items()) + '\n')
        (self.instance / 'whitelist.json').write_text('[]\n'); (self.instance / 'ops.json').write_text('[]\n')
        mojang = Path.home() / '.gradle/caches/fabric-loom' / a.version / 'minecraft-server.jar'
        if mojang.is_file():
            (self.instance / 'cache').mkdir(exist_ok=True); shutil.copy2(mojang, self.instance / 'cache' / f'mojang_{a.version}.jar')
        run(a, 'projects', 'destination', self.name, '--instance', self.name, '--platform', 'plugin', '--mc', a.version)
        run(a, 'projects', 'deploy', a.build_run, '--to', self.name, '--target', 'animus:bukkit', '--json')
        plugins = list((self.instance / 'plugins').glob('*.jar'))
        target = [file for file in plugins if file.name.startswith('Animus v')]
        assert len(target) == 1 and sha(target[0]) == a.artifact_sha256
        assert all(file == target[0] or file.name == a.observer.name for file in plugins)
        shutil.copy2(a.observer, self.instance / 'plugins' / a.observer.name)
        self.report['plugins'] = [{'file': f.name, 'sha256': sha(f)} for f in sorted((self.instance / 'plugins').glob('*.jar'))]
        modules = self.data / 'modules'; modules.mkdir(exist_ok=True)
        allowed = {item['file'] for item in self.fixtures['modules']}
        assert all(f.name in allowed for f in modules.glob('*.jar'))
        # Cold probe has no trusted fixtures to initialize, and no behavior source.
        expected_modules = {item['file']: item['sha256'] for item in self.fixtures['modules']}
        for file in modules.glob('*.jar'):
            no_symlinks(file); assert sha(file) == expected_modules[file.name], 'Preexisting module bytes differ from owned fixture inputs'
            file.unlink()
        self.save()

    def start(self, phase, cold=False):
        if self.log.exists():
            shutil.copy2(self.log, self.evidence / f'previous-{phase}.log'); self.log.unlink()
        output = run(self.a, 'runtime', 'start', self.name, '--no-console')
        self.session = re.search(r'tmux session: (\S+)', output)[1]
        self.started = True; self.disabled = False; self.report['runningPhase'] = phase
        self.report['phases'].append({'phase': phase, 'ready': wait_log(self.log, r'.*Done \([\d.]+s\)!.*')})
        self.report['status'] = 'running'; self.save()
        if cold:
            return
        self.command('animus status', rf'.*Animus 0\.1\.0 \| BUKKIT \| Minecraft {re.escape(self.a.version)}.*')
        self.until(lambda: self.command('animus modules status', r'.*Modules (?:NEW|STARTING|READY|FAILED|STOPPING|STOPPED) \|.*'), lambda line: 'Modules READY |' in line, 30)
        ready = self.until(lambda: self.command('animus behaviors status', r'.*Behaviors [A-Z]+ \|.*'), lambda line: 'Behaviors READY |' in line and 'catalog-work=idle' in line, 30)
        self.catalog_generation = int(re.search(r'catalog-generation=(\d+)', ready)[1])
        self.observe()

    def stop(self, phase):
        receipt = run(self.a, 'runtime', 'stop', self.name, '--graceful')
        self.session = None; self.started = False
        with socket.socket() as sock:
            assert sock.connect_ex(('127.0.0.1', self.report['port'])) != 0
        target = self.evidence / f'{phase}-server.log'; shutil.copy2(self.log, target)
        self.report['stops'].append({'phase': phase, 'receipt': receipt, 'serverPortClosed': True, 'log': str(target), 'logSha256': sha(target)})
        self.save()

    def cold(self):
        self.start('cold-disable', cold=True)
        receipt = wait_log(self.log, r'.*Behavior QA disabled \{.*', timeout=30)
        disabled = json_receipt(receipt, 'Behavior QA disabled ')
        self.disabled = True
        assert disabled['cold'] and not disabled['enabled'] and disabled['serviceClosed']
        assert disabled['before'] is None and disabled['durationNanos'] < 2_000_000_000 and disabled['coordinatorBefore']
        # Enabled service owns a coordinator synchronously before fixture startup.
        final = self.until(self.status, self.quiescent, 30)
        assert final['runtime'] is None and final['modules'] is None and final['files'] is None
        content = self.log.read_text(errors='replace')
        assert 'Animus behavior QA enabled' not in content and 'Animus behavior QA event' not in content
        assert not any(item['command'].startswith('animus ') for item in self.report['commands'])
        self.check('cold-immediate-disable', disable=disabled, final=final,
                   limitation='Normal scheduler race observed; this does not replace deterministic close-before-owner-start classloader tests')
        self.stop('cold-disable')

    def install_fixtures(self):
        self.fixture_mode('manual')
        modules = self.data / 'modules'
        for item in self.fixtures['modules']:
            source = self.a.module_fixtures / item['file']
            assert Path(item['file']).name == item['file'] and sha(source) == item['sha256']
            shutil.copy2(source, modules / item['file'])
        self.report['initialModuleHashes'] = {f.name: sha(f) for f in sorted(modules.glob('*.jar'))}
        for source in (HERE / 'fixtures/valid').rglob('*.json'):
            target = self.data / 'behaviors' / source.relative_to(HERE / 'fixtures/valid')
            self.owned_write(target, source.read_bytes())
        self.report['initialBehaviorHashes'] = {str(p.relative_to(self.data)): sha(p) for p in sorted((self.data / 'behaviors').rglob('*.json'))}
        self.save()

    def manual(self):
        self.start('manual')
        self.command('animus modules list', r'.*qabprobe \[.*\] \| ACTIVE \|.*')
        listing = self.output()
        assert 'Module list: 4;' in listing
        for item in self.fixtures['modules']:
            assert re.search(re.escape(item['id']) + r' \[' + re.escape(item['file']) + r'\] \| ' + item['expectedStartup'] + r' \|', listing), listing
        self.command('animus modules capabilities', r'.*qabehaviorctl:reset \| ACTION \| available=true.*')
        catalog = self.output(); assert 'Module capabilities: 8; built-in: 19;' in catalog and 'qabehaviorbad:staged' not in catalog and 'qabehaviorbad:unsupported' not in catalog
        self.check('module-schema-staging')
        saved = self.create(self.npc_id); self.create(self.control_id)
        self.report['npcUuid'] = saved['uuid']; self.report['initialRevision'] = saved['revision']
        self.snapshot('initial-definition', [self.source(), self.checkpoint(self.npc_id)])
        sources = sorted((self.data / 'behaviors').rglob('*.json'))
        before = {str(path): path.read_bytes() for path in sources}
        self.command('animus behaviors list', r'.*Behavior qabehavior:wait \|.*')
        listing = self.output(); assert 'Behaviors: 10 documents;' in listing and 'Behavior qabehavior:missing' in listing
        original_generation = int(re.search(r'catalog-generation=(\d+)', self.command('animus behaviors status', r'.*Behaviors READY \|.*'))[1])
        for source in (HERE / 'fixtures/invalid').rglob('*.json'):
            self.owned_write(self.data / 'behaviors' / source.relative_to(HERE / 'fixtures/invalid'), source.read_bytes())
        self.command('animus behaviors validate', r'.*Behaviors validate: INVALID \|.*published=false.*')
        offset = self.report['commands'][-1]['logOffset']
        for name in ('unknown-node', 'repeat-limit', 'parallel-limit', 'persistent-scope'):
            wait_log(self.log, rf'.*Behavior diagnostic: behaviors/qabehavior/{name}\.json \|.*', offset, 5)
        diagnostics = self.output()
        status = self.command('animus behaviors status', r'.*Behaviors READY \|.*')
        assert int(re.search(r'catalog-generation=(\d+)', status)[1]) == original_generation
        assert all(Path(path).read_bytes() == value for path, value in before.items())
        self.snapshot('invalid-validation', sorted((self.data / 'behaviors').rglob('*.json')))
        for source in (HERE / 'fixtures/invalid').rglob('*.json'):
            target = self.data / 'behaviors' / source.relative_to(HERE / 'fixtures/invalid')
            assert target.read_bytes() == source.read_bytes(); self.owned_delete(target)
        self.check('readonly-file-validation', acceptedGeneration=original_generation, diagnostics=diagnostics)
        self.action('reset'); execution = self.run_behavior('basic')
        result = self.terminal(execution, 'COMPLETED', 30)
        assert result['npc'] == self.npc_id and result['npc-uuid'] == saved['uuid'] and result['npc-revision'] == saved['revision'] and result['behavior-revision'] == 1 and result['source-hash'] == sha(self.behavior('basic'))
        assert read(self.source())['spawned'] is False
        self.check('manual-dormant-start', execution=result)
        events = self.action('inspect'); tags = basic_events(events, saved['uuid'])
        self.check('typed-arguments-and-conditions', events=events)
        self.check('sequence-repeat-routine-parallel', tags=tags)
        self.action('reset'); execution = self.run_behavior('final-wait')
        running = self.inspect(execution); assert running['state'] == 'RUNNING'
        offset = self.report['commands'][-1]['logOffset']
        due_receipt = wait_log(self.log, r'.*Behavior wait: tick:(\d+).*', offset, 5)
        due = int(re.search(r'Behavior wait: tick:(\d+)', due_receipt)[1])
        state = self.status(); assert counter(state['runtime'], 'tick') < due
        terminal = self.terminal(execution, 'COMPLETED', 15)
        state = self.status(); assert counter(state['runtime'], 'tick') >= due
        self.check('logical-final-wait', dueTick=due, terminal=terminal, status=state)
        refusals = []
        # A wrong literal argument type is rejected when the catalog loads, so it never starts.
        self.action('reset'); invalid = self.catalog_entry('bad-argument', 'INVALID', False, 0, 'none')
        diagnostic = wait_log(self.log, r'.*Behavior diagnostic: behaviors/qabehavior/bad-argument\.json \| behavior-arguments-invalid \| \$\.routines\.main\.arguments/longValue \|.*expected integer.*', 0, 5)
        refused = self.run_behavior('bad-argument', expected='UNAVAILABLE')
        assert not self.action('inspect')['events']; refusals.append({'behavior': 'bad-argument', 'catalog': invalid, 'diagnostic': diagnostic, 'run': refused})
        for name in ('bad-binding',):
            self.action('reset'); execution = self.run_behavior(name); result = self.terminal(execution, 'FAILED')
            offset = self.report['commands'][-1]['logOffset']
            diagnostic = wait_log(self.log, r'.*Behavior diagnostic: execution \|.*longValue.*', offset, 5)
            assert not self.action('inspect')['events']; refusals.append({'behavior': name, 'execution': result, 'diagnostic': diagnostic})
        self.check('resolved-schema-refusal', refusals=refusals)
        missing = self.behavior('missing').read_bytes(); self.run_behavior('missing', expected='UNAVAILABLE')
        assert self.behavior('missing').read_bytes() == missing and not self.action('inspect')['events']
        self.check('unavailable-capability')
        budgets = []
        for name, reason in [('recursive', 'routine-depth'), ('step-budget', 'execution-step-budget')]:
            execution = self.run_behavior(name); result = self.terminal(execution, 'BUDGET_EXHAUSTED', 20)
            assert result['reason'] == reason; budgets.append(result)
        self.idle(); self.check('execution-budgets', executions=budgets)
        execution = self.run_behavior('parallel-conflict'); result = self.terminal(execution, 'FAILED')
        assert result['reason'].startswith('parallel-write-conflict:'); self.idle()
        self.check('parallel-write-conflict', execution=result)
        self.action('reset'); execution = self.hold(); self.run_behavior('basic', expected='BUSY')
        assert self.inspect(execution)['state'] == 'RUNNING'
        self.check('one-active-execution-per-npc', execution=execution)
        self.cancel(execution); retention = self.cancelled_hold(execution)
        self.check('manual-cancel-raw-retention', **retention)
        self.check('explicit-release-quiescence', **self.release())
        self.catalog_revisions()
        self.definition_changes()
        self.capacity()
        self.action('reset'); execution = self.hold()
        self.report['interruptedExecution'] = execution
        files = [self.source(), self.checkpoint(self.npc_id), self.behavior('hold')]
        self.report['beforeRestart'] = {str(p.relative_to(self.data)): sha(p) for p in files}
        self.snapshot('before-restart', files)
        self.stop('manual')
        text = (self.evidence / 'manual-server.log').read_text(errors='replace')
        assert 'Animus behavior QA dependency disable releases {"released":1,"cancelled":1}' in text
        assert not any(event['kind'] == 'record' for event in self.events_in(text)[-2:])

    @staticmethod
    def events_in(text):
        return [json_receipt(line, 'Animus behavior QA event ') for line in text.splitlines() if 'Animus behavior QA event {' in line]

    def catalog_revisions(self):
        self.action('reset'); execution = self.hold(); original = self.behavior('hold').read_bytes(); old = self.inspect(execution)
        self.reload(); assert self.inspect(execution) == old
        self.catalog_entry('hold', 'VALID', True, old['behavior-revision'], old['source-hash'])
        self.check('unchanged-reload-preserves-execution', execution=old)
        bad = b'{"schemaVersion":1,"revision":1,"routines": '
        self.owned_write(self.behavior('hold'), bad); self.reload()
        diagnostic = wait_log(self.log, r'.*Behavior diagnostic: behaviors/qabehavior/hold.json \| behavior-json-invalid \| \$ \|.*', self.report['commands'][-1]['logOffset'], 5)
        entry = self.catalog_entry('hold', 'INVALID', True, old['behavior-revision'], old['source-hash'])
        self.snapshot('malformed-behavior', [self.behavior('hold')])
        assert self.behavior('hold').read_bytes() == bad and self.inspect(execution) == old
        self.check('invalid-reload-retains-program', execution=old, invalidSha256=sha(self.behavior('hold')), entry=entry, diagnostic=diagnostic)
        changed = json.loads(original); changed['routines']['main']['steps'].append({'type': 'wait', 'ticks': 1})
        self.owned_write(self.behavior('hold'), encode(changed)); self.reload()
        diagnostic = wait_log(self.log, r'.*Behavior diagnostic: behaviors/qabehavior/hold.json \| behavior-revision-conflict \| \$\.revision \|.*', self.report['commands'][-1]['logOffset'], 5)
        entry = self.catalog_entry('hold', 'CONFLICT', True, old['behavior-revision'], old['source-hash'])
        assert self.inspect(execution) == old
        self.snapshot('same-revision-conflict', [self.behavior('hold')])
        self.check('same-revision-conflict', execution=old, changedSha256=sha(self.behavior('hold')), entry=entry, diagnostic=diagnostic)
        changed['revision'] = 2; self.owned_write(self.behavior('hold'), encode(changed)); self.reload()
        cancelled = self.cancelled_hold(execution); released = self.release(); self.action('reset')
        replacement = self.hold(); fresh = self.inspect(replacement)
        assert fresh['behavior-revision'] == 2 and fresh['source-hash'] == sha(self.behavior('hold')) and fresh['source-hash'] != old['source-hash']
        self.check('accepted-program-replacement', previous=old, current=fresh, cancellation=cancelled, release=released)
        accepted = self.behavior('hold').read_bytes(); self.owned_delete(self.behavior('hold')); self.reload()
        self.catalog_entry('hold', 'MISSING', False, fresh['behavior-revision'], fresh['source-hash'])
        self.cancelled_hold(replacement); self.release(); self.run_behavior('hold', expected='UNAVAILABLE')
        self.owned_write(self.behavior('hold'), accepted); self.reload(); self.action('reset')
        restored = self.hold(); restored_value = self.inspect(restored)
        assert restored_value['source-hash'] == fresh['source-hash']
        self.cancel(restored); self.cancelled_hold(restored); self.release()
        self.check('source-removal-and-restore', previous=restored_value, sourceSha256=sha(self.behavior('hold')))

    def definition_changes(self):
        self.action('reset'); before = read(self.source()); execution = self.hold()
        self.command('animus edit ' + self.npc_id + ' {"name":"Behavior Edited"}', r'.*Saved; transaction .*')
        after = read(self.source()); assert after['revision'] == before['revision'] + 1 and after['uuid'] == before['uuid']
        self.cancelled_hold(execution); released = self.release()
        self.snapshot('definition-edit', [self.source(), self.checkpoint(self.npc_id)])
        self.check('definition-revision-cancellation', before=before['revision'], after=after['revision'], release=released)
        after['revision'] += 1; after['spawned'] = True; self.owned_write(self.source(), encode(after))
        self.command('animus reload', r'.*reload successful.*'); self.action('reset'); execution = self.hold()
        before = read(self.source()); self.snapshot('before-despawn', [self.source(), self.checkpoint(self.npc_id)])
        self.command('animus despawn ' + self.npc_id, rf'.*Despawn requested for {re.escape(self.npc_id)}; transaction .*')
        after = read(self.source()); assert before['spawned'] and not after['spawned'] and after['revision'] == before['revision'] + 1 and before['uuid'] == after['uuid']
        self.cancelled_hold(execution); released = self.release()
        self.snapshot('after-despawn', [self.source(), self.checkpoint(self.npc_id)])
        self.check('definition-despawn-intent', before=before, after=after, release=released, physicalNpcAssertion=False)
        self.action('reset'); execution = self.hold()
        self.command('animus delete ' + self.npc_id, r'.*Deleted; rollback transaction .*')
        assert not self.source().exists() and not self.checkpoint(self.npc_id).exists()
        self.cancelled_hold(execution); self.release()
        recreated = self.create(self.npc_id); assert recreated['uuid'] != before['uuid']
        self.check('definition-delete-cancellation', oldUuid=before['uuid'], recreatedUuid=recreated['uuid'])

    def capacity(self):
        self.action('reset'); executions = []
        for identity in self.owned_ids[2:]:
            self.create(identity)
        for identity in self.owned_ids[2:6]:
            executions.append(self.hold(identity))
        for execution in executions:
            self.cancel(execution)
        retained = self.cancelled_hold(executions[-1], 4)
        fifth = self.run_behavior('hold', self.owned_ids[6]); current = self.inspect(fifth)
        assert current['state'] == 'RUNNING'
        offset = self.report['commands'][-1]['logOffset']
        wait_log(self.log, r'.*Behavior wait: capacity:invocation.*', offset, 5)
        pending = self.action('inspect'); assert len(pending['pending']) == 4
        state = self.status(); assert counter(state['runtime'], 'callsInFlight') == 4 and counter(state['runtime'], 'retiredCalls') == 4
        self.cancel(fifth); assert self.action('release_all') == {'released': 4, 'cancelled': 4}
        final = self.idle()
        assert len([event for event in self.action('inspect')['events'] if event['kind'] == 'hold-enter']) == 4
        for identity in self.owned_ids[2:]:
            self.command('animus delete ' + identity, r'.*Deleted; rollback transaction .*')
        self.check('invocation-capacity-after-cancel', retained=retained, blocked=fifth, status=state, final=final)

    def restart(self):
        self.start('restart-and-disable')
        assert all(sha(self.data / path) == digest for path, digest in self.report['beforeRestart'].items())
        state = self.idle(); assert not self.action('inspect')['events']
        self.command('animus behaviors inspect ' + self.report['interruptedExecution'], r'.*Behavior inspect: NOT_FOUND \|.*')
        execution = self.hold(); assert execution != self.report['interruptedExecution']
        self.check('managed-restart-no-stack-resume', priorExecution=self.report['interruptedExecution'], freshExecution=execution, startup=state)
        self.command(f'behaviorqa disable {self.npc_id} qabehavior:hold', r'.*Behavior QA disable queued.*')
        receipt = wait_log(self.log, r'.*Behavior QA disabled \{.*', timeout=15)
        value = json_receipt(receipt, 'Behavior QA disabled '); self.disabled = True
        assert not value['cold'] and value['serviceClosed'] and not value['enabled'] and value['durationNanos'] < 2_000_000_000
        assert counter(value['before'], 'callsInFlight') == 1 and counter(value['before'], 'activeExecutions') == 1
        wait_log(self.log, r'.*Behavior QA post-disable run=UNAVAILABLE.*', timeout=10)
        final = self.until(self.status, self.quiescent, 30)
        assert counter(final['runtime'], 'callsInFlight') == 0 and counter(final['runtime'], 'retiredCalls') == 0 and counter(final['runtime'], 'activeExecutions') == 0
        assert counter(final['modules'], 'handlersOutstanding') == 0
        text = self.log.read_text(errors='replace')
        assert 'Animus behavior QA dependency disable releases {"released":1,"cancelled":1}' in text
        events = self.events_in(text); assert [event['kind'] for event in events] == ['hold-enter', 'hold-release'] and events[-1]['arguments']['cancelled'] is True
        self.check('actual-plugin-disable', disable=value, final=final)
        self.stop('restart-and-disable')
        self.report['plannedCleanupAuthorizedByScenario'] = True

    def finish(self, failure):
        if self.owned:
            self.report['stop'] = run(self.a, 'runtime', 'stop', self.name, '--graceful', allow_failure=True)
            self.report['runtimeAfter'] = run(self.a, 'runtime', 'status', self.name, allow_failure=True)
        if self.report.get('port'):
            with socket.socket() as sock:
                self.report['serverPortClosed'] = sock.connect_ex(('127.0.0.1', self.report['port'])) != 0
            if not self.report['serverPortClosed']:
                self.report['status'] = 'failed'
            else:
                paths = [no_symlinks(self.data / relative) for relative in self.owned_files]
                existing = [p for p in paths if p.exists()]
                self.snapshot('offline-owned-cleanup', existing)
                self.report['offlineCleanup'] = {'planned': bool(self.report.get('plannedCleanupAuthorizedByScenario')), 'files': [str(p.relative_to(self.data)) for p in existing]}
                for path in existing:
                    assert path.is_file(); self.owned_delete(path)
                if existing and not self.report['offlineCleanup']['planned']:
                    self.report['status'] = 'failed'
        no_symlinks(self.data)
        self.report['remainingDefinitions'] = [str(p.relative_to(self.data)) for p in (self.data / 'npcs').rglob('*.json')]
        self.report['remainingCheckpoints'] = [str(p.relative_to(self.data)) for p in (self.data / 'state/npc-definitions/last-valid/npcs').rglob('*.json')]
        self.report['remainingBehaviors'] = [str(p.relative_to(self.data)) for p in (self.data / 'behaviors').rglob('*.json')]
        if any(self.report[key] for key in ('remainingDefinitions', 'remainingCheckpoints', 'remainingBehaviors')):
            self.report['status'] = 'failed'
        if self.log.exists():
            target = self.evidence / 'final-server.log'; shutil.copy2(self.log, target)
            self.report['finalServerLog'] = str(target); self.report['finalServerLogSha256'] = sha(target)
        self.report['logFailures'] = [{'log': str(path), 'line': line} for path in sorted(self.evidence.glob('*-server.log')) for line in path.read_text(errors='replace').splitlines() if re.search(r'\bERROR\b|\bSEVERE\b|Exception in thread|^\s+at [\w.$]+\(', line)]
        if self.report['logFailures']:
            self.report['status'] = 'failed'
        self.report['deployedPlugins'] = [{'file': f.name, 'sha256': sha(f)} for f in sorted((self.instance / 'plugins').glob('*.jar'))]
        if self.report.get('plugins') and self.report['deployedPlugins'] != self.report['plugins']:
            self.report['status'] = 'failed'; self.report['artifactError'] = 'Deployed plugin bytes changed'
        if (self.instance / 'server.jar').exists():
            self.report['finalServerSha256'] = sha(self.instance / 'server.jar')
            if self.report['finalServerSha256'] != self.report['serverSha256']:
                self.report['status'] = 'failed'; self.report['artifactError'] = 'Server bytes changed'
        self.report['finalModuleHashes'] = {f.name: sha(f) for f in sorted((self.data / 'modules').glob('*.jar'))}
        if self.report.get('initialModuleHashes') and self.report['finalModuleHashes'] != self.report['initialModuleHashes']:
            self.report['status'] = 'failed'; self.report['artifactError'] = 'Module bytes changed'
        self.report['finalProvenanceProblems'] = []
        comparisons = [(ROOT / name, digest) for name, digest in self.report['sources'].items()]
        comparisons += [(self.evidence / 'sources' / name, digest) for name, digest in self.report['sources'].items()]
        comparisons += [(Path(name), digest) for name, digest in self.report['inputFiles'].items()]
        comparisons += [(self.evidence / name, digest) for name, digest in self.report['archivedFixtures'].items()]
        for path, digest in comparisons:
            no_symlinks(path)
            if not path.is_file() or sha(path) != digest: self.report['finalProvenanceProblems'].append(str(path))
        if self.report['finalProvenanceProblems']: self.report['status'] = 'failed'
        if not failure and self.report['status'] == 'pass':
            self.check('cleanup-and-provenance')
        expected = {gate['id'] for gate in read(HERE / 'acceptance.json')['gates']}
        actual = {item['id'] for item in self.report['checks']}
        self.report['outstandingGates'] = sorted(expected - actual)
        if self.report['outstandingGates']:
            self.report['status'] = 'failed'
        self.report.pop('runningPhase', None); self.save()
        print(json.dumps({'status': self.report['status'], 'evidence': str(self.evidence), 'checks': len(self.report['checks'])}), flush=True)

    def execute(self):
        failure = None
        try:
            self.prepare(); self.cold(); self.install_fixtures(); self.manual(); self.restart()
            self.report['status'] = 'pass'
        except Exception as error:
            failure = error; self.report['status'] = 'failed'; self.report['error'] = repr(error)
        finally:
            try:
                self.finish(failure)
            except Exception as cleanup_error:
                self.report['status'] = 'failed'; self.report['cleanupError'] = repr(cleanup_error); self.save()
                if failure is None: failure = cleanup_error
        if failure:
            raise failure
        assert self.report['status'] == 'pass', self.report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, default=mw.default_binary())
    parser.add_argument('--workspace', type=Path, default=mw.default_workspace())
    parser.add_argument('--build-home', type=Path, help='Only when the build used a MULTIPLEXOR_BUILD_HOME override')
    for name in ('module-fixtures', 'observer'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--build-run', required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--project', choices=('paper', 'spigot', 'folia'), required=True)
    parser.add_argument('--version', choices=('26.1.2', '26.2', '26.3'), required=True)
    parser.add_argument('--instance-name', help='Explicit animus-gate-behaviors-<project>-<version> isolated QA instance')
    Scenario(parser.parse_args()).execute()


if __name__ == '__main__':
    main()
