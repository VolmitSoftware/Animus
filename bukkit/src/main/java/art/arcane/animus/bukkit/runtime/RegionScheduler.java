package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;

/**
 * Region scheduling as the NPC runtime needs it. Folia binds it to its region scheduler and ownership checks; tests
 * model it deterministically. A chunk is a scheduling address, not a permanent region identity: ownership is checked
 * again in every callback.
 */
public interface RegionScheduler {
    /** Cancels a repeating task. Safe from any thread and more than once. */
    @FunctionalInterface interface Task { void cancel(); }
    /** One tick of one region. Two ticks are the same only when both the region and its number match. */
    record Tick(Object region, long number) {}

    /** True when the calling thread currently owns every chunk of the rectangle. */
    boolean owns(String world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);
    /** Repeats once per tick, starting next tick, on whichever region owns the chunk; null when refused. */
    Task repeat(ChunkPosition anchor, Runnable tick);
    /** Runs once on whichever region owns the chunk; false when refused. */
    boolean execute(ChunkPosition anchor, Runnable work);
    /** The region tick running on this thread, or null away from a region thread. */
    Tick tick();
}
