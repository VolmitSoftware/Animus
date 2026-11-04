package art.arcane.animus.core.extension;

import art.arcane.animus.api.Outcome;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.extension.MutationLaneTest.*;

class EngineBridgeTest {
    @Test void executorAndPublicApiUseSameSerializationAndImmediateAdmissionBound()throws Exception{
        var order=Collections.synchronizedList(new ArrayList<String>());AtomicReference<Thread> worker=new AtomicReference<>(),cleanupThread=new AtomicReference<>();
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1),last=new CountDownLatch(1);
        MutationLane lane=new MutationLane(3,Duration.ofSeconds(1),()->{cleanupThread.set(Thread.currentThread());order.add("cleanup");},failure->fail(failure));
        Executor executor=lane.executor((task,event,failure)->fail("Unexpected internal event: "+event));
        try{
            executor.execute(()->{worker.set(Thread.currentThread());order.add("command-validate");started.countDown();release(finish);order.add("command-commit-publish");});release(started);
            var api=lane.submit(()->{order.add("api-validate-commit-publish");return Outcome.ok("api");});
            executor.execute(()->{order.add("second-command");last.countDown();});
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->fail("Rejected internal command ran")));
            assertEquals(Outcome.Status.BUSY,await(lane.submit(()->Outcome.ok("excess"))).status());
            finish.countDown();release(last);assertEquals(Outcome.Status.OK,await(api).status());lane.close();assertTrue(lane.awaitCleanup(Duration.ofSeconds(1)));
            assertEquals(List.of("command-validate","command-commit-publish","api-validate-commit-publish","second-command","cleanup"),order);
            assertSame(worker.get(),cleanupThread.get());assertNotSame(Thread.currentThread(),cleanupThread.get());
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->{}));
        }finally{finish.countDown();lane.close();}
    }
    @Test void queuedCancellationObserverSettlesPendingWrapperOnWorkerBeforeCleanup()throws Exception{
        var order=Collections.synchronizedList(new ArrayList<String>());CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);CompletableFuture<String> pending=new CompletableFuture<>();
        AtomicReference<Thread> observerThread=new AtomicReference<>();AtomicReference<Runnable> observed=new AtomicReference<>();
        MutationLane lane=new MutationLane(3,Duration.ofSeconds(1),()->order.add("cleanup"),failure->fail(failure));
        Executor executor=lane.executor((task,event,failure)->{assertEquals(MutationLane.EngineTaskEvent.CANCELLED,event);assertNull(failure);observed.set(task);observerThread.set(Thread.currentThread());order.add("cancel-notice");pending.completeExceptionally(new CancellationException("closed"));});
        Runnable queued=()->{fail("Cancelled task ran");pending.complete("bad");};
        try{
            executor.execute(()->{started.countDown();release(finish);order.add("running-finished");});release(started);executor.execute(queued);
            lane.close();assertEquals(1,lane.status().queuedEngineNotifications());assertFalse(pending.isDone());
            assertFalse(lane.awaitCleanup(Duration.ZERO));finish.countDown();assertTrue(lane.awaitCleanup(Duration.ofSeconds(1)));
            assertTrue(pending.isCancelled());assertSame(queued,observed.get());assertNotSame(Thread.currentThread(),observerThread.get());
            assertEquals(List.of("running-finished","cancel-notice","cleanup"),order);assertEquals(1,lane.status().cancelledEngineTasks());
        }finally{finish.countDown();lane.close();}
    }
    @Test void acceptedFailureAndObserverFailureRemainVisibleWithoutKillingWorkerOrCleanup()throws Exception{
        AssertionError original=new AssertionError("synthetic internal failure");AtomicReference<Throwable> reported=new AtomicReference<>();CountDownLatch next=new CountDownLatch(1);
        MutationLane lane=new MutationLane(3);Executor executor=lane.executor((task,event,failure)->{assertEquals(MutationLane.EngineTaskEvent.FAILED,event);reported.set(failure);throw new AssertionError("observer failure");});
        try{
            executor.execute(()->{throw original;});executor.execute(next::countDown);release(next);
            lane.close();assertTrue(lane.awaitCleanup(Duration.ofSeconds(1)));
            assertSame(original,reported.get());assertEquals(1,lane.status().failedEngineTasks());assertEquals(1,lane.status().observerFailures());
            assertEquals(MutationLane.CleanupState.SUCCEEDED,lane.status().cleanupState());assertTrue(lane.status().lastEngineFailure().startsWith("observer:"));
        }finally{lane.close();}
    }
    @Test void cleanupFailureHasAuthoritativeFailedStateAndExplicitFailureObserver()throws Exception{
        RuntimeException original=new IllegalStateException("cleanup failed");AtomicReference<Throwable> reported=new AtomicReference<>();AtomicInteger attempts=new AtomicInteger();
        MutationLane lane=new MutationLane(1,Duration.ofSeconds(1),()->{attempts.incrementAndGet();throw original;},reported::set);
        lane.close();lane.close();assertTrue(lane.awaitCleanup(Duration.ofSeconds(1)));
        assertSame(original,reported.get());assertEquals(1,attempts.get());assertEquals(MutationLane.CleanupState.FAILED,lane.status().cleanupState());
    }
    @Test void cleanupIsQueuedExactlyOnceAfterMutationAndHasAnIndependentCompletionGate()throws Exception{
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1),cleanupStarted=new CountDownLatch(1),cleanupFinish=new CountDownLatch(1);AtomicInteger attempts=new AtomicInteger();
        MutationLane lane=new MutationLane(1,Duration.ofSeconds(1),()->{attempts.incrementAndGet();cleanupStarted.countDown();release(cleanupFinish);},failure->fail(failure));
        try{
            var mutation=lane.submit(()->{started.countDown();release(finish);return Outcome.ok("durable");});release(started);
            List<CompletableFuture<Void>> closes=new ArrayList<>();for(int i=0;i<16;i++)closes.add(CompletableFuture.runAsync(lane::close));for(var close:closes)await(close);
            assertFalse(lane.awaitMutations(Duration.ZERO));assertEquals(0,attempts.get());finish.countDown();release(cleanupStarted);
            assertTrue(lane.awaitMutations(Duration.ZERO));assertFalse(lane.awaitCleanup(Duration.ZERO));assertEquals(MutationLane.CleanupState.RUNNING,lane.status().cleanupState());
            assertEquals("durable",await(mutation).value().orElseThrow());cleanupFinish.countDown();assertTrue(lane.awaitCleanup(Duration.ofSeconds(1)));assertEquals(1,attempts.get());
        }finally{finish.countDown();cleanupFinish.countDown();lane.close();}
    }
    @Test void blockedPublicDeliveryAndDrainContinuationsCannotPostponeEngineCleanup()throws Exception{
        CountDownLatch unblock=new CountDownLatch(1),drainCallback=new CountDownLatch(1);List<CountDownLatch> releases=new ArrayList<>();AtomicInteger cleanups=new AtomicInteger();
        MutationLane lane=new MutationLane(3,Duration.ofMillis(10),cleanups::incrementAndGet,failure->fail(failure));
        try{
            for(int i=0;i<2;i++){
                var started=new CountDownLatch(1);var publish=new CountDownLatch(1);var callback=new CountDownLatch(1);releases.add(publish);
                var result=lane.submit(()->{started.countDown();release(publish);return Outcome.ok("durable");});release(started);
                result.thenRun(()->{callback.countDown();release(unblock);});publish.countDown();release(callback);
            }
            lane.drained().thenRun(()->{drainCallback.countDown();release(unblock);});
            var close=CompletableFuture.runAsync(lane::close);await(close);release(drainCallback);
            assertTrue(lane.awaitCleanup(Duration.ofSeconds(1)));assertEquals(1,cleanups.get());assertEquals(2,lane.status().activeDeliveries());assertTrue(lane.status().drainContinuationActive());
            assertFalse(lane.status().quiescent());assertFalse(lane.awaitQuiescence(Duration.ZERO));
            unblock.countDown();assertTrue(lane.awaitQuiescence(Duration.ofSeconds(1)));assertTrue(lane.status().quiescent());
        }finally{releases.forEach(CountDownLatch::countDown);unblock.countDown();lane.close();}
    }
}
