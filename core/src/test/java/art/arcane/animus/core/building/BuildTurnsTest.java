package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** One region turn per build at a time, handed back once, and never run after the service reclaimed it. */
@Timeout(10)
class BuildTurnsTest {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(5);

    /** Region tasks wait in a queue until the test runs them; chunks marked absent are not known to be loaded. */
    private static final class Regions implements BuildService.Regions {
        final Deque<Runnable> queued = new ArrayDeque<>();
        final List<String> chunks = new ArrayList<>();
        final Set<String> absent = new HashSet<>();
        boolean refuse;
        @Override public boolean run(String world, int chunkX, int chunkZ, Runnable work) {
            if (refuse) return false;
            chunks.add(chunkX + "," + chunkZ);
            queued.add(work);
            return true;
        }
        @Override public String unreachable(String world, int chunkX, int chunkZ) {
            return absent.contains(chunkX + "," + chunkZ) ? "chunk " + chunkX + " " + chunkZ + " is not loaded" : null;
        }
        void runAll() { for (Runnable task; (task = queued.poll()) != null; ) task.run(); }
    }

    private final Regions regions = new Regions();
    private final BuildTurns turns = new BuildTurns(regions, TIMEOUT);

    @Test void aTurnGoesToTheRegionOfItsChunkAndComesBackOnce() {
        AtomicInteger ran = new AtomicInteger();
        assertNull(turns.send("tower", "world", new BlockPos(-17, 64, 40), 0, ran::incrementAndGet));
        assertEquals(List.of("-2,2"), regions.chunks);
        assertTrue(turns.out("tower"));
        assertTrue(turns.collect(1).isEmpty(), "the build is still out until its region runs the turn");
        regions.runAll();
        assertEquals(1, ran.get());
        assertEquals(List.of("tower"), turns.collect(2));
        assertFalse(turns.out("tower"));
        assertTrue(turns.collect(3).isEmpty(), "a turn comes back only once");
    }

    @Test void aBuildOutOnATurnIsNotSentAgain() {
        assertNull(turns.send("tower", "world", new BlockPos(0, 64, 0), 0, () -> {}));
        assertNotNull(turns.send("tower", "world", new BlockPos(40, 64, 0), 0, () -> {}));
        assertEquals(1, regions.queued.size());
        assertNull(turns.send("wall", "world", new BlockPos(40, 64, 0), 0, () -> {}), "another build goes out on its own");
    }

    @Test void anUnreachableOrRefusingRegionKeepsTheBuildAtHome() {
        regions.absent.add("3,0");
        String why = turns.send("tower", "world", new BlockPos(50, 64, 1), 0, () -> {});
        assertTrue(why.contains("not loaded"), why);
        assertFalse(turns.out("tower"));
        assertTrue(regions.queued.isEmpty(), "nothing is scheduled for a chunk not known to be loaded");
        regions.refuse = true;
        assertTrue(turns.send("tower", "world", new BlockPos(0, 64, 0), 0, () -> {}).contains("refused"));
        assertFalse(turns.out("tower"));
    }

    @Test void aTurnNoRegionStartedInTimeIsReclaimedAndNeverRunsLater() {
        AtomicInteger ran = new AtomicInteger();
        turns.send("tower", "world", new BlockPos(0, 64, 0), 0, ran::incrementAndGet);
        assertTrue(turns.collect(TIMEOUT - 1).isEmpty());
        assertEquals(List.of("tower"), turns.collect(TIMEOUT));
        assertEquals(1, turns.lost());
        assertNull(turns.send("tower", "world", new BlockPos(0, 64, 0), TIMEOUT, ran::incrementAndGet), "the reclaimed build can go out again");
        regions.runAll();
        assertEquals(1, ran.get(), "the late first task ran nothing; only the new turn ran");
        assertEquals(List.of("tower"), turns.collect(TIMEOUT + 1));
    }

    @Test void aRunningTurnIsNeverReclaimed() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        turns.send("tower", "world", new BlockPos(0, 64, 0), 0, () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        Thread region = new Thread(regions::runAll);
        region.start();
        started.await();
        assertTrue(turns.collect(TIMEOUT * 10).isEmpty(), "a turn already running belongs to its region until it ends");
        assertTrue(turns.out("tower"));
        release.countDown();
        region.join();
        assertEquals(List.of("tower"), turns.collect(TIMEOUT * 10));
    }

    @Test void shutdownReclaimsUnstartedTurnsAndReportsRunningOnes() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger late = new AtomicInteger();
        turns.send("busy", "world", new BlockPos(0, 64, 0), 0, () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        Runnable busy = regions.queued.poll();
        turns.send("idle", "world", new BlockPos(0, 64, 0), 0, late::incrementAndGet);
        Thread region = new Thread(busy);
        region.start();
        started.await();
        assertEquals(List.of("busy"), turns.close(TimeUnit.MILLISECONDS.toNanos(50)));
        regions.runAll();
        assertEquals(0, late.get(), "a turn reclaimed at shutdown never runs");
        release.countDown();
        region.join();
    }
}
