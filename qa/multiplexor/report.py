#!/usr/bin/env python3
"""Collect existing Multiplexor evidence without rerunning or relabeling checks."""
import argparse, hashlib, json, socket
from datetime import datetime, timezone
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--workspace',type=Path,default=Path('.qa/multiplexor-workspace'))
parser.add_argument('--binary',type=Path,default=Path('../ServerMultiplexor/multiplexor'))
args=parser.parse_args()
root=args.workspace/'animus-evidence'
status_path=args.workspace/'build-status-final.json'
status=json.loads(status_path.read_text())['runs'][0]
artifacts=[a for job in status['jobs'] for a in job.get('artifacts',[])]
def digest(file):
    h=hashlib.sha256()
    with file.open('rb') as stream:
        for data in iter(lambda:stream.read(1024*1024),b''):h.update(data)
    return h.hexdigest()
report={'schemaVersion':1,'generatedAt':datetime.now(timezone.utc).isoformat(),'runner':{'binary':str(args.binary.resolve()),'sha256':digest(args.binary)},'build':{'id':status['id'],'status':status['status'],'record':str(status_path),'artifacts':artifacts,'sourceSnapshot':str(args.workspace/'source-snapshot-final.json')},'lifecycle':[],'gameplay':[],'native':[],'failedAttempts':[],'protocolCapability':[]}
for target in sorted(root.glob('animus-*')):
    runtime=target/'runtime.json'
    if runtime.exists():
        data=json.loads(runtime.read_text())
        with socket.socket() as sock:
            sock.settimeout(0.2)
            port_closed=sock.connect_ex(('127.0.0.1',data['port']))!=0
        report['lifecycle'].append({key:data[key] for key in ['project','minecraftVersion','build','channel','status','buildRun','plugins']}|{'evidence':str(runtime),'portClosedNow':port_closed})
    for phase in ['prepare','verify']:
        game=target/f'gameplay-final-{phase}.json'
        if game.exists():
            data=json.loads(game.read_text())
            report['gameplay'].append({'phase':phase,'status':data['status'],'steps':len(data['steps']),'plugins':data['plugins'],'evidence':str(game),'runner':'Multiplexor gameplay run'})
    native_dirs=sorted(target.glob('native-*'))
    if native_dirs:
        latest=native_dirs[-1]/'result.json'
        if latest.exists():
            data=json.loads(latest.read_text())
            report['native'].append({'minecraftVersion':data['minecraftVersion'],'status':data['status'],'checks':len(data.get('clientResult',{}).get('checks',[])),'plugins':data['plugins'],'serverPortClosed':data.get('serverPortClosed',False),'error':data.get('clientResult',{}).get('error',data.get('error')),'evidence':str(latest),'runner':data['runner']})
        else:report['native'].append({'target':target.name,'status':'running','evidence':str(latest)})
    for attempt in list(target.glob('prior-runtime-*.json'))+list(target.glob('native-*/result.json')):
        data=json.loads(attempt.read_text())
        if data.get('status')=='failed':
            failure={'evidence':str(attempt),'error':data.get('clientResult',{}).get('error',data.get('error'))}
            client_evidence=data.get('clientResult',{}).get('evidence')
            if client_evidence:
                failure['clientEvidence']=client_evidence
                failure['decoderCauses']=[line.strip() for log in Path(client_evidence).glob('*.stderr.log') for line in log.read_text().splitlines() if 'Caused by:' in line][:10]
            report['failedAttempts'].append(failure)
for version in ['26.1.2','26.2','26.3']:
    doctor=args.workspace/f'doctor-{version}.json'
    if doctor.exists():
        data=json.loads(doctor.read_text())
        report['protocolCapability'].append({'minecraftVersion':version,'status':data['status'],'runner':'Multiplexor Mineflayer doctor','evidence':str(doctor),'errors':data.get('protocolCapability',{}).get('errors',[])})
report['limitations']=['Folia packet proof remains deliberately disabled; Folia evidence covers managed lifecycle and file commands.','Native-codec client reports are distinct from Multiplexor Mineflayer reports.','Spigot has exact API compilation evidence only; no cached runtime jar was available.','Protocol checks do not demonstrate graphical fidelity, production NPC lifecycle, combat parity, or comparative performance.']
(root/'index.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'build':report['build']['status'],'lifecycle':[(x['project'],x['minecraftVersion'],x['status']) for x in report['lifecycle']],'native':[(x.get('minecraftVersion'),x['status'],x.get('checks')) for x in report['native']],'evidence':str(root/'index.json')}))
