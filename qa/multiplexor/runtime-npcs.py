#!/usr/bin/env python3
"""Verify production NPCs across an actual Multiplexor-managed restart.
The wrapper deploys a recorded Multiplexor artifact, owns only an isolated QA
instance, and retains stage logs, native-client receipts, definitions and hashes.
"""
import argparse, hashlib, json, os, re, runpy, shutil, signal, socket, subprocess, time
from pathlib import Path

helpers = runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')))
run, wait_log, console = helpers['run'], helpers['wait_log'], helpers['console']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--workspace', type=Path, required=True)
parser.add_argument('--build-home', type=Path)
parser.add_argument('--build-run', required=True)
parser.add_argument('--project', choices=['paper', 'spigot'], required=True)
parser.add_argument('--version', choices=['26.1.2', '26.2', '26.3'], required=True)
parser.add_argument('--instance-name', help='Explicit fresh animus-r<N>-<project>-<version> isolated QA instance')
parser.add_argument('--fixture', type=Path, required=True)
parser.add_argument('--skin-file', type=Path)
parser.add_argument('--combat', action='store_true', help='Also run qa/combat-client.mjs after the production lifecycle scenario')
parser.add_argument('--combat-only', action='store_true', help='Diagnostic combat iteration without repeating the production restart scenario')
parser.add_argument('--control', action='store_true', help='Run native remote-control checks after lifecycle verification')
parser.add_argument('--movement', action='store_true', help='Run authoritative navigation and waypoint checks after lifecycle verification')
parser.add_argument('--motion-only', action='store_true', help='Run selected control/movement scenarios without repeating lifecycle checks')
parser.add_argument('--recovery', action='store_true', help='Make the owned definition invalid while stopped and verify durable last-valid recovery')
parser.add_argument('--inline-recovery', action='store_true', help='Also retain an invalid inline navigation edit across a second managed restart')
parser.add_argument('--readiness-only', action='store_true', help='Diagnostic native-client chunk admission probe; never a full matrix result')
args = parser.parse_args()
if args.inline_recovery: args.recovery = True
if args.combat_only: args.combat = True
if sum([args.motion_only, args.combat_only, args.readiness_only]) > 1: parser.error('Choose only one diagnostic phase mode')
if args.recovery and (args.motion_only or args.combat_only or args.readiness_only): parser.error('Recovery requires the prepare/restart/verify lifecycle')
if args.motion_only and not (args.control or args.movement): args.control = args.movement = True
args.binary = args.binary.resolve(); args.workspace = args.workspace.resolve(); args.transcript = []
assert '.qa' in args.workspace.parts, 'Dedicated QA workspace required'
repo = Path(__file__).resolve().parents[2]
name = f'animus-{args.project}-{args.version.replace(".", "-")}'
if args.instance_name:
    assert re.fullmatch(r'animus-r[1-9][0-9]*-'+args.project+'-'+re.escape(args.version.replace('.','-')),args.instance_name), 'Instance name must bind the requested platform/version'
    name = args.instance_name
instance = args.workspace / 'consumers/plugin-consumers/instances' / name
assert instance.is_dir(), 'First provision this exact instance with managed-runtime.py'
assert run(args, 'instance', 'isolated', name).strip() == 'true', 'Refusing shared instance'
run(args, 'runtime', 'stop', name, '--graceful')
run(args, 'projects', 'deploy', args.build_run, '--to', name, '--target', 'animus:bukkit', '--json')
shutil.copy2(args.fixture, instance/'plugins'/args.fixture.name)
owner = {'uuid':'b2b7d4ea-c517-3a19-8a16-ee03e26cbe9f', 'name':'AnimusProofQA', 'level':4, 'bypassesPlayerLimit':False}
peer = {'uuid':'262b16de-d06b-325b-b115-4ad978f962f2', 'name':'AnimusProofPeer'}
(instance/'ops.json').write_text(json.dumps([owner])+'\n')
(instance/'whitelist.json').write_text(json.dumps([{key:owner[key] for key in ['uuid','name']}, peer])+'\n')
data = instance/'plugins/Animus'
if args.skin_file:
    (data/'skins').mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.skin_file, data/'skins'/args.skin_file.name)
