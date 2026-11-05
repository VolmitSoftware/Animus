package art.arcane.animus.core.navigation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Owner-thread capture followed by bounded asynchronous planning, for a platform with one owner thread. One pump per
 * owner tick captures every job; the worker receives only immutable snapshots.
 */
public final class OwnerNavigation implements AutoCloseable {
    private final TerrainReader terrain;
    private final NavigationWorkQueue work;
    private final AutoCloseable pump;
    private long tick;
    private volatile boolean closed;

    /** Construct on the owner thread. {@code ticks} runs its argument once per owner tick until the handle closes. */
    public OwnerNavigation(TerrainReader terrain, Function<Runnable, AutoCloseable> ticks, NavigationLimits limits) {
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        work = new NavigationWorkQueue(Objects.requireNonNull(limits, "limits"));
        try { pump = Objects.requireNonNull(ticks, "ticks").apply(this::captureTick); }
        catch (RuntimeException failure) { work.close(); throw failure; }
    }

    /** May be requested from any thread; completion is not a permission to perform off-thread world work. */
    public NavigationPlan plan(String world, CaptureBounds bounds, BlockPos start, BlockPos goal,
                               MovementProfile profile, PathPlanner.Limits searchLimits) {
        if (closed) throw new IllegalStateException("Navigation bridge is closed");
        return work.plan(world, bounds, start, goal, profile, searchLimits);
    }
    public int outstandingJobs() { return work.outstandingJobs(); }

    private void captureTick() {
        if (closed) return;
        // This cache lives for one owner pump only and is never passed to the worker.
        Map<String, MotionValidator.Sampler> samplers = new HashMap<>();
        work.pump(new NavigationWorkQueue.Sampler() {
            @Override public boolean worldAvailable(String world) { return terrain.worldLoaded(world); }
            @Override public boolean chunkAvailable(String world, int x, int z) { return terrain.worldLoaded(world) && terrain.chunkLoaded(world, x, z); }
            @Override public VoxelSnapshot.Cell sample(String world, int x, int y, int z) {
                if (!terrain.worldLoaded(world)) return VoxelSnapshot.Cell.UNAVAILABLE;
                return samplers.computeIfAbsent(world, terrain::collision).sample(x, y, z);
            }
        }, ++tick);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { pump.close(); }
        catch (Exception ignored) { /* The pump checks closed before every capture. */ }
        finally { work.close(); }
    }
}
