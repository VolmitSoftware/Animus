package art.arcane.animus.core.extension;

import art.arcane.animus.api.Outcome;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MutationLaneTest {
    static <T> T await(CompletionStage<T> value) throws Exception{return value.toCompletableFuture().get(3,TimeUnit.SECONDS);}
    /** A completed future precedes its delivery task's own accounting, so the count settles shortly afterward. */
    static void awaitActiveDeliveries(MutationLane lane,int expected) throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(lane.status().activeDeliveries()!=expected&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals(expected,lane.status().activeDeliveries());
    }
    static void release(CountDownLatch latch){try{assertTrue(latch.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
    static void eventually(java.util.function.BooleanSupplier check)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!check.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue(check.getAsBoolean());
    }
    @Test void queueBoundCountsRunningWorkAndSerializesWholeOperations() throws Exception {
        try(MutationLane lane=new MutationLane(2)){
            CountDownLatch started=new CountDownLatch(1),continueFirst=new CountDownLatch(1);
            List<String> order=Collections.synchronizedList(new ArrayList<>());
            var first=lane.submit(()->{order.add("validate");started.countDown();release(continueFirst);order.add("commit");order.add("publish");return Outcome.ok(1);});release(started);
            var second=lane.submit(()->{order.add("import-validation");return Outcome.ok(2);});
            assertEquals(Outcome.Status.BUSY,await(lane.submit(()->Outcome.ok(3))).status());assertEquals(List.of("validate"),order);
            continueFirst.countDown();assertEquals(1,await(first).value().orElseThrow());assertEquals(2,await(second).value().orElseThrow());
            assertEquals(List.of("validate","commit","publish","import-validation"),order);
        }
    }
    @Test void shutdownCancelsQueuedWorkAndLetsRunningCommitFinishDurably()throws Exception{
        MutationLane lane=new MutationLane(3);CountDownLatch started=new CountDownLatch(1),continueCommit=new CountDownLatch(1);
        try{
            var running=lane.submit(()->{started.countDown();release(continueCommit);return Outcome.ok("durable");});release(started);
            var queued=lane.submit(()->{fail("Cancelled operation ran");return Outcome.ok("bad");});
            lane.close();assertEquals(Outcome.Status.CANCELLED,await(queued).status());assertFalse(running.toCompletableFuture().isDone());
            assertFalse(lane.awaitMutations(Duration.ZERO));assertFalse(lane.status().mutationsDrained());
            assertEquals(Outcome.Status.UNAVAILABLE,await(lane.submit(()->Outcome.ok("late"))).status());
            continueCommit.countDown();assertTrue(lane.awaitMutations(Duration.ofSeconds(1)));assertEquals("durable",await(running).value().orElseThrow());
        }finally{continueCommit.countDown();lane.close();}
    }
    @Test void failedWorkReleasesCapacityAndDoesNotLeakPayloads()throws Exception{
        try(MutationLane lane=new MutationLane(1)){
            var bad=await(lane.submit(()->{throw new IllegalArgumentException("private payload");}));
            assertEquals(Outcome.Status.FAILED,bad.status());assertFalse(bad.diagnostics().toString().contains("private payload"));
            eventually(()->lane.status().admitted()==0);
            assertEquals(Outcome.Status.OK,await(lane.submit(()->Outcome.ok("next"))).status());
        }
    }
    @Test void assertionFailureReleasesCapacityAndSettlesResult()throws Exception{
        try(MutationLane lane=new MutationLane(1)){
            assertEquals(Outcome.Status.FAILED,await(lane.submit(()->{throw new AssertionError("private assertion");})).status());
            eventually(()->lane.status().admitted()==0);
            assertEquals(Outcome.Status.OK,await(lane.submit(()->Outcome.ok("next"))).status());
        }
    }
    @Test void blockingContinuationCannotOccupyMutationWorkerAndKeepsItsAdmissionSlot()throws Exception{
        MutationLane lane=new MutationLane(2,Duration.ofMillis(20));
        CountDownLatch firstStarted=new CountDownLatch(1),publish=new CountDownLatch(1),callback=new CountDownLatch(1),unblock=new CountDownLatch(1);
        AtomicReference<String> mutationThread=new AtomicReference<>(),deliveryThread=new AtomicReference<>();
        try{
            var first=lane.submit(()->{mutationThread.set(Thread.currentThread().getName());firstStarted.countDown();release(publish);return Outcome.ok("first");});release(firstStarted);
            first.thenRun(()->{deliveryThread.set(Thread.currentThread().getName());callback.countDown();release(unblock);});publish.countDown();release(callback);
            var second=lane.submit(()->Outcome.ok("second durable commit"));assertEquals("second durable commit",await(second).value().orElseThrow());
            eventually(()->lane.status().admitted()==1&&lane.status().overBudgetDeliveries()==1);
            assertNotEquals(mutationThread.get(),deliveryThread.get());assertEquals(1,lane.status().activeDeliveries());
            unblock.countDown();eventually(()->lane.status().admitted()==0);assertTrue(lane.status().completedSlowDeliveries()>=1);
        }finally{publish.countDown();unblock.countDown();lane.close();}
    }
    @Test void twoBlockedCallbacksBoundDeliveryAndDoNotPreventAcceptedMutationDrain()throws Exception{
        MutationLane lane=new MutationLane(3,Duration.ofMillis(10));CountDownLatch unblock=new CountDownLatch(1);
        List<CountDownLatch> starts=new ArrayList<>(),publishes=new ArrayList<>(),callbacks=new ArrayList<>();
        try{
            for(int i=0;i<2;i++){
                var started=new CountDownLatch(1);var publish=new CountDownLatch(1);var callback=new CountDownLatch(1);starts.add(started);publishes.add(publish);callbacks.add(callback);
                var result=lane.submit(()->{started.countDown();release(publish);return Outcome.ok("blocked callback");});release(started);
                result.thenRun(()->{callback.countDown();release(unblock);});publish.countDown();release(callback);
            }
            var committed=new CountDownLatch(1);var third=lane.submit(()->{committed.countDown();return Outcome.ok("third durable");});release(committed);
            eventually(()->lane.status().queuedDeliveries()==1&&lane.status().overBudgetDeliveries()==2);
            assertEquals(3,lane.status().admitted());assertFalse(third.toCompletableFuture().isDone());
            assertEquals(Outcome.Status.BUSY,await(lane.submit(()->Outcome.ok("excess"))).status());
            lane.close();assertTrue(lane.awaitMutations(Duration.ofSeconds(1)));assertTrue(lane.status().mutationsDrained());await(lane.drained());
            assertEquals(2,lane.status().activeDeliveries());assertFalse(third.toCompletableFuture().isDone(),"Delivery is honestly delayed while both workers are occupied");
            unblock.countDown();assertEquals("third durable",await(third).value().orElseThrow());eventually(()->lane.status().admitted()==0);
        }finally{publishes.forEach(CountDownLatch::countDown);unblock.countDown();lane.close();}
    }
    @Test void blockedCanceledAcknowledgmentDoesNotBlockCloseOrRunningCommitDrain()throws Exception{
        MutationLane lane=new MutationLane(2);CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1),callback=new CountDownLatch(1),unblock=new CountDownLatch(1);
        try{
            var running=lane.submit(()->{started.countDown();release(finish);return Outcome.ok("durable");});release(started);
            var queued=lane.submit(()->{fail("Cancelled mutation ran");return Outcome.ok("bad");});queued.thenRun(()->{callback.countDown();release(unblock);});
            var closed=CompletableFuture.runAsync(lane::close);release(callback);await(closed);
            assertFalse(lane.awaitMutations(Duration.ZERO));finish.countDown();assertTrue(lane.awaitMutations(Duration.ofSeconds(1)));
            assertEquals("durable",await(running).value().orElseThrow());awaitActiveDeliveries(lane,1);
            unblock.countDown();assertEquals(Outcome.Status.CANCELLED,await(queued).status());
        }finally{finish.countDown();unblock.countDown();lane.close();}
    }
    @Test void drainContinuationCannotBlockMutationBarrierOrClose()throws Exception{
        MutationLane lane=new MutationLane(1,Duration.ofMillis(10));CountDownLatch callback=new CountDownLatch(1),unblock=new CountDownLatch(1);
        try{
            lane.drained().thenRun(()->{callback.countDown();release(unblock);});
            var closed=CompletableFuture.runAsync(lane::close);release(callback);await(closed);
            assertTrue(lane.awaitMutations(Duration.ZERO));assertTrue(lane.status().mutationsDrained());
            eventually(()->lane.status().drainContinuationOverBudget());assertTrue(lane.status().drainContinuationActive());
        }finally{unblock.countDown();lane.close();}
    }
    @Test void callerFutureCopyCannotForgeAcknowledgmentOrReleaseAdmission()throws Exception{
        MutationLane lane=new MutationLane(1);CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
        try{
            var result=lane.submit(()->{started.countDown();release(finish);return Outcome.ok("actual");});release(started);
            var copy=result.toCompletableFuture();assertTrue(copy.complete(Outcome.ok("forged local copy")));
            assertEquals(1,lane.status().admitted());assertEquals(Outcome.Status.BUSY,await(lane.submit(()->Outcome.ok("excess"))).status());
            assertFalse(result.toCompletableFuture().isDone());finish.countDown();assertEquals("actual",await(result).value().orElseThrow());
        }finally{finish.countDown();lane.close();}
    }
}
