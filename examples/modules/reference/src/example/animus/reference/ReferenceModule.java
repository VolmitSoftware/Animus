package example.animus.reference;

import art.arcane.animus.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A trusted extension compiled against only animus-api and the JDK. */
public final class ReferenceModule implements TrustedModule {
    private static final Set<Platform> PLATFORMS=Set.of(Platform.SPIGOT,Platform.PAPER,Platform.FOLIA);

    @Override public ModuleManifest manifest(){
        return new ModuleManifest("reference",new Version(1,0,0),new ApiRange(1,1),PLATFORMS,List.of());
    }

    @Override public void enable(ModuleContext context){
        context.registrations().action(new Capabilities.Descriptor(
                new Capabilities.Key("reference","hello"),Capabilities.Kind.ACTION,"Reference greeting",
                "{\"type\":\"object\",\"additionalProperties\":false}",PLATFORMS,false),
                invocation->CompletableFuture.completedFuture("{\"message\":\"Hello from a trusted external module\"}"));
    }

    @Override public void disable(){
        // Register startup-owned resources with context.onClose(...) when a module acquires any.
    }
}
