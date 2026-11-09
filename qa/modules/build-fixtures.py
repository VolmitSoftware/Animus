#!/usr/bin/env python3
"""Build public synthetic module JAR fixtures against only the active public API."""
import argparse,hashlib,json,os,shutil,subprocess,time,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--api-jar',type=Path,default=ROOT/'animus-api/build/libs/animus-api-1.2.0-SNAPSHOT.jar')
parser.add_argument('--output',type=Path)
parser.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk@25'))
args=parser.parse_args();api=args.api_jar.resolve();output=(args.output or ROOT/'.qa/module-fixtures'/f'{time.time_ns()}-{os.getpid()}').resolve()
output.mkdir(parents=True,exist_ok=False);modules=output/'modules';modules.mkdir()
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
api_hash=sha(api)
with zipfile.ZipFile(api) as archive:
    assert all(name.startswith('art/arcane/animus/api/') for name in archive.namelist() if name.endswith('.class'))
report={'schemaVersion':1,'scope':'Public synthetic trusted module fixtures; no deployment','apiJar':str(api),'apiSha256':api_hash,'builderSha256':sha(Path(__file__)),'modules':[],'status':'building'}
platforms=['SPIGOT','PAPER','FOLIA']

def descriptor(identity,dependencies=(),exports=(),api_major=1,supported=platforms):
    return {'schemaVersion':1,'id':identity,'version':'1.0.0','api':{'minimumMajor':api_major,'maximumMajor':api_major},'platforms':supported,
            'dependencies':[{'id':name,'minimumInclusive':'1.0.0','maximumExclusive':'2.0.0'} for name in dependencies],
            'main':'fixtureqa.'+identity+'.Module','exports':list(exports)}

def expression(manifest):
    deps=','.join('new ModuleManifest.Dependency("'+d['id']+'",new Version(1,0,0),new Version(2,0,0))' for d in manifest['dependencies'])
    return 'new ModuleManifest("'+manifest['id']+'",new Version(1,0,0),new ApiRange('+str(manifest['api']['minimumMajor'])+','+str(manifest['api']['maximumMajor'])+'),Set.of('+','.join('Platform.'+p for p in manifest['platforms'])+'),List.of('+deps+'))'

def module(manifest,enable='',disable='',fields='',extra_sources=None,classpath=(),raw_entries=None,expected='ACTIVE',source_override=None,filename=None):
    identity=manifest['id'];work=output/'sources'/identity;work.mkdir(parents=True,exist_ok=True);classes=output/'classes'/identity;classes.mkdir(parents=True,exist_ok=True)
    source=source_override or ('package fixtureqa.'+identity+';import art.arcane.animus.api.*;import java.util.*;import java.util.concurrent.*;'
        'public final class Module implements TrustedModule {'+fields+'static {System.out.println("Animus module QA initialized '+identity+'");}'
        'public ModuleManifest manifest(){return '+expression(manifest)+';}public void enable(ModuleContext context)throws Exception {'+enable+'}'
        'public void disable()throws Exception {'+disable+'}static void mark(String value){System.out.println("Animus module QA "+value);}'
        'static Capabilities.Descriptor action(String name){return new Capabilities.Descriptor(new Capabilities.Key("'+identity+'",name),Capabilities.Kind.ACTION,"QA "+name,"{}",Set.of(Platform.SPIGOT,Platform.PAPER,Platform.FOLIA),false);}}')
    sources={manifest['main'].replace('.','/')+'.java':source,**(extra_sources or {})}
    paths=[]
    for name,text in sources.items():
        path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);paths.append(path)
    command=[str(args.java_home/'bin/javac'),'--release','25','-classpath',os.pathsep.join(map(str,[api,*classpath])),'-d',str(classes),*map(str,paths)]
    compiled=subprocess.run(command,text=True,capture_output=True);(work/'compile.log').write_text(compiled.stdout+compiled.stderr);assert compiled.returncode==0,compiled.stderr
    entries={str(path.relative_to(classes)):path.read_bytes() for path in classes.rglob('*.class')}
    entries['animus-module.json']=(json.dumps(manifest,indent=2)+'\n').encode();entries.update(raw_entries or {})
    jar=modules/(filename or identity+'.jar')
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,body in sorted(entries.items()):
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,body)
    report['modules'].append({'file':jar.name,'sha256':sha(jar),'manifest':manifest,'expectedStartup':expected,'sourceHashes':{str(p.relative_to(output)):sha(p) for p in paths},'compileClasspath':[str(api),*map(str,classpath)]})
    return jar

