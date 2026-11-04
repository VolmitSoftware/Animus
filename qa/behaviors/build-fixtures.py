#!/usr/bin/env python3
"""Build isolated trusted behavior fixtures using only the pinned public API/JDK."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,time,zipfile

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--api-jar',type=Path,default=ROOT/'animus-api/build/libs/animus-api-1.0.0-SNAPSHOT.jar');p.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'));a=p.parse_args()
api=a.api_jar.resolve();java=a.java_home/'bin'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
with zipfile.ZipFile(api) as archive:
    classes=[x.filename for x in archive.infolist() if x.filename.endswith('.class')]
    assert classes and all(x.startswith('art/arcane/animus/api/') for x in classes),'Standalone public API JAR required'
api_hash=sha(api)
out=ROOT/'.qa/behavior-fixtures'/f'{time.time_ns()}-{os.getpid()}';out.mkdir(parents=True)
modules=[]
dependency={'id':'qabprobe','minimumInclusive':'1.0.0','maximumExclusive':'2.0.0'}
def build(identity,main,sources,dependencies=(),exports=(),classpath=()):
    classes=out/(identity+'-classes');classes.mkdir()
    subprocess.run([str(java/'javac'),'--release','25','-cp',os.pathsep.join(map(str,[api,*classpath])),'-d',str(classes),*map(str,sources)],check=True)
    manifest={'schemaVersion':1,'id':identity,'version':'1.0.0','api':{'minimumMajor':1,'maximumMajor':1},'platforms':['PAPER','SPIGOT','FOLIA'],'dependencies':list(dependencies),'main':main,'exports':list(exports)}
    jar=out/(identity+'.jar');entries={str(p.relative_to(classes)):p.read_bytes() for p in classes.rglob('*.class')};entries['animus-module.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in sorted(entries.items()):
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,data)
    modules.append({'id':identity,'file':jar.name,'sha256':sha(jar),'manifest':manifest,'compileClasspath':[{'file':str(p),'sha256':sha(p)} for p in [api,*classpath]],'expectedStartup':'FAILED' if identity=='qabehaviorbad' else 'ACTIVE','sources':{str(p.relative_to(ROOT)):sha(p) for p in sources}})
    return jar
base=HERE/'modules/src/example/animus/behavior'
probe=build('qabprobe','example.animus.behavior.control.ProbeFixture',sorted((base/'control').glob('*.java')),exports=['example.animus.behavior.control'])
build('qabehavior','example.animus.behavior.BehaviorFixture',[base/'BehaviorFixture.java'],[dependency],classpath=[probe])
build('qabehaviorctl','example.animus.behavior.release.ReleaseFixture',[base/'release/ReleaseFixture.java'],[dependency],classpath=[probe])
build('qabehaviorbad','example.animus.behavior.BadSchemaFixture',[base/'BadSchemaFixture.java'])
assert sha(api)==api_hash,'API JAR changed during compile'
record={'schemaVersion':1,'status':'compiled-only','scope':'Synthetic API-only behavior fixture modules; no server or interpreter validation','apiSha256':sha(api),'modules':modules,'builderSha256':sha(Path(__file__))}
(out/'build.json').write_text(json.dumps(record,indent=2)+'\n');(ROOT/'.qa/behavior-fixtures/latest.json').write_text(json.dumps({'build':str(out),'receiptSha256':sha(out/'build.json')},indent=2)+'\n');print(json.dumps({'build':str(out),'modules':len(modules),'apiSha256':sha(api)}))
