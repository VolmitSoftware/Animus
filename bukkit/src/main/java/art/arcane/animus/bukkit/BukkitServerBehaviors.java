package art.arcane.animus.bukkit;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.trigger.ServerCommandSync;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.platform.BukkitWorlds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.entity.Projectile;
import art.arcane.animus.bukkit.combat.BukkitCombatWorld;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
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
 * Listeners publish; cancellable world events are cancelled inline when a trigger's cancel condition holds. Snapshots are refreshed every {@link SnapshotFacts#REFRESH_TICKS} ticks on each player's owner and
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
    /** The loaded worlds, refreshed with the server snapshot, so fact readers on any thread resolve worlds without the server. */
    private volatile List<BukkitWorlds.Loaded> worlds;

    /** {@code servers} is null when behaviors are disabled; facts still serve NPC behaviors then. */
    BukkitServerBehaviors(JavaPlugin plugin, BukkitOwners owners, ServerTriggerDispatcher servers, SnapshotFacts facts, Consumer<String> log) {
        this.plugin = plugin; server = plugin.getServer(); this.owners = owners; this.servers = servers; this.facts = facts; this.log = log;
        worlds = BukkitWorldLookup.loaded(server);
        facts.worlds((String reference) -> BukkitWorlds.resolve(reference, worlds));
        ServerCommandSync bound = null;
        if (servers != null) {
            servers.worlds(this::world);
            try {
                bound = new ServerCommandSync(BukkitServerCommands.bind(server, servers, this::resendCommands), log);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                log.accept("Server behavior commands are unavailable: the server's command map cannot be reached (" + failure.getClass().getSimpleName() + ")");
            }
        }
        sync = bound;
    }

    SnapshotFacts facts() { return facts; }

    /** The name a trigger's world reference resolves to among the loaded worlds; events report worlds by name. */
    private Optional<String> world(String reference) { return BukkitWorlds.resolve(reference, BukkitWorldLookup.loaded(server)); }

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
        String world = at.getWorld() == null ? "" : at.getWorld().getName();
        facts.player(player.getUniqueId(), SnapshotFacts.player(player.getName(), player.getUniqueId(), player.getHealth(), player.getMaxHealth(),
                player.getFoodLevel(), player.getLevel(), world, at.getX(), at.getY(), at.getZ(),
                player.getGameMode().name(), player.isOp(), player.isSneaking(), player.isFlying(),
                empty ? "minecraft:air" : hand.getType().getKey().toString(), empty ? 0 : hand.getAmount()));
        if (servers != null && !closed) servers.position(player.getUniqueId(), world, at.getX(), at.getY(), at.getZ());
    }

    /** Global owner. The tick counts from when Animus enabled. */
    private void serverSnapshot() {
        serverTicks += SnapshotFacts.REFRESH_TICKS;
        worlds = BukkitWorldLookup.loaded(server);
        List<UUID> online = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) online.add(player.getUniqueId());
        facts.server(SnapshotFacts.server(online.size(), server.getMaxPlayers(), serverTicks, System.currentTimeMillis(), online));
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
        if (servers != null && !closed) servers.departed(id);
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void inventoryOpen(InventoryOpenEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_INVENTORY_OPEN);
        if (dispatcher == null || !(event.getPlayer() instanceof Player player)) return;
        if (dispatcher.decide(ServerEvent.inventory(TriggerKind.PLAYER_INVENTORY_OPEN, player.getUniqueId(), inventoryType(event.getView())))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void inventoryClose(InventoryCloseEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_INVENTORY_CLOSE);
        if (dispatcher == null || !(event.getPlayer() instanceof Player player)) return;
        dispatcher.publish(ServerEvent.inventory(TriggerKind.PLAYER_INVENTORY_CLOSE, player.getUniqueId(), inventoryType(event.getView())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void inventoryClick(InventoryClickEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_INVENTORY_CLICK);
        if (dispatcher == null || !(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (dispatcher.decide(ServerEvent.inventoryClick(player.getUniqueId(), inventoryType(event.getView()), event.getClick().name().toLowerCase(Locale.ROOT),
                slot < 0 ? -1 : slot, itemKey(event.getCurrentItem())))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void itemDrop(PlayerDropItemEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_ITEM_DROP);
        if (dispatcher == null) return;
        ItemStack stack = event.getItemDrop().getItemStack();
        if (dispatcher.decide(ServerEvent.item(TriggerKind.PLAYER_ITEM_DROP, event.getPlayer().getUniqueId(), itemKey(stack), stack.getAmount()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void itemPickup(EntityPickupItemEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_ITEM_PICKUP);
        if (dispatcher == null || !(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (dispatcher.decide(ServerEvent.item(TriggerKind.PLAYER_ITEM_PICKUP, player.getUniqueId(), itemKey(stack), stack.getAmount()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void itemConsume(PlayerItemConsumeEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_ITEM_CONSUME);
        if (dispatcher == null) return;
        if (dispatcher.decide(ServerEvent.item(TriggerKind.PLAYER_ITEM_CONSUME, event.getPlayer().getUniqueId(), itemKey(event.getItem()), 1))) event.setCancelled(true);
    }

    /** A right click fires once per hand, as Denizen's clicks do. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void blockClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_BLOCK_CLICK);
        Block block = event.getClickedBlock();
        if (dispatcher == null || block == null) return;
        String hand = event.getHand() == EquipmentSlot.OFF_HAND ? "off_hand" : "main_hand";
        if (dispatcher.decide(ServerEvent.blockClick(event.getPlayer().getUniqueId(), action == Action.LEFT_CLICK_BLOCK ? "left" : "right", hand,
                block.getType().getKey().toString(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void blockPlace(BlockPlaceEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_BLOCK_PLACE);
        if (dispatcher == null) return;
        if (dispatcher.decide(block(TriggerKind.PLAYER_BLOCK_PLACE, event.getPlayer(), event.getBlockPlaced()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void blockBreak(BlockBreakEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PLAYER_BLOCK_BREAK);
        if (dispatcher == null) return;
        if (dispatcher.decide(block(TriggerKind.PLAYER_BLOCK_BREAK, event.getPlayer(), event.getBlock()))) event.setCancelled(true);
    }

    /**
     * A player taking damage, and a player's direct hit on another living entity; a hit between players publishes both.
     * Projectile and other indirect hits count only as damage taken, with the shooter as damager.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void damage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        ServerTriggerDispatcher damaged = victim instanceof Player ? listening(TriggerKind.PLAYER_DAMAGED) : null;
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player hitter
                && victim instanceof LivingEntity && victim != hitter ? hitter : null;
        ServerTriggerDispatcher hits = attacker == null ? null : listening(TriggerKind.PLAYER_DAMAGE_ENTITY);
        if (damaged == null && hits == null) return;
        DamageSource source = event.getDamageSource();
        String cause = source.getDamageType().getKey().getKey();
        double amount = event.getFinalDamage();
        boolean cancel = false;
        if (damaged != null) {
            Entity by = source.getCausingEntity() != null ? source.getCausingEntity() : source.getDirectEntity();
            cancel = damaged.decide(ServerEvent.damaged(victim.getUniqueId(), cause, amount, by == null ? "" : typeKey(by)));
        }
        if (hits != null) cancel |= hits.decide(ServerEvent.damageEntity(attacker.getUniqueId(), typeKey(victim), victim.getUniqueId(), cause, amount));
        if (cancel) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void kill(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        ServerTriggerDispatcher dispatcher = killer == null ? null : listening(TriggerKind.PLAYER_KILL);
        if (dispatcher != null) dispatcher.publish(ServerEvent.kill(killer.getUniqueId(), typeKey(victim), victim.getUniqueId()));
    }

    /** Real projectiles only: an NPC's combat helpers publish nothing, and their hits are cancelled before this sees them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void projectileLaunch(ProjectileLaunchEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PROJECTILE_LAUNCH);
        if (dispatcher == null || BukkitCombatWorld.launching()) return;
        Projectile projectile = event.getEntity();
        Entity shooter = projectile.getShooter() instanceof Entity source ? source : null;
        Location at = projectile.getLocation();
        if (dispatcher.decide(ServerEvent.projectileLaunch(playerOf(shooter), typeKey(projectile), projectile.getUniqueId(), shooter == null ? "" : typeKey(shooter),
                shooter == null ? "" : shooter.getUniqueId().toString(), projectile.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void projectileHit(ProjectileHitEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.PROJECTILE_HIT);
        if (dispatcher == null) return;
        Projectile projectile = event.getEntity();
        Entity shooter = projectile.getShooter() instanceof Entity source ? source : null, hit = event.getHitEntity();
        Block block = event.getHitBlock();
        Location at = block != null ? block.getLocation() : projectile.getLocation();
        if (dispatcher.decide(ServerEvent.projectileHit(playerOf(shooter), typeKey(projectile), projectile.getUniqueId(), shooter == null ? "" : typeKey(shooter),
                shooter == null ? "" : shooter.getUniqueId().toString(), hit == null ? "" : typeKey(hit), hit == null ? "" : hit.getUniqueId().toString(),
                block == null ? "" : block.getType().getKey().toString(), projectile.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()))) event.setCancelled(true);
    }

    /** Force is a player's bow draw; crossbows and mobs report 1, as on mod servers. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void shootBow(EntityShootBowEvent event) {
        ServerTriggerDispatcher dispatcher = listening(TriggerKind.ENTITY_SHOOT_BOW);
        if (dispatcher == null) return;
        LivingEntity shooter = event.getEntity();
        ItemStack bow = event.getBow();
        Entity projectile = event.getProjectile();
        Location at = shooter.getLocation();
        double force = shooter instanceof Player && bow != null && bow.getType() == Material.BOW ? event.getForce() : 1;
        if (dispatcher.decide(ServerEvent.shootBow(playerOf(shooter), typeKey(shooter), shooter.getUniqueId(), itemKey(bow), typeKey(projectile), projectile.getUniqueId(),
                force, event.getHand() == EquipmentSlot.OFF_HAND ? "off_hand" : "main_hand", shooter.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()))) event.setCancelled(true);
    }

    private static Optional<UUID> playerOf(Entity shooter) { return shooter instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(); }

    /** The dispatcher when an enabled trigger listens for this kind, else null, so hot handlers return before allocating. */
    private ServerTriggerDispatcher listening(TriggerKind kind) {
        ServerTriggerDispatcher dispatcher = servers;
        return dispatcher != null && !closed && dispatcher.listens(kind) ? dispatcher : null;
    }

    private static ServerEvent block(TriggerKind kind, Player player, Block block) {
        return ServerEvent.block(kind, player.getUniqueId(), block.getType().getKey().toString(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String inventoryType(InventoryView view) { return view.getTopInventory().getType().name().toLowerCase(Locale.ROOT); }

    /** The item's namespaced key; minecraft:air for none. */
    static String itemKey(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR ? "minecraft:air" : stack.getType().getKey().toString();
    }

    private static String typeKey(Entity entity) {
        EntityType type = entity.getType();
        return type == EntityType.UNKNOWN ? "" : type.getKey().toString();
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
