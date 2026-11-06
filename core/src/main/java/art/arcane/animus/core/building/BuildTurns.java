package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Hands one build at a time to the region that owns where it works, and takes it back. A build is either at home with
 * the service or out on exactly one region turn; while it is out, nothing else reads or changes it. The turn's own
 * compare-and-set decides between the region starting it and the service reclaiming it after a timeout, so a late
 * region task can never run a build the service already took back. Control-plane object: every method except the
 * submitted work runs on the service's owner.
 */
final class BuildTurns {
    private static final int SENT = 0, RUNNING = 1, DONE = 2, LOST = 3;

    private static final class Turn {
        final AtomicInteger phase = new AtomicInteger(SENT);
        final long sent;
        Turn(long sent) { this.sent = sent; }
    }

    private final BuildService.Regions regions;
    private final long timeoutNanos;
    private final Map<String, Turn> out = new HashMap<>();
    private final ConcurrentLinkedQueue<String> back = new ConcurrentLinkedQueue<>();
    private long sent, lost;

    BuildTurns(BuildService.Regions regions, long timeoutNanos) {
        this.regions = Objects.requireNonNull(regions);
        if (timeoutNanos <= 0) throw new IllegalArgumentException("A turn timeout must be positive");
        this.timeoutNanos = timeoutNanos;
    }

    boolean out(String id) { return out.containsKey(id); }
    int outstanding() { return out.size(); }
    long sent() { return sent; }
    long lost() { return lost; }

    /**
     * Sends the build's work to the region that owns the block's chunk. Null when it went; otherwise why it could not:
     * the build is already out, or the region cannot take it now.
     */
    String send(String id, String world, BlockPos at, long now, Runnable work) {
        if (out.containsKey(id)) return "the build's previous turn has not come back yet";
        int chunkX = Math.floorDiv(at.x(), 16), chunkZ = Math.floorDiv(at.z(), 16);
        String unreachable = regions.unreachable(world, chunkX, chunkZ);
        if (unreachable != null) return unreachable;
        Turn turn = new Turn(now);
        out.put(id, turn);
        boolean accepted;
        try {
            accepted = regions.run(world, chunkX, chunkZ, () -> {
                if (!turn.phase.compareAndSet(SENT, RUNNING)) return;
                try { work.run(); }
                finally { turn.phase.set(DONE); back.add(id); }
            });
        } catch (RuntimeException refused) { accepted = false; }
        if (!accepted) { out.remove(id, turn); return "the region that owns chunk " + chunkX + " " + chunkZ + " refused the build's turn"; }
        sent++;
        return null;
    }

    /** Takes back every finished turn, and reclaims turns no region started in time. Returns the builds now at home again. */
    List<String> collect(long now) {
        List<String> home = new ArrayList<>();
        for (String id; (id = back.poll()) != null; ) {
            Turn turn = out.get(id);
            if (turn != null && turn.phase.get() == DONE) { out.remove(id); home.add(id); }
        }
        for (Iterator<Map.Entry<String, Turn>> pending = out.entrySet().iterator(); pending.hasNext(); ) {
            Map.Entry<String, Turn> entry = pending.next();
            Turn turn = entry.getValue();
            if (now - turn.sent >= timeoutNanos && turn.phase.compareAndSet(SENT, LOST)) {
                pending.remove();
                lost++;
                home.add(entry.getKey());
            }
        }
        return home;
    }

    /**
     * Shutdown: reclaims every turn no region has started and waits up to {@code waitNanos} for running ones. Returns the
     * builds still out, which the caller must leave alone.
     */
    List<String> close(long waitNanos) {
        long deadline = System.nanoTime() + waitNanos;
        for (Turn turn : out.values()) turn.phase.compareAndSet(SENT, LOST);
        while (System.nanoTime() < deadline && out.values().stream().anyMatch((Turn turn) -> turn.phase.get() == RUNNING)) LockSupport.parkNanos(1_000_000);
        List<String> running = new ArrayList<>();
        for (Map.Entry<String, Turn> entry : out.entrySet()) if (entry.getValue().phase.get() == RUNNING) running.add(entry.getKey());
        out.clear();
        back.clear();
        return running;
    }
}
