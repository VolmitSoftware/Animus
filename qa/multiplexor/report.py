#!/usr/bin/env python3
"""Refresh evidence links for one explicit build/round; never certify or rerun checks."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import socket
import multiplexor_workspace as mw

CATEGORIES = ['lifecycle','gameplay','native','production','importApplications','foliaStationaryPreview','modules']
ATTEMPTS = [('native','native'),('production','production'),('application','importApplications'),('folia-proof','foliaStationaryPreview'),('modules','modules')]


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for data in iter(lambda:stream.read(1024*1024),b''):h.update(data)
    return h.hexdigest()


def latest(directory,prefix):
    paths=sorted((p for p in directory.glob(prefix+'-*') if p.is_dir() and re.fullmatch(re.escape(prefix)+r'-[0-9]+(?:-[0-9]+)?',p.name)),
                 key=lambda p:tuple(map(int,p.name[len(prefix)+1:].split('-'))))
    return paths[-1]/'result.json' if paths else None


def target_in_round(name,round_number,category):
    prefix='animus'+(f'-r{round_number}' if round_number is not None else '')
    if category=='modules':prefix='animus-modules'
    elif category=='importApplications':prefix+='-import'
    elif round_number is None:prefix='animus-baseline'
    return re.fullmatch(re.escape(prefix)+r'-(paper|spigot|folia)-(26-1-2|26-2|26-3)',name) is not None


def selected(evidence,build_id,hashes,round_number,category):
    return (evidence.get('buildRun')==build_id and target_in_round(evidence['target'],round_number,category) and
            (evidence.get('artifactSha256') in hashes or any(p.get('sha256') in hashes for p in evidence.get('plugins',[]))))


def closed(port):
    with socket.socket() as sock:
        sock.settimeout(.2);return sock.connect_ex(('127.0.0.1',port))!=0


def descriptor(path):
    value=json.loads(path.read_text())
    if value.get('status')=='prepared':
        if value.get('scope')!='Fresh isolated regression instance preparation only; no server startup or runtime validation':raise ValueError('Unknown prepared descriptor scope')
        return 'provisioning',value|{'evidence':str(path),'portClosedNow':closed(value['port']),'certifiesRuntime':False}
    return 'lifecycle',{key:value.get(key) for key in ['project','minecraftVersion','build','channel','status','buildRun','plugins']}|{'target':path.parent.name,'evidence':str(path),'portClosedNow':closed(value['port'])}


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--workspace',type=Path,default=mw.default_workspace());p.add_argument('--binary',type=Path,default=mw.default_binary())
    p.add_argument('--build-status',type=Path);p.add_argument('--source-snapshot',type=Path);p.add_argument('--round',type=int)
    p.add_argument('--audit',type=Path,action='append',default=[],help='Repeat for independent passing scopes on the selected build')
    a=p.parse_args(argv);root=mw.evidence_root(a.workspace);index=root/'index.json';previous=json.loads(index.read_text()) if index.exists() else {};oldbuild=previous.get('build',{})
    if not a.build_status and not oldbuild.get('record'):p.error('Supply an explicit build status/source snapshot')
    status_path=a.build_status or Path(oldbuild['record']);value=json.loads(status_path.read_text());runs=value.get('runs',[value])
    if len(runs)!=1:p.error('Select exactly one successful build')
    run=runs[0];artifacts=[x for j in run['jobs'] for x in j.get('artifacts',[])];runtime=[x for x in artifacts if x.get('role')=='runtime']
    if run['status']!='succeeded' or not runtime or any(j.get('status')!='succeeded' or j.get('exitCode')!=0 for j in run['jobs']):p.error('Successful build/jobs required')
    for artifact in artifacts:
        if digest(artifact['file'])!=artifact['sha256']:p.error('Build artifact changed')
    hashes={x['sha256'] for x in runtime};same=oldbuild.get('id')==run['id'];round_number=a.round if a.round is not None else previous.get('round') if same else None
    if round_number is not None and not 1<=round_number<=1000000:p.error('Invalid round')
    snapshot=a.source_snapshot or (Path(oldbuild['sourceSnapshot']) if same and oldbuild.get('sourceSnapshot') else None)
    report={'schemaVersion':5,'generatedAt':datetime.now(timezone.utc).isoformat(),'round':round_number,
            'runner':{'binary':str(a.binary.resolve()),'sha256':digest(a.binary)},
            'build':{'id':run['id'],'status':run['status'],'record':str(status_path),'artifacts':artifacts,'sourceSnapshot':str(snapshot) if snapshot else None},
            **{k:[] for k in CATEGORIES},'provisioning':[],'failedAttempts':[],'protocolCapability':[]}
    for target in sorted(root.glob('animus-*')):
        if (target/'runtime.json').exists():
            category,entry=descriptor(target/'runtime.json');report[category].append(entry)
        for phase in ['prepare','verify']:
            path=target/f'gameplay-final-{phase}.json'
            if path.exists():
                d=json.loads(path.read_text());report['gameplay'].append({'target':target.name,'phase':phase,'status':d['status'],'steps':len(d['steps']),'plugins':d['plugins'],'buildRun':d.get('buildRun'),'evidence':str(path),'runner':'Multiplexor gameplay run'})
        for prefix,category in ATTEMPTS:
            path=latest(target,prefix)
            if path is None:continue
            if not path.exists():report[category].append({'target':target.name,'status':'running','evidence':str(path)});continue
            d=json.loads(path.read_text());checks=len(d.get('checks',[])) if category in ['importApplications','modules'] else len(d.get('clientResult',{}).get('checks',[])) if category=='native' else sum(len(x.get('clientResult',{}).get('checks',[])) for x in d.get('phases',[]))
            report[category].append({'target':target.name,'project':d.get('project','folia'),'minecraftVersion':d.get('minecraftVersion'),'status':d['status'],
                'scope':d.get('scope'),'buildRun':d.get('buildRun'),'plugins':d.get('plugins',[]),'artifactSha256':d.get('pluginSha256'),
                'checks':checks,'phases':[{'name':x['phase'],'exitCode':x.get('exitCode'),'checks':len(x.get('clientResult',{}).get('checks',[]))} for x in d.get('phases',[])],
                'serverPortClosed':d.get('serverPortClosed',False),'evidence':str(path),'error':d.get('error',d.get('clientResult',{}).get('error'))})
        paths=list(target.glob('prior-runtime-*.json'))+[f for prefix,_ in ATTEMPTS for f in target.glob(prefix+'-*/result.json')]
        for path in paths:
            d=json.loads(path.read_text())
            if d.get('status')=='failed':report['failedAttempts'].append({'target':target.name,'project':d.get('project'),'minecraftVersion':d.get('minecraftVersion'),'buildRun':d.get('buildRun'),'evidence':str(path),'error':d.get('error',d.get('clientResult',{}).get('error'))})
    report['historicalEvidence']={}
    for category in CATEGORIES:
        entries=report[category];report[category]=[e for e in entries if selected(e,run['id'],hashes,round_number,category)]
        report['historicalEvidence'][category]=[e for e in entries if not selected(e,run['id'],hashes,round_number,category)]
    for version in ['26.1.2','26.2','26.3']:
        path=a.workspace/f'doctor-{version}.json'
        if path.exists():
            d=json.loads(path.read_text());report['protocolCapability'].append({'minecraftVersion':version,'status':d['status'],'runner':'Multiplexor Mineflayer doctor','evidence':str(path),'errors':d.get('protocolCapability',{}).get('errors',[])})
    current=list(previous.get('currentArtifactScopes',[])) if same else []
    legacy=previous.get('currentArtifact',{})
    if same and legacy.get('sha256') in hashes and legacy.get('auditSha256') not in {x.get('auditSha256') for x in current}:current.append(legacy)
    for path in a.audit:
        d=json.loads(path.read_text());build=d.get('build',{})
        if d.get('status')!='passed' or build.get('id')!=run['id'] or build.get('artifact',{}).get('sha256') not in hashes:p.error('Audit does not pass for selected build/artifact')
        if 'round' in d and d['round']!=round_number:p.error('Audit round differs from selected round')
        entry={'sha256':build['artifact']['sha256'],'buildRun':run['id'],'verifiedAt':d['completedAt'],'scope':d['scope'],'audit':str(path.resolve()),'auditSha256':digest(path),'counts':d['counts'],**{k:d.get(k,[]) for k in ['limitations','outsideScope','unverifiedStationaryEdges']}}
        current=[x for x in current if x.get('scope')!=entry['scope']]+[entry]
    report['currentArtifactScopes']=current
    if current:report['currentArtifact']=current[-1]
    history=list(previous.get('historicalArtifactAudits',[]))
    for entry in previous.get('currentArtifactScopes',[])+([legacy] if legacy else []):
        if entry.get('auditSha256') not in {x.get('auditSha256') for x in current+history}:history.append(entry)
    report['historicalArtifactAudits']=history
    report['immutableSummaries']=[{'file':str(x.resolve()),'sha256':digest(x)} for x in sorted(root.glob('runtime-*-final.json'))]
    report['limitations']=['Provisioning records certify no runtime behavior. Scenario status is separate from immutable independent audit scopes.',
        'Module reports verify trusted startup modules and dormant definition API; import reports verify synthetic data workflows, neither is native gameplay.',
        'General Folia persisted runtime/navigation/control/combat remain disabled. Stationary preview covers only named scenarios and exact artifact.',
        'Current categories require the selected build, artifact and explicit instance round. Earlier or unattributed results remain historical.',
        'Refreshing this index does not rerun audits. Protocol clients do not establish graphical fidelity, riding, parity or performance.']
    root.mkdir(parents=True,exist_ok=True);temporary=index.with_suffix('.json.tmp');temporary.write_text(json.dumps(report,indent=2)+'\n');temporary.replace(index)
    print(json.dumps({'build':run['id'],'round':round_number,'categories':{k:len(report[k]) for k in CATEGORIES+['provisioning']},'audits':len(current),'evidence':str(index)}))

if __name__=='__main__':main()
