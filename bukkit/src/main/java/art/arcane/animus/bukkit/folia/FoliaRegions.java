package art.arcane.animus.bukkit.folia;

import art.arcane.animus.bukkit.runtime.RegionScheduler;
import art.arcane.animus.core.virtual.ChunkPosition;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Exact optional Folia API binding: entity, region and global schedulers, ownership checks, chunk delivery and the
 * current region tick. There is no Bukkit-scheduler fallback. Location work is only admitted for chunks the caller
 * already knows are loaded; scheduling never loads or generates a chunk on Animus's behalf.
 */
public final class FoliaRegions implements RegionScheduler {
    private final Plugin plugin;
    private final Server server;
    private final Method entityScheduler, entityExecute, entityRepeat, cancel, entityOwned, rectangleOwned, chunkSent;
    private final Method regionScheduler, regionExecute, regionRepeat, globalScheduler, globalExecute, globalRepeat;
    private final Method currentRegion, regionData, regionTick, teleportAsync;
    private final java.util.Map<String, World> worlds = new java.util.concurrent.ConcurrentHashMap<>();

    public FoliaRegions(Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin; server = plugin.getServer();
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        Class<?> entity = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        Class<?> region = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
        Class<?> global = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
        entityScheduler = Entity.class.getMethod("getScheduler");
        entityExecute = entity.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        entityRepeat = entity.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
        cancel = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");
        entityOwned = Server.class.getMethod("isOwnedByCurrentRegion", Entity.class);
        rectangleOwned = Server.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class, int.class, int.class);
        chunkSent = Player.class.getMethod("isChunkSent", long.class);
        regionScheduler = Server.class.getMethod("getRegionScheduler");
        regionExecute = region.getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
        regionRepeat = region.getMethod("runAtFixedRate", Plugin.class, World.class, int.class, int.class, Consumer.class, long.class, long.class);
        globalScheduler = Server.class.getMethod("getGlobalRegionScheduler");
        globalExecute = global.getMethod("execute", Plugin.class, Runnable.class);
        globalRepeat = global.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
        // Region tick identity bounds per-region work. Identical on the 26.1.2, 26.2 and 26.3 builds.
        currentRegion = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion");
        regionData = Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer$ThreadedRegion").getMethod("getData");
        regionTick = Class.forName("io.papermc.paper.threadedregions.TickRegions$TickRegionData").getMethod("getCurrentTick");
        teleportAsync = Entity.class.getMethod("teleportAsync", org.bukkit.Location.class);
    }

    /** Runs on the entity's owner. {@code retired} runs instead, without world access, when the entity is gone. */
    public boolean execute(Entity player, Runnable action, Runnable retired) {
        if (!plugin.isEnabled()) { retired.run(); return false; }
        try {
            boolean accepted = (boolean) entityExecute.invoke(entityScheduler.invoke(player), plugin, action, retired, 1L);
            if (!accepted) retired.run();
            return accepted;
        } catch (ReflectiveOperationException | RuntimeException failure) { retired.run(); return false; }
    }
    /**
     * Runs on the entity's owner; unlike {@link #execute(Entity, Runnable, Runnable)}, {@code retired} runs only when an
     * accepted task is later dropped because the entity left. False when the scheduler did not accept the work.
     */
    public boolean submit(Entity entity, Runnable action, Runnable retired) {
        if (!plugin.isEnabled()) return false;
        try { return (boolean) entityExecute.invoke(entityScheduler.invoke(entity), plugin, action, retired, 1L); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    /** Repeats on the player's owner until cancelled or the player retires. */
    public AutoCloseable repeat(Player player, Runnable action, Runnable retired) { return repeat(player, action, retired, 1L, 1L); }
    /** Repeats on the player's owner every {@code period} ticks until cancelled or the player retires. */
    public AutoCloseable repeat(Player player, Runnable action, Runnable retired, long delay, long period) {
        try {
            Object task = entityRepeat.invoke(entityScheduler.invoke(player), plugin, (Consumer<Object>) ignored -> action.run(), retired, delay, period);
            if (task == null) { retired.run(); return () -> {}; }
            return () -> cancel.invoke(task);
        } catch (ReflectiveOperationException | RuntimeException failure) { retired.run(); return () -> {}; }
    }
    /** Control-plane work: bookkeeping only, never world or entity state. */
    public boolean global(Runnable action) {
        try { globalExecute.invoke(globalScheduler.invoke(server), plugin, action); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    public AutoCloseable globalTicks(Runnable tick) { return globalTicks(tick, 1L); }
    /** Repeats on the global region every {@code period} ticks until cancelled. */
    public AutoCloseable globalTicks(Runnable tick, long period) {
        try {
            Object task = globalRepeat.invoke(globalScheduler.invoke(server), plugin, (Consumer<Object>) ignored -> tick.run(), period, period);
            return () -> cancel.invoke(task);
        } catch (ReflectiveOperationException | RuntimeException failure) { throw new IllegalStateException("Global region scheduler refused the task", failure); }
    }
    /** Folia's only teleport: asynchronous, from the entity's owner. False when it could not be started. */
    public boolean teleport(Entity entity, org.bukkit.Location destination) {
        try { teleportAsync.invoke(entity, destination); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    public boolean owns(Entity entity) { return invokeBoolean(entityOwned, server, entity); }
    public void require(Player player) {
        if (!owns(player)) throw new IllegalStateException("Player owner callback required before entity access");
    }
    public boolean owns(World world, int minX, int minZ, int maxX, int maxZ) {
        return world != null && invokeBoolean(rectangleOwned, server, world, minX, minZ, maxX, maxZ);
    }
    /** Owner of the player only. */
    public boolean sent(Player player, int x, int z) {
        require(player);
        return invokeBoolean(chunkSent, player, ((long) z << 32) | (x & 0xffffffffL));
    }

    @Override public boolean owns(String world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return owns(world(world), minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }
    @Override public Task repeat(ChunkPosition anchor, Runnable tick) {
        World world = world(anchor.world());
        if (world == null || !plugin.isEnabled()) return null;
        try {
            Object task = regionRepeat.invoke(regionScheduler.invoke(server), plugin, world, anchor.x(), anchor.z(), (Consumer<Object>) ignored -> tick.run(), 1L, 1L);
            if (task == null) return null;
            return () -> { try { cancel.invoke(task); } catch (ReflectiveOperationException | RuntimeException ignored) { /* Already finished. */ } };
        } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }
    @Override public boolean execute(ChunkPosition anchor, Runnable work) {
        World world = world(anchor.world());
        if (world == null || !plugin.isEnabled()) return false;
        try { regionExecute.invoke(regionScheduler.invoke(server), plugin, world, anchor.x(), anchor.z(), work); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    @Override public Tick tick() {
        try {
            Object region = currentRegion.invoke(null);
            if (region == null) return null;
            Object data = regionData.invoke(region);
            return new Tick(data, (long) regionTick.invoke(data));
        } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }
    /** Folia loads no world after startup, so a found world stays valid; a missing one is looked up again. */
    public World world(String id) {
        World cached = worlds.get(id);
        if (cached != null) return cached;
        try { World found = server.getWorld(UUID.fromString(id)); if (found != null) worlds.put(id, found); return found; }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static boolean invokeBoolean(Method method, Object target, Object... arguments) {
        try { return (boolean) method.invoke(target, arguments); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
}