try:
    api_source=ROOT/'qa/modules/fixtures/src/example/animus/qa/ApiModule.java'
    data=descriptor('qaapi');data['main']='example.animus.qa.ApiModule';module(data,source_override=api_source.read_text())
    report['apiFixtureSourceSha256']=sha(api_source)
    base=module(descriptor('qabase',exports=['fixtureqa.shared']),enable='context.onClose(()->mark("resource qabase"));mark("enabled qabase");',disable='mark("disabled qabase");',extra_sources={'fixtureqa/shared/Link.java':'package fixtureqa.shared;public final class Link {public static String value(){return "{\\\"linked\\\":true}";}}'.replace('\\\"','\\"')})
    module(descriptor('qalink',dependencies=['qabase']),enable='context.onClose(()->mark("resource qalink"));context.registrations().action(action("linked"),i->CompletableFuture.completedFuture(fixtureqa.shared.Link.value()));mark("enabled qalink");',disable='mark("disabled qalink");',classpath=[base])
    module(descriptor('qabroken'),enable='context.onClose(()->mark("resource qabroken first"));context.onClose(()->mark("resource qabroken second"));context.registrations().action(action("partial"),i->CompletableFuture.completedFuture("{}"));throw new IllegalStateException("public synthetic startup failure");',disable='mark("disabled qabroken");',expected='FAILED')
    module(descriptor('qachildbad',dependencies=['qabroken']),expected='UNAVAILABLE')
    module(descriptor('qanamespace'),enable='context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key("reference","stolen"),Capabilities.Kind.ACTION,"Invalid foreign namespace","{}",Set.of(Platform.PAPER,Platform.SPIGOT,Platform.FOLIA),false),i->CompletableFuture.completedFuture("{}"));',disable='mark("disabled qanamespace");',expected='FAILED')
    module(descriptor('qafuture',api_major=2),expected='REJECTED')
    module(descriptor('qaplatform',supported=['FABRIC']),expected='REJECTED')
    module(descriptor('qamissing',dependencies=['absent']),expected='REJECTED')
    module(descriptor('qacyclea',dependencies=['qacycleb']),expected='REJECTED')
    module(descriptor('qacycleb',dependencies=['qacyclea']),expected='REJECTED')
    duplicate=module(descriptor('qaduplicate'),expected='REJECTED');copy=modules/'qaduplicate-copy.jar';shutil.copy2(duplicate,copy);report['modules'].append({**report['modules'][-1],'file':copy.name,'sha256':sha(copy)})
    module(descriptor('qabundled'),raw_entries={'art/arcane/animus/api/TrustedModule.class':b'not an API class'},expected='REJECTED')
    module(descriptor('qawait'),fields='private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<String>> pending=new java.util.concurrent.atomic.AtomicReference<>();',enable='context.registrations().action(action("hold"),i->{CompletableFuture<String> future=new CompletableFuture<>();if(!pending.compareAndSet(null,future))throw new IllegalStateException("Only one owned hold");mark("pending qawait");return future;});mark("enabled qawait");',disable='mark("disabled qawait");CompletableFuture<String> future=pending.getAndSet(null);if(future!=null)future.complete("{\\\"late\\\":true}");'.replace('\\\"','\\"'))
    reference_source=ROOT/'examples/modules/reference/src/example/animus/reference/ReferenceModule.java'
    replacement_manifest=descriptor('reference');replacement_manifest['main']='example.animus.reference.ReferenceModule'
    variant=module(replacement_manifest,source_override=reference_source.read_text().replace('Hello from a trusted external module','Replacement reference after restart'),filename='reference-replacement.jar')
    replacement=output/'replacements/reference.jar';replacement.parent.mkdir();shutil.move(variant,replacement)
    report['replacement']={**report['modules'].pop(),'file':str(replacement.relative_to(output)),'originalSourceSha256':sha(reference_source)}
    assert sha(api)==api_hash,'API artifact changed during fixture compilation'
    report['status']='built'
except BaseException as error:
    report['status']='failed';report['error']=str(error);raise
finally:
    (output/'build.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'output':str(output),'modules':len(report['modules'])}))
