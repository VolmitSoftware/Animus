package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic model of Folia region ownership. A chunk belongs to at most one region; an unassigned chunk is not
 * loaded. Location tasks follow their chunk when it is reassigned, as Folia moves them on a split or merge. Each
 * region ticks on whichever thread calls {@link #tick(int)}; one region never ticks on two threads at once.
 */
final class FakeRegions implements RegionScheduler {
    private record Once(ChunkPosition anchor, Runnable work) {}
    private static final class Repeat implements Task {
        final ChunkPosition anchor; final Runnable work; final AtomicBoolean cancelled = new AtomicBoolean();
        Repeat(ChunkPosition anchor, Runnable work) { this.anchor = anchor; this.work = work; }
        @Override public void cancel() { cancelled.set(true); }
    }
    private static final class Region {
        final Object identity = new Object();
        final AtomicLong ticks = new AtomicLong();
        final Object lock = new Object();
    }
    private final Map<ChunkPosition, Integer> owners = new ConcurrentHashMap<>();
    private final Map<Integer, Region> regions = new ConcurrentHashMap<>();
    private final Queue<Once> once = new ConcurrentLinkedQueue<>();
    private final List<Repeat> repeating = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Integer> current = new ThreadLocal<>();
    volatile boolean refuseRepeats, refuseExecutes;

    /** Assigns a loaded chunk to a region, or moves it there (a split or merge). */
    FakeRegions assign(int region, ChunkPosition... chunks) {
        regions.computeIfAbsent(region, ignored -> new Region());
        for (ChunkPosition chunk : chunks) owners.put(chunk, region);
        return this;
    }
    /** The chunk unloads: it no longer belongs to any region. */
    void unload(ChunkPosition chunk) { owners.remove(chunk); }

    /** Runs one tick of the region on the calling thread: queued one-shot tasks first, then repeating tasks. */
    void tick(int region) {
        Region data = regions.computeIfAbsent(region, ignored -> new Region());
        synchronized (data.lock) {
            current.set(region);
            try {
                data.ticks.incrementAndGet();
                // Repeating work admitted during this tick first runs on the next one, like a one-tick initial delay.
                List<Repeat> repeats = List.copyOf(repeating);
                List<Once> mine = new ArrayList<>();
                for (Once task : once) if (Integer.valueOf(region).equals(owners.get(task.anchor())) && once.remove(task)) mine.add(task);
                for (Once task : mine) task.work().run();
                for (Repeat task : repeats) {
                    if (task.cancelled.get()) { repeating.remove(task); continue; }
                    if (Integer.valueOf(region).equals(owners.get(task.anchor))) task.work.run();
                }
            } finally { current.remove(); }
        }
    }
    int pendingOnce() { return once.size(); }
    int liveRepeats() { int live = 0; for (Repeat task : repeating) if (!task.cancelled.get()) live++; return live; }

    @Override public boolean owns(String world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        Integer region = current.get();
        if (region == null) return false;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++)
            if (!region.equals(owners.get(new ChunkPosition(world, x, z)))) return false;
        return true;
    }
    @Override public Task repeat(ChunkPosition anchor, Runnable tick) {
        if (refuseRepeats) return null;
        Repeat task = new Repeat(anchor, tick); repeating.add(task); return task;
    }
    @Override public boolean execute(ChunkPosition anchor, Runnable work) {
        if (refuseExecutes) return false;
        once.add(new Once(anchor, work)); return true;
    }
    @Override public Tick tick() {
        Integer region = current.get();
        if (region == null) return null;
        Region data = regions.get(region);
        return new Tick(data.identity, data.ticks.get());
    }
    Integer currentRegion() { return current.get(); }
}
