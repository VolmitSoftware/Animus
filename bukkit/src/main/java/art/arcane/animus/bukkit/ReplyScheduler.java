package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import java.lang.reflect.*;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Binds optional Paper scheduling APIs once. Never falls back to Bukkit scheduling on Folia. */
final class ReplyScheduler {
    private final JavaPlugin plugin;
    private final boolean folia;
    private final Method entityScheduler, entityExecute, globalScheduler, globalExecute;
    ReplyScheduler(JavaPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        folia = FoliaRegions.present();
        if (folia) {
            entityScheduler = Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
            entityExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
                    .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            globalScheduler = Class.forName("org.bukkit.Server").getMethod("getGlobalRegionScheduler");
            globalExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
                    .getMethod("execute", Plugin.class, Runnable.class);
        } else { entityScheduler = null; entityExecute = null; globalScheduler = null; globalExecute = null; }
    }
    boolean isFolia() { return folia; }

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
        try {
            if (!folia) plugin.getServer().getScheduler().runTask(plugin, action);
            else if (sender instanceof Player player) return (Boolean) entityExecute.invoke(entityScheduler.invoke(player), plugin, action, (Runnable) () -> {}, 1L);
            else globalExecute.invoke(globalScheduler.invoke(plugin.getServer()), plugin, action);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (plugin.isEnabled()) plugin.getLogger().warning("Cannot deliver command response: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
