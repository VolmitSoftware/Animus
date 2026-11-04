#!/usr/bin/env python3
"""Build the QA-only manual behavior lifecycle observer against an exact Spigot API."""
import argparse,hashlib,json,os,shutil,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--spigot-api',type=Path,required=True)
p.add_argument('--plugin',type=Path,required=True)
p.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'))
a=p.parse_args();out=ROOT/'.qa/behavior-plugin-fixture'/f'{time.time_ns()}-{os.getpid()}';classes=out/'classes';classes.mkdir(parents=True)
sources=sorted((ROOT/'qa/behaviors/fixture/src').rglob('*.java'));metadata=ROOT/'qa/behaviors/fixture/plugin.yml'
subprocess.run([str(a.java_home/'bin/javac'),'--release','25','-cp',str(a.spigot_api.resolve()),'-d',str(classes),*map(str,sources)],check=True)
shutil.copy2(metadata,classes/'plugin.yml');artifact=out/'AnimusBehaviorFixture.jar'
subprocess.run([str(a.java_home/'bin/jar'),'--create','--file',str(artifact),'-C',str(classes),'.'],check=True)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
record={'schemaVersion':1,'plugin':str(a.plugin.resolve()),'pluginSha256':sha(a.plugin),'spigotApi':str(a.spigot_api.resolve()),'spigotApiSha256':sha(a.spigot_api),'fixtureSha256':sha(artifact),'sources':{str(path.relative_to(ROOT)):sha(path) for path in [Path(__file__).resolve(),metadata,*sources]}}
(out/'build.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'fixture':str(artifact),'receipt':str(out/'build.json')}))
