package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.bukkit.BukkitWorldLookup;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.navigation.BukkitDoors;
import art.arcane.animus.bukkit.navigation.BukkitTerrain;
import art.arcane.animus.bukkit.navigation.TerrainChanges;
import art.arcane.animus.bukkit.navigation.VirtualNpcWaypointEvent;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.navigation.DoorPassage;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.runtime.RuntimeHost;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** The Bukkit and Paper main thread as the single owner of every persisted NPC. World keys are world UUIDs. */
public final class BukkitRuntimeHost implements RuntimeHost<Player, ItemStack>, Listener {
    private final JavaPlugin plugin;
    private final String minecraftVersion;
    private final NativePacketAdapter.Scheduler replies;
    private final BukkitTerrain terrain;
    private DoorPassage.Doors doors = DoorPassage.NONE;
    private ChunkDelivery chunks;
    private PersistedNpcService<Player, ItemStack> service;

    private BukkitRuntimeHost(JavaPlugin plugin, String minecraftVersion, NativePacketAdapter.Scheduler replies) {
        this.plugin = Objects.requireNonNull(plugin); this.minecraftVersion = Objects.requireNonNull(minecraftVersion);
        this.replies = Objects.requireNonNull(replies); terrain = new BukkitTerrain(plugin.getServer());
    }

