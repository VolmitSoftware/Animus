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
    @Test void selfAndDuplicateDependenciesAreRejected(){
        var dep=new ModuleManifest.Dependency("example",new Version(1,0,0),new Version(2,0,0));
        assertThrows(IllegalArgumentException.class,()->new ModuleManifest("example",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of(dep)));
        assertThrows(IllegalArgumentException.class,()->new ModuleManifest("other",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of(dep,dep)));
    }
}
