#!/usr/bin/env python3
"""Build a separate Folia-only QA fixture with immutable source provenance."""
import argparse, hashlib, json, os, shutil, subprocess, time
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--plugin',type=Path,required=True)
p.add_argument('--libraries',type=Path,required=True)
p.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'))
a=p.parse_args();root=Path(__file__).resolve().parents[2]
out=root/'.qa/folia-fixture'/f'{time.time_ns()}-{os.getpid()}';classes=out/'classes';classes.mkdir(parents=True)
sources=sorted((root/'qa/folia-proof/fixture').rglob('*.java'))
libraries=sorted(a.libraries.resolve().rglob('*.jar'))
api=[x for x in libraries if x.name.startswith('folia-api-')];assert len(api)==1
cp=os.pathsep.join(map(str,[a.plugin.resolve(),*libraries]))
subprocess.run([str(a.java_home/'bin/javac'),'--release','25','-cp',cp,'-d',str(classes),*map(str,sources)],check=True)
manifest=root/'qa/folia-proof/fixture/plugin.yml';shutil.copy2(manifest,classes/'plugin.yml')
jar=out/'AnimusFoliaFixture.jar';subprocess.run([str(a.java_home/'bin/jar'),'--create','--file',str(jar),'-C',str(classes),'.'],check=True)
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
receipt={'plugin':str(a.plugin.resolve()),'pluginSha256':sha(a.plugin),'foliaApi':str(api[0]),'foliaApiSha256':sha(api[0]),'fixtureSha256':sha(jar),'sources':{str(x.relative_to(root)):sha(x) for x in [*sources,manifest,Path(__file__).resolve()]}}
(out/'build.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'fixture':str(jar),'receipt':str(out/'build.json'),**receipt}))
