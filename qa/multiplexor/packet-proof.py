#!/usr/bin/env python3
"""Attach Animus's exact-codec client to a real Multiplexor-managed Paper instance.
Multiplexor owns creation/deployment separately and owns start/stop here. The native
client is external to Mineflayer; its results are recorded as that distinct test path.
"""
import argparse, hashlib, json, re, runpy, shutil, socket, subprocess, time
from pathlib import Path

helpers=runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')))
run,wait_log,console=helpers['run'],helpers['wait_log'],helpers['console']
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--binary',type=Path,required=True)
parser.add_argument('--workspace',type=Path,required=True)
parser.add_argument('--build-home',type=Path)
parser.add_argument('--version',choices=['26.1.2','26.2','26.3'],required=True)
parser.add_argument('--skin-file',type=Path)
parser.add_argument('--fixture',type=Path)
parser.add_argument('--navigation',action='store_true',help='Requires the proof path diagnostic in the deployed build')
parser.add_argument('--skin-race',action='store_true',help='Exercise pending signed-skin spawn cancellation')
args=parser.parse_args()
args.binary=args.binary.resolve();args.workspace=args.workspace.resolve();args.transcript=[]
assert '.qa' in args.workspace.parts,'Dedicated QA workspace required'
repo=Path(__file__).resolve().parents[2]
name=f'animus-paper-{args.version.replace(".","-")}'
instance=args.workspace/'consumers/plugin-consumers/instances'/name
assert instance.is_dir(),'First create/deploy the exact instance with managed-runtime.py'
assert run(args,'instance','isolated',name).strip()=='true','Refusing shared instance'
run(args,'runtime','stop',name,'--graceful')
owner={'uuid':'b2b7d4ea-c517-3a19-8a16-ee03e26cbe9f','name':'AnimusProofQA','level':4,'bypassesPlayerLimit':False}
peer={'uuid':'262b16de-d06b-325b-b115-4ad978f962f2','name':'AnimusProofPeer'}
(instance/'ops.json').write_text(json.dumps([owner])+'\n')
(instance/'whitelist.json').write_text(json.dumps([{key:owner[key] for key in ['uuid','name']},peer])+'\n')
if args.skin_file:
    skins=instance/'plugins/Animus/skins';skins.mkdir(parents=True,exist_ok=True)
    shutil.copy2(args.skin_file,skins/args.skin_file.name)
if args.fixture:shutil.copy2(args.fixture,instance/'plugins'/args.fixture.name)
evidence=args.workspace/'animus-evidence'/name/f'native-{time.time_ns()}'
evidence.mkdir(parents=True)
log=instance/'logs/latest.log'
if log.exists():shutil.copy2(log,evidence/'previous-server.log');log.unlink()
report={'runner':'external Animus native-codec client on Multiplexor-managed runtime','minecraftVersion':args.version,'status':'running','plugins':[{'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (instance/'plugins').glob('*.jar')]}
report['serverSha256']=hashlib.sha256((instance/'server.jar').read_bytes()).hexdigest()
source=json.loads((args.workspace/'animus-evidence'/name/'runtime.json').read_text())
assert report['serverSha256']==source['serverSha256'],'Managed server jar changed after exact-version lifecycle validation'
report['serverBuild']=source['build'];report['serverChannel']=source['channel']
session=None
arena=False
try:
    output=run(args,'runtime','start',name,'--no-console')
    session=re.search(r'tmux session: (\S+)',output).group(1)
    wait_log(log,r'.*Done \([\d.]+s\)!.*')
    report['capability']=console(session,log,'animus proof status',r'.*Packet proof ready.*')
    console(session,log,'forceload add -16 -16 16 16',r'.*(Marked|already marked|already forced).*')
    arena=True
    time.sleep(1)
    console(session,log,'fill -8 60 -8 8 60 8 stone',r'.*(Successfully filled|No blocks were filled).*')
    console(session,log,'fill -8 61 -8 8 85 8 air',r'.*(Successfully filled|No blocks were filled).*')
    console(session,log,'setworldspawn 0 61 0',r'.*(Set the world spawn point|Set world spawn).*')
    props=dict(line.split('=',1) for line in (instance/'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    port=props['server-port'];report['port']=int(port)
    command=['node',str(repo/'qa/packet-client.mjs'),'--version',args.version,'--port',port,'--libraries',str(instance/'libraries')]
    if args.skin_file:command+=['--skin',args.skin_file.stem,'--skin-file',str(args.skin_file.resolve())]
    if args.fixture:command+=['--event-fixture']
    if args.navigation:command+=['--navigation']
    if args.skin_race:command+=['--skin-race']
    with (evidence/'client.ndjson').open('w') as out:
        result=subprocess.run(command,cwd=repo,stdout=out,stderr=subprocess.STDOUT,timeout=180)
    report['clientExitCode']=result.returncode
    for line in (evidence/'client.ndjson').read_text().splitlines():
        try:entry=json.loads(line)
        except ValueError:continue
        if entry.get('event')=='result':report['clientResult']=entry
    assert result.returncode==0,f'Native client failed: {evidence}/client.ndjson'
    report['status']='pass'
except Exception as error:
    report['status']='failed';report['error']=str(error)
    raise
finally:
    if arena:
        try:console(session,log,'kill @e[type=minecraft:cow,tag=AnimusRegistryQA]',r'.*(Killed|No entity was found|No entities were found).*')
        except Exception as error:report['cowCleanupError']=str(error)
        try:console(session,log,'forceload remove -16 -16 16 16',r'.*(Unmarked|not marked|not forced).*')
        except Exception as error:report['arenaCleanupError']=str(error)
    report['stop']=run(args,'runtime','stop',name,'--graceful',allow_failure=True)
    report['runtimeAfter']=run(args,'runtime','status',name,allow_failure=True)
    props=dict(line.split('=',1) for line in (instance/'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    with socket.socket() as sock:
        sock.settimeout(1)
        report['serverPortClosed']=sock.connect_ex(('127.0.0.1',int(props['server-port']))) != 0
    if not report['serverPortClosed']:
        report['status']='failed';report['cleanupError']='Owned server port remains open'
    if report.get('arenaCleanupError') or report.get('cowCleanupError'):
        report['status']='failed'
    if log.exists():shutil.copy2(log,evidence/'server.log')
    (evidence/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    (evidence/'commands.json').write_text(json.dumps(args.transcript,indent=2)+'\n')
    assert report['serverPortClosed'],'Owned server did not stop'
    assert not report.get('arenaCleanupError') and not report.get('cowCleanupError'),'Owned QA arena cleanup failed'
print(json.dumps({'status':report['status'],'version':args.version,'evidence':str(evidence),'client':report.get('clientResult')}),flush=True)
