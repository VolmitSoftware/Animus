#!/usr/bin/env python3
"""Load public fixture JARs against captured current core/API artifacts, without a server."""
import argparse,hashlib,json,os,shutil,subprocess,time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fixtures',type=Path,required=True)
args=parser.parse_args();fixtures=args.fixtures.resolve();manifest=json.loads((fixtures/'build.json').read_text())
assert manifest['status']=='built'
output=ROOT/'.qa/module-fixture-validation'/f'{time.time_ns()}-{os.getpid()}';libs=output/'lib';libs.mkdir(parents=True)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
api=Path(manifest['apiJar']);assert sha(api)==manifest['apiSha256']
core=ROOT/'core/build/libs/core-0.1.0.jar';assert core.is_file()
sources=[Path(__file__).resolve(),ROOT/'qa/modules/FixtureSmoke.java',ROOT/'qa/modules/build-fixtures.py',ROOT/'qa/modules/fixtures/src/example/animus/qa/ApiModule.java']
inputs=[*sorted((ROOT/'.qa/next-modules/dependencies/lib').glob('*.jar')),core,api]
# Explicit active core/API override pinned third-party dependencies of the earlier isolated draft.
for path in inputs:
    if path.name.startswith(('core-','converter-','animus-api-')) and path not in [core,api]:continue
    target=libs/path.name;shutil.copy2(path,target);assert sha(target)==sha(path)
for source in sources:
    target=output/'sources'/source.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
report={'scope':'Actual public fixture JARs against captured active core/API; no server verification','status':'running','fixtureBuild':str(fixtures/'build.json'),'fixtureBuildSha256':sha(fixtures/'build.json'),'libraries':{p.name:sha(p) for p in sorted(libs.glob('*.jar'))},'sources':{str(p.relative_to(ROOT)):sha(p) for p in sources},'commands':[]}
for item in manifest['modules']:assert sha(fixtures/'modules'/item['file'])==item['sha256']
classes=output/'classes';classes.mkdir();java=Path('/opt/homebrew/opt/openjdk@25/bin')
classpath=os.pathsep.join(map(str,[classes,libs/'*']))
try:
    for label,command in [('compile',[java/'javac','--release','25','-cp',classpath,'-d',classes,ROOT/'qa/modules/FixtureSmoke.java']),('run',[java/'java','-cp',classpath,'art.arcane.animus.qa.FixtureSmoke',fixtures/'modules',output/'data'])]:
        result=subprocess.run(list(map(str,command)),capture_output=True,text=True,timeout=30)
        log=output/f'{label}.log';log.write_text(result.stdout+result.stderr);report['commands'].append({'label':label,'exitCode':result.returncode,'logSha256':sha(log)})
        assert result.returncode==0,f'{label}: {log}'
    report['status']='passed'
except BaseException as error:
    report['status']='failed';report['error']=str(error);raise
finally:
    path=output/'summary.json';path.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'evidence':str(path),'sha256':sha(path)}))
