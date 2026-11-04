package art.arcane.animus.core.extension.loader;

import art.arcane.animus.api.*;
import art.arcane.animus.core.extension.*;
import art.arcane.animus.core.config.ModuleSettings;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModuleLoadingTest {
    @TempDir Path temp;
    private final Path api=Path.of(System.getProperty("apiJar"));
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final DefinitionGateway DEFINITIONS=new DefinitionGateway(){
        public DefinitionService scoped(BooleanSupplier available){return this;}
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.NOT_FOUND,"missing","Missing"));}
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"offline","Offline"));}
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"offline","Offline"));}
    };
    @BeforeEach void reset()throws IOException {temp=temp.toRealPath();System.clearProperty("qa.module.events");for(String id:List.of("base","child","other","bad","alpha","beta","leaf","unrelated","mismatch","held"))System.clearProperty("module.init."+id);}
    private ModuleHost host(){return new ModuleHost(Platform.PAPER,DEFINITIONS,4,Duration.ofSeconds(1));}
    private static JsonObject descriptor(String id,String...dependencies){
        JsonObject d=new JsonObject();d.addProperty("schemaVersion",1);d.addProperty("id",id);d.addProperty("version","1.0.0");
        JsonObject api=new JsonObject();api.addProperty("minimumMajor",1);api.addProperty("maximumMajor",1);d.add("api",api);
        JsonArray platforms=new JsonArray();platforms.add("PAPER");d.add("platforms",platforms);JsonArray deps=new JsonArray();
        for(String required:dependencies){JsonObject dep=new JsonObject();dep.addProperty("id",required);dep.addProperty("minimumInclusive","1.0.0");dep.addProperty("maximumExclusive","2.0.0");deps.add(dep);}d.add("dependencies",deps);
        d.addProperty("main","fixture."+id+".Module");d.add("exports",new JsonArray());return d;
    }
    private static String manifestExpression(ModuleManifest manifest){
        return "new ModuleManifest(\""+manifest.id()+"\",new Version("+manifest.version().major()+","+manifest.version().minor()+","+manifest.version().patch()+"),new ApiRange("+manifest.api().minimumMajor()+","+manifest.api().maximumMajor()+"),Set.of("+String.join(",",manifest.platforms().stream().sorted().map(p->"Platform."+p).toList())+"),List.of("+String.join(",",manifest.dependencies().stream().map(d->"new ModuleManifest.Dependency(\""+d.id()+"\",new Version("+d.minimumInclusive().major()+","+d.minimumInclusive().minor()+","+d.minimumInclusive().patch()+"),new Version("+d.maximumExclusive().major()+","+d.maximumExclusive().minor()+","+d.maximumExclusive().patch()+"))").toList())+"))";
    }
    private Path jar(Path directory,String file,JsonObject descriptor,String enable,String disable,Map<String,String> extra,List<Path> dependencies)throws Exception {
        Files.createDirectories(directory);String id=descriptor.get("id").getAsString();Path work=Files.createTempDirectory(temp,"compile-"),classes=Files.createDirectory(work.resolve("classes"));
        String source="package fixture."+id+"; import art.arcane.animus.api.*;import java.util.*;import java.util.concurrent.*;public final class Module implements TrustedModule {static {System.setProperty(\"module.init."+id+"\",\"true\");}public Module(){} public ModuleManifest manifest(){return "+manifestExpression(ModuleDescriptor.parse(JSON.toJson(descriptor)).manifest())+";} public void enable(ModuleContext context)throws Exception {"+enable+"}public void disable()throws Exception {"+disable+"}static void mark(String s){System.setProperty(\"qa.module.events\",System.getProperty(\"qa.module.events\",\"\")+s+\";\");}}";
        Map<String,String> all=new LinkedHashMap<>(extra);all.put("fixture/"+id+"/Module.java",source);List<String> arguments=new ArrayList<>(List.of("--release","25","-classpath",api+ (dependencies.isEmpty()?"":File.pathSeparator+String.join(File.pathSeparator,dependencies.stream().map(Path::toString).toList())),"-d",classes.toString()));
        for(var entry:all.entrySet()){Path path=work.resolve(entry.getKey());Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());arguments.add(path.toString());}
        ByteArrayOutputStream diagnostics=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,arguments.toArray(String[]::new)),diagnostics.toString());
        Map<String,byte[]> entries=new TreeMap<>();try(var paths=Files.walk(classes)){for(Path path:paths.filter(Files::isRegularFile).toList())entries.put(classes.relativize(path).toString(),Files.readAllBytes(path));}
        entries.put(ModuleDescriptor.FILE,(JSON.toJson(descriptor)+"\n").getBytes(StandardCharsets.UTF_8));Path target=directory.resolve(file);writeJar(target,entries);return target;
    }
    private Path jar(Path directory,String id,String...dependencies)throws Exception{return jar(directory,id+".jar",descriptor(id,dependencies),"mark(\""+id+"-enable\");","mark(\""+id+"-disable\");",Map.of(),List.of());}
    private static void writeJar(Path target,Map<String,byte[]> entries)throws IOException {
        try(JarOutputStream output=new JarOutputStream(Files.newOutputStream(target))){for(var e:entries.entrySet()){output.putNextEntry(new JarEntry(e.getKey()));output.write(e.getValue());output.closeEntry();}}
        String evidence=System.getProperty("fixtureEvidence");if(evidence!=null){Path directory=Path.of(evidence);Files.createDirectories(directory);try{String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)));Files.copy(target,directory.resolve(hash+".jar"),StandardCopyOption.REPLACE_EXISTING);}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    }
    private static Map<String,byte[]> entries(Path jar)throws IOException {Map<String,byte[]> entries=new TreeMap<>();try(ZipFile file=new ZipFile(jar.toFile())){for(var iterator=file.entries();iterator.hasMoreElements();){ZipEntry entry=iterator.nextElement();entries.put(entry.getName(),file.getInputStream(entry).readAllBytes());}}return entries;}
    private static void until(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}
    private static String reasons(ModuleDiscovery.Plan plan){return plan.problems().toString();}

    @Test void realExternalJarsLinkOnlyDeclaredExportsAndUseParentApi()throws Exception {
        Path modules=temp.resolve("modules");JsonObject base=descriptor("base");base.getAsJsonArray("exports").add("fixture.lib");
        Path dependency=jar(modules,"z-base.jar",base,"mark(\"base-enable\");","mark(\"base-disable\");",Map.of("fixture/lib/Greeting.java","package fixture.lib;public final class Greeting {public static String message(){return \"linked\";}}"),List.of());
        String enable="if(TrustedModule.class.getClassLoader()==getClass().getClassLoader())throw new AssertionError(\"API duplicated\");"
                +"try{Class.forName(\"art.arcane.animus.core.extension.ModuleHost\",false,getClass().getClassLoader());throw new AssertionError(\"Private host leaked\");}catch(ClassNotFoundException expected){}"
                +"if(getClass().getClassLoader().getResource(\"art/arcane/animus/prototype/ModuleHost.class\")!=null)throw new AssertionError(\"Private resource leaked\");"
                +"try{Class.forName(\"com.google.gson.Gson\",false,getClass().getClassLoader());throw new AssertionError(\"Application parent leaked\");}catch(ClassNotFoundException expected){}"
                +"mark(fixture.lib.Greeting.message());context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key(\"child\",\"hello\"),Capabilities.Kind.ACTION,\"Hello\",\"{}\",Set.of(Platform.PAPER),false),i->CompletableFuture.completedFuture(\"{\\\"message\\\":\\\"linked\\\"}\"));";
        jar(modules,"a-child.jar",descriptor("child","base"),enable,"mark(\"child-disable\");",Map.of(),List.of(dependency));
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertEquals(List.of("base","child"),plan.order());assertTrue(plan.problems().isEmpty());
        var host=host();try(var runtime=new StartupModules(host,plan)){assertTrue(runtime.start().stream().allMatch(s->s.state().equals("ACTIVE")));assertThrows(IllegalStateException.class,plan::take);var field=ModuleDiscovery.Plan.class.getDeclaredField("ordered");field.setAccessible(true);assertTrue(((List<?>)field.get(plan)).isEmpty());var value=host.action(new Capabilities.Key("child","hello"),new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}",false,false).toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals("{\"message\":\"linked\"}",value.value().orElseThrow());}
        assertEquals("base-enable;linked;child-disable;base-disable;",System.getProperty("qa.module.events"));
    }
    @Test void undeclaredAndUnexportedDependencyClassesCannotLink()throws Exception {
        Path modules=temp.resolve("modules");Path base=jar(modules,"base.jar",descriptor("base"),"","",Map.of("fixture/lib/Hidden.java","package fixture.lib;public class Hidden {public static void call(){}}"),List.of());
        jar(modules,"child.jar",descriptor("child","base"),"fixture.lib.Hidden.call();","",Map.of(),List.of(base));
        jar(modules,"other.jar",descriptor("other"),"fixture.lib.Hidden.call();","",Map.of(),List.of(base));
        try(var runtime=new StartupModules(host(),ModuleDiscovery.discover(modules,Platform.PAPER))){var result=runtime.start();assertEquals(1,result.stream().filter(s->s.state().equals("ACTIVE")).count());assertEquals(2,result.stream().filter(s->s.state().equals("FAILED")).count());}
    }
    @Test void duplicatesIncompatibilitiesAndMissingDependenciesNeverInitialize()throws Exception {
        Path modules=temp.resolve("modules");Path base=jar(modules,"base");Files.copy(base,modules.resolve("second.jar"));
        JsonObject future=descriptor("bad");future.getAsJsonObject("api").addProperty("minimumMajor",2);future.getAsJsonObject("api").addProperty("maximumMajor",2);jar(modules,"bad.jar",future,"","",Map.of(),List.of());
        JsonObject platform=descriptor("other");platform.getAsJsonArray("platforms").set(0,new JsonPrimitive("FABRIC"));jar(modules,"other.jar",platform,"","",Map.of(),List.of());jar(modules,"child","missing");
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertTrue(plan.order().isEmpty());for(String reason:List.of("duplicate-id","api-incompatible","platform-incompatible","dependency-unavailable"))assertTrue(reasons(plan).contains(reason));
        try(var runtime=new StartupModules(host(),plan)){runtime.start();}assertNull(System.getProperty("module.init.base"));assertNull(System.getProperty("module.init.bad"));
    }
    @Test void cyclesAndTransitiveDependentsRejectButIndependentModuleStarts()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"alpha","beta");jar(modules,"beta","alpha");jar(modules,"leaf","alpha");jar(modules,"unrelated");
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertEquals(List.of("unrelated"),plan.order());assertEquals(3,plan.problems().size());try(var runtime=new StartupModules(host(),plan)){runtime.start();}assertNull(System.getProperty("module.init.alpha"));
    }
    @Test void dependencyVersionMismatchIsRejectedBeforeInitialization()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"base");JsonObject child=descriptor("child","base");child.getAsJsonArray("dependencies").get(0).getAsJsonObject().addProperty("minimumInclusive","1.1.0");jar(modules,"child.jar",child,"","",Map.of(),List.of());var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertEquals(List.of("base"),plan.order());assertTrue(reasons(plan).contains("dependency-unavailable"));
    }
    @Test void startupFailureCleansResourcesAndPreventsDependentEnable()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"bad.jar",descriptor("bad"),"context.onClose(()->mark(\"resource1\"));context.onClose(()->mark(\"resource2\"));throw new IllegalStateException(\"trusted failure\");","mark(\"bad-disable\");",Map.of(),List.of());jar(modules,"child","bad");jar(modules,"other");
        var host=host();try(var runtime=new StartupModules(host,ModuleDiscovery.discover(modules,Platform.PAPER))){var result=runtime.start();assertEquals("FAILED",result.stream().filter(s->s.id().equals("bad")).findFirst().orElseThrow().state());assertEquals("UNAVAILABLE",result.stream().filter(s->s.id().equals("child")).findFirst().orElseThrow().state());assertNull(System.getProperty("module.init.child"));assertTrue(host.capabilities().isEmpty());}
        assertEquals("bad-disable;resource2;resource1;other-enable;other-disable;",System.getProperty("qa.module.events"));
    }
    @Test void runtimeManifestMismatchCannotRegister()throws Exception {
        Path modules=temp.resolve("modules");Path jar=jar(modules,"mismatch");Map<String,byte[]> contents=entries(jar);JsonObject altered=descriptor("mismatch");altered.addProperty("version","1.1.0");contents.put(ModuleDescriptor.FILE,JSON.toJson(altered).getBytes(StandardCharsets.UTF_8));writeJar(jar,contents);
        try(var runtime=new StartupModules(host(),ModuleDiscovery.discover(modules,Platform.PAPER))){assertEquals("FAILED",runtime.start().get(0).state());}assertEquals("mismatch-disable;",System.getProperty("qa.module.events"));
    }
    @Test void reverseDependencyAndResourceCleanupContinuesAfterFailure()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"base.jar",descriptor("base"),"context.onClose(()->mark(\"base-resource\"));","mark(\"base-disable\");",Map.of(),List.of());jar(modules,"child.jar",descriptor("child","base"),"context.onClose(()->mark(\"child-resource1\"));context.onClose(()->{mark(\"child-resource2\");throw new IllegalStateException();});","mark(\"child-disable\");throw new IllegalStateException();",Map.of(),List.of());
        var runtime=new StartupModules(host(),ModuleDiscovery.discover(modules,Platform.PAPER));runtime.start();runtime.close();runtime.close();assertTrue(runtime.cleanupStatus().archivesReleased());assertEquals(0,runtime.cleanupStatus().retainedLoaders());assertEquals("child-disable;child-resource2;child-resource1;base-disable;base-resource;",System.getProperty("qa.module.events"));
    }
    @Test void validatedArchiveBytesAreStableDespiteLaterBinaryReplacement()throws Exception {
        Path modules=temp.resolve("modules");Path first=jar(modules,"base");var plan=ModuleDiscovery.discover(modules,Platform.PAPER);String digest=plan.archiveHashes().get("base.jar");Files.writeString(first,"invalid replacement while engine starts");var runtime=new StartupModules(host(),plan);assertEquals("ACTIVE",runtime.start().get(0).state());assertEquals(digest,runtime.statuses().get(0).sha256());assertThrows(IllegalStateException.class,runtime::start);runtime.close();assertTrue(runtime.cleanupStatus().archivesReleased());assertFalse(ModuleDiscovery.discover(modules,Platform.PAPER).problems().isEmpty());
    }
    @Test void symlinkRootArchiveAndAncestorAreRefusedWithoutFollowing()throws Exception {
        Path modules=temp.resolve("modules");Path original=jar(temp.resolve("outside"),"base");Files.createDirectory(modules);Files.createSymbolicLink(modules.resolve("linked.jar"),original);assertTrue(reasons(ModuleDiscovery.discover(modules,Platform.PAPER)).contains("Symbolic links"));Path linked=temp.resolve("linked-root");Files.createSymbolicLink(linked,modules);assertThrows(IOException.class,()->ModuleDiscovery.discover(linked,Platform.PAPER));assertThrows(IOException.class,()->ModuleDiscovery.discover(linked.resolve("absent"),Platform.PAPER));
    }
    @Test void malformedDuplicateUnknownAndOversizedManifestsReject()throws Exception {
        Path modules=temp.resolve("modules");Path base=jar(temp.resolve("outside"),"base");Map<String,byte[]> original=entries(base);
        List<String> invalid=List.of("{\"schemaVersion\":1,\"schemaVersion\":1}","[]",JSON.toJson(descriptor("base")).replace("\"schemaVersion\": 1","\"schemaVersion\": 1, \"unknown\": true")," ".repeat(65537));
        Files.createDirectory(modules);int i=0;for(String text:invalid){var all=new TreeMap<>(original);all.put(ModuleDescriptor.FILE,text.getBytes(StandardCharsets.UTF_8));writeJar(modules.resolve("invalid"+(i++)+".jar"),all);}
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertTrue(plan.order().isEmpty());assertEquals(invalid.size(),plan.problems().size());
    }
    @Test void zipDirectoryPayloadCannotBypassExpansionBudget()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));writeJar(modules.resolve("directory.jar"),Map.of("payload/",new byte[1024*1024]));
        var limits=new ModuleDiscovery.Limits(8,16,65536,128,64,32,256,64);var plan=ModuleDiscovery.discover(modules,Platform.PAPER,limits);assertTrue(reasons(plan).contains("Directory entry contains payload"));
    }
    @Test void archiveEntryAggregateAndModuleLimitsReject()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));writeJar(modules.resolve("large.jar"),Map.of("payload",new byte[1024]));
        var limits=new ModuleDiscovery.Limits(2,4,4096,128,64,3,256,4);assertTrue(reasons(ModuleDiscovery.discover(modules,Platform.PAPER,limits)).contains("expanded byte limit"));
        Files.delete(modules.resolve("large.jar"));writeJar(modules.resolve("many.jar"),Map.of("a",new byte[0],"b",new byte[0],"c",new byte[0],"d",new byte[0]));assertTrue(reasons(ModuleDiscovery.discover(modules,Platform.PAPER,limits)).contains("entry count limit"));
        Files.delete(modules.resolve("many.jar"));for(int n=0;n<3;n++)writeJar(modules.resolve(n+".jar"),Map.of("x",new byte[0]));assertThrows(IOException.class,()->ModuleDiscovery.discover(modules,Platform.PAPER,limits));
    }
    @Test void privateApiBundlingNestedJarsAndTraversalAreRefused()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));int i=0;for(String name:List.of("art/arcane/animus/api/TrustedModule.class","art/arcane/animus/core/Hidden.class","META-INF/versions/25/fixture/Test.class","nested.jar","../escaped","/absolute","bad\\path"))writeJar(modules.resolve("invalid"+(i++)+".jar"),Map.of(name,new byte[0]));
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertTrue(plan.order().isEmpty());assertEquals(i,plan.problems().size());assertFalse(Files.exists(temp.resolve("escaped")));
    }
    @Test void stablePublicApiNamespaceRejectsInjectedAndRealApiClassesBeforeInitialization()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));Path normal=jar(temp.resolve("outside"),"base");Map<String,byte[]> original=entries(normal);
        try(ZipFile contract=new ZipFile(api.toFile())){
            for(String name:List.of("art/arcane/animus/api/Injected.class","art/arcane/animus/api/TrustedModule.class")){
                Map<String,byte[]> changed=new TreeMap<>(original);changed.put(name,name.endsWith("Injected.class")?new byte[0]:contract.getInputStream(contract.getEntry(name)).readAllBytes());
                writeJar(modules.resolve(name.endsWith("Injected.class")?"injected.jar":"bundled.jar"),changed);
            }
        }
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertTrue(plan.order().isEmpty());assertEquals(2,plan.problems().size());assertTrue(plan.problems().stream().allMatch(p->p.reason().contains("Reserved/unsupported class namespace")));assertNull(System.getProperty("module.init.base"));
    }
    @Test void unknownConfigurationIsUntouchedIncludingMissingModules()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));Path config=modules.resolve("missing-module.json");String bytes="{\"missing:action\":{\"retained\":true}}\n";Files.writeString(config,bytes);var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertTrue(plan.order().isEmpty());try(var runtime=new StartupModules(host(),plan)){runtime.start();}assertEquals(bytes,Files.readString(config));try(var paths=Files.list(modules)){assertEquals(1,paths.count());}
    }
    @Test void closeRetainsArchivesUntilHostHandlerAndDeliveryActuallyFinish()throws Exception {
        Path modules=temp.resolve("modules");String enable="context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key(\"held\",\"slow\"),Capabilities.Kind.ACTION,\"Slow\",\"{}\",Set.of(Platform.PAPER),false),i->{mark(\"entered\");return CompletableFuture.supplyAsync(()->{while(!Boolean.getBoolean(\"qa.module.release\")){try{Thread.sleep(5);}catch(InterruptedException e){throw new RuntimeException(e);}}return \"{}\";});});";
        jar(modules,"held.jar",descriptor("held"),enable,"mark(\"disabled\");",Map.of(),List.of());System.clearProperty("qa.module.release");var host=host();var runtime=new StartupModules(host,ModuleDiscovery.discover(modules,Platform.PAPER));
        try{runtime.start();host.action(new Capabilities.Key("held","slow"),new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}",false,false);until(()->System.getProperty("qa.module.events","").contains("entered"));runtime.close();assertEquals(1,runtime.cleanupStatus().retainedLoaders());assertFalse(runtime.cleanupStatus().archivesReleased());System.setProperty("qa.module.release","true");until(()->host.outstandingInvocations()==0);assertTrue(runtime.drainCleanup().archivesReleased());}finally{System.setProperty("qa.module.release","true");runtime.close();}
    }
    @Test void closeBeforeStartConsumesArchivePlanAndDoesNotInitialize()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"base");var plan=ModuleDiscovery.discover(modules,Platform.PAPER);var runtime=new StartupModules(host(),plan);runtime.close();assertTrue(runtime.cleanupStatus().archivesReleased());assertThrows(IllegalStateException.class,runtime::start);assertThrows(IllegalStateException.class,plan::take);assertNull(System.getProperty("module.init.base"));
    }
    @Test void ambiguousExportedClassesRejectConsumerBeforeItsInitialization()throws Exception {
        Path modules=temp.resolve("modules");Map<String,String> extra=Map.of("fixture/shared/Value.java","package fixture.shared;public final class Value {}");
        for(String id:List.of("alpha","beta")){JsonObject descriptor=descriptor(id);descriptor.getAsJsonArray("exports").add("fixture.shared");jar(modules,id+".jar",descriptor,"","",extra,List.of());}
        jar(modules,"child","alpha","beta");var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertEquals(List.of("alpha","beta"),plan.order());assertTrue(reasons(plan).contains("ambiguous-visible-class"));try(var runtime=new StartupModules(host(),plan)){runtime.start();}assertNull(System.getProperty("module.init.child"));
    }
    @Test void duplicateZipNamesAndExternalManifestClasspathReject()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));Path duplicates=modules.resolve("duplicates.jar");writeJar(duplicates,Map.of("aa",new byte[0],"bb",new byte[0]));byte[] bytes=Files.readAllBytes(duplicates);for(int i=0;i<bytes.length-1;i++)if(bytes[i]=='b'&&bytes[i+1]=='b'){bytes[i]='a';bytes[++i]='a';}Files.write(duplicates,bytes);
        Path normal=jar(temp.resolve("outside"),"base");Map<String,byte[]> all=entries(normal);all.put("META-INF/MANIFEST.MF","Manifest-Version: 1.0\nClass-Path: external.jar\n\n".getBytes(StandardCharsets.UTF_8));writeJar(modules.resolve("external.jar"),all);
        var plan=ModuleDiscovery.discover(modules,Platform.PAPER);assertEquals(2,plan.problems().size());assertTrue(reasons(plan).contains("duplicate archive path"));assertTrue(reasons(plan).contains("External Class-Path"));
    }
    @Test void aggregateExpansionBudgetAppliesAcrossRejectedArchives()throws Exception {
        Path modules=Files.createDirectory(temp.resolve("modules"));for(int n=0;n<3;n++)writeJar(modules.resolve(n+".jar"),Map.of("payload",new byte[64]));
        var limits=new ModuleDiscovery.Limits(4,8,4096,128,128,8,128,32);assertThrows(IOException.class,()->ModuleDiscovery.discover(modules,Platform.PAPER,limits));
    }
    @Test void releasedLoaderNoLongerServesRetainedResourceUrls()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"base");var plan=ModuleDiscovery.discover(modules,Platform.PAPER);var candidates=plan.take();var loader=new ModuleArchiveLoader(candidates.get(0),List.of());var url=loader.getResource(ModuleDescriptor.FILE);assertNotNull(url);try(var input=url.openStream()){assertTrue(input.readAllBytes().length>0);}loader.close();assertNull(loader.getResource(ModuleDescriptor.FILE));assertThrows(IOException.class,url::openStream);
    }
    @Test void diagnosticsRemainResponsiveWhileTrustedEnableBlocks()throws Exception {
        Path modules=temp.resolve("modules");System.clearProperty("qa.module.lifecycle-release");jar(modules,"base.jar",descriptor("base"),"mark(\"enable-entered\");while(!Boolean.getBoolean(\"qa.module.lifecycle-release\")){Thread.sleep(5);}","",Map.of(),List.of());
        var runtime=new StartupModules(host(),ModuleDiscovery.discover(modules,Platform.PAPER));ExecutorService caller=Executors.newSingleThreadExecutor();
        try{Future<?> start=caller.submit(runtime::start);until(()->System.getProperty("qa.module.events","").contains("enable-entered"));var status=CompletableFuture.supplyAsync(runtime::lifecycleStatus).get(1,TimeUnit.SECONDS);assertEquals("base",status.module());assertEquals("enable",status.phase());assertEquals("STARTING",CompletableFuture.supplyAsync(runtime::statuses).get(1,TimeUnit.SECONDS).get(0).state());System.setProperty("qa.module.lifecycle-release","true");start.get(2,TimeUnit.SECONDS);}finally{System.setProperty("qa.module.lifecycle-release","true");caller.shutdown();assertTrue(caller.awaitTermination(2,TimeUnit.SECONDS));runtime.close();}
    }
    @Test void blockedResourceCleanupStaysObservableAndCannotReleaseArchivesEarly()throws Exception {
        Path modules=temp.resolve("modules");System.clearProperty("qa.module.lifecycle-release");jar(modules,"base.jar",descriptor("base"),"context.onClose(()->{mark(\"resource-entered\");while(!Boolean.getBoolean(\"qa.module.lifecycle-release\")){Thread.sleep(5);}});","",Map.of(),List.of());
        var runtime=new StartupModules(host(),ModuleDiscovery.discover(modules,Platform.PAPER));runtime.start();ExecutorService caller=Executors.newSingleThreadExecutor();
        try{Future<?> close=caller.submit(runtime::close);until(()->System.getProperty("qa.module.events","").contains("resource-entered"));var status=CompletableFuture.supplyAsync(runtime::lifecycleStatus).get(1,TimeUnit.SECONDS);assertTrue(status.closing());assertEquals("resource-close",status.phase());assertFalse(CompletableFuture.supplyAsync(runtime::drainCleanup).get(1,TimeUnit.SECONDS).archivesReleased());System.setProperty("qa.module.lifecycle-release","true");close.get(2,TimeUnit.SECONDS);assertTrue(runtime.cleanupStatus().archivesReleased());}finally{System.setProperty("qa.module.lifecycle-release","true");caller.shutdown();assertTrue(caller.awaitTermination(2,TimeUnit.SECONDS));runtime.close();}
    }
    @Test void revocationDuringEnableNeverInitializesRemainingArchives()throws Exception {
        Path modules=temp.resolve("modules");System.clearProperty("qa.module.lifecycle-release");
        jar(modules,"alpha.jar",descriptor("alpha"),"mark(\"alpha-entered\");context.onClose(()->mark(\"alpha-resource\"));while(!Boolean.getBoolean(\"qa.module.lifecycle-release\")){Thread.sleep(5);}","mark(\"alpha-disable\");",Map.of(),List.of());
        jar(modules,"beta");var host=host();var runtime=new StartupModules(host,ModuleDiscovery.discover(modules,Platform.PAPER));ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            Future<?> start=caller.submit(runtime::start);until(()->System.getProperty("qa.module.events","").contains("alpha-entered"));
            CompletableFuture.runAsync(host::revoke).get(1,TimeUnit.SECONDS);assertTrue(host.invocationStatus().closed());
            System.setProperty("qa.module.lifecycle-release","true");start.get(2,TimeUnit.SECONDS);
            assertNull(System.getProperty("module.init.beta"));var beta=runtime.statuses().stream().filter(s->s.id().equals("beta")).findFirst().orElseThrow();
            assertEquals("UNAVAILABLE",beta.state());assertEquals("host-revoked-before-initialization",beta.reason());assertTrue(host.capabilities().isEmpty());
            runtime.close();assertTrue(runtime.cleanupStatus().archivesReleased());assertEquals("alpha-entered;alpha-disable;alpha-resource;",System.getProperty("qa.module.events"));
        }finally{System.setProperty("qa.module.lifecycle-release","true");caller.shutdown();assertTrue(caller.awaitTermination(2,TimeUnit.SECONDS));runtime.close();}
    }
    @Test void orchestratorStartAndCloseReturnWhileEnableBlocksAndSkipLaterInitialization()throws Exception {
        Path modules=temp.resolve("modules");System.clearProperty("qa.module.lifecycle-release");
        jar(modules,"alpha.jar",descriptor("alpha"),"mark(\"alpha-entered\");context.onClose(()->mark(\"alpha-resource\"));while(!Boolean.getBoolean(\"qa.module.lifecycle-release\")){Thread.sleep(5);}","mark(\"alpha-disable\");",Map.of(),List.of());jar(modules,"beta");
        var runtime=new ExtensionRuntime(modules,Platform.PAPER,DEFINITIONS,new ModuleSettings(true,4,1000,250),ignored->{});
        try{
            CompletableFuture.runAsync(runtime::start).get(1,TimeUnit.SECONDS);until(()->System.getProperty("qa.module.events","").contains("alpha-entered"));
            var status=CompletableFuture.supplyAsync(runtime::status).get(1,TimeUnit.SECONDS);assertEquals(ExtensionRuntime.State.STARTING,status.state());assertEquals("enable",status.lifecycle().phase());
            CompletableFuture.runAsync(runtime::close).get(1,TimeUnit.SECONDS);assertTrue(runtime.status().invocations().closed());assertFalse(runtime.awaitStopped(Duration.ZERO));
            System.setProperty("qa.module.lifecycle-release","true");assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));assertNull(System.getProperty("module.init.beta"));assertTrue(runtime.status().cleanup().archivesReleased());
            assertEquals("alpha-entered;alpha-disable;alpha-resource;",System.getProperty("qa.module.events"));
        }finally{System.setProperty("qa.module.lifecycle-release","true");runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));}
    }
    @Test void orchestratorAutomaticallyReleasesArchivesAfterRawHandlerTerminates()throws Exception {
        Path modules=temp.resolve("modules");System.clearProperty("qa.module.release");
        String enable="context.registrations().action(action(),i->{mark(\"handler-entered\");return CompletableFuture.supplyAsync(()->{while(!Boolean.getBoolean(\"qa.module.release\")){try{Thread.sleep(5);}catch(InterruptedException e){throw new RuntimeException(e);}}return \"{}\";});});";
        // Use an inline descriptor so this external JAR needs only the public API.
        enable=enable.replace("action()","new Capabilities.Descriptor(new Capabilities.Key(\"held\",\"hold\"),Capabilities.Kind.ACTION,\"Hold\",\"{}\",Set.of(Platform.PAPER),false)");
        jar(modules,"held.jar",descriptor("held"),enable,"mark(\"held-disable\");",Map.of(),List.of());List<String> reports=new CopyOnWriteArrayList<>();
        var runtime=new ExtensionRuntime(modules,Platform.PAPER,DEFINITIONS,new ModuleSettings(true,4,5000,250),reports::add);
        try{
            runtime.start();until(()->runtime.status().state()==ExtensionRuntime.State.READY);
            var result=runtime.action(new Capabilities.Key("held","hold"),new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}");until(()->System.getProperty("qa.module.events","").contains("handler-entered"));
            CompletableFuture.runAsync(runtime::close).get(1,TimeUnit.SECONDS);assertEquals(Outcome.Status.CANCELLED,result.toCompletableFuture().get(1,TimeUnit.SECONDS).status());
            until(()->System.getProperty("qa.module.events","").contains("held-disable"));assertFalse(runtime.awaitStopped(Duration.ofMillis(30)));assertEquals(1,runtime.status().cleanup().retainedLoaders());assertFalse(runtime.status().cleanup().archivesReleased());
            System.setProperty("qa.module.release","true");assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));assertTrue(runtime.status().cleanup().archivesReleased());assertEquals(0,runtime.status().invocations().admitted());assertTrue(reports.stream().anyMatch(s->s.contains("archives remain retained")));
        }finally{System.setProperty("qa.module.release","true");runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));}
    }
    @Test void orchestratorDiscoveryFailureStopsAutomaticallyAndKeepsFailureDiagnostic()throws Exception {
        Path invalid=temp.resolve("not-a-directory");Files.writeString(invalid,"synthetic");List<String> reports=new CopyOnWriteArrayList<>();
        var runtime=new ExtensionRuntime(invalid,Platform.PAPER,DEFINITIONS,new ModuleSettings(true,4,1000,250),reports::add);
        try{runtime.start();assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));assertEquals(ExtensionRuntime.State.STOPPED,runtime.status().state());assertEquals("module-startup: IOException",runtime.status().failure());assertTrue(runtime.status().invocations().closed());assertTrue(runtime.startupStatuses().isEmpty());assertTrue(reports.contains("module-startup: IOException"));}finally{runtime.close();}
    }
    @Test void orchestratorDisabledSettingsDoNotInitializeArchives()throws Exception {
        Path modules=temp.resolve("modules");jar(modules,"base");var runtime=new ExtensionRuntime(modules,Platform.PAPER,DEFINITIONS,new ModuleSettings(false,4,1000,250),ignored->{});
        try{runtime.start();assertEquals(ExtensionRuntime.State.DISABLED,runtime.status().state());assertTrue(runtime.status().invocations().closed());assertNull(System.getProperty("module.init.base"));assertTrue(runtime.startupStatuses().isEmpty());assertEquals(Outcome.Status.UNAVAILABLE,runtime.action(new Capabilities.Key("base","none"),new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}").toCompletableFuture().get(1,TimeUnit.SECONDS).status());}finally{runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));}
    }
    @Test void engineInitializationObserverReceivesExactlyOneTerminalStartupState()throws Exception {
        for(ExtensionRuntime.State expected:List.of(ExtensionRuntime.State.READY,ExtensionRuntime.State.DISABLED,ExtensionRuntime.State.FAILED)){
            Path path=temp.resolve("observer-"+expected);if(expected==ExtensionRuntime.State.FAILED)Files.writeString(path,"not a module directory");
            List<ExtensionRuntime.State> observed=new CopyOnWriteArrayList<>();var runtime=new ExtensionRuntime(path,Platform.PAPER,DEFINITIONS,new ModuleSettings(expected!=ExtensionRuntime.State.DISABLED,4,1000,250),ignored->{},observed::add);
            try{runtime.start();until(()->!observed.isEmpty());assertEquals(List.of(expected),observed);assertThrows(IllegalStateException.class,runtime::start);}
            finally{runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));}assertEquals(List.of(expected),observed);
        }
    }
    @Test void initializationObserverFailureIsVisibleWithoutFailingReadyModulesOrRetryingHook()throws Exception {
        List<String> reports=new CopyOnWriteArrayList<>();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        var runtime=new ExtensionRuntime(temp.resolve("observer-failure"),Platform.PAPER,DEFINITIONS,ModuleSettings.DEFAULT,reports::add,state->{calls.incrementAndGet();throw new RejectedExecutionException("engine file admission rejected");});
        try{runtime.start();until(()->!reports.isEmpty());assertEquals(ExtensionRuntime.State.READY,runtime.status().state());assertTrue(runtime.status().failure().contains("module-initialization-observer"));assertEquals(1,calls.get());}
        finally{runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));}assertEquals(1,calls.get());
    }
    @Test void retainedBehaviorAdapterRequiresReadyStateCarriesPreviewAndNeverGrantsPrivilege()throws Exception {
        Path modules=temp.resolve("behavior-modules");String action="new Capabilities.Descriptor(new Capabilities.Key(\"base\",\"plain\"),Capabilities.Kind.ACTION,\"Plain\",\"{}\",Set.of(Platform.PAPER),false)";
        String privileged=action.replace("\"plain\"","\"privileged\"").replace("false)","true)");
        String condition="new Capabilities.Descriptor(new Capabilities.Key(\"base\",\"check\"),Capabilities.Kind.CONDITION,\"Check\",\"{}\",Set.of(Platform.PAPER),false)";
        String enable="context.registrations().action("+action+",i->CompletableFuture.completedFuture(\"{\\\"preview\\\":\"+i.preview()+\"}\"));context.registrations().action("+privileged+",i->CompletableFuture.completedFuture(\"{}\"));context.registrations().condition("+condition+",i->CompletableFuture.completedFuture(!i.preview()));";
        jar(modules,"base.jar",descriptor("base"),enable,"",Map.of(),List.of());var runtime=new ExtensionRuntime(modules,Platform.PAPER,DEFINITIONS,ModuleSettings.DEFAULT,ignored->{});var adapter=runtime.behaviorInvoker();
        var plain=new Capabilities.Key("base","plain");var invocation=new art.arcane.animus.core.behavior.BehaviorInvoker.Invocation(plain,new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}",Map.of(),true,()->true,"sample:test");
        try{
            assertFalse(adapter.available(plain,Capabilities.Kind.ACTION));assertEquals(Outcome.Status.UNAVAILABLE,adapter.action(invocation).result().toCompletableFuture().get(1,TimeUnit.SECONDS).status());
            runtime.start();until(()->runtime.status().state()==ExtensionRuntime.State.READY);assertTrue(adapter.available(plain,Capabilities.Kind.ACTION));assertFalse(adapter.available(plain,Capabilities.Kind.CONDITION));assertFalse(adapter.available(new Capabilities.Key("base","privileged"),Capabilities.Kind.ACTION));
            var call=adapter.action(invocation);assertEquals("{\"preview\":true}",call.result().toCompletableFuture().get(1,TimeUnit.SECONDS).value().orElseThrow());until(()->call.isQuiescent().getAsBoolean());
            var check=new art.arcane.animus.core.behavior.BehaviorInvoker.Invocation(new Capabilities.Key("base","check"),invocation.npc(),Optional.empty(),"{}",Map.of(),true,()->true,"sample:test");assertFalse(adapter.condition(check).result().toCompletableFuture().get(1,TimeUnit.SECONDS).value().orElseThrow());
            var revokedAction=new art.arcane.animus.core.behavior.BehaviorInvoker.Invocation(plain,invocation.npc(),Optional.empty(),"{}",Map.of(),false,()->false,"sample:test");
            var revokedCondition=new art.arcane.animus.core.behavior.BehaviorInvoker.Invocation(check.capability(),invocation.npc(),Optional.empty(),"{}",Map.of(),false,()->false,"sample:test");
            assertEquals(Outcome.Status.CANCELLED,adapter.action(revokedAction).result().toCompletableFuture().get(1,TimeUnit.SECONDS).status());
            assertEquals(Outcome.Status.CANCELLED,adapter.condition(revokedCondition).result().toCompletableFuture().get(1,TimeUnit.SECONDS).status());
            var denied=new art.arcane.animus.core.behavior.BehaviorInvoker.Invocation(new Capabilities.Key("base","privileged"),invocation.npc(),Optional.empty(),"{}",Map.of(),false,()->true,"sample:test");assertEquals(Outcome.Status.UNAVAILABLE,adapter.action(denied).result().toCompletableFuture().get(1,TimeUnit.SECONDS).status());
        }finally{runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));}
        assertFalse(adapter.available(plain,Capabilities.Kind.ACTION));var rejected=adapter.action(invocation);assertEquals(Outcome.Status.UNAVAILABLE,rejected.result().toCompletableFuture().get(1,TimeUnit.SECONDS).status());assertTrue(rejected.isQuiescent().getAsBoolean());
    }
    @Test void closeDuringBlockedEnableSuppressesLateInitializationObserver()throws Exception {
        Path modules=temp.resolve("observer-blocked");System.clearProperty("qa.behavior.observer-release");List<ExtensionRuntime.State> observed=new CopyOnWriteArrayList<>();
        jar(modules,"base.jar",descriptor("base"),"mark(\"behavior-enable-blocked\");while(!Boolean.getBoolean(\"qa.behavior.observer-release\")){Thread.sleep(5);}","",Map.of(),List.of());
        var runtime=new ExtensionRuntime(modules,Platform.PAPER,DEFINITIONS,ModuleSettings.DEFAULT,ignored->{},observed::add);
        try{runtime.start();until(()->System.getProperty("qa.module.events","").contains("behavior-enable-blocked"));runtime.close();System.setProperty("qa.behavior.observer-release","true");assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));assertTrue(observed.isEmpty());}
        finally{System.setProperty("qa.behavior.observer-release","true");runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)));System.clearProperty("qa.behavior.observer-release");}
    }
}
