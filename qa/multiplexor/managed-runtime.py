#!/usr/bin/env python3
"""Exercise real Multiplexor creation, artifact deployment, runtime and console transport.
Uses only isolated animus-baseline-* instances in the shared Multiplexor workspace. Does not launch Java itself.
"""
import argparse, hashlib, json, os, re, shutil, socket, subprocess, time
from pathlib import Path
import multiplexor_workspace as mw


def run(args, *command, allow_failure=False):
    environment = os.environ.copy()
    if args.build_home:
        environment['MULTIPLEXOR_BUILD_HOME'] = str(args.build_home.resolve())
    deadline = time.monotonic() + 30
    while True:
        completed = subprocess.run([str(args.binary), '--root', str(args.workspace), '--consumer', 'plugin', *command], text=True, capture_output=True, env=environment)
        args.transcript.append({'command': list(command), 'exitCode': completed.returncode, 'stdout': completed.stdout, 'stderr': completed.stderr})
        busy = any(message in completed.stderr + completed.stdout for message in ['Another network command is running', 'Another project operation owns the workspace'])
        if completed.returncode and busy and time.monotonic() < deadline:
            time.sleep(0.25)
            continue
        break
    if completed.returncode and not allow_failure:
        raise RuntimeError(f'Multiplexor command {command[0:2]} failed: {completed.stderr or completed.stdout}')
    return completed.stdout


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wait_log(path, pattern, offset=0, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            content = path.read_text(errors='replace')[offset:]
            match = re.search(pattern, content, re.MULTILINE)
            if match:
                return match.group(0)
        time.sleep(0.1)
    raise AssertionError(f'Timed out waiting for {pattern} in {path}')


def console(session, log, command, pattern):
    offset = len(log.read_text(errors='replace')) if log.exists() else 0
    subprocess.run(['tmux', 'send-keys', '-t', session, '-l', command], check=True)
    subprocess.run(['tmux', 'send-keys', '-t', session, 'Enter'], check=True)
    return wait_log(log, pattern, offset, 30)


def target(args, source):
    project, version, build = source['project'], source['minecraftVersion'], source['build']
    name = mw.baseline_name(project, version)
    evidence_dir = mw.evidence_root(args.workspace) / name
    evidence_dir.mkdir(parents=True, exist_ok=True)
    if (evidence_dir / 'runtime.json').exists():
        previous = evidence_dir / 'lifecycle-history' / str(time.time_ns())
        previous.mkdir(parents=True)
        for pattern in ['runtime.json', 'commands.json', 'cycle-*.log', 'pre-start-*.log', 'recovery-*.json']:
            for old in evidence_dir.glob(pattern): shutil.copy2(old, previous / old.name)
        shutil.copy2(evidence_dir / 'runtime.json', evidence_dir / f'prior-runtime-{time.time_ns()}.json')
    npc_id = f'managed-{time.time_ns()}'
    report = {'runner': 'ServerMultiplexor', 'project': project, 'minecraftVersion': version, 'build': build,
              'channel': source['channel'], 'serverSha256': source['serverSha256'], 'buildRun': args.build_run,
              'scope': 'managed runtime, artifact deployment and public console lifecycle; no gameplay certification', 'npcId':npc_id, 'checks': [], 'status': 'running'}
    owned = False
    try:
        instance = args.workspace / 'consumers/plugin-consumers/instances' / name
        if not instance.exists():
            jar = Path(source['evidence']).resolve().parent / 'server.jar'
            assert sha(jar) == source['serverSha256'], 'Pinned server hash mismatch'
            run(args, 'server', 'create', name, '--jar', str(jar), '--type', project, '--mc', version, '--isolated')
        else:
            assert run(args, 'instance', 'isolated', name).strip() == 'true', 'Refusing shared instance'
        assert sha(instance / 'server.jar') == source['serverSha256'], 'Managed instance jar differs from the pinned official server build'
        run(args, 'runtime', 'stop', name, '--graceful')
        owned = True
        run(args, 'runtime', 'settings', 'set-java', str(args.java_home / 'bin/java'), '--instance', name)
        run(args, 'runtime', 'settings', 'set-heap', '1G', '--instance', name)
        run(args, 'runtime', 'settings', 'set-preset', 'vanilla', '--instance', name)
        run(args, 'gameplay', 'prepare', name)
        port = free_port()
        run(args, 'instance', 'port', name, str(port))
        report['port'] = port
        shutil.copy2(args.accepted_eula, instance / 'eula.txt')
        data = instance / 'plugins/Animus'
        data.mkdir(parents=True, exist_ok=True)
        definition = data/'npcs/qa'/f'{npc_id}.json'
        assert not definition.exists(), 'Refusing an existing definition'
        (data / 'config.toml').write_text('statusMessage = "Multiplexor QA"\npacketProofEnabled = true\n')
        properties = instance / 'server.properties'
        existing = properties.read_text().splitlines()
        overrides = {'server-ip':'127.0.0.1','online-mode':'false','level-type':'minecraft:flat','generator-settings':json.dumps({'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:stone','height':59},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains'},separators=(',',':')), 'generate-structures':'false','view-distance':'2','simulation-distance':'2','max-players':'8','spawn-protection':'0','pause-when-empty-seconds':'-1'}
        properties.write_text('\n'.join(line for line in existing if line.partition('=')[0] not in overrides)+'\n'+'\n'.join(f'{key}={value}' for key,value in overrides.items())+'\n')
        cache = instance / 'cache'
        cache.mkdir(exist_ok=True)
        mojang = Path.home() / '.gradle/caches/fabric-loom' / version / 'minecraft-server.jar'
        if mojang.exists(): shutil.copy2(mojang, cache / f'mojang_{version}.jar')
        run(args, 'projects', 'destination', name, '--instance', name, '--platform', 'plugin', '--mc', version)
        run(args, 'projects', 'deploy', args.build_run, '--to', name, '--target', 'animus:bukkit', '--json')
        report['plugins'] = [{'file':p.name,'sha256':sha(p)} for p in (instance/'plugins').glob('*.jar')]
        for cycle in range(2):
            log = instance / 'logs/latest.log'
            if log.exists():
                # The managed start returns before Minecraft rotates latest.log. Never accept a prior ready marker.
                shutil.copy2(log, evidence_dir / f'pre-start-{cycle+1}.log')
                log.unlink()
            output = run(args, 'runtime', 'start', name, '--no-console')
            session = re.search(r'tmux session: (\S+)', output).group(1)
            report['checks'].append(wait_log(log, r'.*Done \([\d.]+s\)!.*'))
            report['checks'].append(console(session, log, 'animus status', rf'.*Animus 0\.1\.0 \| BUKKIT \| Minecraft {re.escape(version)} \| Multiplexor QA.*'))
            expected = r'Packet proof unavailable on Folia' if project == 'folia' else rf'Packet proof ready \| Minecraft {re.escape(version)}'
            report['checks'].append(console(session, log, 'animus proof status', rf'.*{expected}.*'))
            if project == 'folia':
                report['checks'].append(console(session, log, 'animus status', r'.*NPC runtime unavailable on Folia.*'))
                report['checks'].append(console(session, log, 'animus combat status', r'.*Combat unavailable: .*Folia.*'))
                report['checks'].append(console(session, log, f'animus navigation {npc_id} status', r'.*Navigation .*unavailable on Folia.*'))
            if cycle == 0:
                report['checks'].append(console(session, log, f'animus create {npc_id} minecraft:player world 0 64 0 qa Managed Runtime', rf'.*Created dormant {npc_id};.*'))
                saved = json.loads(definition.read_text())
                report['persistedUuid'] = saved['uuid']
                if project == 'folia':
                    before = definition.read_bytes()
                    report['checks'].append(console(session, log, f'animus spawn {npc_id}', r'.*Cannot activate: NPC runtime unavailable on Folia.*'))
                    assert definition.read_bytes() == before, 'Unsupported Folia activation changed the persisted definition'
                    report['checks'].append(console(session, log, f'animus edit {npc_id} {{"name":"Folia Dormant"}}', r'.*Saved; transaction.*'))
                    edited = json.loads(definition.read_text())
                    assert edited['name'] == 'Folia Dormant' and not edited['spawned'] and edited['uuid'] == saved['uuid']
                    assert edited['revision'] == saved['revision'] + 1
                    report['dormantEditRevision'] = edited['revision']
            else:
                expected_name = 'Folia Dormant' if project == 'folia' else 'Managed Runtime'
                inspection = console(session, log, f'animus inspect {npc_id}', rf'.*"name":"{expected_name}".*')
                report['checks'].append(inspection)
                saved = json.loads(re.search(r'\{.*\}', inspection).group(0))
                assert saved['uuid'] == report['persistedUuid'] and not saved['spawned']
                if project == 'folia': assert saved['revision'] == report['dormantEditRevision']
                if args.recovery:
                    report['checks'].append(wait_log(log, rf'.*NPC definition recovery:.*{npc_id}.*{re.escape(report["recoveryField"])}.*original file unchanged.*'))
                    assert sha(definition) == report['recoveryInvalidSha256'], 'Recovery overwrote invalid source bytes'
                    report['checks'].append(console(session, log, 'animus status', r'.*Definition file problems: 1.*'))
                    report['checks'].append(console(session, log, f'animus edit {npc_id} {{"name":"{expected_name}"}}', r'.*Conflict:.*'))
                    assert sha(definition) == report['recoveryInvalidSha256']
                    definition.write_bytes((evidence_dir/'recovery-valid.json').read_bytes())
                    assert sha(definition) == report['recoveryValidSha256']
                    report['checks'].append(console(session, log, 'animus reload', r'.*reload successful.*'))
                    report['checks'].append(console(session, log, f'animus edit {npc_id} {{"name":"{expected_name}"}}', r'.*Saved; transaction.*'))
                    repaired = json.loads(definition.read_text())
                    assert repaired['uuid'] == saved['uuid'] and repaired['revision'] == saved['revision'] + 1 and not repaired['spawned']
                    report['recoveryVerified'] = True
                report['checks'].append(console(session, log, f'animus delete {npc_id}', r'.*Deleted; rollback transaction.*'))
            report['checks'].append(console(session, log, 'animus validate', r'.*validate successful;.*'))
            if cycle == 0 and args.inline_recovery:
                valid = definition.read_bytes(); before = json.loads(valid)
                (evidence_dir/'recovery-valid.json').write_bytes(valid)
                report['recoveryValidSha256'] = sha(definition)
                invalid = dict(before)
                invalid['modules'] = {**invalid.get('modules', {}), 'animus:navigation': {'enabled':True, 'mode':'walk', 'speed':'bad', 'points':[{'x':0, 'y':64, 'z':0}]}}
                definition.write_text(json.dumps(invalid, indent=2)+'\n')
                shutil.copy2(definition, evidence_dir/'recovery-invalid.json')
                report['recoveryInvalidSha256'] = sha(definition)
                report['recoveryField'] = '$.modules.animus:navigation'
                report['checks'].append(console(session, log, 'animus reload', r'.*\$\.modules\.animus:navigation.*'))
                inspection = console(session, log, f'animus inspect {npc_id}', rf'.*"id":"{npc_id}".*')
                assert json.loads(re.search(r'\{.*\}', inspection).group(0)) == before
                assert sha(definition) == report['recoveryInvalidSha256']
                report['liveInlineRecoveryVerified'] = True
            run(args, 'runtime', 'stop', name, '--graceful')
            shutil.copy2(log, evidence_dir/f'cycle-{cycle+1}.log')
            report['checks'].append(run(args, 'runtime', 'status', name).strip())
            with socket.socket() as sock:
                assert sock.connect_ex(('127.0.0.1',port)) != 0, 'Owned server is still listening'
            if cycle == 0 and args.recovery and not args.inline_recovery:
                shutil.copy2(definition, evidence_dir/'recovery-valid.json')
                report['recoveryValidSha256'] = sha(definition)
                invalid = json.loads(definition.read_text()); invalid['position']['x'] = 'invalid-qa-coordinate'
                definition.write_text(json.dumps(invalid, indent=2)+'\n')
                shutil.copy2(definition, evidence_dir/'recovery-invalid.json')
                report['recoveryInvalidSha256'] = sha(definition)
                report['recoveryField'] = '$.position.x'
        assert not definition.exists()
        assert not (data/'state/npc-definitions/last-valid/npcs/qa'/f'{npc_id}.json').exists()
        report['ownedDefinitionAbsent'] = report['ownedCheckpointAbsent'] = True
        report['status'] = 'pass'
        print(f'PASS {name} {source["channel"]} build{build}', flush=True)
        return report
    except Exception as error:
        report['status']='failed'
        report['error']=str(error)
        raise
    finally:
        if owned:
            run(args, 'runtime', 'stop', name, '--graceful', allow_failure=True)
            if report.get('port'):
                with socket.socket() as sock:
                    report['serverPortClosed'] = sock.connect_ex(('127.0.0.1', report['port'])) != 0
                if not report['serverPortClosed']:
                    report['status'] = 'failed'; report['cleanupError'] = 'Owned server port remains open'
                else:
                    owned_source = instance/'plugins/Animus/npcs/qa'/f'{npc_id}.json'
                    owned_pointer = instance/'plugins/Animus/state/npc-definitions/last-valid/npcs/qa'/f'{npc_id}.json'
                    for candidate, kind in [(owned_source, 'source'), (owned_pointer, 'checkpoint')]:
                        if not candidate.exists(): continue
                        value = json.loads(candidate.read_text())
                        assert (value.get('id') == npc_id and value.get('group') == 'qa') if kind == 'source' else value.get('path') == f'npcs/qa/{npc_id}.json'
                        shutil.copy2(candidate, evidence_dir/f'recovery-emergency-{kind}.json'); candidate.unlink()
                        report['status'] = 'failed'; report.setdefault('emergencyCleanup', []).append(kind)
        (evidence_dir/'runtime.json').write_text(json.dumps(report,indent=2)+'\n')
        (evidence_dir/'commands.json').write_text(json.dumps(args.transcript,indent=2)+'\n')
        (mw.evidence_root(args.workspace)/'commands.json').write_text(json.dumps(args.transcript,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=mw.default_binary())
    parser.add_argument('--workspace',type=Path,default=mw.default_workspace())
    parser.add_argument('--build-run',required=True)
    parser.add_argument('--build-home',type=Path,help='Only when the recorded build used a MULTIPLEXOR_BUILD_HOME override')
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--accepted-eula',type=Path,required=True)
    parser.add_argument('--source-evidence',type=Path,help='Optional index of official server download evidence')
    parser.add_argument('--server-root',type=Path,default=Path('.qa/platform-tests'),help='Find platform-smoke.py evidence.json files here')
    parser.add_argument('--versions',nargs='+',default=['26.1.2','26.2','26.3'])
    parser.add_argument('--projects',nargs='+',default=['paper','folia'])
    parser.add_argument('--recovery',action='store_true',help='Verify durable last-valid recovery after an invalid offline definition edit')
    parser.add_argument('--inline-recovery',action='store_true',help='Reject invalid inline navigation data live, then verify durable recovery after restart')
    args=parser.parse_args()
    if args.inline_recovery: args.recovery = True
    args.binary=args.binary.resolve();args.workspace=args.workspace.resolve()
    if not re.search(r'(?m)^eula=true$',args.accepted_eula.read_text()):parser.error('Existing accepted EULA required')
    args.transcript=[]
    if args.source_evidence:
        records=json.loads(args.source_evidence.read_text())['results']
    else:
        records=[]
        for evidence in sorted(args.server_root.rglob('evidence.json')):
            record=json.loads(evidence.read_text())
            record['evidence']=str(evidence)
            records.append(record)
    if not records:parser.error('No verified server downloads; run qa/platform-smoke.py first')
    for record in records:
        if record['project'] in args.projects and record['minecraftVersion'] in args.versions:
            target(args,record)


if __name__ == '__main__': main()
