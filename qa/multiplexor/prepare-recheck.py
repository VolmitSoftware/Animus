#!/usr/bin/env python3
"""Provision a fresh named regression instance through Multiplexor; never starts it."""
import argparse,hashlib,json,re,runpy,shutil,socket,time
from pathlib import Path
import multiplexor_workspace as mw

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--binary',type=Path,default=mw.default_binary());p.add_argument('--workspace',type=Path,default=mw.default_workspace())
p.add_argument('--build-home',type=Path,help='Only when the build used a MULTIPLEXOR_BUILD_HOME override')
p.add_argument('--round',required=True,help='Positive round number, or gate for the milestone gate')
p.add_argument('--project',choices=['paper','spigot','folia'],required=True)
p.add_argument('--version',choices=['26.1.2','26.2','26.3'],required=True)
a=p.parse_args();assert re.fullmatch(r'[1-9][0-9]*|gate',a.round);a.binary=a.binary.resolve();a.workspace=a.workspace.resolve();a.transcript=[]
helpers=runpy.run_path(str(Path(__file__).with_name('managed-runtime.py')));run=helpers['run'];sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
version=a.version.replace('.','-');source_name=mw.baseline_name(a.project,a.version);name=f'animus-{"gate" if a.round=="gate" else "r"+a.round}-{a.project}-{version}'
base=a.workspace/'consumers/plugin-consumers/instances'/source_name;instance=a.workspace/'consumers/plugin-consumers/instances'/name
source_file=mw.evidence_root(a.workspace)/source_name/'runtime.json';source=json.loads(source_file.read_text());assert sha(base/'server.jar')==source['serverSha256']
evidence=mw.evidence_root(a.workspace)/name;evidence.mkdir(parents=True,exist_ok=True)
assert not instance.exists(),'Recheck preparation only creates a fresh instance; inspect existing evidence instead of resetting it'
run(a,'server','create',name,'--jar',str(base/'server.jar'),'--type',a.project,'--mc',a.version,'--isolated')
assert run(a,'instance','isolated',name).strip()=='true' and sha(instance/'server.jar')==source['serverSha256']
run(a,'runtime','settings','set-java','/opt/homebrew/opt/openjdk@25/bin/java','--instance',name)
run(a,'runtime','settings','set-heap','1G','--instance',name);run(a,'runtime','settings','set-preset','vanilla','--instance',name);run(a,'gameplay','prepare',name)
port=helpers['free_port']();run(a,'instance','port',name,str(port));assert 'eula=true' in (base/'eula.txt').read_text().lower();shutil.copy2(base/'eula.txt',instance/'eula.txt')
properties=instance/'server.properties';lines=properties.read_text().splitlines()
values={'server-ip':'127.0.0.1','online-mode':'false','white-list':'true','difficulty':'peaceful','pause-when-empty-seconds':'-1','view-distance':'2','simulation-distance':'2','max-players':'12','spawn-protection':'0','level-type':'minecraft:flat','generate-structures':'false','generator-settings':json.dumps({'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:stone','height':59},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains'},separators=(',',':'))}
properties.write_text('\n'.join(line for line in lines if line.partition('=')[0] not in values)+'\n'+'\n'.join(k+'='+v for k,v in values.items())+'\n')
(instance/'ops.json').write_text('[]\n');(instance/'whitelist.json').write_text('[]\n')
data=instance/'plugins/Animus';data.mkdir(parents=True,exist_ok=True);(data/'config.toml').write_text('statusMessage = "Multiplexor QA"\npacketProofEnabled = true\n')
mojang=Path.home()/'.gradle/caches/fabric-loom'/a.version/'minecraft-server.jar'
if mojang.exists():(instance/'cache').mkdir(exist_ok=True);shutil.copy2(mojang,instance/'cache'/f'mojang_{a.version}.jar')
run(a,'projects','destination',name,'--instance',name,'--platform','plugin','--mc',a.version)
with socket.socket() as s:assert s.connect_ex(('127.0.0.1',port))!=0
record={'schemaVersion':1,'scope':'Fresh isolated regression instance preparation only; no server startup or runtime validation','status':'prepared','instance':name,'project':a.project,'minecraftVersion':a.version,'build':source['build'],'channel':source['channel'],'serverSha256':sha(instance/'server.jar'),'port':port,'sourceDescriptor':str(source_file),'sourceDescriptorSha256':sha(source_file),'sourceInstance':source_name,'copiedWorldOrPlayerData':False,'preparedAtNs':time.time_ns(),'helperSha256':sha(Path(__file__)),'commands':a.transcript}
(evidence/'runtime.json').write_text(json.dumps(record,indent=2)+'\n');shutil.copy2(Path(__file__),evidence/'prepare-source.py');(evidence/'commands-prepare.json').write_text(json.dumps(a.transcript,indent=2)+'\n')
print(json.dumps({'status':'prepared','instance':name,'port':port,'descriptor':str(evidence/'runtime.json'),'descriptorSha256':sha(evidence/'runtime.json')}))
