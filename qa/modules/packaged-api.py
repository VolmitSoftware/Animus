#!/usr/bin/env python3
"""Verify public API identity using actual Shadow archives and an API-only external module.

Builds the custom-group variant in a captured source tree. Does not start servers,
change canonical build outputs, publish artifacts, or establish gameplay support.
Every invocation writes a new immutable evidence directory, including failures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT=Path(__file__).resolve().parents[2]
PUBLIC='art/arcane/animus/api/'
CUSTOM='qa.animus.relocated'
DESCRIPTION='A Manager for all things NPC.'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(condition,message):
    if not condition:raise AssertionError(message)
def write_json(path,value):path.write_text(json.dumps(value,indent=2,sort_keys=True)+'\n')
def source_files(root,folders,files=()):
    selected=[root/name for name in files]
    for folder in folders:
        selected.extend(path for path in (root/folder).rglob('*') if path.is_file()
                        and not any(part in {'build','.gradle','.git','__pycache__'} for part in path.relative_to(root).parts)
                        and path.name!='.DS_Store')
    return sorted(set(path for path in selected if path.is_file()))
def archive_classes(path):
    with zipfile.ZipFile(path) as archive:
        names=[entry.filename for entry in archive.infolist() if entry.filename.endswith('.class')]
        require(len(names)==len(set(names)),f'Duplicate class entries: {path}')
        return {name:archive.read(name) for name in names}
def metadata(path):
    with zipfile.ZipFile(path) as archive:text=archive.read('plugin.yml').decode()
    fields={}
    for line in text.splitlines():
        match=re.fullmatch(r'([a-z-]+):\s*(.*?)\s*',line)
        if match:fields[match[1]]=match[2].strip("'\"")
    return fields

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-jar',type=Path,default=ROOT/'animus-api/build/libs/animus-api-1.1.0-SNAPSHOT.jar')
    parser.add_argument('--runtime-jar',type=Path,default=ROOT/'bukkit/build/libs/Animus v0.1.0 [CraftBukkit] 26.1.2-26.3.jar')
    parser.add_argument('--volmlib',type=Path,default=ROOT/'.qa/multiplexor-sources-runtime-9/VolmLib')
    parser.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'))
    parser.add_argument('--build-log',type=Path)
    parser.add_argument('--output',type=Path,default=ROOT/f'.qa/module-packaging/{time.time_ns()}-{os.getpid()}')
    args=parser.parse_args();output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    receipt={'schemaVersion':1,'status':'running','scope':'packaged public API identity and trusted external module lifecycle; no live server or gameplay',
             'startedAtUnixNs':time.time_ns(),'commands':[],'checks':[],'sources':{},'artifacts':{},
             'limitations':['Standalone JVM probes do not start Bukkit, Paper, Spigot or Folia servers',
                            'Platform enum scenarios only exercise module manifest/lifecycle semantics',
                            'Trusted Java extensions are not sandboxed','No API publication or binary hot replacement performed']}
    watched={};inputs=output/'inputs';inputs.mkdir();environment=os.environ.copy();environment['JAVA_HOME']=str(args.java_home.resolve())
    def capture(path,destination):
        path=path.resolve();require(path.is_file() and not path.is_symlink(),f'Missing/non-regular input {path}')
        before=sha(path);destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,destination)
        require(sha(destination)==before and sha(path)==before,f'Input changed during capture: {path}')
        watched[str(path)]=before;watched[str(destination.resolve())]=before;return before
    def check(identity,condition,detail=None):
        require(condition,identity);item={'id':identity,'passed':True}
        if detail is not None:item['detail']=detail
        receipt['checks'].append(item)
    def run(label,command,cwd=ROOT,timeout=300):
        log=output/f'{label}.log';started=time.monotonic()
        timed_out=False;exit_code=None
        with log.open('w') as stream:
            try:exit_code=subprocess.run(list(map(str,command)),cwd=cwd,env=environment,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout).returncode
            except subprocess.TimeoutExpired:timed_out=True
        receipt['commands'].append({'label':label,'command':list(map(str,command)),'cwd':str(cwd),'exitCode':exit_code,'timedOut':timed_out,
                                    'seconds':round(time.monotonic()-started,3),'log':str(log),'logSha256':sha(log)})
        require(exit_code==0 and not timed_out,f'{label} failed or timed out; see {log}')
        return log.read_text()
    try:
        run('java-version',[args.java_home/'bin/java','-version'])
        api=inputs/'animus-api.jar';runtime=inputs/'animus-runtime.jar'
        receipt['artifacts']['canonicalApi']={'original':str(args.api_jar.resolve()),'path':str(api),'sha256':capture(args.api_jar,api)}
        receipt['artifacts']['canonicalRuntime']={'original':str(args.runtime_jar.resolve()),'path':str(runtime),'sha256':capture(args.runtime_jar,runtime)}
        if args.build_log:
            receipt['canonicalBuildLog']={'path':str(inputs/'canonical-build.log'),'sha256':capture(args.build_log,inputs/'canonical-build.log')}
        # Production/build inputs are isolated; ignored private fixtures and arbitrary workspace files are never copied.
        source=output/'custom-source'/'Animus';volmlib=output/'custom-source'/'VolmLib'
        production=source_files(ROOT,['animus-api/src','core/src','bukkit/src','converter/src','gradle'],
            ['settings.gradle','build.gradle','gradle.properties','gradlew','gradlew.bat','LICENSE',
             'animus-api/build.gradle','core/build.gradle','bukkit/build.gradle','converter/build.gradle'])
        for path in production:
            name=str(path.relative_to(ROOT));receipt['sources'][name]=capture(path,source/name)
        for path in [Path(__file__),ROOT/'qa/modules/PackagedApiProbe.java',*source_files(ROOT,['examples/modules/reference/src'],['examples/modules/reference/build.py'])]:
            name=str(path.relative_to(ROOT));receipt['sources'][name]=capture(path,inputs/'sources'/name)
        pinned=args.volmlib.resolve();require((pinned/'settings.gradle').is_file(),'Pinned VolmLib source unavailable')
        dependency_sources=source_files(pinned,['.'])
        dependency_hashes={str(path.relative_to(pinned)):capture(path,volmlib/path.relative_to(pinned)) for path in dependency_sources}
        receipt['volmLib']={'source':str(pinned),'files':dependency_hashes}
        write_json(output/'source-manifest.json',{'sources':receipt['sources'],'volmLib':receipt['volmLib']})
        receipt['sourceManifestSha256']=sha(output/'source-manifest.json')
        api_classes=archive_classes(api)
        check('standalone-api-only-public-namespace',bool(api_classes) and all(name.startswith(PUBLIC) for name in api_classes),{'classCount':len(api_classes)})
        check('api-java-base-only',run('api-jdeps',[args.java_home/'bin/jdeps','--multi-release','25','--print-module-deps',api]).strip()=='java.base')
        reference_root=inputs/'sources'/'examples/modules/reference'
        reference_build=output/'reference-build'
        run('reference-api-only-compile',[sys.executable,reference_root/'build.py','--api-jar',api,'--java-home',args.java_home,'--output',reference_build])
        reference_receipt=json.loads((reference_build/'build.json').read_text())
        check('reference-compile-only-standalone-api',reference_receipt['compileClasspath']==[str(api)] and reference_receipt['apiSha256']==sha(api))
        good=output/'modules';good.mkdir();reference=good/'reference.jar'
        shutil.copy2(reference_receipt['artifact'],reference)
        receipt['artifacts']['externalReference']={'path':str(reference),'sha256':sha(reference),'buildReceiptSha256':sha(reference_build/'build.json')}
        bad=output/'invalid-modules';bad.mkdir()
        with zipfile.ZipFile(reference) as original,zipfile.ZipFile(bad/'bundled-api.jar','w') as modified:
            for entry in original.infolist():modified.writestr(entry,original.read(entry.filename))
            modified.writestr(PUBLIC+'Injected.class',api_classes[PUBLIC+'TrustedModule.class'])
        template=(inputs/'sources'/'qa/modules/PackagedApiProbe.java').read_text()
        def variant(label,jar,namespace):
            classes=archive_classes(jar);prefix=namespace.replace('.','/')+'/'
            check(label+'-api-class-set-identical',{name for name in classes if name.startswith(PUBLIC)}==set(api_classes))
            check(label+'-api-bytecode-identical',all(classes[name]==body for name,body in api_classes.items()))
            check(label+'-private-core-packaged',prefix+'core/extension/ModuleHost.class' in classes and prefix+'core/extension/loader/StartupModules.class' in classes and prefix+'core/extension/ExtensionRuntime.class' in classes)
            fields=metadata(jar)
            check(label+'-exact-description',fields.get('description')==DESCRIPTION)
            check(label+'-entrypoint-namespace',fields.get('main')==namespace+'.bukkit.AnimusPlugin')
            if namespace!='art.arcane.animus':
                check(label+'-no-relocated-public-api',not any(name.startswith(prefix+'api/') for name in classes))
                check(label+'-private-core-relocated',not any(name.startswith('art/arcane/animus/core/') for name in classes))
            probe=output/label/'src'/'qa/animus/packaging/PackagedApiProbe.java';probe.parent.mkdir(parents=True)
            probe.write_text(template.replace('@PRIVATE_NAMESPACE@',namespace));compiled=output/label/'classes';compiled.mkdir()
            run(label+'-probe-compile',[args.java_home/'bin/javac','--release','25','-classpath',jar,'-d',compiled,probe])
            classpath=os.pathsep.join([str(compiled),str(jar)])
            transcript=run(label+'-packaged-jvm',[args.java_home/'bin/java','-classpath',classpath,'qa.animus.packaging.PackagedApiProbe',good,bad,jar])
            records=[json.loads(line) for line in transcript.splitlines() if line.startswith('{')]
            expected={'api-loaded-from-packaged-runtime','host-loaded-from-packaged-runtime','external-module-absent-from-jvm-classpath','bundled-public-api-rejected'}
            for platform in ['spigot','paper','folia']:
                expected.update(name+'-'+platform for name in ['reference-discovery','reference-start','parent-api-class-identity','private-core-hidden-from-module','reference-action','reference-cleanup','post-close-admission-rejected'])
            check(label+'-complete-jvm-checks',len(records)==len(expected) and {r['check'] for r in records}==expected and all(r['passed'] is True for r in records))
            receipt.setdefault('variants',{})[label]={'runtime':str(jar),'runtimeSha256':sha(jar),'namespace':namespace,'metadata':fields,
                'probeSha256':sha(probe),'runtimeClasspath':[str(compiled),str(jar)],'checks':records}
        variant('canonical',runtime,'art.arcane.animus')
        run('custom-group-shadow-build',[source/'gradlew','--console=plain','--no-daemon','--max-workers=2',
            '--project-cache-dir',output/'gradle-project-cache',f'-PlocalVolmLibDirectory={volmlib}',f'-PprojectGroup={CUSTOM}',
            ':animus-api:jar',':bukkit:verifyArtifact'],cwd=source,timeout=600)
        built=list((source/'bukkit/build/libs').glob('*.jar'));require(len(built)==1,'Expected exactly one custom Shadow artifact')
        custom=inputs/'custom-runtime.jar';shutil.copy2(built[0],custom)
        receipt['artifacts']['customRuntime']={'path':str(custom),'sha256':sha(custom),'builtFrom':str(source),'projectGroup':CUSTOM}
        watched[str(custom)]=sha(custom)
        custom_api=source/'animus-api/build/libs/animus-api-1.1.0-SNAPSHOT.jar'
        receipt['artifacts']['customApi']={'path':str(custom_api),'sha256':sha(custom_api)}
        check('custom-api-bytecode-stable',archive_classes(custom_api)==api_classes)
        variant('custom',custom,CUSTOM)
        for path,digest in watched.items():require(sha(Path(path))==digest,f'Source or artifact changed during verification: {path}')
        receipt['status']='passed'
    except Exception as error:
        receipt['status']='failed';receipt['error']=f'{type(error).__name__}: {error}'
    finally:
        receipt['finishedAtUnixNs']=time.time_ns()
        receipt['harnessSha256']=sha(Path(__file__))
        receipt['jvmChecks']=sum(len(value['checks']) for value in receipt.get('variants',{}).values())
        write_json(output/'result.json',receipt)
    print(json.dumps({'status':receipt['status'],'result':str(output/'result.json'),'sha256':sha(output/'result.json'),'checks':len(receipt['checks']),
                      'error':receipt.get('error')}))
    return 0 if receipt['status']=='passed' else 1

if __name__=='__main__':raise SystemExit(main())