evidence = args.workspace/'animus-evidence'/name/f'production-{time.time_ns()}'
evidence.mkdir(parents=True)
state_file = evidence/'npc-state.json'
log = instance/'logs/latest.log'
props = dict(line.split('=',1) for line in (instance/'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
port = int(props['server-port'])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
fixture_receipt = args.fixture.parent/'build.json'
if fixture_receipt.exists():
    assert json.loads(fixture_receipt.read_text())['fixtureSha256'] == sha(args.fixture), 'Fixture provenance does not match its jar'
    shutil.copy2(fixture_receipt, evidence/'fixture-build.json')
shutil.copy2(Path(__file__), evidence/'wrapper-source.py')
report = {'runner':'Animus native-codec scenarios on Multiplexor-managed runtime', 'project':args.project, 'minecraftVersion':args.version, 'buildRun':args.build_run, 'status':'running', 'port':port, 'serverSha256':sha(instance/'server.jar'), 'plugins':[{'file':p.name,'sha256':sha(p)} for p in (instance/'plugins').glob('*.jar')], 'phases':[]}
report['wrapperSha256'] = sha(evidence/'wrapper-source.py')
if args.combat: report['combatNpcId'] = f'combat-{time.time_ns()}'
if args.movement: report['movementNpcId'] = f'movement-{time.time_ns()}'
if not (args.combat_only or args.motion_only or args.readiness_only): report['runtimeNpcId'] = f'runtime-{time.time_ns()}'
source = json.loads((args.workspace/'animus-evidence'/name/'runtime.json').read_text())
assert report['serverSha256'] == source['serverSha256'], 'Server artifact changed after exact-version verification'
report['serverBuild'] = source['build']; report['serverChannel'] = source['channel']
libraries = instance/('bundler/libraries' if args.project == 'spigot' else 'libraries')
session = None
arena = False
state = None
cleanup_errors = []
expanded_arena = args.control or args.movement
force_bounds = '-32 -32 32 32' if expanded_arena else '-16 -16 16 16'
floor_bounds = '-20 60 -20 20 60 20' if expanded_arena else '-8 60 -8 8 60 8'
air_bounds = '-20 61 -20 20 72 20' if expanded_arena else '-8 61 -8 8 85 8'


def execute_client(script, label, extra):
    command = ['node', str(repo/'qa'/script), '--version', args.version, '--port', str(port), '--libraries', str(libraries), *extra]
    client_log = evidence/f'{label}-client.ndjson'
    timed_out = False
    with client_log.open('w') as out:
        child = subprocess.Popen(command, cwd=repo, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        try: child.wait(timeout=240)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(child.pid, signal.SIGTERM)
            try: child.wait(timeout=5)
            except subprocess.TimeoutExpired: os.killpg(child.pid, signal.SIGKILL); child.wait()
    result = {'phase':label, 'exitCode':child.returncode, 'timedOut':timed_out, 'transcript':str(client_log), 'transcriptSha256':sha(client_log)}
    for line in client_log.read_text().splitlines():
        try: entry = json.loads(line)
        except ValueError: continue
        if entry.get('event') == 'result': result['clientResult'] = entry
    report['phases'].append(result)
    if child.returncode or timed_out: raise AssertionError(f'{label} client failed: {client_log}')
    assert result.get('clientResult', {}).get('status') == 'passed', 'Client did not acknowledge completion'
    return result['clientResult']

try:
    phases = ['readiness'] if args.readiness_only else ['motion'] if args.motion_only else ['combat'] if args.combat_only else ['prepare', 'recover-position', 'verify'] if args.inline_recovery else ['prepare', 'verify']
    for phase in phases:
        if log.exists(): shutil.copy2(log, evidence/f'previous-{phase}-server.log'); log.unlink()
        output = run(args, 'runtime', 'start', name, '--no-console')
        session = re.search(r'tmux session: (\S+)', output).group(1)
        wait_log(log, r'.*Done \([\d.]+s\)!.*')
        report.setdefault('capabilities', []).append(console(session, log, 'animus status', r'.*NPC runtime ready.*'))
        # This entire instance is an isolated QA fixture. Prevent natural
        # hostiles from killing the unattended non-operator observer between
        # cases; explicitly spawned/tagged combat fixtures remain enabled.
        console(session, log, 'gamerule minecraft:spawn_mobs false', r'.*spawn_mobs.*false.*')
        console(session, log, 'gamerule minecraft:advance_time false', r'.*advance_time.*false.*')
        console(session, log, 'time set day', r'.*(Set .*time|Clock .*already at time marker).*')
        difficulty = props.get('difficulty', 'easy')
        assert difficulty in ['peaceful', 'easy', 'normal', 'hard']
        console(session, log, 'difficulty peaceful', r'.*difficulty.*')
        report['arenaRules'] = {'spawn_mobs':False, 'advance_time':False, 'time':'day', 'difficulty':'easy' if difficulty == 'peaceful' else difficulty, 'savedHostilesCleared':True}
        if phase in ['recover-position', 'verify'] and args.recovery:
            expected_field = state['recovery'].get('field', '$.position.x')
            warning = wait_log(log, rf'.*NPC definition recovery:.*{re.escape(report["runtimeNpcId"])}.*{re.escape(expected_field)}.*retained last valid revision.*original file unchanged.*')
            report.setdefault('recoveryWarnings', []).append(str(warning))
        console(session, log, f'forceload add {force_bounds}', r'.*(Marked|already marked|already forced|No chunks were marked).*')
        arena = True
        time.sleep(1)
        console(session, log, f'fill {floor_bounds} stone', r'.*(Successfully filled|No blocks were filled).*')
        console(session, log, f'fill {air_bounds} air', r'.*(Successfully filled|No blocks were filled).*')
        console(session, log, 'setworldspawn 0 61 0', r'.*(Set the world spawn point|Set world spawn).*')
        time.sleep(.2)
        console(session, log, f'difficulty {"easy" if difficulty == "peaceful" else difficulty}', r'.*difficulty.*')
        if phase == 'readiness': execute_client('readiness-client.mjs', 'readiness', ['--event-fixture'])
        if phase in ['prepare', 'recover-position', 'verify']:
            extra = ['--phase', 'prepare' if phase == 'prepare' else 'verify', '--id', report['runtimeNpcId'], '--state-file', str(state_file), '--data-dir', str(data), '--event-fixture']
            if phase == 'recover-position': extra += ['--recovery-only', '--stage-inline-recovery']
            if args.skin_file: extra += ['--skin', args.skin_file.stem, '--skin-file', str(args.skin_file.resolve())]
            result = execute_client('runtime-client.mjs', phase, extra)
            state = result['state']
        if phase == 'prepare':
            document = data/'npcs'/state['group']/f'{state["id"]}.json'
            persisted = json.loads(document.read_text())
            assert persisted['uuid'] == state['uuid'] and persisted['spawned']
            shutil.copy2(document, evidence/'before-restart-npc.json')
            report['persistedSha256'] = sha(document)
        if phase in ['verify', 'combat'] and args.combat:
            execute_client('combat-client.mjs', 'combat', ['--event-fixture', '--id', report['combatNpcId']])
        if phase in ['verify', 'motion']:
            if args.control: execute_client('control-client.mjs', 'control', ['--event-fixture'])
            if args.movement: execute_client('movement-client.mjs', 'movement', ['--event-fixture', '--id', report['movementNpcId'], '--data-dir', str(data)])
        if phase in ['prepare', 'recover-position']:
            run(args, 'runtime', 'stop', name, '--graceful')
            shutil.copy2(log, evidence/f'{phase}-server.log')
            with socket.socket() as sock:
                assert sock.connect_ex(('127.0.0.1', port)) != 0, 'Managed restart did not close the first server process'
            session = None
            if args.recovery and phase == 'prepare':
                document = data/'npcs'/state['group']/f'{state["id"]}.json'
                valid_backup = evidence/'before-restart-npc.json'
                assert document.read_bytes() == valid_backup.read_bytes()
                invalid = json.loads(document.read_text()); invalid['position']['x'] = 'invalid-qa-coordinate'
                document.write_text(json.dumps(invalid, indent=2)+'\n')
                shutil.copy2(document, evidence/'invalid-offline-npc.json')
                state['recovery'] = {'field':'$.position.x', 'validBackup':str(valid_backup), 'validSha256':sha(valid_backup), 'invalidSha256':sha(document)}
                state_file.write_text(json.dumps(state, indent=2)+'\n')
                report['offlineRecovery'] = state['recovery']
            elif phase == 'recover-position':
                assert sha(data/'npcs'/state['group']/f'{state["id"]}.json') == state['recovery']['invalidSha256']
                report['inlineRecovery'] = state['recovery']
    report['status'] = 'pass'
except Exception as error:
    report['status'] = 'failed'; report['error'] = str(error)
    raise
finally:
    # Only the synthetic IDs named in this scenario's receipt may be removed.
    if state is None:
        for item in report['phases']:
            if item.get('clientResult', {}).get('state'): state = item['clientResult']['state']
    if state is None and report.get('runtimeNpcId'): state = {'id':report['runtimeNpcId'], 'group':'runtimeqa'}
    recovery = Path(str(state_file)+'.reload-backup.json')
    if recovery.exists():
        try:
            original = json.loads(recovery.read_text())
            assert original['id'] == report['runtimeNpcId'] and original['group'] == 'runtimeqa'
            (data/'npcs/runtimeqa'/f'{original["id"]}.json').write_bytes(recovery.read_bytes())
            recovery.unlink()
            report['interruptedReloadFixtureRecovered'] = True
        except Exception as error: cleanup_errors.append(str(error))
    if session:
        if args.recovery and state and state.get('recovery'):
            document = data/'npcs'/state['group']/f'{state["id"]}.json'
            if document.exists() and sha(document) == state['recovery']['invalidSha256']:
                shutil.copy2(state['recovery']['validBackup'], document)
                try: console(session, log, 'animus reload', r'.*reload successful.*')
                except Exception as error: cleanup_errors.append(str(error))
        if state:
            for suffix in ['', '-world', '-type', '-name']:
                npc_id = state['id'] + suffix
                document = data/'npcs'/state['group']/f'{npc_id}.json'
                if document.exists():
                    try: console(session, log, f'animus delete {npc_id}', r'.*Deleted; rollback transaction.*')
                    except Exception as error: cleanup_errors.append(str(error))
        if args.combat:
            combat_id = report['combatNpcId']
            if (data/'npcs/combatqa'/f'{combat_id}.json').exists():
                try: console(session, log, f'animus delete {combat_id}', r'.*Deleted; rollback transaction.*')
                except Exception as error: cleanup_errors.append(str(error))
            try: console(session, log, 'kill @e[tag=AnimusCombatQA]', r'.*(Killed|No entity was found|No entities were found).*')
            except Exception as error: cleanup_errors.append(str(error))
        if args.movement:
            for suffix in ['', '-unloaded']:
                movement_id = report['movementNpcId'] + suffix
                if (data/'npcs/movementqa'/f'{movement_id}.json').exists():
                    try: console(session, log, f'animus delete {movement_id}', r'.*Deleted; rollback transaction.*')
                    except Exception as error: cleanup_errors.append(str(error))
        if arena:
            if expanded_arena:
                try:
                    console(session, log, f'fill {air_bounds} air', r'.*(Successfully filled|No blocks were filled).*')
                    console(session, log, f'fill {floor_bounds} stone', r'.*(Successfully filled|No blocks were filled).*')
                except Exception as error: cleanup_errors.append(str(error))
            try: console(session, log, f'forceload remove {force_bounds}', r'.*(Unmarked|not marked|not forced).*')
            except Exception as error: cleanup_errors.append(str(error))
    report['stop'] = run(args, 'runtime', 'stop', name, '--graceful', allow_failure=True)
    report['runtimeAfter'] = run(args, 'runtime', 'status', name, allow_failure=True)
    with socket.socket() as sock:
        sock.settimeout(1); report['serverPortClosed'] = sock.connect_ex(('127.0.0.1', port)) != 0
    if not report['serverPortClosed']: cleanup_errors.append('Owned server port remains open')
    if report['serverPortClosed']:
        owned = []
        if report.get('runtimeNpcId'):
            owned += [('runtimeqa', report['runtimeNpcId'] + suffix) for suffix in ['', '-world', '-type', '-name']]
        if report.get('combatNpcId'): owned.append(('combatqa', report['combatNpcId']))
        if report.get('movementNpcId'):
            owned += [('movementqa', report['movementNpcId'] + suffix) for suffix in ['', '-unloaded']]
        report['ownedDefinitionCleanup'] = []
        for group, npc_id in owned:
            relative = Path('npcs')/group/f'{npc_id}.json'
            source_path, pointer = data/relative, data/'state/npc-definitions/last-valid'/relative
            item = {'id':npc_id, 'sourceAbsent':not source_path.exists(), 'checkpointAbsent':not pointer.exists()}
            report['ownedDefinitionCleanup'].append(item)
            if source_path.exists():
                cleanup_errors.append(f'Owned definition survived command cleanup: {relative}')
                try:
                    source_document = json.loads(source_path.read_text())
                    assert source_document['id'] == npc_id and source_document['group'] == group
                    shutil.copy2(source_path, evidence/f'emergency-{npc_id}.json'); source_path.unlink()
                    item['emergencySourceRemoved'] = True
                except Exception as error: cleanup_errors.append(str(error))
            if pointer.exists():
                cleanup_errors.append(f'Owned recovery pointer survived command cleanup: {relative}')
                try:
                    assert json.loads(pointer.read_text())['path'] == relative.as_posix()
                    shutil.copy2(pointer, evidence/f'emergency-pointer-{npc_id}.json'); pointer.unlink()
                    item['emergencyCheckpointRemoved'] = True
                except Exception as error: cleanup_errors.append(str(error))
        if report.get('movementNpcId'):
            route_file = data/'behaviors/routes'/f'{report["movementNpcId"]}.json'
            if route_file.exists():
                cleanup_errors.append('Owned reusable route survived client cleanup')
                shutil.copy2(route_file, evidence/'emergency-route.json'); route_file.unlink()
    if cleanup_errors: report['status'] = 'failed'; report['cleanupErrors'] = cleanup_errors
    if log.exists(): shutil.copy2(log, evidence/'final-server.log')
    (evidence/'result.json').write_text(json.dumps(report, indent=2)+'\n')
    (evidence/'commands.json').write_text(json.dumps(args.transcript, indent=2)+'\n')
    assert not cleanup_errors, '; '.join(cleanup_errors)
print(json.dumps({'status':report['status'], 'project':args.project, 'version':args.version, 'evidence':str(evidence), 'checks':sum(len(p.get('clientResult', {}).get('checks', [])) for p in report['phases'])}), flush=True)
