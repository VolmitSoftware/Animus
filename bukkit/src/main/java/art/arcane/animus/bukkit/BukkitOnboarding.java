package art.arcane.animus.bukkit;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.onboarding.Onboarding;
import art.arcane.animus.core.text.ChatLine;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * First-run guidance on Bukkit, Paper and Folia. An admin who joins gets the join hint {@link Onboarding#JOIN_DELAY_TICKS}
 * later on the player's own owner; at startup the migration notice goes to the log and to every online admin. File
 * reads run on the file lane. Never falls back to Bukkit scheduling on Folia.
 */
final class BukkitOnboarding implements Listener {
    private final JavaPlugin plugin;
    private final ReplyScheduler replies;
    private final AnimusRuntime runtime;
    private final Onboarding onboarding;
    private final Executor files;
    private final BooleanSupplier stopping;
    private final Method entityScheduler, entityExecute;

    private BukkitOnboarding(JavaPlugin plugin, ReplyScheduler replies, AnimusRuntime runtime, Executor files, BooleanSupplier stopping) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.replies = replies;
        this.runtime = runtime;
        this.files = files;
        this.stopping = stopping;
        onboarding = Onboarding.of(runtime);
        if (replies.isFolia()) {
            entityScheduler = Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
            entityExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler").getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        } else { entityScheduler = null; entityExecute = null; }
    }

    /** Listens for joins and sends the startup notice. Call once every plugin has enabled, so a Citizens loaded after Animus is seen. */
    static void start(JavaPlugin plugin, ReplyScheduler replies, AnimusRuntime runtime, Executor files, BooleanSupplier stopping) throws ReflectiveOperationException {
        BukkitOnboarding onboarding = new BukkitOnboarding(plugin, replies, runtime, files, stopping);
        plugin.getServer().getPluginManager().registerEvents(onboarding, plugin);
        onboarding.announce();
    }

    static boolean admin(Player player) { return player.hasPermission(AnimusDirector.ADMIN); }

    /** Whether a guidance line still reaches the player: online and an admin. Read on the player's owner. */
    static boolean hears(Player player) { return player.isOnline() && admin(player); }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!admin(player)) return;
        work("Join hint", () -> {
            Optional<ChatLine> hint = onboarding.joinHint(player.getUniqueId());
            hint.ifPresent((ChatLine line) -> later(player, () -> { if (hears(player) && !stopping.getAsBoolean()) send(player, line); }));
        });
    }

    private void announce() {
        replies.reply(plugin.getServer().getConsoleSender(), () -> work("Citizens migration check", () -> {
            Optional<String> notice = onboarding.startupNotice();
            if (notice.isEmpty()) return;
            plugin.getLogger().info(notice.get());
            ChatLine line = Onboarding.adminNotice(notice.get());
            replies.eachPlayer((Player player) -> { if (hears(player) && !stopping.getAsBoolean()) send(player, line); }, null);
        }));
    }

    @FunctionalInterface private interface FileWork { void run() throws IOException; }

    private void work(String label, FileWork work) {
        try {
            files.execute(() -> {
                if (stopping.getAsBoolean() || !runtime.isRunning()) return;
                try { work.run(); }
                catch (IOException | RuntimeException failure) { plugin.getLogger().warning(label + " failed: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException busy) { plugin.getLogger().warning(label + " skipped: file queue full"); }
    }

    private void send(Player player, ChatLine line) { new BukkitActor(player, replies).rich(line); }

    /** On the player's owner after the join delay; a player who leaves first gets nothing. */
    private void later(Player player, Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            if (entityExecute == null) plugin.getServer().getScheduler().runTaskLater(plugin, task, Onboarding.JOIN_DELAY_TICKS);
            else entityExecute.invoke(entityScheduler.invoke(player), plugin, task, (Runnable) () -> {}, Onboarding.JOIN_DELAY_TICKS);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().warning("Join hint not scheduled: " + failure.getClass().getSimpleName());
        }
    }
}
