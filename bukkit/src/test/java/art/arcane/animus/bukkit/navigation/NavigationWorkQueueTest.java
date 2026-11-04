package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationWorkQueueTest {
    private static final CaptureBounds BOUNDS = new CaptureBounds(0, 0, 0, 4, 4, 4);
    private static final BlockPos START = new BlockPos(1, 1, 1), GOAL = new BlockPos(2, 1, 2);
    private static final PathPlanner.Limits SEARCH = new PathPlanner.Limits(256, 256, 64);
    private static final MovementProfile WALKER = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 2, 0, 1, 3, true);

    @Test void captureBudgetIsGlobalAndRoundRobinWhileWorkerReceivesOnlySnapshot() throws Exception {
        var worldA = UUID.randomUUID(); var worldB = UUID.randomUUID();
        var sampler = new Sampler();
        AtomicLong nanos = new AtomicLong(100);
        try (var queue = new NavigationWorkQueue(new NavigationLimits(8, 2, 8192), nanos::incrementAndGet)) {
            NavigationPlan first = submit(queue, worldA), second = submit(queue, worldB);
            assertThrows(RejectedExecutionException.class, () -> submit(queue, UUID.randomUUID()));
            assertEquals(8, queue.pump(sampler, 1));
            assertEquals(3, sampler.reads.get(worldA));
            assertEquals(3, sampler.reads.get(worldB));
            for (int tick = 2; tick <= 20; tick++) {
                int before = sampler.totalReads;
                assertTrue(queue.pump(sampler, tick) <= 8);
                assertTrue(sampler.totalReads - before <= 8);
            }
            assertEquals(PathPlanner.Status.FOUND, await(first).status());
            assertEquals(PathPlanner.Status.FOUND, await(second).status());
            CaptureProvenance capture = first.capture().orElseThrow();
            assertEquals(worldA, capture.world()); assertEquals(BOUNDS, capture.bounds());
            assertEquals(1, capture.firstTick()); assertTrue(capture.lastTick() > 1);
            assertTrue(capture.lastNanos() > capture.firstNanos());
            assertEquals(64, capture.sampledVoxels()); assertEquals(0, capture.unavailableVoxels());
            assertEquals(64, sampler.reads.get(worldA)); assertEquals(64, sampler.reads.get(worldB));
            assertFalse(first.cancel());
        }
    }

    @Test void cancellationBeforeCaptureReleasesAdmissionAndDoesNotReadWorld() throws Exception {
        try (var queue = new NavigationWorkQueue(new NavigationLimits(256, 1, 8192))) {
            NavigationPlan plan = submit(queue, UUID.randomUUID());
            assertTrue(plan.cancel()); assertFalse(plan.cancel());
            assertEquals(PathPlanner.Status.CANCELLED, await(plan).status());
            assertEquals(0, queue.outstandingJobs());
            var sampler = new Sampler(); assertEquals(0, queue.pump(sampler, 1));
            assertEquals(0, sampler.totalCalls);
            submit(queue, UUID.randomUUID());
        }
    }

    @Test void unavailableWorldCompletesWithoutSamplingAndReleasesAdmission() throws Exception {
        try (var queue = new NavigationWorkQueue(NavigationLimits.DEFAULT)) {
            var sampler = new Sampler(); sampler.worldPresent = false;
            NavigationPlan plan = submit(queue, UUID.randomUUID());
            assertEquals(1, queue.pump(sampler, 1));
            assertEquals(PathPlanner.Status.UNAVAILABLE_TERRAIN, await(plan).status());
            assertEquals(0, sampler.totalReads); assertEquals(0, queue.outstandingJobs());
            assertTrue(plan.capture().isEmpty());
        }
    }

    @Test void unavailableChunksAreNeverSampledOrTreatedAsAir() throws Exception {
        var world = UUID.randomUUID(); var sampler = new Sampler(); sampler.unloadedChunks.add(1);
        try (var queue = new NavigationWorkQueue(NavigationLimits.DEFAULT)) {
            NavigationPlan plan = queue.plan(world, new CaptureBounds(14, 0, 0, 4, 4, 4),
                    new BlockPos(15, 1, 1), new BlockPos(17, 1, 1), WALKER, SEARCH);
            queue.pump(sampler, 1);
            assertEquals(PathPlanner.Status.UNAVAILABLE_TERRAIN, await(plan).status());
            assertEquals(32, sampler.totalReads);
            CaptureProvenance capture = plan.capture().orElseThrow();
            assertEquals(32, capture.sampledVoxels()); assertEquals(32, capture.unavailableVoxels());
        }
    }

    @Test void finalChunkCheckInvalidatesPreviouslyCapturedCellsWhenChunkUnloads() throws Exception {
        var sampler = new Sampler();
        try (var queue = new NavigationWorkQueue(new NavigationLimits(1, 1, 8192))) {
            NavigationPlan plan = submit(queue, UUID.randomUUID());
            for (int tick = 1; tick <= 65; tick++) assertEquals(1, queue.pump(sampler, tick));
            assertEquals(64, sampler.totalReads);
            assertFalse(plan.future().toCompletableFuture().isDone());
            sampler.unloadedChunks.add(0);
            assertEquals(1, queue.pump(sampler, 66));
            assertEquals(PathPlanner.Status.UNAVAILABLE_TERRAIN, await(plan).status());
            assertEquals(64, sampler.totalReads);
        }
    }

    @Test void chunkMissingDuringCaptureRemainsUnknownAfterItReloads() throws Exception {
        var sampler = new Sampler();
        try (var queue = new NavigationWorkQueue(new NavigationLimits(1, 1, 8192))) {
            NavigationPlan plan = submit(queue, UUID.randomUUID());
            queue.pump(sampler, 1);
            sampler.unloadedChunks.add(0); queue.pump(sampler, 2);
            sampler.unloadedChunks.clear();
            for (int tick = 3; tick <= 66; tick++) queue.pump(sampler, tick);
            assertEquals(PathPlanner.Status.UNAVAILABLE_TERRAIN, await(plan).status());
            assertEquals(63, sampler.totalReads);
            assertEquals(1, plan.capture().orElseThrow().unavailableVoxels());
        }
    }

    @Test void failedCaptureIsReportedAndDoesNotStrandTheQueue() throws Exception {
        var sampler = new Sampler(); sampler.sampleFailure = new IllegalStateException("fixture failure");
        try (var queue = new NavigationWorkQueue(new NavigationLimits(256, 1, 8192))) {
            NavigationPlan failed = submit(queue, UUID.randomUUID());
            queue.pump(sampler, 1);
            ExecutionException exception = assertThrows(ExecutionException.class, () -> await(failed));
            assertEquals(sampler.sampleFailure, exception.getCause());
            assertEquals(0, queue.outstandingJobs());
            sampler.sampleFailure = null;
            NavigationPlan retry = submit(queue, UUID.randomUUID()); queue.pump(sampler, 2);
            assertEquals(PathPlanner.Status.FOUND, await(retry).status());
        }
    }

    @Test void runningAndQueuedPlanningRemainBoundedAndBothCancelOnClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
        Thread owner = Thread.currentThread();
        var queue = new NavigationWorkQueue(new NavigationLimits(256, 2, 8192), System::nanoTime,
                (snapshot, start, goal, profile, limits, cancelled) -> {
                    assertNotEquals(owner, Thread.currentThread()); calls.incrementAndGet(); entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException expected) { interrupted.set(true); Thread.currentThread().interrupt(); }
                    assertTrue(cancelled.getAsBoolean());
                    return new PathPlanner.Result(PathPlanner.Status.CANCELLED, List.of(), 0, 0, false);
                });
        try {
            NavigationPlan first = submit(queue, UUID.randomUUID());
            NavigationPlan second = submit(queue, UUID.randomUUID());
            queue.pump(new Sampler(), 1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(2, queue.outstandingJobs());
            assertThrows(RejectedExecutionException.class, () -> submit(queue, UUID.randomUUID()));
            queue.close();
            assertEquals(PathPlanner.Status.CANCELLED, await(first).status());
            assertEquals(PathPlanner.Status.CANCELLED, await(second).status());
            assertTrue(queue.awaitTermination(2, TimeUnit.SECONDS)); assertTrue(interrupted.get());
            assertEquals(1, calls.get()); assertEquals(0, queue.outstandingJobs());
            assertEquals(0, queue.pump(new Sampler(), 2));
            assertThrows(IllegalStateException.class, () -> submit(queue, UUID.randomUUID()));
        } finally { release.countDown(); queue.close(); }
    }

    @Test void cancellingQueuedPlanRemovesItBeforeWorkerExecutionAndRestoresCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var queue = new NavigationWorkQueue(new NavigationLimits(256, 2, 8192), System::nanoTime,
                (snapshot, start, goal, profile, limits, cancelled) -> {
                    calls.incrementAndGet(); entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                    return new PathPlanner().plan(snapshot, start, goal, profile, limits, cancelled);
                });
        try {
            NavigationPlan first = submit(queue, UUID.randomUUID());
            NavigationPlan second = submit(queue, UUID.randomUUID());
            queue.pump(new Sampler(), 1); assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(second.cancel()); assertEquals(PathPlanner.Status.CANCELLED, await(second).status());
            assertEquals(1, queue.outstandingJobs());
            NavigationPlan replacement = submit(queue, UUID.randomUUID());
            release.countDown();
            assertEquals(PathPlanner.Status.FOUND, await(first).status());
            assertEquals(1, calls.get());
            queue.close(); assertEquals(PathPlanner.Status.CANCELLED, await(replacement).status());
            assertTrue(queue.awaitTermination(2, TimeUnit.SECONDS));
        } finally { release.countDown(); queue.close(); }
    }

    @Test void ownerPumpCapturesOnlyItsOwnJobWithinItsBudget() throws Exception {
        UUID mine = UUID.randomUUID(), theirs = UUID.randomUUID();
        try (var queue = new NavigationWorkQueue(new NavigationLimits(256, 2, 8192))) {
            NavigationPlan own = submit(queue, mine), other = submit(queue, theirs);
            var sampler = new Sampler();
            assertEquals(5, queue.pump(own, sampler, 1, 5));
            assertEquals(4, sampler.reads.get(mine), "one start step, then four voxels");
            assertNull(sampler.reads.get(theirs), "another owner's capture is never read here");
            for (int tick = 2; tick < 40 && !own.done(); tick++) queue.pump(own, sampler, tick, 8);
            assertEquals(PathPlanner.Status.FOUND, await(own).status());
            assertNull(sampler.reads.get(theirs));
            assertEquals(0, queue.pump(own, sampler, 50, 8), "a finished job has nothing left to capture");
            assertEquals(1, queue.outstandingJobs());
            assertTrue(other.cancel());
        }
    }

    @Test void admissionChecksRejectOversizeCaptureBeforeAllocatingAJob() {
        try (var queue = new NavigationWorkQueue(new NavigationLimits(256, 8, 32))) {
            assertThrows(IllegalArgumentException.class, () -> submit(queue, UUID.randomUUID()));
            assertEquals(0, queue.outstandingJobs());
        }
    }

    private static NavigationPlan submit(NavigationWorkQueue queue, UUID world) {
        return queue.plan(world, BOUNDS, START, GOAL, WALKER, SEARCH);
    }
    private static PathPlanner.Result await(NavigationPlan plan) throws Exception {
        return plan.future().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
    private static final class Sampler implements NavigationWorkQueue.Sampler {
        final Thread owner = Thread.currentThread();
        final Set<Integer> unloadedChunks = new HashSet<>();
        final Map<UUID, Integer> reads = new HashMap<>();
        int totalReads, totalCalls;
        boolean worldPresent = true;
        RuntimeException sampleFailure;
        private void owningThread() { assertEquals(owner, Thread.currentThread()); totalCalls++; }
        @Override public boolean worldAvailable(UUID world) { owningThread(); return worldPresent; }
        @Override public boolean chunkAvailable(UUID world, int chunkX, int chunkZ) {
            owningThread(); return worldPresent && !unloadedChunks.contains(chunkX);
        }
        @Override public VoxelSnapshot.Cell sample(UUID world, int x, int y, int z) {
            owningThread(); assertTrue(worldPresent); assertFalse(unloadedChunks.contains(Math.floorDiv(x, 16)));
            if (sampleFailure != null) throw sampleFailure;
            totalReads++; reads.merge(world, 1, Integer::sum);
            return y == 0 ? VoxelSnapshot.Cell.SOLID : VoxelSnapshot.Cell.AIR;
        }
    }
}
