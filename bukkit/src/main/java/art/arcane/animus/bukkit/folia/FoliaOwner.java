package art.arcane.animus.bukkit.folia;

import java.lang.reflect.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

/** Exact optional API binding; no Bukkit-scheduler fallback or location task admission. */
final class FoliaOwner {
    private final Plugin plugin;
    private final Method scheduler, execute, repeat, cancel, entityOwned, rectangleOwned, chunkSent;
    FoliaOwner(Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        Class<?> entityScheduler = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        scheduler = Entity.class.getMethod("getScheduler");
        execute = entityScheduler.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        repeat = entityScheduler.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
        cancel = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");
        entityOwned = Server.class.getMethod("isOwnedByCurrentRegion", Entity.class);
        rectangleOwned = Server.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class, int.class, int.class);
        chunkSent = Player.class.getMethod("isChunkSent", long.class);
    }
    boolean execute(Player player, Runnable action, Runnable retired) {
        if (!plugin.isEnabled()) { retired.run(); return false; }
        try {
            boolean accepted = (boolean) execute.invoke(scheduler.invoke(player), plugin, action, retired, 1L);
            if (!accepted) retired.run();
            return accepted;
        } catch (ReflectiveOperationException | RuntimeException failure) { retired.run(); return false; }
    }
    AutoCloseable repeat(Player player, Runnable action, Runnable retired) {
        try {
            Object task = repeat.invoke(scheduler.invoke(player), plugin, (Consumer<Object>) ignored -> action.run(), retired, 1L, 1L);
            if (task == null) { retired.run(); return () -> {}; }
            return () -> cancel.invoke(task);
        } catch (ReflectiveOperationException | RuntimeException failure) { retired.run(); return () -> {}; }
    }
    boolean owns(Player player) { return invokeBoolean(entityOwned, plugin.getServer(), player); }
    void require(Player player) {
        if (!owns(player)) throw new IllegalStateException("Player owner callback required before entity access");
    }
    boolean owns(World world, int minX, int minZ, int maxX, int maxZ) {
        return invokeBoolean(rectangleOwned, plugin.getServer(), world, minX, minZ, maxX, maxZ);
    }
    boolean sent(Player player, int x, int z) {
        require(player);
        return invokeBoolean(chunkSent, player, ((long) z << 32) | (x & 0xffffffffL));
    }
    private static boolean invokeBoolean(Method method, Object target, Object... arguments) {
        try { return (boolean) method.invoke(target, arguments); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
}
