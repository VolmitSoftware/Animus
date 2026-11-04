package art.arcane.animus.bukkit;

import java.lang.reflect.*;
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
        boolean regionized;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); regionized = true; }
        catch (ClassNotFoundException e) { regionized = false; }
        folia = regionized;
        if (folia) {
            entityScheduler = Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
            entityExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
                    .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            globalScheduler = Class.forName("org.bukkit.Server").getMethod("getGlobalRegionScheduler");
            globalExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
                    .getMethod("execute", Plugin.class, Runnable.class);
        } else { entityScheduler = null; entityExecute = null; globalScheduler = null; globalExecute = null; }
    }
    void reply(CommandSender sender, Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            if (!folia) plugin.getServer().getScheduler().runTask(plugin, action);
            else if (sender instanceof Player player) entityExecute.invoke(entityScheduler.invoke(player), plugin, action, (Runnable) () -> {}, 1L);
            else globalExecute.invoke(globalScheduler.invoke(plugin.getServer()), plugin, action);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (plugin.isEnabled()) plugin.getLogger().warning("Cannot deliver command response: " + e.getClass().getSimpleName());
        }
    }
}
