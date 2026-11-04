import art.arcane.animus.api.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixture self-check only. This is deliberately not the production host or schema validator. */
public final class ProbeHarness {
    private static final Map<Capabilities.Key,Capabilities.Action> actions=new HashMap<>();
    private static final Map<Capabilities.Key,Capabilities.Condition> conditions=new HashMap<>();
    private static final List<AutoCloseable> resources=new ArrayList<>();
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static Capabilities.Invocation invocation(UUID npc,AtomicBoolean cancellation,String arguments){return new Capabilities.Invocation(new NpcHandle(npc),Optional.empty(),arguments,false,cancellation::get);}
    private static CompletionStage<String> call(String key,Capabilities.Invocation invocation)throws Exception {String[] pieces=key.split(":");return actions.get(new Capabilities.Key(pieces[0],pieces[1])).run(invocation);}
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);URL[] urls={directory.resolve("qabprobe.jar").toUri().toURL(),directory.resolve("qabehavior.jar").toUri().toURL(),directory.resolve("qabehaviorctl.jar").toUri().toURL()};
        try(URLClassLoader loader=new URLClassLoader(urls,ProbeHarness.class.getClassLoader())) {
            ModuleContext context=new ModuleContext() {
                public DefinitionService definitions(){throw new AssertionError("Fixture must not access definitions");}
                public ModuleScheduler scheduler(){throw new AssertionError("Fixture must not schedule work");}
                public void onClose(AutoCloseable resource){resources.add(resource);}
                public Capabilities.Registrar registrations(){return new Capabilities.Registrar(){
                    public void action(Capabilities.Descriptor d,Capabilities.Action action){if(actions.putIfAbsent(d.key(),action)!=null)throw new AssertionError("Duplicate action");}
                    public void condition(Capabilities.Descriptor d,Capabilities.Condition condition){if(conditions.putIfAbsent(d.key(),condition)!=null)throw new AssertionError("Duplicate condition");}
                    public void trigger(Capabilities.Descriptor d){throw new AssertionError("No trigger fixture yet");}
                };}
            };
            List<TrustedModule> modules=new ArrayList<>();
            for(String name:List.of("example.animus.behavior.control.ProbeFixture","example.animus.behavior.BehaviorFixture","example.animus.behavior.release.ReleaseFixture")) {
                Object instance=Class.forName(name,true,loader).getConstructor().newInstance();check(instance instanceof TrustedModule,"Stable parent API identity");TrustedModule module=(TrustedModule)instance;modules.add(module);module.enable(context);
            }
            check(actions.size()==6 && conditions.size()==2,"Expected eight fixture capabilities");
            UUID npc=UUID.fromString("215fa2cf-d096-4c27-bb76-326bf29b7c64");AtomicBoolean cancellation=new AtomicBoolean();var invocation=invocation(npc,cancellation,"{}");
            var held=call("qabehavior:hold",invocation).toCompletableFuture();check(!held.isDone(),"Raw hold starts unresolved");cancellation.set(true);check(!held.isDone(),"Cancellation token does not fabricate raw completion");
            String observed=call("qabehaviorctl:inspect",invocation).toCompletableFuture().join();check(observed.contains("\"cancelled\":true"),"Independent controller observes actual token");
            boolean refused=false;try{call("qabehaviorctl:reset",invocation);}catch(IllegalStateException expected){refused=true;}check(refused,"Reset cannot discard an unfinished hold");
            String released=call("qabehaviorctl:release_all",invocation).toCompletableFuture().join();check(released.equals("{\"released\":1,\"cancelled\":1}"),"Release records cancellation");check(held.join().equals("{\"released\":true}"),"Explicit release completes raw stage");
            call("qabehaviorctl:reset",invocation);
            for(int i=0;i<64;i++)call("qabehavior:hold",invocation(new UUID(0,i+1),new AtomicBoolean(),"{}"));
            refused=false;try{call("qabehavior:hold",invocation);}catch(IllegalStateException expected){refused=true;}check(refused,"Raw hold capacity is sixty-four");call("qabehaviorctl:release_all",invocation);call("qabehaviorctl:reset",invocation);
            for(int i=0;i<257;i++)call("qabehavior:record",invocation(npc,new AtomicBoolean(),"{\"tag\":\"loop\"}"));
            observed=call("qabehaviorctl:inspect",invocation).toCompletableFuture().join();check(observed.contains("\"sequence\":257,\"dropped\":1"),"Event retention is finite and reported");
            check(!conditions.get(new Capabilities.Key("qabehavior","false")).test(invocation).toCompletableFuture().join(),"False condition stays false");
            var closeHeld=call("qabehavior:hold",invocation).toCompletableFuture();
            Collections.reverse(modules);for(TrustedModule module:modules)module.disable();check(closeHeld.isDone(),"Shared dependency cleanup releases raw stages");
            Collections.reverse(resources);for(AutoCloseable resource:resources)resource.close();
            System.out.println("{\"status\":\"passed\",\"checks\":"+checks+",\"scope\":\"API-only fixture self-check; no host/schema/behavior/server verification\"}");
        }
    }
}
