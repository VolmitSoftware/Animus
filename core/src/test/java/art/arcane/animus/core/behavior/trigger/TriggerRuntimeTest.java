package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntime;
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
    private ManualBehaviorRuntime runtime;
    private long revision;

    private void behavior(String routine,String triggers){
        revision++;
        BehaviorProgram program=BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\""+BEHAVIOR+"\",\"entry\":\"main\",\"routines\":{\"main\":"+routine+"},\"triggers\":"+triggers+"}").program().orElseThrow();
        BehaviorCatalog.Accepted accepted=new BehaviorCatalog.Accepted(revision,String.format("%064x",revision),100,program,Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(revision,Map.of(),Map.of(BEHAVIOR,new BehaviorCatalog.Resolution(BEHAVIOR,true,Optional.of(accepted),List.of())),List.of()));
    }
    private void definition(long version,boolean spawned)throws Exception{
        definitions.set(Map.of("test",NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":"+version+",\"uuid\":\""+npc+"\",\"id\":\"test\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":"+spawned+",\"behaviors\":[\""+BEHAVIOR+"\"],\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}")));
    }
    private void start(int perTrigger){
        TriggerSettings settings=new TriggerSettings(true,16,32,8,64,4,4,1000,32,4,perTrigger,0,0,4096,1024,1,32,5,16,32,64);
        dispatcher=new TriggerDispatcher(settings,definitions::get,catalog::get);dispatcher.connect();
        BehaviorInvoker invoker=new BehaviorInvoker(){
            public boolean available(Capabilities.Key key,Capabilities.Kind kind){return true;}
            public Call<String> action(Invocation invocation){invocations.add(invocation);return new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),()->{});}
            public Call<Boolean> condition(Invocation invocation){return new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)),()->{});}
        };
        runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8,8,50,20),BehaviorEngine.Limits.defaults(),definitions::get,catalog::get,invoker,dispatcher,art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
    }
    private static void eventually(BooleanSupplier check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!check.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue(check.getAsBoolean());}
    private List<ManualBehaviorRuntime.Execution> running(){return runtime.snapshot().executions().values().stream().filter(item->item.report().state()==BehaviorEngine.State.RUNNING).toList();}
    private void click()throws Exception{
        eventually(()->dispatcher.snapshot().bindings()>0);assertEquals(Outcome.Status.OK,dispatcher.publish(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT)).status());
    }
    private void active()throws Exception{
        eventually(()->dispatcher.snapshot().bindings()>0);dispatcher.activated(npc);
    }
    @AfterEach void stop()throws Exception{if(runtime!=null){runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)),runtime.status().toString());}}

    @Test void eventRunsOnTheBehaviorOwnerWithThePlayerAndNeverPreview()throws Exception{
        behavior(ACTION,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->!invocations.isEmpty());
        assertEquals(Optional.of(viewer),invocations.getFirst().player());assertFalse(invocations.getFirst().preview());assertEquals(new NpcHandle(npc),invocations.getFirst().npc());
        assertTrue(invocations.getFirst().sourceCurrent().getAsBoolean());
        eventually(()->dispatcher.snapshot().active()==0);
        eventually(()->!runtime.snapshot().executions().isEmpty()&&running().isEmpty());
        ManualBehaviorRuntime.Execution execution=runtime.snapshot().executions().values().iterator().next();
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
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(3);active();click();click();click();
        eventually(()->running().size()==3);assertEquals(3,dispatcher.snapshot().active());
    }
    @Test void perTriggerConcurrencyHoldsUnderTheRealRuntime()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();click();
        eventually(()->dispatcher.snapshot().queued()==1);eventually(()->running().size()==1);
    }
    @Test void manualRunsRemainOnePerNpcAndCoexistWithTriggeredWork()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->running().size()==1);
        Outcome<UUID> manual=runtime.run("test",BEHAVIOR).toCompletableFuture().get(4,TimeUnit.SECONDS);assertEquals(Outcome.Status.OK,manual.status());
        assertEquals(Outcome.Status.BUSY,runtime.run("test",BEHAVIOR).toCompletableFuture().get(4,TimeUnit.SECONDS).status());
        eventually(()->runtime.inspect(manual.value().orElseThrow()).isPresent());
        assertEquals(Optional.empty(),runtime.inspect(manual.value().orElseThrow()).orElseThrow().trigger());
    }
    @Test void closingTheRuntimeReleasesTriggeredWorkAndRefusesEvents()throws Exception{
        behavior(WAIT,"[{\"id\":\"click\",\"event\":\"interaction\"}]");definition(1,true);start(1);active();click();
        eventually(()->running().size()==1);
        runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));
        assertEquals(0,dispatcher.snapshot().active());assertFalse(dispatcher.available());
        assertEquals(Outcome.Status.UNAVAILABLE,dispatcher.publish(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT)).status());
    }
}
