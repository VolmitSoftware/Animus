#!/usr/bin/env python3
"""Validate public fixtures/assertions locally; never starts a Minecraft server."""
import argparse, hashlib, json, os, runpy, shutil, subprocess, time
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--draft-run',type=Path,required=True,help='Explicit successful isolated draft compilation')
args=parser.parse_args();root=Path(__file__).resolve().parents[2];draft=args.draft_run.resolve()
verification=json.loads((draft/'verification.json').read_text());assert verification['status']=='passed'
output=root/'.qa/import-fixture-validation'/f'{time.time_ns()}-{os.getpid()}';classes=output/'classes';classes.mkdir(parents=True)
sources=[root/'qa/import-applications/FixtureCheck.java',Path(__file__).resolve(),root/'qa/import-applications/assertions.py',*sorted((root/'qa/import-applications/fixtures').glob('*.json'))]
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
receipt={'scope':'Public fixture and assertion validation against isolated draft; no production/server verification','draftVerification':str(draft/'verification.json'),'draftVerificationSha256':sha(draft/'verification.json'),'sources':{str(p.relative_to(root)):sha(p) for p in sources},'phases':[],'status':'running'}
for path in sources:
    dest=output/'sources'/path.relative_to(root);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
api=runpy.run_path(str(root/'qa/import-applications/assertions.py'))
classpath=os.pathsep.join(map(str,[classes,draft/'classes',draft/'lib/*']))
java=Path('/opt/homebrew/opt/openjdk@25/bin')
try:
    subprocess.run([str(java/'javac'),'--release','25','-cp',classpath,'-d',str(classes),str(sources[0])],check=True)
    for phase in ['prepare','rollback-replacement','rollback-rest']:
        log=output/f'{phase}.log'
        with log.open('w') as stream:
            result=subprocess.run([str(java/'java'),'-cp',classpath,'art.arcane.animus.core.migration.FixtureCheck',str(output),str(root/'qa/import-applications/fixtures'),phase],stdout=stream,stderr=subprocess.STDOUT,timeout=30)
        receipt['phases'].append({'phase':phase,'exitCode':result.returncode,'log':str(log),'logSha256':sha(log)})
        assert result.returncode==0,log.read_text()
        state=json.loads((output/'receipt.json').read_text());data=output/'data'
        if phase=='prepare':
            api['installed'](data,state['updatedImport'],state['replacementApplication'])
            assert len(api['duplicate'](data,state['baselineImport'],state['copyApplication'],'qa'))==3
            assert len(api['definitions'](data))==7
        elif phase=='rollback-replacement':
            api['installed'](data,state['baselineImport'],state['baseApplication'])
            assert len(api['duplicate'](data,state['baselineImport'],state['copyApplication'],'qa'))==3
            assert len(api['definitions'](data))==6
        else:assert not api['snapshot'](data)
        for kind,key in [('baseline','baselineImport'),('updated','updatedImport')]:api['original_archive'](data,state[key],(root/'qa/import-applications/fixtures'/f'{kind}.json').read_bytes())
    receipt['status']='passed'
except Exception as error:
    receipt['status']='failed';receipt['error']=str(error);raise
finally:
    (output/'validation.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({'status':receipt['status'],'evidence':str(output/'validation.json')}))
