package art.arcane.animus.bukkit.recording;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** The recorder's owner: the main thread on Bukkit and Paper, the player's entity scheduler on Folia. */
final class RecorderOwners {
    private final JavaPlugin plugin;
    private final FoliaRegions regions;

    /** {@code regions} is null off Folia. */
    RecorderOwners(JavaPlugin plugin, FoliaRegions regions) { this.plugin = plugin; this.regions = regions; }

    /** Runs {@code work} on the player's owner later, or {@code gone} instead when the player retired first. Never inline. */
    void later(Player player, Runnable work, Runnable gone) {
        if (regions != null) { regions.execute(player, work, gone); return; }
        if (!plugin.isEnabled()) { gone.run(); return; }
        try { plugin.getServer().getScheduler().runTask(plugin, work); }
        catch (RuntimeException refused) { gone.run(); }
    }

    /** Repeats every tick on the player's owner until closed; {@code retired} runs when Folia drops the player first. */
    AutoCloseable everyTick(Player player, Runnable tick, Runnable retired) {
        if (regions != null) return regions.repeat(player, tick, retired);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, tick, 1L, 1L);
        return task::cancel;
    }
}
