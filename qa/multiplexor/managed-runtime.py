#!/usr/bin/env python3
"""Exercise real Multiplexor creation, artifact deployment, runtime and console transport.
Uses only isolated instances in a dedicated QA workspace. Does not launch Java itself.
"""
import argparse, hashlib, json, os, re, shutil, socket, subprocess, time
from pathlib import Path


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
    name = f'animus-{project}-{version.replace(".", "-")}'
    evidence_dir = args.workspace / 'animus-evidence' / name
    evidence_dir.mkdir(parents=True, exist_ok=True)
    if (evidence_dir / 'runtime.json').exists():
        shutil.copy2(evidence_dir / 'runtime.json', evidence_dir / f'prior-runtime-{time.time_ns()}.json')
    report = {'runner': 'ServerMultiplexor', 'project': project, 'minecraftVersion': version, 'build': build,
              'channel': source['channel'], 'serverSha256': source['serverSha256'], 'buildRun': args.build_run,
              'scope': 'managed runtime, artifact deployment and public console lifecycle; no gameplay certification', 'checks': [], 'status': 'running'}
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
            if cycle == 0:
                if (data/'npcs/qa/managed-runtime.json').exists():
                    report['checks'].append(console(session, log, 'animus delete managed-runtime', r'.*Deleted; rollback transaction.*'))
                report['checks'].append(console(session, log, 'animus create managed-runtime minecraft:player world 0 64 0 qa Managed Runtime', r'.*Created dormant managed-runtime;.*'))
                saved = json.loads((data/'npcs/qa/managed-runtime.json').read_text())
                report['persistedUuid'] = saved['uuid']
            else:
                report['checks'].append(console(session, log, 'animus inspect managed-runtime', r'.*"name":"Managed Runtime".*'))
                saved = json.loads((data/'npcs/qa/managed-runtime.json').read_text())
                assert saved['uuid'] == report['persistedUuid'] and not saved['spawned']
                report['checks'].append(console(session, log, 'animus delete managed-runtime', r'.*Deleted; rollback transaction.*'))
            report['checks'].append(console(session, log, 'animus validate', r'.*validate successful;.*'))
            run(args, 'runtime', 'stop', name, '--graceful')
            shutil.copy2(log, evidence_dir/f'cycle-{cycle+1}.log')
            report['checks'].append(run(args, 'runtime', 'status', name).strip())
            with socket.socket() as sock:
                assert sock.connect_ex(('127.0.0.1',port)) != 0, 'Owned server is still listening'
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
        (evidence_dir/'runtime.json').write_text(json.dumps(report,indent=2)+'\n')
        (evidence_dir/'commands.json').write_text(json.dumps(args.transcript,indent=2)+'\n')
        (args.workspace/'animus-evidence/commands.json').write_text(json.dumps(args.transcript,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--build-run',required=True)
    parser.add_argument('--build-home',type=Path,help='Same MULTIPLEXOR_BUILD_HOME used for the recorded build')
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--accepted-eula',type=Path,required=True)
    parser.add_argument('--source-evidence',type=Path,help='Optional index of official server download evidence')
    parser.add_argument('--server-root',type=Path,default=Path('.qa/platform-tests'),help='Find platform-smoke.py evidence.json files here')
    parser.add_argument('--versions',nargs='+',default=['26.1.2','26.2','26.3'])
    parser.add_argument('--projects',nargs='+',default=['paper','folia'])
    args=parser.parse_args()
    args.binary=args.binary.resolve();args.workspace=args.workspace.resolve()
    if '.qa' not in args.workspace.parts: parser.error('Use a dedicated .qa workspace')
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
