package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    @Test void manifestsAndDiagnosticsDefensivelyCopyCollections(){
        Set<Platform> platforms=new HashSet<>(Set.of(Platform.PAPER));
        List<ModuleManifest.Dependency> deps=new ArrayList<>();
        var manifest=new ModuleManifest("example",new Version(1,0,0),new ApiRange(1,1),platforms,deps);
        platforms.clear();deps.add(new ModuleManifest.Dependency("other",new Version(1,0,0),new Version(2,0,0)));
        assertEquals(Set.of(Platform.PAPER),manifest.platforms());assertTrue(manifest.dependencies().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->manifest.platforms().clear());
    }
    @Test void versionsAndDependencyRangesAreExplicit(){
        assertTrue(new ApiRange(1,1).accepts(AnimusApi.API_VERSION));assertFalse(new ApiRange(2,3).accepts(AnimusApi.API_VERSION));
        var dependency=new ModuleManifest.Dependency("other",new Version(1,2,0),new Version(2,0,0));
        assertTrue(dependency.accepts(new Version(1,2,0)));assertFalse(dependency.accepts(new Version(2,0,0)));
        assertThrows(IllegalArgumentException.class,()->new Version(-1,0,0));
        assertThrows(IllegalArgumentException.class,()->new ApiRange(2,1));
    }
    @Test void malformedUtf8AndActualByteBoundsAreRejected(){
        var handle=new NpcHandle(UUID.randomUUID());var revision=new RevisionToken(1,"0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->new DefinitionEdit(handle,revision,"\uD800"));
        assertThrows(IllegalArgumentException.class,()->new Capabilities.Invocation(handle,Optional.empty(),"é".repeat(32769),false,()->false,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->new RevisionToken(0,"0".repeat(64)));
    }
    @Test void minorVersionTwoKeepsMajorOneModulesRunning(){
        assertEquals(new Version(1,2,0),AnimusApi.API_VERSION);
        assertEquals("animus-api-"+AnimusApi.API_VERSION+"-SNAPSHOT.jar",java.nio.file.Path.of(System.getProperty("apiJar")).getFileName().toString(),"Published artifact version follows the contract version");
        assertTrue(new ApiRange(1,1).accepts(AnimusApi.API_VERSION));
    }
    @Test void apiJarNamesAStableJavaModule()throws Exception{
        try(var jar=new java.util.jar.JarFile(System.getProperty("apiJar"))){
            assertEquals("art.arcane.animus.api",jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
        }
    }
    @Test void apiRangeCanRequireAMinimumMinorVersion(){
        ApiRange needsTwo=ApiRange.atLeast(new Version(1,2,0));
        assertEquals(new ApiRange(1,1,2),needsTwo);
        assertTrue(needsTwo.accepts(new Version(1,2,0)));assertTrue(needsTwo.accepts(new Version(1,9,3)));
        assertFalse(needsTwo.accepts(new Version(1,1,4)));assertFalse(needsTwo.accepts(new Version(2,0,0)));
        assertTrue(new ApiRange(1,2,1).accepts(new Version(2,0,0)),"The minimum minor binds only the minimum major");
        assertEquals(0,new ApiRange(1,1).minimumMinor());
        assertEquals("requires API 1.2 or newer within 1.x",needsTwo.describe());
        assertEquals("requires API 1.x",new ApiRange(1,1).describe());
        assertThrows(IllegalArgumentException.class,()->new ApiRange(1,1,-1));
    }
    /**
     * Modules implement these interfaces, so a minor version may only add default methods to them. Adding an abstract
     * method here is a major change: update this list only together with a major version.
     */
    @Test void moduleImplementedInterfacesGainOnlyDefaultMethods(){
        Map<Class<?>,Set<String>> pinned=Map.of(
                TrustedModule.class,Set.of("manifest","enable","disable"),
                Capabilities.Action.class,Set.of("run"),
                Capabilities.Condition.class,Set.of("test"),
                Capabilities.Cancellation.class,Set.of("cancelled"),
                InteractionNotice.Listener.class,Set.of("interacted"),
                ImportConverters.Converter.class,Set.of("detect","convert"),
                Integrations.Protection.class,Set.of("decide"),
                Integrations.Economy.class,Set.of("has","withdraw","deposit","format"));
        pinned.forEach((Class<?> type,Set<String> methods)->{
            Set<String> abstractMethods=new TreeSet<>();
            for(java.lang.reflect.Method method:type.getDeclaredMethods())
                if(java.lang.reflect.Modifier.isAbstract(method.getModifiers()))abstractMethods.add(method.getName());
            assertEquals(new TreeSet<>(methods),abstractMethods,type.getName()+" may only gain default methods in a minor version");
        });
    }
    @Test void importAndIntegrationRecordsAreBoundedAndCopied(){
        List<String> warnings=new ArrayList<>(List.of("note"));
        var record=new ImportConverters.Record(0,"{}",warnings);warnings.clear();
        assertEquals(List.of("note"),record.warnings());
        assertThrows(IllegalArgumentException.class,()->new ImportConverters.Record(-1,"{}",List.of()));
        assertThrows(IllegalArgumentException.class,()->new ImportConverters.Conversion(List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ImportConverters.Source("x","a".repeat(16*1024*1024+1)));
        assertThrows(IllegalArgumentException.class,()->new ImportConverters.Descriptor(new Capabilities.Key("a","b"),"d","p",Set.of()));
        assertThrows(IllegalArgumentException.class,()->new Integrations.Position("world",Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->new Integrations.Descriptor("d",Set.of()));
        Set<String> categories=new HashSet<>(Set.of("animals"));
        var party=new Integrations.Party(Integrations.PartyKind.NPC,UUID.randomUUID());var at=new Integrations.Position("world",0,0,0);
        var request=new Integrations.ProtectionRequest(Integrations.ProtectionAction.BLOCK_PLACE,party,at,Optional.empty(),at,categories,Optional.empty());
        categories.clear();assertEquals(Set.of("animals"),request.targetCategories());
        assertThrows(IllegalArgumentException.class,()->new InteractionNotice(new NpcHandle(UUID.randomUUID()),UUID.randomUUID(),InteractionNotice.Action.ATTACK,-1,false,0));
    }
    @Test void selfAndDuplicateDependenciesAreRejected(){
        var dep=new ModuleManifest.Dependency("example",new Version(1,0,0),new Version(2,0,0));
        assertThrows(IllegalArgumentException.class,()->new ModuleManifest("example",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of(dep)));
        assertThrows(IllegalArgumentException.class,()->new ModuleManifest("other",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of(dep,dep)));
    }
}
