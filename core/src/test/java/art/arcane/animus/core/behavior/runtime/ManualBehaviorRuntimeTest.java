package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.npc.NpcDefinition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualBehaviorRuntimeTest {
    static final String ACTION="{\"type\":\"action\",\"capability\":\"sample:act\"}";
    static final String WAIT="{\"type\":\"wait\",\"ticks\":100}";
    static final String EMPTY="{\"type\":\"sequence\",\"steps\":[]}";
    static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(4,TimeUnit.SECONDS);}
    static void eventually(BooleanSupplier check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!check.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue(check.getAsBoolean());}
    static void latch(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    static BehaviorProgram program(String node){return BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"example:manual\",\"entry\":\"main\",\"routines\":{\"main\":"+node+"}}").program().orElseThrow();}
    static NpcDefinition npc(String id,UUID uuid,long revision,boolean spawned)throws Exception{return NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":"+revision+",\"uuid\":\""+uuid+"\",\"id\":\""+id+"\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":"+spawned+",\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}");}
    static final class Sources {
        final AtomicReference<Map<String,NpcDefinition>> npcs=new AtomicReference<>();
        final AtomicReference<BehaviorCatalog.Snapshot> catalog=new AtomicReference<>();
        Sources(String node)throws Exception{npcs.set(Map.of("test",npc("test",UUID.randomUUID(),1,false)));replaceProgram(node,1);}
        void replaceProgram(String node,long revision){var accepted=new BehaviorCatalog.Accepted(revision,String.format("%064x",revision),100,program(node),Set.of());publish(accepted,true);}
        void publish(BehaviorCatalog.Accepted accepted,boolean available){catalog.set(new BehaviorCatalog.Snapshot(1,Map.of(),Map.of("example:manual",new BehaviorCatalog.Resolution("example:manual",available,Optional.ofNullable(accepted),List.of())),List.of()));}
        void add(String id)throws Exception{Map<String,NpcDefinition> copy=new HashMap<>(npcs.get());copy.put(id,npc(id,UUID.randomUUID(),1,false));npcs.set(Map.copyOf(copy));}
        ManualBehaviorRuntime runtime(Fake fake){return new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8,8,50,20),BehaviorEngine.Limits.defaults(),npcs::get,catalog::get,fake);}
    }
    static final class Fake implements BehaviorInvoker {
        final List<Invocation> invocations=new CopyOnWriteArrayList<>();final AtomicInteger cancellations=new AtomicInteger();
        final AtomicReference<Thread> thread=new AtomicReference<>();Runnable availability=()->{};
        Function<Invocation,Call<String>> action=i->new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),cancellations::incrementAndGet);
        public boolean available(Capabilities.Key key,Capabilities.Kind kind){availability.run();return true;}
        public Call<String> action(Invocation invocation){thread.set(Thread.currentThread());invocations.add(invocation);return action.apply(invocation);}
        public Call<Boolean> condition(Invocation invocation){return new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)),()->{});}
    }
    static ThreadFactory heldOwner(CountDownLatch release){return task->{Thread thread=new Thread(()->{latch(release);task.run();},"held-behavior-owner");thread.setDaemon(true);return thread;};}
    static void stop(ManualBehaviorRuntime runtime)throws Exception{runtime.close();assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)),runtime.status().toString());}

    @Test void dormantManualExecutionRunsOnPrivateOwnerWithoutPreviewAndPreservesSenderIdentity()throws Exception{
        Sources sources=new Sources(ACTION);Fake fake=new Fake();UUID player=UUID.randomUUID();var runtime=sources.runtime(fake);
        try{
            UUID id=await(runtime.run("test","example:manual",Optional.of(player))).value().orElseThrow();
            eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            assertNotSame(Thread.currentThread(),fake.thread.get());assertEquals("animus-behavior-owner",fake.thread.get().getName());
            assertEquals(Optional.of(player),fake.invocations.getFirst().player());assertFalse(fake.invocations.getFirst().preview());
            var view=runtime.inspect(id).orElseThrow();assertEquals(1,view.npcRevision());assertEquals(1,view.behaviorRevision());assertEquals(sources.npcs.get().get("test").uuid(),view.npcUuid());
            assertThrows(UnsupportedOperationException.class,()->runtime.snapshot().executions().clear());
        }finally{stop(runtime);}
    }
    @Test void oneActiveExecutionPerNpcAndExplicitCancelReleaseItsLease()throws Exception{
        Sources sources=new Sources(WAIT);var runtime=sources.runtime(new Fake());
        try{
            UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();assertEquals(Outcome.Status.BUSY,await(runtime.run("test","example:manual")).status());
            assertTrue(await(runtime.cancel(id)).value().orElseThrow());assertEquals(BehaviorEngine.State.CANCELLED,runtime.inspect(id).orElseThrow().report().state());
            assertFalse(await(runtime.cancel(id)).value().orElseThrow());assertEquals(Outcome.Status.OK,await(runtime.run("test","example:manual")).status());
        }finally{stop(runtime);}
    }
    @Test void queuedRunCannotBindToAnNpcRevisionPublishedAfterAdmission()throws Exception{
        Sources sources=new Sources(ACTION);CountDownLatch release=new CountDownLatch(1);Fake fake=new Fake();
        var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,fake,heldOwner(release));
        try{
            var request=runtime.run("test","example:manual");NpcDefinition old=sources.npcs.get().get("test");sources.npcs.set(Map.of("test",npc("test",old.uuid(),2,false)));release.countDown();
            assertEquals(Outcome.Status.CONFLICT,await(request).status());assertTrue(fake.invocations.isEmpty());assertTrue(runtime.snapshot().executions().isEmpty());
        }finally{release.countDown();stop(runtime);}
    }
    @Test void queuedRunCannotBindToAReplacedAcceptedProgram()throws Exception{
        Sources sources=new Sources(ACTION);CountDownLatch release=new CountDownLatch(1);Fake fake=new Fake();
        var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,fake,heldOwner(release));
        try{var request=runtime.run("test","example:manual");sources.replaceProgram(EMPTY,2);release.countDown();assertEquals(Outcome.Status.CONFLICT,await(request).status());assertTrue(fake.invocations.isEmpty());}
        finally{release.countDown();stop(runtime);}
    }
    @Test void deletionRevisionReplacementAndPersistedDespawnEachRevokeActiveExecution()throws Exception{
        for(int mode=0;mode<3;mode++){
            Sources sources=new Sources(WAIT);NpcDefinition original=npc("test",UUID.randomUUID(),1,true);sources.npcs.set(Map.of("test",original));var runtime=sources.runtime(new Fake());
            try{
                UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();
                sources.npcs.set(mode==0?Map.of():Map.of("test",npc("test",original.uuid(),mode==1?2:1,mode!=2)));
                eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);assertEquals("captured-source-changed",runtime.inspect(id).orElseThrow().report().reason());
            }finally{stop(runtime);}
        }
    }
    @Test void missingUnavailableAndReplacedBehaviorEachRevokeButSameAcceptedTokenSurvives()throws Exception{
        for(int mode=0;mode<3;mode++){
            Sources sources=new Sources(WAIT);var runtime=sources.runtime(new Fake());
            try{
                UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();var accepted=sources.catalog.get().byId().get("example:manual").accepted().orElseThrow();
                long tick=runtime.status().engine().tick();sources.publish(accepted,true);eventually(()->runtime.status().engine().tick()>tick+1);
                assertEquals(BehaviorEngine.State.RUNNING,runtime.inspect(id).orElseThrow().report().state());
                if(mode==0)sources.catalog.set(new BehaviorCatalog.Snapshot(2,Map.of(),Map.of(),List.of()));else if(mode==1)sources.publish(accepted,false);else sources.replaceProgram(WAIT,2);
                eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            }finally{stop(runtime);}
        }
    }
    @Test void authorityIsCheckedAgainAfterCapabilityPreparationBeforeCallingHandler()throws Exception{
        Sources sources=new Sources(ACTION);Fake fake=new Fake();fake.availability=()->sources.npcs.set(Map.of());var runtime=sources.runtime(fake);
        try{
            UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();eventually(()->runtime.inspect(id).orElseThrow().report().state()!=BehaviorEngine.State.RUNNING);
            assertTrue(fake.invocations.isEmpty());assertNotEquals(BehaviorEngine.State.COMPLETED,runtime.inspect(id).orElseThrow().report().state());
        }finally{stop(runtime);}
    }
    @Test void closeBeforeFirstOwnerTurnCancelsQueuedRequestsAndNeverInvokes()throws Exception{
        Sources sources=new Sources(ACTION);Fake fake=new Fake();CountDownLatch release=new CountDownLatch(1);
        var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,fake,heldOwner(release));
        try{
            var queued=runtime.run("test","example:manual");runtime.close();assertEquals(ManualBehaviorRuntime.State.STOPPING,runtime.status().state());
            assertEquals(Outcome.Status.UNAVAILABLE,await(runtime.run("test","example:manual")).status());release.countDown();
            assertEquals(Outcome.Status.CANCELLED,await(queued).status());assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));assertTrue(fake.invocations.isEmpty());assertEquals(0,runtime.status().admittedOperations());
        }finally{release.countDown();stop(runtime);}
    }
    @Test void earlyCancellationResultDoesNotReleaseUnquiescentHostCallOrClaimShutdown()throws Exception{
        Sources sources=new Sources(ACTION);Fake fake=new Fake();CompletableFuture<Outcome<String>> result=new CompletableFuture<>();AtomicBoolean quiet=new AtomicBoolean();
        fake.action=i->new BehaviorInvoker.Call<>(result,()->{fake.cancellations.incrementAndGet();result.complete(Outcome.rejected(Outcome.Status.CANCELLED,"cancelled","Cancelled"));},quiet::get);
        var runtime=sources.runtime(fake);
        try{
            UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();eventually(()->fake.invocations.size()==1);runtime.close();
            eventually(()->fake.cancellations.get()==1);eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            assertFalse(runtime.awaitStopped(Duration.ofMillis(125)));assertEquals(ManualBehaviorRuntime.State.STOPPING,runtime.status().state());assertEquals(1,runtime.status().engine().retiredCalls());
            quiet.set(true);assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));assertEquals(0,runtime.status().engine().callsInFlight());
        }finally{quiet.set(true);result.complete(Outcome.ok("{}"));stop(runtime);}
    }
    @Test void boundedResultWorkersCannotBlockTicksAndRetainAdmissionWhileListenersBlock()throws Exception{
        Sources sources=new Sources(WAIT);sources.add("second");Fake fake=new Fake();CountDownLatch ownerRelease=new CountDownLatch(1),listeners=new CountDownLatch(2),releaseListeners=new CountDownLatch(1);
        var runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(3,3,50,1),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,fake,heldOwner(ownerRelease));
        try{
            var one=runtime.run("test","example:manual");var two=runtime.run("second","example:manual");
            one.thenAccept(value->{listeners.countDown();latch(releaseListeners);});two.thenAccept(value->{listeners.countDown();latch(releaseListeners);});ownerRelease.countDown();latch(listeners);
            UUID id=one.toCompletableFuture().get(1,TimeUnit.SECONDS).value().orElseThrow();long before=runtime.status().engine().tick();
            var cancelled=runtime.cancel(id);eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            assertFalse(cancelled.toCompletableFuture().isDone());assertEquals(3,runtime.status().admittedOperations());assertEquals(1,runtime.status().queuedDeliveries());
            assertEquals(Outcome.Status.BUSY,await(runtime.cancel(UUID.randomUUID())).status());eventually(()->runtime.status().engine().tick()>before+1);
            assertEquals(2,runtime.status().overBudgetDeliveries());runtime.close();assertFalse(runtime.awaitStopped(Duration.ofMillis(125)));assertEquals(0,runtime.status().engine().activeExecutions());
            releaseListeners.countDown();assertTrue(await(cancelled).value().orElseThrow());assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));assertTrue(runtime.status().completedOverBudgetDeliveries()>=2);
        }finally{ownerRelease.countDown();releaseListeners.countDown();stop(runtime);}
    }
    @Test void callerFutureCopyCannotCancelOrForgeQueuedOperation()throws Exception{
        Sources sources=new Sources(EMPTY);CountDownLatch release=new CountDownLatch(1);
        var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,new Fake(),heldOwner(release));
        try{
            var stage=runtime.run("test","example:manual");assertTrue(stage.toCompletableFuture().complete(Outcome.ok(UUID.randomUUID())));release.countDown();
            UUID actual=await(stage).value().orElseThrow();assertTrue(runtime.inspect(actual).isPresent());assertEquals(1,runtime.snapshot().executions().size());
        }finally{release.countDown();stop(runtime);}
    }
    @Test void smallHistoryNeverStrandsNpcLeasesWhenManyExecutionsFinishInOneTick()throws Exception{
        Sources sources=new Sources(EMPTY);sources.add("second");sources.add("third");CountDownLatch release=new CountDownLatch(1);
        var budgets=new BehaviorEngine.Limits(4,8,4,32,100,100,4,8,1);
        var runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8,8,50,20),budgets,sources.npcs::get,sources.catalog::get,new Fake(),heldOwner(release));
        try{
            var first=runtime.run("test","example:manual");var second=runtime.run("second","example:manual");var third=runtime.run("third","example:manual");release.countDown();
            assertEquals(Outcome.Status.OK,await(first).status());assertEquals(Outcome.Status.OK,await(second).status());assertEquals(Outcome.Status.OK,await(third).status());
            eventually(()->runtime.status().engine().activeExecutions()==0);assertEquals(1,runtime.snapshot().executions().size());assertEquals(1,runtime.status().engine().history());
            assertEquals(Outcome.Status.OK,await(runtime.run("test","example:manual")).status());assertEquals(Outcome.Status.OK,await(runtime.run("second","example:manual")).status());assertEquals("",runtime.status().failure());
        }finally{release.countDown();stop(runtime);}
    }
    @Test void sourceObservationFailureClosesAdmissionAndCancelsExistingRuns()throws Exception{
        Sources sources=new Sources(WAIT);AtomicBoolean fail=new AtomicBoolean();
        var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),()->{if(fail.get())throw new IllegalStateException("synthetic source failure");return sources.npcs.get();},sources.catalog::get,new Fake());
        try{UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();fail.set(true);eventually(()->runtime.status().state()==ManualBehaviorRuntime.State.STOPPED);assertEquals("IllegalStateException",runtime.status().failure());assertEquals(BehaviorEngine.State.CANCELLED,runtime.inspect(id).orElseThrow().report().state());}
        finally{stop(runtime);}
    }
    @Test void invalidIdentitiesMissingSourcesAndUnavailableCatalogAreExpectedRejections()throws Exception{
        Sources sources=new Sources(EMPTY);var runtime=sources.runtime(new Fake());
        try{
            assertEquals(Outcome.Status.INVALID,await(runtime.run("../bad","example:manual")).status());assertEquals(Outcome.Status.INVALID,await(runtime.run("test","bad//path")).status());
            assertEquals(Outcome.Status.NOT_FOUND,await(runtime.run("missing","example:manual")).status());assertEquals(Outcome.Status.NOT_FOUND,await(runtime.run("test","example:missing")).status());
            sources.publish(null,false);assertEquals(Outcome.Status.UNAVAILABLE,await(runtime.run("test","example:manual")).status());assertEquals(0,runtime.status().admittedOperations());
        }finally{stop(runtime);}
    }
    @Test void concurrentAdmissionAndCloseNeverExceedBoundsOrLoseAcceptedAcknowledgments()throws Exception{
        Sources sources=new Sources(ACTION);Fake fake=new Fake();CountDownLatch ownerRelease=new CountDownLatch(1),start=new CountDownLatch(1);
        var runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(4,2,50,20),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,fake,heldOwner(ownerRelease));
        List<CompletionStage<Outcome<UUID>>> results=new CopyOnWriteArrayList<>();
        try(var callers=Executors.newFixedThreadPool(8)){
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<40;i++)tasks.add(callers.submit(()->{latch(start);results.add(runtime.run("test","example:manual"));assertTrue(runtime.status().admittedOperations()<=4);}));
            start.countDown();for(Future<?> task:tasks)task.get(2,TimeUnit.SECONDS);
            assertEquals(4,runtime.status().admittedOperations());assertEquals(4,runtime.status().queuedOperations());
            runtime.close();ownerRelease.countDown();int cancelled=0,busy=0;
            for(var result:results){Outcome.Status status=await(result).status();if(status==Outcome.Status.CANCELLED)cancelled++;else if(status==Outcome.Status.BUSY)busy++;else fail(status.toString());}
            assertEquals(4,cancelled);assertEquals(36,busy);assertTrue(fake.invocations.isEmpty());assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));
        }finally{start.countDown();ownerRelease.countDown();stop(runtime);}
    }
    @Test void closeRacingRequestAdmissionAlwaysAcknowledgesOrExplicitlyRejects()throws Exception{
        for(int attempt=0;attempt<12;attempt++){
            Sources sources=new Sources(ACTION);CountDownLatch ownerRelease=new CountDownLatch(1),start=new CountDownLatch(1);Fake fake=new Fake();
            var runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(2,2,50,20),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,fake,heldOwner(ownerRelease));
            try(var callers=Executors.newFixedThreadPool(2)){
                Future<CompletionStage<Outcome<UUID>>> request=callers.submit(()->{latch(start);return runtime.run("test","example:manual");});
                Future<?> close=callers.submit(()->{latch(start);runtime.close();});start.countDown();close.get(2,TimeUnit.SECONDS);var result=request.get(2,TimeUnit.SECONDS);ownerRelease.countDown();
                assertTrue(Set.of(Outcome.Status.CANCELLED,Outcome.Status.UNAVAILABLE).contains(await(result).status()));assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));assertTrue(fake.invocations.isEmpty());
            }finally{start.countDown();ownerRelease.countDown();stop(runtime);}
        }
    }
    @Test void completedExecutionStillRetainsUnquiescentResultDeliveryUntilShutdownDrains()throws Exception{
        Sources sources=new Sources(ACTION);Fake fake=new Fake();AtomicBoolean quiet=new AtomicBoolean();
        fake.action=i->new BehaviorInvoker.Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),fake.cancellations::incrementAndGet,quiet::get);
        var runtime=sources.runtime(fake);
        try{
            UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            assertEquals(1,runtime.status().engine().callsInFlight());runtime.close();assertFalse(runtime.awaitStopped(Duration.ofMillis(125)));assertEquals(0,fake.cancellations.get());
            quiet.set(true);assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));
        }finally{quiet.set(true);stop(runtime);}
    }
    @Test void limitsRejectUnboundedOrOverTwentyHertzConfiguration(){
        for(int[] values:new int[][]{{0,1,50,1},{257,1,50,1},{1,0,50,1},{64,65,50,1},{1,2,50,1},{1,1,49,1},{1,1,1001,1},{1,1,50,0},{1,1,50,60001}})
            assertThrows(IllegalArgumentException.class,()->new ManualBehaviorRuntime.Limits(values[0],values[1],values[2],values[3]));
        assertEquals(50,ManualBehaviorRuntime.Limits.defaults().tickMillis());
    }
}
