package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The player and global owners of behavior work: the main thread on Bukkit and Paper; the player's entity scheduler and
 * the global region scheduler on Folia. Never falls back to Bukkit scheduling on Folia.
 */
final class BukkitOwners {
    private final JavaPlugin plugin;
    private final Server server;
    private final FoliaRegions regions;

    /** {@code regions} is null off Folia. */
    BukkitOwners(JavaPlugin plugin, FoliaRegions regions) { this.plugin = plugin; server = plugin.getServer(); this.regions = regions; }

    boolean folia() { return regions != null; }

    /**
     * Same contract as {@link art.arcane.animus.core.behavior.builtin.BehaviorPlatform#onPlayerOwner}. An offline player
     * has no owner: its work runs here and finds no player, so player actions report it as offline.
     */
    boolean player(UUID player, Runnable work, Consumer<String> refused) {
        if (!plugin.isEnabled()) return false;
        if (regions == null) return main(work);
        Player online = server.getPlayer(player);
        if (online == null || regions.owns(online)) { work.run(); return true; }
        return regions.submit(online, work, () -> refused.accept("The player left before the action ran"));
    }

    /** Same contract as {@link art.arcane.animus.core.behavior.builtin.BehaviorPlatform#onGlobalOwner}. */
    boolean global(Runnable work, Consumer<String> refused) {
        if (!plugin.isEnabled()) return false;
        return regions == null ? main(work) : regions.global(work);
    }

    /** Fire and forget on the player's owner; false when it was not accepted. */
    boolean player(Player online, Runnable work) {
        if (!plugin.isEnabled()) return false;
        if (regions == null) return main(work);
        if (regions.owns(online)) { work.run(); return true; }
        return regions.submit(online, work, () -> {});
    }

    /** Whether the calling thread owns the player. */
    boolean owns(Player player) { return regions == null ? server.isPrimaryThread() : regions.owns(player); }

    /** Repeats on the global owner every {@code period} ticks. */
    AutoCloseable globalTicks(Runnable tick, long period) {
        if (regions != null) return regions.globalTicks(tick, period);
        return server.getScheduler().runTaskTimer(plugin, tick, period, period)::cancel;
    }

    /** Folia only: repeats on the player's owner every {@code period} ticks until closed or the player retires. */
    AutoCloseable playerTicks(Player player, Runnable tick, Runnable retired, long period) {
        if (regions == null) throw new IllegalStateException("Per-player schedulers exist only on Folia");
        return regions.repeat(player, tick, retired, period, period);
    }

    /** From the player's owner. Folia teleports only asynchronously; false when the teleport could not start. */
    boolean teleport(Player player, Location destination) {
        return regions == null ? player.teleport(destination) : regions.teleport(player, destination);
    }

    private boolean main(Runnable work) {
        if (server.isPrimaryThread()) { work.run(); return true; }
        try { server.getScheduler().runTask(plugin, work); return true; }
        catch (RuntimeException refused) { return false; }
    }
}
