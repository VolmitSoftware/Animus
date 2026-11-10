package art.arcane.animus.bukkit.quest;

import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.core.quest.QuestCatalog;
import art.arcane.animus.core.quest.QuestService;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.text.Palette;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Animus quests on Bukkit, Paper and Folia: the quest service, the {@code /quests} command, the events quests count
 * (mob and NPC kills) and the check timer. Every player event runs where Bukkit fires it; the service hands player
 * work to each player's owner.
 */
public final class BukkitQuests implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final QuestService<Player, ItemStack> service;
    private volatile BehaviorFacts facts = BehaviorFacts.NONE;
    private AutoCloseable ticks;
    private long tick;

    public BukkitQuests(JavaPlugin plugin, AnimusRuntime runtime, ScreenManager<Player, ItemStack> screens, NpcRuntime<Player, ItemStack> living,
                        DisplayTransport<Player> displays, Consumer<Player> watch, Executor files, Supplier<ServerTriggerDispatcher> servers, Consumer<String> log) throws IOException {
        this.plugin = plugin;
        service = new QuestService<>(runtime, screens, living, displays, watch, files, servers, () -> facts,
                (String name) -> Optional.ofNullable(plugin.getServer().getPlayerExact(name)).map(Player::getUniqueId), log);
    }

    public QuestService<Player, ItemStack> service() { return service; }

    /** The world facts requirement conditions read, once the behavior runtime has them. */
    public void facts(BehaviorFacts provider) { facts = provider; }

    /** Registers the events and {@code /quests}, starts the check timer on {@code ownerTicks} and reads the catalog. */
    public void start(Function<Runnable, AutoCloseable> ownerTicks) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PluginCommand journal = plugin.getCommand("quests");
        if (journal != null) journal.setExecutor((sender, command, label, arguments) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage(Palette.error("Only a player has a quest journal")); return true; }
            if (!player.hasPermission(PermissionNodes.QUESTS_USE)) { sender.sendMessage(Palette.error("You may not use quests here")); return true; }
            service.journal(player.getUniqueId());
            return true;
        });
        ticks = ownerTicks.apply(() -> {
            if (++tick % QuestService.CHECK_TICKS != 0) return;
            for (Player online : plugin.getServer().getOnlinePlayers()) service.onPlayer(online.getUniqueId(), () -> service.tick(online));
        });
        service.reload((QuestCatalog catalog) -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void killed(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || victim.getType() == EntityType.UNKNOWN) return;
        service.killed(killer.getUniqueId(), victim.getType().getKey().toString(), Optional.empty());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void npcKilled(VirtualNpcDeathEvent event) {
        if (!event.getKillerType().filter("player"::equals).isPresent()) return;
        UUID killer = event.getKiller().orElse(null);
        if (killer != null) service.killed(killer, "", Optional.of(event.getNpcId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { service.quit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(PlayerRespawnEvent event) { service.worldChanged(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldChanged(PlayerChangedWorldEvent event) { service.worldChanged(event.getPlayer()); }

    @Override public void close() {
        if (ticks != null) {
            try { ticks.close(); }
            catch (Exception ignored) { /* The scheduler stops with the plugin. */ }
            ticks = null;
        }
        service.close();
    }
}
