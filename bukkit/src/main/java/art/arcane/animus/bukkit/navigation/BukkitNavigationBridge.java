package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread capture followed by bounded asynchronous planning. Folia is explicitly unsupported until region capture exists. */
public final class BukkitNavigationBridge implements AutoCloseable {
    private final JavaPlugin plugin;
    private final NavigationWorkQueue work;
    private final BukkitTask pump;
    private long tick;
    private volatile boolean closed;

    /** Construct on the Bukkit main thread. No NPC entities or world-loading requests are created. */
    public BukkitNavigationBridge(JavaPlugin plugin, NavigationLimits limits) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(limits, "limits");
        if (isFolia(plugin)) throw new UnsupportedOperationException("Navigation snapshot capture is unavailable on Folia until region-aware capture is implemented");
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Construct navigation bridge on the Bukkit owner thread");
        work = new NavigationWorkQueue(limits);
        try { pump = plugin.getServer().getScheduler().runTaskTimer(plugin, this::captureTick, 1, 1); }
        catch (RuntimeException failure) { work.close(); throw failure; }
    }

    /** May be requested from any thread; completion is not a permission to perform off-thread Bukkit work. */
    public NavigationPlan plan(UUID world, CaptureBounds bounds, BlockPos start, BlockPos goal,
                               MovementProfile profile, PathPlanner.Limits searchLimits) {
        if (closed) throw new IllegalStateException("Navigation bridge is closed");
        return work.plan(world, bounds, start, goal, profile, searchLimits);
    }
    public int outstandingJobs() { return work.outstandingJobs(); }

    private void captureTick() {
        if (closed) return;
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().severe("Navigation capture was invoked away from the owning thread; disabling capture");
            close(); return;
        }
        // This cache lives for one owner-thread pump only and is never passed to the worker.
        Map<UUID, World> worlds = new HashMap<>();
        work.pump(new NavigationWorkQueue.Sampler() {
            private World world(UUID id) {
                if (!worlds.containsKey(id)) worlds.put(id, plugin.getServer().getWorld(id));
                return worlds.get(id);
            }
            @Override public boolean worldAvailable(UUID id) { return world(id) != null; }
            @Override public boolean chunkAvailable(UUID id, int x, int z) {
                World world = world(id); return world != null && world.isChunkLoaded(x, z);
            }
            @Override public VoxelSnapshot.Cell sample(UUID id, int x, int y, int z) {
                World world = world(id);
                if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()
                        || !world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) return VoxelSnapshot.Cell.UNAVAILABLE;
                // Collision shapes of loaded blocks only; no neighboring-block queries or chunk retrieval.
                return CollisionCells.of(world.getBlockAt(x, y, z));
            }
        }, ++tick);
    }

    private static boolean isFolia(JavaPlugin plugin) {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, plugin.getServer().getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) { return false; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { pump.cancel(); }
        finally { work.close(); }
    }
}
