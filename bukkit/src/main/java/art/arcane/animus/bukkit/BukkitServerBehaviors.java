package art.arcane.animus.bukkit;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.trigger.ServerCommandSync;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The platform half of server behaviors: player events published as immutable {@link ServerEvent}s, Animus-managed commands
 * kept in step with the enabled command triggers, and the player and server fact snapshots behavior expressions read.
 * Listeners only publish. Snapshots are refreshed every {@link SnapshotFacts#REFRESH_TICKS} ticks on each player's owner and
 * the global owner; nothing scans the world.
 */
final class BukkitServerBehaviors implements Listener, AutoCloseable {
    private static final long COMMAND_POLL_TICKS = 20;
    private final JavaPlugin plugin;
    private final Server server;
    private final BukkitOwners owners;
    private final ServerTriggerDispatcher servers;
    private final SnapshotFacts facts;
    private final Consumer<String> log;
    private final ServerCommandSync sync;
    private final Map<UUID, AutoCloseable> refreshers = new ConcurrentHashMap<>();
    private final List<AutoCloseable> ticks = new ArrayList<>();
    private volatile boolean closed;
    private List<Diagnostic> logged = List.of(); // global owner only
    private long serverTicks; // global owner only

    /** {@code servers} is null when behaviors are disabled; facts still serve NPC behaviors then. */
    BukkitServerBehaviors(JavaPlugin plugin, BukkitOwners owners, ServerTriggerDispatcher servers, SnapshotFacts facts, Consumer<String> log) {
        this.plugin = plugin; server = plugin.getServer(); this.owners = owners; this.servers = servers; this.facts = facts; this.log = log;
        ServerCommandSync bound = null;
        if (servers != null) {
            try {
                bound = new ServerCommandSync(BukkitServerCommands.bind(server, servers, this::resendCommands), log);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                log.accept("Server behavior commands are unavailable: the server's command map cannot be reached (" + failure.getClass().getSimpleName() + ")");
            }
        }
        sync = bound;
    }

    SnapshotFacts facts() { return facts; }

    void start() {
        server.getPluginManager().registerEvents(this, plugin);
        if (owners.folia()) {
            ticks.add(owners.globalTicks(this::serverSnapshot, SnapshotFacts.REFRESH_TICKS));
            for (Player online : server.getOnlinePlayers()) track(online);
        } else {
            ticks.add(owners.globalTicks(() -> {
                for (Player online : server.getOnlinePlayers()) refresh(online);
                serverSnapshot();
            }, SnapshotFacts.REFRESH_TICKS));
        }
        if (sync != null) ticks.add(owners.globalTicks(this::syncCommands, COMMAND_POLL_TICKS));
    }

    /** Unregisters the commands; called on the main thread while the plugin disables. */
    @Override public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        for (AutoCloseable task : ticks) quietly(task);
        ticks.clear();
        for (AutoCloseable refresher : refreshers.values()) quietly(refresher);
        refreshers.clear();
        if (sync != null) sync.clear();
    }

    /** Global owner. A dispatcher that went unavailable, such as behaviors disabled by a reload, drops its commands. */
    private void syncCommands() {
        if (closed) return;
        if (!servers.available()) sync.clear();
        else sync.sync(servers.commands());
        List<Diagnostic> refusals = sync.refusals();
        if (refusals.equals(logged)) return;
        logged = refusals;
        // The sync itself logs name collisions; alias collisions are logged here once per change.
        for (Diagnostic refusal : refusals)
            if (!refusal.code().equals("server-command-collision")) log.accept("Server behavior command " + refusal.field() + ": " + refusal.message());
    }

    /** Players resend their command tree on their own owner. */
    private void resendCommands() {
        for (Player online : server.getOnlinePlayers()) owners.player(online, online::updateCommands);
    }

    /** Folia: refresh on the player's own scheduler until quit. */
    private void track(Player player) {
        if (closed) return;
        UUID id = player.getUniqueId();
        AutoCloseable previous = refreshers.put(id, owners.playerTicks(player, () -> refresh(player), () -> facts.forget(id), SnapshotFacts.REFRESH_TICKS));
        if (previous != null) quietly(previous);
    }

    /** On the player's owner. */
    private void refresh(Player player) {
        if (closed || !player.isOnline()) return;
        Location at = player.getLocation();
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean empty = hand == null || hand.getType() == Material.AIR;
        facts.player(player.getUniqueId(), SnapshotFacts.player(player.getName(), player.getUniqueId(), player.getHealth(), player.getMaxHealth(),
                player.getFoodLevel(), player.getLevel(), at.getWorld() == null ? "" : at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                player.getGameMode().name(), player.isOp(), player.isSneaking(), player.isFlying(),
                empty ? "minecraft:air" : hand.getType().getKey().toString(), empty ? 0 : hand.getAmount()));
    }

    /** Global owner. The tick counts from when Animus enabled. */
    private void serverSnapshot() {
        serverTicks += SnapshotFacts.REFRESH_TICKS;
        facts.server(SnapshotFacts.server(server.getOnlinePlayers().size(), server.getMaxPlayers(), serverTicks, System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (owners.folia()) track(player);
        refresh(player);
        publish(TriggerKind.PLAYER_JOIN, () -> ServerEvent.join(player.getUniqueId(), !player.hasPlayedBefore()));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        AutoCloseable refresher = refreshers.remove(id);
        if (refresher != null) quietly(refresher);
        facts.forget(id);
        publish(TriggerKind.PLAYER_QUIT, () -> ServerEvent.quit(id));
    }

    /** Spigot's chat event; Paper still fires it beside its component chat event, and the plain text is all a run needs. */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void chat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String message = event.getMessage();
        publish(TriggerKind.PLAYER_CHAT, () -> ServerEvent.chat(id, plain(message).orElse("")));
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        String message = event.getDeathMessage();
        publish(TriggerKind.PLAYER_DEATH, () -> ServerEvent.death(id, plain(message)));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        publish(TriggerKind.PLAYER_RESPAWN, () -> ServerEvent.respawn(id));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void worldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String from = event.getFrom().getName(), to = event.getPlayer().getWorld().getName();
        publish(TriggerKind.PLAYER_WORLD_CHANGE, () -> ServerEvent.worldChange(id, from, to));
    }

    /** Builds the event only when an enabled trigger listens for its kind. */
    private void publish(TriggerKind kind, Supplier<ServerEvent> event) {
        ServerTriggerDispatcher dispatcher = servers;
        if (dispatcher != null && !closed && dispatcher.listens(kind)) dispatcher.publish(event.get());
    }

    /** Text without colour codes; empty for a missing or blank message. */
    static Optional<String> plain(String text) {
        if (text == null) return Optional.empty();
        String stripped = ChatColor.stripColor(text);
        return stripped == null || stripped.isBlank() ? Optional.empty() : Optional.of(stripped);
    }

    private static void quietly(AutoCloseable task) {
        try { task.close(); }
        catch (Exception ignored) { /* The scheduler stops with the plugin. */ }
    }
}
