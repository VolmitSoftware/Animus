package example.animus.behavior;

import art.arcane.animus.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Intentionally rejected by the supported schema profile; no partial registration may survive. */
public final class BadSchemaFixture implements TrustedModule {
    private static final Set<Platform> PLATFORMS=Set.of(Platform.PAPER,Platform.SPIGOT,Platform.FOLIA);
    @Override public ModuleManifest manifest(){return new ModuleManifest("qabehaviorbad",new Version(1,0,0),new ApiRange(1,1),PLATFORMS,List.of());}
    @Override public void enable(ModuleContext context) {
        context.onClose(()->System.out.println("Animus behavior QA bad-schema resource closed"));
        context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key("qabehaviorbad","staged"),Capabilities.Kind.ACTION,"Must never publish","{\"type\":\"object\"}",PLATFORMS,false),inv->CompletableFuture.completedFuture("{}"));
        context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key("qabehaviorbad","unsupported"),Capabilities.Kind.ACTION,"Intentional unsupported schema keyword","{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"pattern\":\".*\"}}}",PLATFORMS,false),inv->CompletableFuture.completedFuture("{}"));
    }
    @Override public void disable(){System.out.println("Animus behavior QA disabled qabehaviorbad");}
}
