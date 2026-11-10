package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.runtime.BehaviorRuntime;
import art.arcane.animus.core.npc.NpcDefinition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real private owner thread and interpreter; only the module invoker is replaced. */
class TriggerRuntimeTest {
    private static final String WAIT="{\"type\":\"wait\",\"ticks\":2000}";
    private static final String ACTION="{\"type\":\"action\",\"capability\":\"sample:act\"}";
    private static final String BEHAVIOR="example:greeter";
    private final UUID npc=UUID.randomUUID(),viewer=UUID.randomUUID();
    private final AtomicReference<Map<String,NpcDefinition>> definitions=new AtomicReference<>();
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog=new AtomicReference<>();
    private final List<BehaviorInvoker.Invocation> invocations=new CopyOnWriteArrayList<>();
    private TriggerDispatcher dispatcher;
    private BehaviorRuntime runtime;
    private long revision;

    private void behavior(String routine,String triggers){
        revision++;
        BehaviorProgram program=BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\""+BEHAVIOR+"\",\"entry\":\"main\",\"routines\":{\"main\":"+routine+"},\"triggers\":"+triggers+"}").program().orElseThrow();
        BehaviorCatalog.Accepted accepted=new BehaviorCatalog.Accepted(revision,String.format("%064x",revision),100,program,Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(revision,Map.of(),Map.of(BEHAVIOR,new BehaviorCatalog.Resolution(BEHAVIOR,true,Optional.of(accepted),List.of())),List.of()));
    }
    private void definition(long version,boolean spawned)throws Exception{
        definitions.set(Map.of("test",NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":"+version+",\"uuid\":\""+npc+"\",\"id\":\"test\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":"+spawned+",\"behaviors\":[\""+BEHAVIOR+"\"],\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}")));
    }
    private void start(int perTrigger){
        TriggerSettings settings=new TriggerSettings(true,16,32,8,64,4,4,1000,32,4,perTrigger,0,0,4096,1024,1,32,5,16,32,64,0);
        dispatcher=new TriggerDispatcher(settings,definitions::get,catalog::get);dispatcher.connect();
        BehaviorInvoker invoker=new BehaviorInvoker(){
            public boolean available(Capabilities.Key key,Capabilities.Kind kind){return true;}
            public Call<String> action(Invocation invocation){invocations.add(invocation);return new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),()->{});}
            public Call<Boolean> condition(Invocation invocation){return new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)),()->{});}
        };
        runtime=new BehaviorRuntime(new BehaviorRuntime.Limits(8,8,50,20),BehaviorEngine.Limits.defaults(),definitions::get,catalog::get,invoker,dispatcher,art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
    }
    private static final Duration PATIENCE=Duration.ofSeconds(30);
    private static void eventually(BooleanSupplier check)throws Exception{long until=System.nanoTime()+PATIENCE.toNanos();while(!check.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue(check.getAsBoolean(),"condition not met within "+PATIENCE.toSeconds()+" s");}
    private List<BehaviorRuntime.Execution> running(){return runtime.snapshot().executions().values().stream().filter(item->item.report().state()==BehaviorEngine.State.RUNNING).toList();}
    /** Publishes one interaction; a busy owner answers BUSY, so the click is retried until it is accepted. */
    private void click()throws Exception{
        eventually(()->dispatcher.snapshot().bindings()>0);
        AtomicReference<Outcome.Status> last=new AtomicReference<>();
        eventually(()->{last.set(dispatcher.publish(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT)).status());return last.get()!=Outcome.Status.BUSY;});
        assertEquals(Outcome.Status.OK,last.get());
    }
    private void active()throws Exception{
        eventually(()->dispatcher.snapshot().bindings()>0);dispatcher.activated(npc);
    }
    @AfterEach void stop()throws Exception{if(runtime!=null){runtime.close();assertTrue(runtime.awaitStopped(PATIENCE),runtime.status().toString());}}

    @Test void eventRunsOnTheBehaviorOwnerWithThePlayerAndNeverPreview()throws Exception{
        behavior(ACTION,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->!invocations.isEmpty());
        assertEquals(Optional.of(viewer),invocations.getFirst().player());assertFalse(invocations.getFirst().preview());assertEquals(new NpcHandle(npc),invocations.getFirst().npc());
        assertTrue(invocations.getFirst().sourceCurrent().getAsBoolean());
        eventually(()->dispatcher.snapshot().active()==0);
        eventually(()->!runtime.snapshot().executions().isEmpty()&&running().isEmpty());
        BehaviorRuntime.Execution execution=runtime.snapshot().executions().values().iterator().next();
        assertEquals(Optional.of("example:greeter#click"),execution.trigger());assertEquals(BehaviorEngine.State.COMPLETED,execution.report().state());
    }
    @Test void replacingTheBehaviorCancelsRunningTriggeredExecutions()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->running().size()==1);UUID id=running().getFirst().report().id();
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");
        eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
        assertEquals("captured-source-changed",runtime.inspect(id).orElseThrow().report().reason());eventually(()->dispatcher.snapshot().active()==0);
    }
    @Test void despawningTheNpcCancelsRunningTriggeredExecutions()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->running().size()==1);UUID id=running().getFirst().report().id();
        definition(2,false);dispatcher.deactivated(npc);
        eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);eventually(()->dispatcher.snapshot().active()==0);
    }
    @Test void revokedExecutionLosesItsInvocationAuthority()throws Exception{
        behavior(ACTION,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->!invocations.isEmpty());definition(2,true);
        assertFalse(invocations.getFirst().sourceCurrent().getAsBoolean());
    }
    @Test void severalExecutionsOfOneNpcHoldIndependentAuthority()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");definition(1,true);start(3);active();click();click();click();
        eventually(()->running().size()==3);assertEquals(3,dispatcher.snapshot().active());
    }
    @Test void perTriggerConcurrencyHoldsUnderTheRealRuntime()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");definition(1,true);start(1);active();click();
        // The second click waits behind a running one; sent in the same tick, the per-trigger queue would refuse it instead.
        eventually(()->running().size()==1);click();
        eventually(()->dispatcher.snapshot().queued()==1);assertEquals(1,running().size());
    }
    @Test void manualRunsRemainOnePerNpcAndCoexistWithTriggeredWork()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->running().size()==1);
        Outcome<UUID> manual=runtime.run("test",BEHAVIOR).toCompletableFuture().get(PATIENCE.toSeconds(),TimeUnit.SECONDS);assertEquals(Outcome.Status.OK,manual.status());
        assertEquals(Outcome.Status.BUSY,runtime.run("test",BEHAVIOR).toCompletableFuture().get(PATIENCE.toSeconds(),TimeUnit.SECONDS).status());
        eventually(()->runtime.inspect(manual.value().orElseThrow()).isPresent());
        assertEquals(Optional.empty(),runtime.inspect(manual.value().orElseThrow()).orElseThrow().trigger());
    }
    @Test void closingTheRuntimeReleasesTriggeredWorkAndRefusesEvents()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->running().size()==1);
        runtime.close();assertTrue(runtime.awaitStopped(PATIENCE));
        assertEquals(0,dispatcher.snapshot().active());assertFalse(dispatcher.available());
        assertEquals(Outcome.Status.UNAVAILABLE,dispatcher.publish(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT)).status());
    }
}
