#!/usr/bin/env python3
"""Self-check public API fixtures; this does not certify host/schema/server behavior."""
import argparse, hashlib, json, os, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--api-jar',type=Path,default=ROOT/'animus-api/build/libs/animus-api-1.0.0-SNAPSHOT.jar')
p.add_argument('--module-fixtures',type=Path,required=True)
p.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'))
a=p.parse_args();api=a.api_jar.resolve();build=a.module_fixtures.resolve();receipt=json.loads((build/'build.json').read_text())
assert receipt['apiSha256']==sha(api)
for module in receipt['modules']:assert module['sha256']==sha(build/module['file'])
out=ROOT/'.qa/behavior-fixture-checks'/f'{time.time_ns()}-{os.getpid()}';classes=out/'classes';classes.mkdir(parents=True)
source=HERE/'ProbeHarness.java';before={str(path.relative_to(ROOT)):sha(path) for path in (HERE/'fixtures').rglob('*.json')}
subprocess.run([str(a.java_home/'bin/javac'),'--release','25','-cp',str(api),'-d',str(classes),str(source)],check=True)
log=out/'probe.log'
with log.open('w') as stream:code=subprocess.run([str(a.java_home/'bin/java'),'-cp',os.pathsep.join(map(str,[classes,api])),'ProbeHarness',str(build)],stdout=stream,stderr=subprocess.STDOUT).returncode
assert code==0,'Inspect fixture self-check: '+str(log)
result=json.loads(log.read_text().splitlines()[-1]);assert result['status']=='passed' and result['checks']==14
assert before=={str(path.relative_to(ROOT)):sha(path) for path in (HERE/'fixtures').rglob('*.json')}
summary={'schemaVersion':1,'status':'passed','scope':result['scope'],'checks':result['checks'],'build':str(build),'buildReceiptSha256':sha(build/'build.json'),'apiSha256':sha(api),'sourceSha256':sha(source),'verifierSha256':sha(Path(__file__)),'log':str(log),'logSha256':sha(log),'fixturesUnchanged':True,'fixtures':before}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps({'status':'passed','checks':14,'evidence':str(out),'summarySha256':sha(out/'summary.json')}))
