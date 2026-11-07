#!/usr/bin/env python3
"""Exact Folia stationary previews on isolated Multiplexor-managed servers.
No force-loading, remote region task, global terrain query, or direct JVM launch.
Fixture setup explicitly teleports synthetic viewers; no-load claims exclude setup.
"""
import argparse, hashlib, json, os, re, runpy, shutil, signal, socket, subprocess, time, uuid
from pathlib import Path
import multiplexor_workspace as mw

helpers=runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')))
run,wait_log,console=helpers['run'],helpers['wait_log'],helpers['console']
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--binary',type=Path,default=mw.default_binary());p.add_argument('--workspace',type=Path,default=mw.default_workspace())
p.add_argument('--build-home',type=Path,help='Only when the build used a MULTIPLEXOR_BUILD_HOME override')
for name in ['fixture','skin-file']:p.add_argument('--'+name,type=Path,required=True)
p.add_argument('--build-run',required=True)
p.add_argument('--version',choices=['26.1.2','26.2','26.3'],required=True)
p.add_argument('--instance-name',help='Explicit fresh animus-r<N>-folia-<version> isolated QA instance')
p.add_argument('--phase',choices=['main','bounds','shutdown','all'],default='all')
a=p.parse_args();a.binary=a.binary.resolve();a.workspace=a.workspace.resolve();a.transcript=[]
root=Path(__file__).resolve().parents[2];name=mw.baseline_name('folia',a.version)
if a.instance_name:
    assert re.fullmatch(r'animus-(?:r[1-9][0-9]*|gate)-folia-'+re.escape(a.version.replace('.','-')),a.instance_name),'Instance name must bind the requested Folia version'
    name=a.instance_name
instance=a.workspace/'consumers/plugin-consumers/instances'/name
assert instance.is_dir();assert run(a,'instance','isolated',name).strip()=='true'
run(a,'runtime','stop',name,'--graceful')
run(a,'projects','deploy',a.build_run,'--to',name,'--target','animus:bukkit','--json')
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
fixture=json.loads((a.fixture.parent/'build.json').read_text());assert sha(a.fixture)==fixture['fixtureSha256']
plugins=list((instance/'plugins').glob('*.jar'))
animus=[f for f in plugins if f.name.startswith('Animus v')];assert len(animus)==1
assert sha(animus[0])==fixture['pluginSha256'],'Fixture must bind the deployed exact artifact'
assert all(f==animus[0] or f.name=='AnimusFoliaFixture.jar' for f in plugins),'Unexpected plugin in isolated Folia instance'
shutil.copy2(a.fixture,instance/'plugins'/a.fixture.name)
data=instance/'plugins/Animus';(data/'skins').mkdir(parents=True,exist_ok=True)
shutil.copy2(a.skin_file,data/'skins'/a.skin_file.name)
assert 'packetProofEnabled = true' in (data/'config.toml').read_text()
# The persisted Folia runtime watches every player; it stays off so transport counts belong to the proof alone.
config=(data/'config.toml').read_text()
if '[npcs]' not in config:(data/'config.toml').write_text(config.rstrip('\n')+'\n[npcs]\nenabled = false\n')
assert '[npcs]\nenabled = false' in (data/'config.toml').read_text()
players=[]
for index in range(10):
    username=f'AnimusFolia{index}'
    player_id=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+username).encode()).digest(),version=3)
    players.append({'uuid':str(player_id),'name':username})
(instance/'whitelist.json').write_text(json.dumps(players)+'\n')
(instance/'ops.json').write_text(json.dumps([{**entry,'level':4,'bypassesPlayerLimit':False} for entry in players])+'\n')
properties=instance/'server.properties'
lines=properties.read_text().splitlines()
overrides={'max-players':'12','difficulty':'peaceful','gamemode':'creative','white-list':'true','server-ip':'127.0.0.1','online-mode':'false'}
properties.write_text('\n'.join(line for line in lines if line.partition('=')[0] not in overrides)+'\n'+'\n'.join(k+'='+v for k,v in overrides.items())+'\n')
props=dict(line.split('=',1) for line in properties.read_text().splitlines() if '=' in line and not line.startswith('#'))
port=int(props['server-port']);source=json.loads((mw.evidence_root(a.workspace)/name/'runtime.json').read_text())
assert sha(instance/'server.jar')==source['serverSha256']
evidence=mw.evidence_root(a.workspace)/name/f'folia-proof-{time.time_ns()}';evidence.mkdir(parents=True)
shutil.copy2(a.fixture.parent/'build.json',evidence/'fixture-build.json')
shutil.copy2(Path(__file__),evidence/'wrapper-source.py')
owned_sources=[root/'qa/folia-proof/client.mjs',root/'qa/protocol-client/client.mjs',root/'qa/protocol-client/NativeProtocolClient.java',root/'qa/protocol-client'/('v26_3' if a.version=='26.3' else 'v26_1_26_2')/'ProtocolRevision.java']
for relative in fixture['sources']:owned_sources.append(root/relative)
for file in owned_sources:
    target=evidence/'sources'/file.relative_to(root);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
