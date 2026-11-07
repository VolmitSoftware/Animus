#!/usr/bin/env python3
"""Managed trusted module/data acceptance on an explicitly pinned Animus artifact."""
import argparse,hashlib,json,os,re,runpy,shutil,socket,time
from pathlib import Path
import multiplexor_workspace as mw

ROOT=Path(__file__).resolve().parents[2]
HELPERS=runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')))
run,wait_log,console=HELPERS['run'],HELPERS['wait_log'],HELPERS['console']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda p:json.loads(p.read_text())

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--binary',type=Path,default=mw.default_binary());p.add_argument('--workspace',type=Path,default=mw.default_workspace())
    p.add_argument('--build-home',type=Path,help='Only when the build used a MULTIPLEXOR_BUILD_HOME override')
    for name in ['module-fixtures','reference','observer']:p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--build-run',required=True);p.add_argument('--artifact-sha256',required=True)
    p.add_argument('--project',choices=['paper','spigot','folia'],required=True);p.add_argument('--instance-name',help='Explicit animus-gate-modules-<project>-<version> isolated QA instance');p.add_argument('--version',choices=['26.1.2','26.2','26.3'],required=True)
    a=p.parse_args();a.binary=a.binary.resolve();a.workspace=a.workspace.resolve();a.transcript=[]
    assert re.fullmatch(r'[a-f0-9]{64}',a.artifact_sha256)
    fixtures=read(a.module_fixtures/'build.json');reference=read(a.reference.parent/'build.json');observer=read(a.observer.parent/'build.json')
    assert fixtures['status']=='built' and reference['sha256']==sha(a.reference)
    assert observer['fixtureSha256']==sha(a.observer) and observer['pluginSha256']==a.artifact_sha256
    assert fixtures['apiSha256']==reference['apiSha256']
    source_name=mw.baseline_name(a.project,a.version);name='animus-modules-'+a.project+'-'+a.version.replace('.','-')
    if a.instance_name:
        assert re.fullmatch(r'animus-gate-modules-'+a.project+'-'+re.escape(a.version.replace('.','-')),a.instance_name),'Instance name must bind requested platform/version'
        name=a.instance_name
    baseline=a.workspace/'consumers/plugin-consumers/instances'/source_name;instance=a.workspace/'consumers/plugin-consumers/instances'/name
    pinned=read(mw.evidence_root(a.workspace)/source_name/'runtime.json');assert sha(baseline/'server.jar')==pinned['serverSha256']
    token=f'{time.time_ns()}-{os.getpid()}';npc_id='module-qa-'+str(time.time_ns());evidence=mw.evidence_root(a.workspace)/name/f'modules-{token}';evidence.mkdir(parents=True)
    data=instance/'plugins/Animus';npc=data/'npcs/qa'/f'{npc_id}.json';checkpoint=data/'state/npc-definitions/last-valid/npcs/qa'/f'{npc_id}.json'
    report={'schemaVersion':1,'runner':'ServerMultiplexor','scope':'trusted startup Java modules and dormant definition API; no gameplay or behavior interpreter','project':a.project,'minecraftVersion':a.version,'serverBuild':pinned['build'],'serverChannel':pinned['channel'],'serverSha256':pinned['serverSha256'],'buildRun':a.build_run,'pluginSha256':a.artifact_sha256,'apiSha256':fixtures['apiSha256'],'status':'preparing','npcId':npc_id,'checks':[],'commands':[],'phases':[],'stops':[]}
    sources=[Path(__file__).resolve(),Path(__file__).with_name('managed-runtime.py').resolve(),ROOT/'qa/modules/build-fixtures.py',ROOT/'qa/modules/fixtures/src/example/animus/qa/ApiModule.java',ROOT/'examples/modules/reference/build.py',ROOT/'examples/modules/reference/src/example/animus/reference/ReferenceModule.java']
    sources += [ROOT/path for path in observer['sources']]
    sources=list(dict.fromkeys(sources));report['sources']={str(f.relative_to(ROOT)):sha(f) for f in sources}
    for file in sources:
        target=evidence/'sources'/file.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
    for label,receipt in [('modules-build',fixtures),('reference-build',reference),('observer-build',observer)]:
        (evidence/f'{label}.json').write_text(json.dumps(receipt,indent=2)+'\n')
    session=None;started=False;owned=False;disabled=False;failure=None;log=instance/'logs/latest.log'
    def save():
        temp=evidence/'result.json.tmp';temp.write_text(json.dumps(report,indent=2)+'\n');temp.replace(evidence/'result.json')
        (evidence/'multiplexor-commands.json').write_text(json.dumps(a.transcript,indent=2)+'\n')
    def check(identifier,**details):
        report['checks'].append({'id':identifier,'status':'passed',**details});save();print(json.dumps({'check':identifier}),flush=True)
    def command(text,pattern):
        offset=len(log.read_text(errors='replace')) if log.exists() else 0
        receipt=console(session,log,text,pattern)
        report['commands'].append({'phase':report.get('runningPhase'),'command':text,'receipt':receipt,'output':log.read_text(errors='replace')[offset:]});save();return receipt
    def action(key,arguments='{}',status='OK'):
        receipt=command(f'animus modules action {key} {npc_id} {arguments}',rf'.*Module {re.escape(key)}: {status}(?:;.*)?')
        if status=='OK':return json.loads(re.search(r'; result (\{.*\})',receipt)[1])
        return receipt
    def config(enabled):
        (data/'config.toml').write_text('packetProofEnabled = false\n[npcs]\nenabled = false\n[modules]\nenabled = '+str(enabled).lower()+'\nmaximumInvocations = 8\ninvocationTimeoutMillis = 1500\ndeliveryBudgetMillis = 250\n')
    def module_status(state):
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            receipt=command('animus modules status',r'.*Modules (?:NEW|STARTING|READY|DISABLED|FAILED|STOPPING|STOPPED) \|.*')
            if f'Modules {state} |' in receipt:return receipt
            time.sleep(.1)
        raise AssertionError('Expected module state '+state)
    def start(phase,expected='READY'):
        nonlocal session,started,disabled
        if log.exists():shutil.copy2(log,evidence/f'previous-{phase}.log');log.unlink()
        output=run(a,'runtime','start',name,'--no-console');session=re.search(r'tmux session: (\S+)',output)[1];started=True;disabled=False;report['runningPhase']=phase
        ready=wait_log(log,r'.*Done \([\d.]+s\)!.*');report['phases'].append({'phase':phase,'ready':ready});report['status']='running';save()
        command('animus status',rf'.*Animus 0\.1\.0 \| BUKKIT \| Minecraft {re.escape(a.version)}.*');module_status(expected)
    def stop(phase):
        nonlocal session,started
        receipt=run(a,'runtime','stop',name,'--graceful');session=None;started=False
        with socket.socket() as s:assert s.connect_ex(('127.0.0.1',report['port']))!=0
        archived=evidence/f'{phase}-server.log';shutil.copy2(log,archived)
        report['stops'].append({'phase':phase,'receipt':receipt,'serverPortClosed':True,'log':str(archived),'logSha256':sha(archived)});save()
    def assert_no_sources():
        assert not list((data/'npcs').rglob('*.json'));assert not list((data/'state/npc-definitions/last-valid/npcs').rglob('*.json'))
    save()
    try:
        if not instance.exists():run(a,'server','create',name,'--jar',str(baseline/'server.jar'),'--type',a.project,'--mc',a.version,'--isolated')
        assert run(a,'instance','isolated',name).strip()=='true';owned=True;run(a,'runtime','stop',name,'--graceful');assert sha(instance/'server.jar')==pinned['serverSha256'];assert_no_sources()
        run(a,'runtime','settings','set-java','/opt/homebrew/opt/openjdk@25/bin/java','--instance',name);run(a,'runtime','settings','set-heap','1G','--instance',name);run(a,'runtime','settings','set-preset','vanilla','--instance',name);run(a,'gameplay','prepare',name)
        report['port']=HELPERS['free_port']();run(a,'instance','port',name,str(report['port']));shutil.copy2(baseline/'eula.txt',instance/'eula.txt');assert 'eula=true' in (instance/'eula.txt').read_text().lower()
        data.mkdir(parents=True,exist_ok=True);config(True)
        properties=instance/'server.properties';lines=properties.read_text().splitlines();overrides={'server-ip':'127.0.0.1','online-mode':'false','white-list':'true','difficulty':'peaceful','pause-when-empty-seconds':'-1','view-distance':'2','simulation-distance':'2','max-players':'1','spawn-protection':'0','level-type':'minecraft:flat','generate-structures':'false','generator-settings':json.dumps({'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:stone','height':59},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains'},separators=(',',':'))}
        properties.write_text('\n'.join(x for x in lines if x.partition('=')[0] not in overrides)+'\n'+'\n'.join(k+'='+v for k,v in overrides.items())+'\n');(instance/'whitelist.json').write_text('[]\n');(instance/'ops.json').write_text('[]\n')
        mojang=Path.home()/'.gradle/caches/fabric-loom'/a.version/'minecraft-server.jar'
        if mojang.is_file():(instance/'cache').mkdir(exist_ok=True);shutil.copy2(mojang,instance/'cache'/f'mojang_{a.version}.jar')
        run(a,'projects','destination',name,'--instance',name,'--platform','plugin','--mc',a.version);run(a,'projects','deploy',a.build_run,'--to',name,'--target','animus:bukkit','--json')
        plugins=list((instance/'plugins').glob('*.jar'));animus=[f for f in plugins if f.name.startswith('Animus v')];assert len(animus)==1 and sha(animus[0])==a.artifact_sha256
        assert all(f==animus[0] or f.name==a.observer.name for f in plugins);shutil.copy2(a.observer,instance/'plugins'/a.observer.name)
        report['plugins']=[{'file':f.name,'sha256':sha(f)} for f in sorted((instance/'plugins').glob('*.jar'))]
        directory=data/'modules';directory.mkdir(exist_ok=True)
        allowed={m['file'] for m in fixtures['modules']}|{a.reference.name}
        assert all(f.name in allowed for f in directory.glob('*.jar')),'Unowned module JAR in dedicated instance'
        for item in fixtures['modules']:
            assert Path(item['file']).name==item['file'];source=a.module_fixtures/'modules'/item['file'];assert sha(source)==item['sha256'];shutil.copy2(source,directory/item['file'])
        shutil.copy2(a.reference,directory/a.reference.name)
        retained=directory/'qa-missing-config.json';retained_bytes=(json.dumps({'missing:module':{'retain':token}},indent=2)+'\n').encode();retained.write_bytes(retained_bytes)
        report['initialModuleHashes']={f.name:sha(f) for f in sorted(directory.glob('*.jar'))};report['retainedConfigSha256']=sha(retained)
        start('prepare')
        command('animus modules list',r'.*reference \[.*\] \| ACTIVE \| active.*');listing=report['commands'][-1]['output'];assert 'Module list: 16;' in listing
        for item in fixtures['modules']:
            identity=item['manifest']['id'];expected=item['expectedStartup']
            if identity=='qabundled':assert f'[{item["file"]}] | REJECTED' in listing
            else:assert re.search(re.escape(identity)+' \\['+re.escape(item['file'])+r'\] \| '+expected+r' \|',listing),(identity,expected,listing)
        startup_log=log.read_text(errors='replace')
        for item in fixtures['modules']:
            if item['expectedStartup'] in ['REJECTED','UNAVAILABLE']:assert 'Animus module QA initialized '+item['manifest']['id'] not in startup_log
        check('startup-rejections-dependencies-and-parent-api-identity')
        command('animus modules capabilities',r'.*reference:hello \| ACTION \| available=true.*');catalog=report['commands'][-1]['output'];assert 'Module capabilities: 13; built-in: 19;' in catalog;assert 'qabroken:partial' not in catalog and 'reference:stolen' not in catalog
        check('failed-and-foreign-namespace-registrations-not-published')
        command(f'animus create {npc_id} minecraft:player world 0 61 3 qa "Module Before"',rf'.*Created dormant {npc_id};.*')
        command(f'animus edit {npc_id} '+json.dumps({'modules':{'missing:module':{'retained':True}}},separators=(',',':')),r'.*Saved[^;]*; tx .*')
        saved=read(npc);report['npcUuid']=saved['uuid'];report['initialRevision']=saved['revision']
        assert action('reference:hello')['message']=='Hello from a trusted external module';assert action('qalink:linked')['linked'] is True
        command(f'animus modules condition qaapi:false {npc_id} {{}}',r'.*Module qaapi:false: OK; result false.*');command(f'animus modules condition qaapi:true {npc_id} {{}}',r'.*Module qaapi:true: OK; result true.*')
        action('qaapi:privileged',status='UNAVAILABLE');action('reference:hello','[]',status='INVALID');action('qabroken:partial',status='UNAVAILABLE');check('typed-dispatch-linkage-privilege-and-json-argument-gates')
        cached=action('qaapi:cache');assert cached['revision']==saved['revision'] and cached['uuid']==saved['uuid'] and cached['sourceSha256']==sha(npc)
        command(f'animus edit {npc_id} '+json.dumps({'name':'Module Concurrent'},separators=(',',':')),r'.*Saved[^;]*; tx .*');before=npc.read_bytes()
        assert action('qaapi:apply_cached')['status']=='CONFLICT';assert npc.read_bytes()==before;check('api-token-conflicts-with-administrator-edit')
        edited=action('qaapi:edit');current=read(npc);assert edited['status']=='OK' and edited['revision']==saved['revision']+2 and edited['sourceSha256']==sha(npc);assert current['name']=='Module Edited' and current['modules']['missing:module']['retained'] is True and not current['spawned'];check('api-validated-dormant-apply-revision-and-unknown-config-preservation')
        before=npc.read_bytes();assert action('qaapi:invalid')['status']=='INVALID';assert action('qaapi:identity')['status']=='INVALID';assert action('qaapi:activate')['status']=='UNAVAILABLE';assert npc.read_bytes()==before;check('invalid-identity-and-activation-drafts-preserve-exact-bytes')
        replacement=a.module_fixtures/fixtures['replacement']['file'];assert sha(replacement)==fixtures['replacement']['sha256'];shutil.copy2(replacement,directory/a.reference.name)
        command('animus reload',r'.*Reloaded; .*');assert action('reference:hello')['message']=='Hello from a trusted external module';assert retained.read_bytes()==retained_bytes;check('binary-replacement-and-file-reload-do-not-reload-java')
        action('qawait:hold',status='TIMED_OUT');command('animus modules status',r'.*Module invocations: .*handlersOutstanding=1.*timedOutHandlers=1.*');action('qawait:hold',status='UNAVAILABLE');check('deadline-suspends-module-and-retains-unfinished-handler')
        report['persistedBeforeRestart']={'definition':npc.read_text(),'checkpoint':checkpoint.read_text()};(evidence/'before-restart-npc.json').write_bytes(npc.read_bytes());(evidence/'before-restart-checkpoint.json').write_bytes(checkpoint.read_bytes())
        stop('prepare');prepare=(evidence/'prepare-server.log').read_text(errors='replace');assert prepare.index('disabled qalink')<prepare.index('disabled qabase');assert 'disabled qawait' in prepare;assert prepare.index('resource qaapi second')<prepare.index('resource qaapi first');check('managed-stop-runs-reverse-module-and-resource-cleanup')
        start('restart-and-disable')
        assert npc.read_text()==report['persistedBeforeRestart']['definition'] and checkpoint.read_text()==report['persistedBeforeRestart']['checkpoint'];assert action('qaapi:inspect')['uuid']==report['npcUuid'];assert action('reference:hello')['message']=='Replacement reference after restart';assert retained.read_bytes()==retained_bytes;check('restart-loads-new-binary-and-preserves-accepted-definition')
        command(f'animus modules action qawait:hold {npc_id} {{}}',r'.*Animus module QA pending qawait.*')
        command('animus delete '+npc_id,r'.*Deleted [^;]*; tx .*');assert_no_sources()
        command('moduleqa disable '+report['npcUuid'],r'.*Module QA disabled enabled=false admission-closed=true duration-ms=\d+.*');disabled=True
        elapsed=int(re.search(r'duration-ms=(\d+)',report['commands'][-1]['receipt'])[1]);assert elapsed<2000
        wait_log(log,r'.*Module QA post-disable action=UNAVAILABLE.*',timeout=10)
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            result=command('moduleqa status',r'.*Module QA status state=.*')
            if 'state=STOPPED admission-closed=true admitted=0 retained=0 released=true' in result:break
            time.sleep(.1)
        else:raise AssertionError('Module shutdown did not release tracked archives')
        check('actual-plugin-disable-revokes-pending-work-and-releases-archives',disableMillis=elapsed)
        stop('restart-and-disable');config(False);start('disabled-start',expected='DISABLED')
        command('animus modules list',r'.*Module list: 0; page 1.*');assert 'Animus module QA initialized ' not in log.read_text(errors='replace');assert_no_sources();assert retained.read_bytes()==retained_bytes;check('disabled-setting-skips-all-binary-initialization-and-retains-config')
        stop('disabled-start');report['status']='pass'
    except Exception as error:failure=error;report['status']='failed';report['error']=str(error)
    finally:
        if started and not disabled and npc.exists():
            try:command('animus delete '+npc_id,r'.*Deleted [^;]*; tx .*')
            except Exception as error:report['commandCleanupError']=str(error)
        if owned:
            report['stop']=run(a,'runtime','stop',name,'--graceful',allow_failure=True);report['runtimeAfter']=run(a,'runtime','status',name,allow_failure=True)
        if report.get('port'):
            with socket.socket() as s:report['serverPortClosed']=s.connect_ex(('127.0.0.1',report['port']))!=0
            if not report['serverPortClosed']:report['status']='failed'
            else:
                # Emergency cleanup only the exact receipt-owned synthetic source and pointer.
                for path in [npc,checkpoint]:
                    if path.exists():report.setdefault('offlineCleanup',[]).append(str(path.relative_to(data)));path.unlink()
        report['remainingDefinitions']=[str(f.relative_to(data)) for f in (data/'npcs').rglob('*.json')];report['remainingCheckpoints']=[str(f.relative_to(data)) for f in (data/'state/npc-definitions/last-valid/npcs').rglob('*.json')]
        if report['remainingDefinitions'] or report['remainingCheckpoints'] or report.get('offlineCleanup'):report['status']='failed'
        if log.exists():
            shutil.copy2(log,evidence/'final-server.log');report['finalServerLog']=str(evidence/'final-server.log');report['finalServerLogSha256']=sha(evidence/'final-server.log')
        report['logFailures']=[{'log':str(path),'line':line} for path in sorted(evidence.glob('*-server.log')) for line in path.read_text(errors='replace').splitlines() if re.search(r'\bERROR\b|Exception in thread|^\s+at [\w.$]+\(',line)]
        if report['logFailures']:report['status']='failed'
        report['deployedPlugins']=[{'file':f.name,'sha256':sha(f)} for f in sorted((instance/'plugins').glob('*.jar'))]
        if report.get('plugins') and report['deployedPlugins']!=report['plugins']:report['status']='failed';report['artifactError']='Plugin bytes changed'
        if (instance/'server.jar').exists():
            report['finalServerSha256']=sha(instance/'server.jar')
            if report['finalServerSha256']!=report['serverSha256']:report['status']='failed';report['artifactError']='Server bytes changed'
        report['finalModuleHashes']={f.name:sha(f) for f in sorted((data/'modules').glob('*.jar'))};report.pop('runningPhase',None);save();print(json.dumps({'status':report['status'],'evidence':str(evidence),'checks':len(report['checks'])}),flush=True)
    if failure:raise failure
    assert report['status']=='pass'

if __name__=='__main__':main()
