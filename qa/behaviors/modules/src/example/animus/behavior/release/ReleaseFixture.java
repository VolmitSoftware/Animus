package example.animus.behavior.release;

import art.arcane.animus.api.*;
import example.animus.behavior.control.ProbeControl;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Independent control capability remains callable after a held action module is suspended. */
public final class ReleaseFixture implements TrustedModule {
    private static final Set<Platform> PLATFORMS=Set.of(Platform.PAPER,Platform.SPIGOT,Platform.FOLIA);
    @Override public ModuleManifest manifest(){return new ModuleManifest("qabehaviorctl",new Version(1,0,0),new ApiRange(1,1),PLATFORMS,List.of(new ModuleManifest.Dependency("qabprobe",new Version(1,0,0),new Version(2,0,0))));}
    private static Capabilities.Descriptor descriptor(String name){return new Capabilities.Descriptor(new Capabilities.Key("qabehaviorctl",name),Capabilities.Kind.ACTION,"Synthetic behavior observation and release","{\"type\":\"object\",\"additionalProperties\":false}",PLATFORMS,false);}
    @Override public void enable(ModuleContext context) {
        context.registrations().action(descriptor("inspect"),inv->CompletableFuture.completedFuture(ProbeControl.inspect()));
        context.registrations().action(descriptor("release_all"),inv->CompletableFuture.completedFuture(ProbeControl.releaseAll()));
        context.registrations().action(descriptor("reset"),inv->CompletableFuture.completedFuture(ProbeControl.reset()));
        System.out.println("Animus behavior QA enabled qabehaviorctl");
    }
    @Override public void disable(){System.out.println("Animus behavior QA disabled qabehaviorctl");}
}
