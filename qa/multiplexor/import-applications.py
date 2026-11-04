#!/usr/bin/env python3
"""Exercise reviewed import applications on a dedicated Multiplexor instance.

Requires an explicitly selected reviewed artifact containing stage/apply-plan.
No private fixture, rendering, or performance claim belongs to this scenario.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import socket
import time

ROOT=Path(__file__).resolve().parents[2]
helpers=runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')))
run,wait_log,console=helpers['run'],helpers['wait_log'],helpers['console']
checks_api=runpy.run_path(str(ROOT/'qa/import-applications/assertions.py'))
sha,read,snapshot,assert_snapshot,definitions,checkpoint,application,installed,duplicate,original_archive=(checks_api[k] for k in ['sha','read','snapshot','assert_snapshot','definitions','checkpoint','application','installed','duplicate','original_archive'])

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['binary','workspace','build-home']:p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--build-run',required=True)
    p.add_argument('--artifact-sha256',required=True)
    p.add_argument('--project',choices=['paper','spigot','folia'],required=True)
    p.add_argument('--version',choices=['26.1.2','26.2','26.3'],required=True)
    p.add_argument('--instance-name',help='Explicit fresh animus-r<N>-import-<project>-<version> isolated QA instance')
    a=p.parse_args();a.binary=a.binary.resolve();a.workspace=a.workspace.resolve();a.transcript=[]
    assert '.qa' in a.workspace.parts and re.fullmatch(r'[a-f0-9]{64}',a.artifact_sha256)
    reference_name=f'animus-{a.project}-{a.version.replace(".","-")}'
    name=f'animus-import-{a.project}-{a.version.replace(".","-")}'
    if a.instance_name:
        assert re.fullmatch(r'animus-r[1-9][0-9]*-import-'+a.project+'-'+re.escape(a.version.replace('.','-')),a.instance_name),'Instance name must bind requested platform/version'
        name=a.instance_name
    reference=a.workspace/'consumers/plugin-consumers/instances'/reference_name
    instance=a.workspace/'consumers/plugin-consumers/instances'/name
    source=read(a.workspace/'animus-evidence'/reference_name/'runtime.json')
    assert sha(reference/'server.jar')==source['serverSha256'],'Pinned reference server changed'
    token=f'{time.time_ns()}-{os.getpid()}'
    evidence=a.workspace/'animus-evidence'/name/f'application-{token}'
    evidence.mkdir(parents=True)
    report={'schemaVersion':1,'runner':'ServerMultiplexor','scope':'synthetic import application commands, persistence, checkpoints and rollback; no rendering or private fixture','project':a.project,'minecraftVersion':a.version,'serverBuild':source['build'],'serverChannel':source['channel'],'serverSha256':source['serverSha256'],'buildRun':a.build_run,'pluginSha256':a.artifact_sha256,'status':'preparing','checks':[],'commands':[],'restarts':[],'phases':[]}
    state={}
    sources=[Path(__file__).resolve(),Path(__file__).with_name('managed-runtime.py').resolve(),ROOT/'qa/import-applications/assertions.py',*sorted((ROOT/'qa/import-applications/fixtures').glob('*.json'))]
    report['sources']={str(f.relative_to(ROOT)):sha(f) for f in sources}
    for f in sources:
        dest=evidence/'sources'/f.relative_to(ROOT);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,dest)
    def save():
        temporary=evidence/'result.json.tmp';temporary.write_text(json.dumps(report,indent=2)+'\n');temporary.replace(evidence/'result.json')
        (evidence/'scenario-state.json').write_text(json.dumps(state,indent=2)+'\n')
        (evidence/'multiplexor-commands.json').write_text(json.dumps(a.transcript,indent=2)+'\n')
    def passed(identifier,**detail):
        report['checks'].append({'id':identifier,'status':'passed',**detail});save();print(json.dumps({'event':'check','id':identifier,**detail}),flush=True)
    session=None;started=False;owned=False;failure=None;log=instance/'logs/latest.log';data=instance/'plugins/Animus'
    owned_ids={f'citizens-{n}' for n in range(900001,900005)}|{f'citizens-{n}-qa' for n in range(900001,900004)}
    def command(text,pattern):
        reply=console(session,log,text,pattern)
        report['commands'].append({'command':text,'receipt':reply});save();return reply
    def tx(reply):
        match=re.search(r'\btransaction ([a-f0-9-]{36}|already-applied)\b',reply)
        assert match,'Missing transaction identity: '+reply
        return match[1]
    def stage(import_id,mode='reject',label=None):
        reply=command(f'animus import stage {import_id} {mode}'+(' '+label if label else ''),r'.*Application [a-f0-9]{64}.*')
        identity=re.search(r'Application ([a-f0-9]{64})',reply)[1]
        plan,candidates=application(data,import_id,identity)
        assert plan['mode']==mode.upper()
        return identity,plan,candidates
    def apply(import_id,identity):
        transaction=tx(command(f'animus import apply-plan {import_id} {identity}',r'.*Installed dormant definitions; transaction .*'))
        installed(data,import_id,identity);return transaction
    def rollback(transaction):
        return tx(command('animus rollback '+transaction,r'.*Rolled back; inverse transaction .*'))
    def preview(filename,source_bytes):
        receipt=command('animus import preview '+filename+' qa_legacy=world',r'.*Preview [a-f0-9]{64}.*')
        identity=re.search(r'Preview ([a-f0-9]{64})',receipt)[1]
        summary=original_archive(data,identity,source_bytes)
        assert summary['valid']==summary['records'] and summary['skins']==1
        return identity
    def start(label):
        nonlocal session,started
        if log.exists():shutil.copy2(log,evidence/f'previous-{label}.log');log.unlink()
        output=run(a,'runtime','start',name,'--no-console');session=re.search(r'tmux session: (\S+)',output)[1];started=True
        ready=wait_log(log,r'.*Done \([\d.]+s\)!.*')
        command('animus status',rf'.*Animus 0\.1\.0 \| BUKKIT \| Minecraft {re.escape(a.version)}.*')
        report['phases'].append({'phase':label,'ready':ready});report['status']='running';report['runningPhase']=label;save()
    def stop(label):
        nonlocal session,started
        before=time.monotonic();receipt=run(a,'runtime','stop',name,'--graceful');elapsed=time.monotonic()-before
        session=None;started=False
        with socket.socket() as sock:assert sock.connect_ex(('127.0.0.1',report['port']))!=0
        target=evidence/f'{label}-server.log';shutil.copy2(log,target)
        report['restarts'].append({'after':label,'receipt':receipt,'seconds':elapsed,'serverPortClosed':True,'log':str(target),'logSha256':sha(target)});save()
    save()
    try:
        if not instance.exists():run(a,'server','create',name,'--jar',str(reference/'server.jar'),'--type',a.project,'--mc',a.version,'--isolated')
        assert run(a,'instance','isolated',name).strip()=='true';owned=True
        run(a,'runtime','stop',name,'--graceful')
        assert sha(instance/'server.jar')==source['serverSha256']
        assert not list((data/'npcs').rglob('*.json')),'Dedicated import instance contains definitions; inspect prior evidence before retrying'
        assert not list((data/'state/npc-definitions/last-valid/npcs').rglob('*.json')),'Dedicated import instance contains recovery pointers'
        java=Path('/opt/homebrew/opt/openjdk@25/bin/java')
        run(a,'runtime','settings','set-java',str(java),'--instance',name)
        run(a,'runtime','settings','set-heap','1G','--instance',name)
        run(a,'runtime','settings','set-preset','vanilla','--instance',name)
        run(a,'gameplay','prepare',name)
        port=helpers['free_port']();run(a,'instance','port',name,str(port));report['port']=port
        assert 'eula=true' in (reference/'eula.txt').read_text().lower();shutil.copy2(reference/'eula.txt',instance/'eula.txt')
        data.mkdir(parents=True,exist_ok=True)
        (data/'config.toml').write_text('statusMessage = "Import QA"\npacketProofEnabled = false\n[npcs]\nenabled = false\n')
        props=instance/'server.properties';lines=props.read_text().splitlines()
        overrides={'server-ip':'127.0.0.1','online-mode':'false','white-list':'true','difficulty':'peaceful','pause-when-empty-seconds':'-1','view-distance':'2','simulation-distance':'2','max-players':'1','spawn-protection':'0','level-type':'minecraft:flat','generate-structures':'false','generator-settings':json.dumps({'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:stone','height':59},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains'},separators=(',',':'))}
        props.write_text('\n'.join(x for x in lines if x.partition('=')[0] not in overrides)+'\n'+'\n'.join(k+'='+v for k,v in overrides.items())+'\n')
        (instance/'whitelist.json').write_text('[]\n');(instance/'ops.json').write_text('[]\n')
        mojang=Path.home()/'.gradle/caches/fabric-loom'/a.version/'minecraft-server.jar'
        if mojang.is_file():
            (instance/'cache').mkdir(exist_ok=True);shutil.copy2(mojang,instance/'cache'/f'mojang_{a.version}.jar')
        run(a,'projects','destination',name,'--instance',name,'--platform','plugin','--mc',a.version)
        run(a,'projects','deploy',a.build_run,'--to',name,'--target','animus:bukkit','--json')
        jars=list((instance/'plugins').glob('*.jar'));assert len(jars)==1 and sha(jars[0])==a.artifact_sha256
        report['plugins']=[{'file':jars[0].name,'sha256':sha(jars[0])}]
        inbox=data/'imports/inbox';inbox.mkdir(parents=True,exist_ok=True)
        prepared={}
        for kind in ['baseline','updated']:
            document=read(ROOT/'qa/import-applications/fixtures'/f'{kind}.json');document['_qaRun']=token
            encoded=(json.dumps(document,indent=2)+'\n').encode();filename=f'qa_{kind}_{token}.yml'
            (inbox/filename).write_bytes(encoded);(evidence/f'{kind}-input.json').write_bytes(encoded)
            prepared[kind]=(filename,encoded)
        state['inputFiles']={kind:value[0] for kind,value in prepared.items()};save()
        start('prepare')
        empty=snapshot(data);assert not empty
        baseline=preview(*prepared['baseline']);state['baselineImport']=baseline
        repeat=preview(*prepared['baseline']);assert repeat==baseline;passed('preview-identity-and-source-preservation')
        base_app,plan,_=stage(baseline);assert plan['records']==3 and plan['replacements']==0
        assert_snapshot(data,empty);assert stage(baseline)[0]==base_app
        passed('stage-idempotent-without-definition-or-checkpoint-mutation')
        state['baseApplication']=base_app;state['baseTransaction']=apply(baseline,base_app)
        assert apply(baseline,base_app)=='already-applied';assert len(definitions(data))==3
        for relative,document in definitions(data).items():
            assert document['position']['world']=='world';checkpoint(data,relative)
        passed('apply-dormant-combat-disabled-checkpoints-and-repeat')
        state['baselineSnapshot']=snapshot(data)
        copy_app,copy_plan,_=stage(baseline,'duplicate','qa');assert copy_plan['replacements']==0
        assert_snapshot(data,state['baselineSnapshot']);assert stage(baseline,'duplicate','qa')[0]==copy_app
        state['copyApplication']=copy_app;state['copyTransaction']=apply(baseline,copy_app)
        copies=duplicate(data,baseline,copy_app,'qa');assert len(copies)==3
        assert apply(baseline,copy_app)=='already-applied';passed('duplicate-deterministic-identities-and-resolved-reference-remap')
        updated=preview(*prepared['updated']);state['updatedImport']=updated
        before=snapshot(data)
        command('animus import stage '+updated,r'.*NPC collision:.*');assert_snapshot(data,before)
        passed('default-stage-rejects-collisions')
        stale,stale_plan,_=stage(updated,'replace');assert stale_plan['records']==4 and stale_plan['replacements']==3
        assert_snapshot(data,before);state['staleApplication']=stale
        state['editTransaction']=tx(command('animus edit citizens-900001 {"name":"QAConcurrent"}',r'.*Saved; transaction .*'))
        changed=snapshot(data)
        command(f'animus import apply-plan {updated} {stale}',r'.*(Conflict:|Application no longer matches current definitions).*')
        assert_snapshot(data,changed);assert not (data/'npcs/citizens/citizens-900004.json').exists()
        assert not (data/'imports'/updated/'applications'/stale/'receipt.json').exists()
        passed('stale-application-rejects-whole-batch-and-keeps-edit')
        fresh,fresh_plan,_=stage(updated,'replace');assert fresh!=stale and fresh_plan['replacements']==3
        state['beforeReplacement']=snapshot(data);prior=definitions(data)
        state['replacementApplication']=fresh;state['replacementTransaction']=apply(updated,fresh)
        for relative,document in definitions(data).items():
            if relative.startswith('npcs/citizens/'):
                expected_revision=prior[relative]['revision']+1 if relative in prior else 1
                assert document['revision']==expected_revision
            checkpoint(data,relative)
        assert len(definitions(data))==7 and read(data/'npcs/citizens/citizens-900001.json')['name']=='QA Update Guard'
        assert apply(updated,fresh)=='already-applied';state['afterReplacement']=snapshot(data)
        original_archive(data,baseline,prepared['baseline'][1]);original_archive(data,updated,prepared['updated'][1])
        passed('reviewed-replacement-increments-revisions-and-adds-record')
        stop('prepare');start('rollback-after-restart')
        assert_snapshot(data,state['afterReplacement']);installed(data,updated,fresh);duplicate(data,baseline,copy_app,'qa')
        passed('actual-restart-retains-applied-definitions-and-checkpoints')
        rollback(state['replacementTransaction']);assert_snapshot(data,state['beforeReplacement'])
        assert not (data/'imports'/updated/'applications'/fresh/'receipt.json').exists()
        passed('replacement-rollback-after-restart-restores-exact-bytes')
        reapplied=apply(updated,fresh);assert reapplied not in ['already-applied',state['replacementTransaction']]
        rollback(reapplied);assert_snapshot(data,state['beforeReplacement']);passed('reviewed-application-can-reapply-after-rollback')
        rollback(state['copyTransaction'])
        assert all(not key.startswith('npcs/copy-qa/') for key in definitions(data))
        assert not (data/'imports'/baseline/'applications'/copy_app/'receipt.json').exists()
        passed('copy-rollback-preserves-original-definitions')
        rollback(state['editTransaction']);assert_snapshot(data,state['baselineSnapshot'])
        rollback(state['baseTransaction']);assert_snapshot(data,empty)
        passed('initial-application-rollback-removes-sources-and-checkpoints')
        legacy=tx(command('animus import apply '+baseline,r'.*Installed dormant definitions; transaction .*'))
        assert legacy!='already-applied' and len(definitions(data))==3
        assert tx(command('animus import apply '+baseline,r'.*Installed dormant definitions; transaction .*'))=='already-applied'
        rollback(legacy);assert_snapshot(data,empty);passed('legacy-default-apply-repeat-and-rollback-unchanged')
        command('animus validate',r'.*validate successful; 0 active definitions.*')
        stop('rollback-after-restart');start('empty-restart')
        assert_snapshot(data,empty);command('animus status',r'.*NPC definitions: 0.*')
        assert not (data/'state/pending.json').exists()
        original_archive(data,baseline,prepared['baseline'][1]);original_archive(data,updated,prepared['updated'][1])
        passed('second-restart-does-not-resurrect-rolled-back-records')
        stop('empty-restart');report['status']='pass'
    except Exception as error:
        failure=error;report['status']='failed';report['error']=str(error)
    finally:
        # Delete only this scenario's exact synthetic IDs while the plugin is
        # available. Preserve all archives/receipts/journals for diagnosis.
        if started:
            try:
                for relative,document in definitions(data).items():
                    assert document['id'] in owned_ids,'Unexpected definition in dedicated import instance'
                    command('animus delete '+document['id'],r'.*Deleted; rollback transaction .*')
            except Exception as error:report['definitionCleanupError']=str(error)
        if owned:
            report['stop']=run(a,'runtime','stop',name,'--graceful',allow_failure=True)
            report['runtimeAfter']=run(a,'runtime','status',name,allow_failure=True)
        if report.get('port'):
            with socket.socket() as sock:report['serverPortClosed']=sock.connect_ex(('127.0.0.1',report['port']))!=0
            if not report['serverPortClosed']:report['status']='failed';report['cleanupError']='Owned port remains open'
        if data.exists():
            report['remainingDefinitions']=[str(x.relative_to(data)) for x in (data/'npcs').rglob('*.json')];report['remainingCheckpoints']=[str(x.relative_to(data)) for x in (data/'state/npc-definitions/last-valid/npcs').rglob('*.json')]
            if report['remainingDefinitions'] or report['remainingCheckpoints']:report['status']='failed'
            for filename in state.get('inputFiles',{}).values():(data/'imports/inbox'/filename).unlink(missing_ok=True)
        report['deployedPlugins']=[{'file':p.name,'sha256':sha(p)} for p in (instance/'plugins').glob('*.jar')]
        if report.get('plugins') and report['deployedPlugins']!=report['plugins']:report['status']='failed';report['artifactError']='Deployed plugin bytes changed during scenario'
        if (instance/'server.jar').exists():
            report['finalServerSha256']=sha(instance/'server.jar')
            if report['finalServerSha256']!=report['serverSha256']:report['status']='failed';report['artifactError']='Server bytes changed during scenario'
        if log.exists():
            shutil.copy2(log,evidence/'final-server.log');report['finalServerLog']=str(evidence/'final-server.log');report['finalServerLogSha256']=sha(evidence/'final-server.log')
        report['logFailures']=[]
        for server_log in sorted(evidence.glob('*-server.log')):
            report['logFailures'].extend({'log':str(server_log),'line':line} for line in server_log.read_text(errors='replace').splitlines() if re.search(r'\bERROR\b|Exception|moved too quickly|moved wrongly',line))
        if report['logFailures']:report['status']='failed'
        report.pop('runningPhase',None);save()
        print(json.dumps({'status':report['status'],'evidence':str(evidence),'checks':len(report['checks'])}),flush=True)
    if failure:raise failure
    assert report['status']=='pass'

if __name__=='__main__':main()