report={'schemaVersion':1,'runner':'ServerMultiplexor with external exact-native client','scope':'viewer-private stationary preview','project':'folia','minecraftVersion':a.version,'buildRun':a.build_run,'pluginSha256':sha(animus[0]),'fixtureSha256':sha(a.fixture),'wrapperSha256':sha(Path(__file__)),'serverSha256':sha(instance/'server.jar'),'serverBuild':source['build'],'serverChannel':source['channel'],'port':port,'status':'running','plugins':[{'file':f.name,'sha256':sha(f)} for f in (instance/'plugins').glob('*.jar')],'phases':[],'generalRuntimeEnabled':False,'graphicalAppearance':'unverified','sources':{str(f.relative_to(root)):sha(f) for f in owned_sources}}
log=instance/'logs/latest.log';session=None;failure=None
gate_ids=['ownership','virtual-presentation','authoritative-interaction','viewer-lifecycle','unavailable-terrain','queued-cancellation','bounds','retirement-shutdown','general-disabled']
def checkpoint():
    temporary=evidence/'result.json.tmp';temporary.write_text(json.dumps(report,indent=2)+'\n');temporary.replace(evidence/'result.json')
    (evidence/'commands.json').write_text(json.dumps(a.transcript,indent=2)+'\n')
checkpoint()
try:
    for phase in (['main','bounds','shutdown'] if a.phase=='all' else [a.phase]):
        report['runningPhase']=phase;checkpoint()
        if log.exists():shutil.copy2(log,evidence/f'previous-{phase}.log');log.unlink()
        output=run(a,'runtime','start',name,'--no-console');session=re.search(r'tmux session: (\S+)',output).group(1)
        wait_log(log,r'.*Done \([\d.]+s\)!.*')
        disabled=[]
        for command,pattern in [
            ('animus status',r'.*NPC runtime disabled in config\.toml.*'),
            ('animus proof status',r'.*Packet proof unavailable on Folia.*'),
            ('animus proof control status',r'.*Proof presentation requires a player viewer.*'),
            ('animus combat status',r'.*Combat unavailable: .*Folia.*'),
            ('animus navigation status folia-qa',r'.*Navigation folia-qa: disabled in config\.toml.*')]:
            disabled.append(console(session,log,command,pattern))
        ready=console(session,log,'animus proof folia status',r'.*Folia stationary proof ready.*admitted=0/8.*')
        transcript=evidence/f'{phase}-client.ndjson'
        command=['node',str(root/'qa/folia-proof/client.mjs'),'--version',a.version,'--port',str(port),'--libraries',str(instance/'libraries'),'--phase',phase,'--skin',a.skin_file.stem,'--skin-file',str(a.skin_file.resolve())]
        timed_out=False
        with transcript.open('w') as out:
            child=subprocess.Popen(command,cwd=root,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
            try:child.wait(timeout=420)
            except subprocess.TimeoutExpired:
                timed_out=True
                os.killpg(child.pid,signal.SIGTERM)
                try:child.wait(timeout=5)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait()
        record={'phase':phase,'exitCode':child.returncode,'timedOut':timed_out,'transcript':str(transcript),'transcriptSha256':sha(transcript),'disabledReceipts':disabled,'readyReceipt':ready}
        for line in transcript.read_text().splitlines():
            try:entry=json.loads(line)
            except ValueError:continue
            if entry.get('event')=='result':record['clientResult']=entry
        report['phases'].append(record)
        checkpoint()
        time.sleep(.25)
        record['cleanupReceipt']=console(session,log,'foliaqa servicestatus' if phase=='shutdown' else 'animus proof folia status',r'.*Folia stationary proof '+('unavailable: closed' if phase=='shutdown' else 'ready')+r'.*admitted=0/8.*pending-spawns=0/8.*command-work=0/8.*skin-reads=0/2.*')
        record['fixtureReceipt']=console(session,log,'foliaqa counters',r'.*Folia QA counters.*failures=0 scheduled=0 states=0 probes=0 joins=0 io-held=0 network-held=0.*')
        assert child.returncode==0 and record.get('clientResult',{}).get('status')=='passed',f'{phase} failed; see {transcript}'
        start=time.monotonic();record['stop']=run(a,'runtime','stop',name,'--graceful');record['stopSeconds']=time.monotonic()-start;session=None
        shutil.copy2(log,evidence/f'{phase}-server.log')
        record['serverLog']=str(evidence/f'{phase}-server.log');record['serverLogSha256']=sha(evidence/f'{phase}-server.log')
        with socket.socket() as sock:record['serverPortClosed']=sock.connect_ex(('127.0.0.1',port))!=0
        assert record['serverPortClosed']
    report['status']='partial'
except Exception as error:
    failure=error;report['status']='failed';report['error']=str(error)
finally:
    start=time.monotonic();report['stop']=run(a,'runtime','stop',name,'--graceful',allow_failure=True);report['finalStopSeconds']=time.monotonic()-start
    report['runtimeAfter']=run(a,'runtime','status',name,allow_failure=True)
    with socket.socket() as sock:report['serverPortClosed']=sock.connect_ex(('127.0.0.1',port))!=0
    if not report['serverPortClosed']:report['status']='failed';report['cleanupError']='Owned port remains open'
    if log.exists():shutil.copy2(log,evidence/'final-server.log')
    ids={c['id'] for phase in report['phases'] for c in phase.get('clientResult',{}).get('checks',[])}
    mapping={
        'ownership':['separate-region-ownership','owner-attack-event'],
        'virtual-presentation':['private-wire-bodies','signed-texture-payload'],
        'authoritative-interaction':['owner-attack-event','server-ray-ignores-forged-point','off-ray-forged-point-rejected','walked-overreach-rejected','occluded-ray-rejected','cancellable-owner-event','interaction-rate-bounded','foreign-viewer-ids-inert','preview-does-not-damage-viewer'],
        'viewer-lifecycle':['cross-region-teleport-retirement','quit-reconnect-generation-isolation','permission-revocation-retirement','death-respawn-retirement','world-change-retirement','visible-nearby-teleport-retirement','visible-same-teleport-retirement','reentrant-same-position-teleport-retirement'],
        'unavailable-terrain':['unsupported-ground-refused-before-spawn','unavailable-footprint-refused-no-load','unowned-footprint-refused-no-load'],
        'queued-cancellation':['queued-spawn-clear-before-owner-admission','reentrant-clear-revokes-authority','reentrant-replacement-isolated','queued-skin-clear-before-completion','pending-spawn-teleport-before-owner-admission','pending-same-position-teleport-retirement'],
        'bounds':['eight-viewers-ninth-refused','lifetime-admission-ceiling','command-callback-bound','concurrent-skin-read-bound','slow-netty-retirement-bound'],
        'retirement-shutdown':['explicit-clear-entity-and-profile-cleanup','scheduler-retirement-cleanup','plugin-disable-pending-work-cleanup','disabled-late-completions-inert','no-live-wire-bodies-or-profiles-after-cleanup']}
    report['limitations']=['Viewer-private stationary preview only; the persisted Folia region runtime is disabled here and covered by live-features.mjs','Graphical appearance and performance unverified','Unowned terrain covered through the production lower-level admission method, not an actual command at an unowned or partially owned four-chunk boundary','Ticket counts are locked before/after snapshots; transient ticket absence is unverified']
    report['gates']=[]
    for gate in gate_ids:
        required=mapping.get(gate,[]);passed=bool(required) and all(x in ids for x in required)
        if gate=='general-disabled':passed=bool(report['phases']) and all(len(x['disabledReceipts'])==5 for x in report['phases'])
        refs=[x for x in required if x in ids]
        if gate=='general-disabled':
            passed=passed and 'general-control-disabled-for-viewer' in ids
            refs=['general-disabled:'+x for x in ['runtime','proof','console-control','combat','navigation']]+(['general-control-disabled-for-viewer'] if 'general-control-disabled-for-viewer' in ids else [])
        report['gates'].append({'id':gate,'status':'passed' if passed else 'unverified','mode':'live','evidence':refs})
    if report['status']!='failed' and [p['phase'] for p in report['phases']]==['main','bounds','shutdown'] and all(g['status']=='passed' for g in report['gates']):report['status']='pass'
    report.pop('runningPhase',None);checkpoint()
    print(json.dumps({'status':report['status'],'version':a.version,'evidence':str(evidence),'phases':[{k:v for k,v in phase.items() if k in ['phase','exitCode']} for phase in report['phases']]}),flush=True)
if failure:raise failure
assert report['serverPortClosed']
