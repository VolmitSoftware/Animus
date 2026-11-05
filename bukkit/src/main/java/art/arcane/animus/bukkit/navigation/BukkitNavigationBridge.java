package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.CaptureBounds;
import art.arcane.animus.core.navigation.ChunkOwnership;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.NavigationLimits;
import art.arcane.animus.core.navigation.NavigationPlan;
import art.arcane.animus.core.navigation.NavigationWorkQueue;
import art.arcane.animus.core.navigation.PathPlanner;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.Objects;
import org.bukkit.World;

/**
 * Folia navigation: no shared pump. Each NPC's region owner captures only its own job and reads only chunks it owns,
 * then planning runs on the bounded worker. Single-owner servers use {@link art.arcane.animus.core.navigation.OwnerNavigation}.
 */
public final class BukkitNavigationBridge implements AutoCloseable {
    private final NavigationWorkQueue work;
    private volatile boolean closed;

    private BukkitNavigationBridge(NavigationLimits limits) { work = new NavigationWorkQueue(Objects.requireNonNull(limits, "limits")); }
    public static BukkitNavigationBridge regionOwned(NavigationLimits limits) { return new BukkitNavigationBridge(limits); }

    /**
     * Region owner only: advances this plan's capture by at most {@code budget} units. Every voxel and chunk check
     * first asks {@code owned}; an unowned or unloaded chunk is captured as unavailable, never read.
     */
    public int capture(NavigationPlan plan, World world, ChunkOwnership owned, long tick, int budget) {
        if (closed) throw new IllegalStateException("Navigation bridge is closed");
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(owned, "owned");
        String key = world.getUID().toString();
        return work.pump(plan, new NavigationWorkQueue.Sampler() {
            @Override public boolean worldAvailable(String id) { return key.equals(id); }
            @Override public boolean chunkAvailable(String id, int x, int z) {
                return key.equals(id) && owned.owns(x, z) && world.isChunkLoaded(x, z);
            }
            @Override public VoxelSnapshot.Cell sample(String id, int x, int y, int z) {
                int chunkX = Math.floorDiv(x, 16), chunkZ = Math.floorDiv(z, 16);
                if (!key.equals(id) || y < world.getMinHeight() || y >= world.getMaxHeight()
                        || !owned.owns(chunkX, chunkZ) || !world.isChunkLoaded(chunkX, chunkZ)) return VoxelSnapshot.Cell.UNAVAILABLE;
                return BlockCells.of(world.getBlockAt(x, y, z));
            }
        }, tick, budget);
    }

    /** May be requested from any thread; completion is not a permission to perform off-thread Bukkit work. */
    public NavigationPlan plan(String world, CaptureBounds bounds, BlockPos start, BlockPos goal,
                               MovementProfile profile, PathPlanner.Limits searchLimits) {
        if (closed) throw new IllegalStateException("Navigation bridge is closed");
        return work.plan(world, bounds, start, goal, profile, searchLimits);
    }
    public int outstandingJobs() { return work.outstandingJobs(); }
    /** New limits for later plans; plans in progress continue. Any thread. */
    public void limits(NavigationLimits next) { work.limits(next); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        work.close();
    }
}
