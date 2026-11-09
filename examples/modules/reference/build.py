#!/usr/bin/env python3
"""Build the reference extension against only the public Animus API and Java 25."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--api-jar',type=Path,default=ROOT/'animus-api/build/libs/animus-api-1.2.0-SNAPSHOT.jar')
parser.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'))
parser.add_argument('--output',type=Path,default=HERE/'build')
args=parser.parse_args();api=args.api_jar.resolve();output=args.output.resolve()
if not api.is_file():parser.error('Build :animus-api:jar first or pass --api-jar')
with zipfile.ZipFile(api) as jar:
    classes=[entry.filename for entry in jar.infolist() if entry.filename.endswith('.class')]
    if not classes or any(not name.startswith('art/arcane/animus/api/') for name in classes):parser.error('Expected the standalone public API JAR')
output.mkdir(parents=True,exist_ok=True);classes=Path(tempfile.mkdtemp(prefix='reference-classes-',dir=output))
sources=sorted((HERE/'src').rglob('*.java'))
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
api_hash=sha(api)
command=[str(args.java_home/'bin/javac'),'--release','25','-classpath',str(api),'-d',str(classes),*map(str,sources)]
subprocess.run(command,check=True)
manifest={'schemaVersion':1,'id':'reference','version':'1.0.0','api':{'minimumMajor':1,'maximumMajor':1},
          'platforms':['SPIGOT','PAPER','FOLIA'],'dependencies':[],'main':'example.animus.reference.ReferenceModule','exports':[]}
encoded=(json.dumps(manifest,indent=2)+'\n').encode()
(output/'animus-module.json').write_bytes(encoded)
archive=output/'animus-reference-1.0.0.jar'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as jar:
    entries={str(path.relative_to(classes)):path.read_bytes() for path in sorted(classes.rglob('*.class'))}
    entries['animus-module.json']=encoded
    for name,body in sorted(entries.items()):
        info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
        jar.writestr(info,body)
shutil.rmtree(classes)
assert sha(api)==api_hash,'API JAR changed during compilation'
receipt={'schemaVersion':1,'module':'reference','version':'1.0.0','apiJar':str(api),'apiSha256':api_hash,
         'artifact':str(archive),'sha256':sha(archive),'sources':{str(path.relative_to(HERE)):sha(path) for path in [Path(__file__).resolve(),*sources]},
         'manifest':manifest,'compileClasspath':[str(api)],'javaRelease':25,'limitations':['Trusted Java code, not sandboxed','No server installation or gameplay verification performed by this builder']}
(output/'build.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'artifact':str(archive),'sha256':receipt['sha256'],'apiSha256':api_hash}))