    /** Starts the main-thread runtime and forwards player lifecycle events to it while it is available. */
    public static PersistedNpcService<Player, ItemStack> start(JavaPlugin plugin, AnimusRuntime runtime, String minecraftVersion, Executor io,
                                                               PersistedNpcService.Settings settings, NativePacketAdapter.Scheduler replies) {
        BukkitRuntimeHost host = new BukkitRuntimeHost(plugin, minecraftVersion, replies);
        host.doors = BukkitDoors.bind(plugin, runtime, new BukkitDoors.Owners() {
            @Override public boolean owns(World world, int chunkX, int chunkZ) { return Bukkit.isPrimaryThread(); }
            @Override public void run(World world, int chunkX, int chunkZ, Runnable work) { if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, work); }
        });
        host.service = new PersistedNpcService<>(host, runtime, io, settings);
        // Registered even while disabled: every handler checks availability, and a hotload may enable the runtime.
        plugin.getServer().getPluginManager().registerEvents(host, plugin);
        plugin.getServer().getPluginManager().registerEvents(new TerrainChanges(host.service::terrainChanged), plugin);
        return host.service;
    }

    @Override public boolean ownerThread() { return Bukkit.isPrimaryThread(); }
    @Override public boolean execute(Runnable work) {
        try { plugin.getServer().getScheduler().runTask(plugin, work); return true; }
        catch (RuntimeException rejected) { return false; }
    }
    @Override public AutoCloseable everyTick(Runnable tick) { return plugin.getServer().getScheduler().runTaskTimer(plugin, tick, 1, 1)::cancel; }
    @Override public void warn(String message) { plugin.getLogger().warning(message); }
    @Override public BodyTransport<Player, ItemStack> openTransport() throws ReflectiveOperationException {
        chunks = ChunkDelivery.bind(plugin, minecraftVersion);
        return NativePacketAdapter.bind(plugin, minecraftVersion, replies);
    }
    @Override public String chunkDelivery() { return chunks == null ? "unbound" : chunks.implementation(); }

    @Override public Collection<? extends Player> onlinePlayers() { return plugin.getServer().getOnlinePlayers(); }
    @Override public Optional<Player> player(UUID id) { return Optional.ofNullable(plugin.getServer().getPlayer(id)); }
    @Override public UUID id(Player player) { return player.getUniqueId(); }
    @Override public String name(Player player) { return player.getName(); }
    @Override public boolean online(Player player) { return player.isOnline(); }
    @Override public boolean dead(Player player) { return player.isDead(); }
    @Override public boolean sleeping(Player player) { return player.isSleeping(); }
    @Override public boolean spectator(Player player) { return player.getGameMode() == GameMode.SPECTATOR; }
    @Override public boolean inVehicle(Player player) { return player.isInsideVehicle(); }
    @Override public int entityId(Player player) { return player.getEntityId(); }
    @Override public WorldPosition position(Player player) { return position(player.getLocation()); }
    @Override public Vec3 eye(Player player) { Location eye = player.getEyeLocation(); return new Vec3(eye.getX(), eye.getY(), eye.getZ()); }
    @Override public Vec3 direction(Player player) { Vector direction = player.getEyeLocation().getDirection(); return new Vec3(direction.getX(), direction.getY(), direction.getZ()); }
    @Override public float yaw(Player player) { return player.getLocation().getYaw(); }
    @Override public float pitch(Player player) { return player.getLocation().getPitch(); }
    @Override public boolean permitted(Player player, String node) { return player.hasPermission(node); }
    @Override public void tell(Player player, String message) { player.sendMessage(RichText.colorize(message)); }
    @Override public boolean teleport(Player player, WorldPosition destination, float yaw, float pitch) {
        World world = terrain.world(destination.world());
        if (world == null) return false;
        Vec3 point = destination.point();
        return player.teleport(new Location(world, point.x(), point.y(), point.z(), yaw, pitch), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override public Optional<String> resolveWorld(String reference) {
        World world = BukkitWorldLookup.find(plugin.getServer(), reference);
        return world == null ? Optional.empty() : Optional.of(world.getUID().toString());
    }
    @Override public boolean worldLoaded(String world) { return terrain.worldLoaded(world); }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) { return terrain.chunkLoaded(world, chunkX, chunkZ); }
    @Override public MotionValidator.Sampler collision(String world) { return terrain.collision(world); }
    @Override public boolean chunkSent(Player player, int chunkX, int chunkZ) { return chunks.sent(player, chunkX, chunkZ); }
    @Override public boolean clearSight(Player player, Vec3 eye, Vec3 direction, double distance) {
        World world = player.getWorld();
        return world.rayTraceBlocks(new Location(world, eye.x(), eye.y(), eye.z()), new Vector(direction.x(), direction.y(), direction.z()),
                distance, FluidCollisionMode.NEVER, true) == null;
    }
    @Override public DoorPassage.Doors doors() { return doors; }
    @Override public boolean solid(String world, int x, int y, int z) {
        World loaded = terrain.world(world);
        if (loaded == null || y < loaded.getMinHeight() || y >= loaded.getMaxHeight() || !loaded.isChunkLoaded(x >> 4, z >> 4)) return false;
        return loaded.getBlockAt(x, y, z).getType().isSolid();
    }
    @Override public boolean registeredEntity(UUID uuid) { return plugin.getServer().getEntity(uuid) != null; }

    @Override public ItemStack item(NpcItem item, String path, List<String> errors) { return DefinitionItems.stack(item, path, errors); }
    @Override public ItemStack copy(ItemStack item) { return item.clone(); }
    @Override public Optional<ItemStack> emptyItem() { return Optional.of(new ItemStack(org.bukkit.Material.AIR)); }

    @Override public boolean interactionAllowed(Player player, UUID npc, Interaction action, double distance) {
        VirtualNpcInteractionEvent event = new VirtualNpcInteractionEvent(player, npc,
                action == Interaction.ATTACK ? VirtualNpcInteractionEvent.Action.ATTACK : VirtualNpcInteractionEvent.Action.INTERACT, distance, false);
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
    @Override public void waypointReached(UUID npc, String id, UUID route, int waypoint, WorldPosition at) {
        plugin.getServer().getPluginManager().callEvent(new VirtualNpcWaypointEvent(npc, id, route, waypoint, at));
    }

    static WorldPosition position(Location location) {
        return new WorldPosition(Objects.requireNonNull(location.getWorld()).getUID().toString(), new Vec3(location.getX(), location.getY(), location.getZ()));
    }

    @EventHandler public void join(PlayerJoinEvent event) { service.joined(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { service.quit(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) { service.teleported(event.getPlayer()); }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event) { service.changedWorld(event.getPlayer()); }
    @EventHandler public void respawn(PlayerRespawnEvent event) { service.respawned(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void death(PlayerDeathEvent event) { service.died(event.getEntity()); }
    @EventHandler public void loaded(WorldLoadEvent event) { service.worldLoaded(); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void unloaded(WorldUnloadEvent event) { service.worldUnloaded(event.getWorld().getUID().toString()); }
}
