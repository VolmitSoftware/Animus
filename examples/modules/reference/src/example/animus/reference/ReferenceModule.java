package example.animus.reference;

import art.arcane.animus.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A trusted extension compiled against only animus-api and the JDK. */
public final class ReferenceModule implements TrustedModule {
    private static final Set<Platform> PLATFORMS=Set.of(Platform.SPIGOT,Platform.PAPER,Platform.FOLIA);
    private final Map<UUID,AtomicLong> interactions=new ConcurrentHashMap<>();
    private final AtomicLong protectionChecks=new AtomicLong();

    @Override public ModuleManifest manifest(){
        return new ModuleManifest("reference",new Version(1,0,0),new ApiRange(1,1),PLATFORMS,List.of());
    }

    @Override public void enable(ModuleContext context){
        context.registrations().action(new Capabilities.Descriptor(
                new Capabilities.Key("reference","hello"),Capabilities.Kind.ACTION,"Reference greeting",
                "{\"type\":\"object\",\"additionalProperties\":false}",PLATFORMS,false),
                invocation->CompletableFuture.completedFuture("{\"message\":\"Hello from a trusted external module\"}"));
        // Interaction notices and integration hooks arrived in API 1.1; a 1.0 engine keeps only the greeting.
        if(AnimusApi.API_VERSION.minor()<1)return;
        context.onInteraction((InteractionNotice notice,Capabilities.Cancellation cancellation)->{
            if(!notice.preview())interactions.computeIfAbsent(notice.npc().uuid(),npc->new AtomicLong()).incrementAndGet();
        });
        context.registrations().action(new Capabilities.Descriptor(
                new Capabilities.Key("reference","interactions"),Capabilities.Kind.ACTION,"Counts accepted interactions with this NPC and protection checks seen",
                "{\"type\":\"object\",\"additionalProperties\":false}",PLATFORMS,false),
                invocation->{
                    AtomicLong count=interactions.get(invocation.npc().uuid());
                    return CompletableFuture.completedFuture("{\"interactions\":"+(count==null?0:count.get())+",\"protectionChecks\":"+protectionChecks.get()+"}");
                });
        // Combined with the server's own protection, so allowing everything keeps its decisions; return
        // Integrations.Decision.DENY here to add a rule, such as refusing NPC builds in a protected area.
        context.integrations().protection(new Integrations.Descriptor("Counts protection checks and allows them",PLATFORMS),
                (Integrations.ProtectionRequest request)->{protectionChecks.incrementAndGet();return Integrations.Decision.ALLOW;});
    }

    @Override public void disable(){
        interactions.clear();
    }
}
