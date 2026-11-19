package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Replies on the sender's owner: the main thread, or {@link FoliaRegions} on Folia. Never falls back to Bukkit scheduling on Folia. */
final class ReplyScheduler {
    private final JavaPlugin plugin;
    private final FoliaRegions regions;

    /** {@code regions} is null off Folia. */
    ReplyScheduler(JavaPlugin plugin, FoliaRegions regions) {
        this.plugin = plugin;
        this.regions = regions;
    }
    boolean isFolia() { return regions != null; }

    /**
     * From the global owner, runs {@code action} on each online player's owner. A non-null {@code refusal} is logged with
     * the count of players whose work could not be scheduled.
     */
    void eachPlayer(Consumer<Player> action, String refusal) {
        reply(plugin.getServer().getConsoleSender(), () -> {
            int refused = 0;
            for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers()))
                if (!reply(player, () -> action.accept(player))) refused++;
            if (refused > 0 && refusal != null) plugin.getLogger().warning(refusal + " for " + refused + " online players");
        });
    }

    boolean reply(CommandSender sender, Runnable action) {
        if (!plugin.isEnabled()) return false;
        if (regions != null && sender instanceof Player player) return regions.submit(player, action, () -> {});
        boolean accepted = regions != null ? regions.global(action) : main(action);
        if (!accepted && plugin.isEnabled()) plugin.getLogger().warning("Cannot deliver command response: the server refused the task");
        return accepted;
    }

    /** Runs on the player's owner after {@code ticks}; on Folia the task is dropped when the player leaves first. */
    boolean later(Player player, Runnable action, long ticks) {
        if (!plugin.isEnabled()) return false;
        if (regions != null) return regions.later(player, action, () -> {}, ticks);
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, action, Math.max(1L, ticks));
            return true;
        } catch (RuntimeException refused) { return false; }
    }

    private boolean main(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
            return true;
        } catch (RuntimeException refused) { return false; }
    }
}
