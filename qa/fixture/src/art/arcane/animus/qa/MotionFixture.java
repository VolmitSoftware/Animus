package art.arcane.animus.qa;

import art.arcane.animus.bukkit.navigation.VirtualNpcWaypointEvent;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Owned local-QA observations and explicit terrain setup; never distributed with Animus. */
final class MotionFixture implements Listener, AutoCloseable {
    private record ChunkKey(UUID world, int x, int z) {}
    private static final class Expected {
        final UUID owner;
        int arrivals;
        Expected(UUID owner) { this.owner = owner; }
    }
    private final JavaPlugin plugin;
    private final Map<UUID, Expected> expected = new HashMap<>();
    private final Map<ChunkKey, UUID> loaded = new HashMap<>();
    private final BukkitTask clock;
    private long tick;
    MotionFixture(JavaPlugin plugin) {
        this.plugin = plugin;
        clock = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick++, 1, 1);
    }

    boolean command(Player player, String[] args) {
        if (args.length == 0 || !args[0].equals("motion")) return false;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Motion fixture requires the owning Bukkit thread");
        if (args.length == 3 && (args[1].equals("expect") || args[1].equals("forget"))) {
            UUID id = UUID.fromString(args[2]);
            if (args[1].equals("expect")) {
                if (expected.size() >= 32 && !expected.containsKey(id)) throw new IllegalStateException("Motion observer limit reached");
                expected.put(id, new Expected(player.getUniqueId()));
            } else expected.remove(id);
            player.sendMessage("QA motion " + args[1] + " npc=" + id); return true;
        }
        if (args.length == 5 && args[1].equals("cell")) {
            int x = Integer.parseInt(args[2]), y = Integer.parseInt(args[3]), z = Integer.parseInt(args[4]);
            org.bukkit.block.Block block = player.getWorld().getBlockAt(x, y, z);
            player.sendMessage("QA cell " + x + " " + y + " " + z + " " + block.getType() + " shape=" + block.getCollisionShape().getBoundingBoxes()
                    + " cell=" + art.arcane.animus.bukkit.navigation.BlockCells.of(block));
            return true;
        }
        if (args.length == 2 && args[1].equals("cleanup")) {
            cleanup(player.getUniqueId()); player.sendMessage("QA motion cleanup complete"); return true;
        }
        if (args.length == 3 && args[1].equals("viewer")) {
            if (!Set.of("AnimusProofQA", "AnimusProofPeer").contains(args[2])) throw new IllegalArgumentException("Use an owned QA viewer");
            Player viewer = Bukkit.getPlayerExact(args[2]);
            if (viewer == null) throw new IllegalArgumentException("QA viewer is offline");
            try {
                Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
                Object connection = handle.getClass().getField("connection").get(handle);
                var pending = connection.getClass().getDeclaredField("awaitingPositionFromClient"); pending.setAccessible(true);
                var timer = connection.getClass().getDeclaredField("clientLoadedTimeoutTimer"); timer.setAccessible(true);
                var loaded = connection.getClass().getDeclaredMethod("hasClientLoaded"); loaded.setAccessible(true);
                Object sent;
                try { sent = Player.class.getMethod("isChunkSent", long.class).invoke(viewer, 0L); }
                catch (NoSuchMethodException absent) { sent = "unavailable"; }
                player.sendMessage("QA viewer name=" + viewer.getName() + " mode=" + viewer.getGameMode() + " world=" + viewer.getWorld().getName()
                        + " health=" + viewer.getHealth() + " dead=" + viewer.isDead() + " valid=" + viewer.isValid()
                        + " world-member=" + viewer.getWorld().getPlayers().contains(viewer)
                        + " view-distance=" + viewer.getClientViewDistance() + " client-loaded=" + loaded.invoke(connection)
                        + " loaded-timer=" + timer.get(connection) + " pending-position=" + pending.get(connection) + " chunk0-sent=" + sent);
                Object loader = handle.getClass().getMethod("moonrise$getChunkLoader").invoke(handle);
                StringBuilder detail = new StringBuilder("QA loader name=").append(viewer.getName())
                        .append(" real=").append(handle.getClass().getMethod("moonrise$isRealPlayer").invoke(handle));
                if (loader == null) detail.append(" loader=null");
                else {
                    for (String key : List.of("removed", "lastChunkX", "lastChunkZ", "lastSendDistance", "lastLoadDistance", "lastTickDistance"))
                        detail.append(' ').append(key).append('=').append(field(loader, key));
                    for (String key : List.of("sentChunks", "sendQueue", "tickingQueue", "generatingQueue", "genQueue", "loadingQueue", "loadQueue")) {
                        Object queue = field(loader, key);
                        detail.append(' ').append(key).append('=').append(queue.getClass().getMethod("size").invoke(queue));
                        if (key.equals("sendQueue") && !(boolean) queue.getClass().getMethod("isEmpty").invoke(queue))
                            detail.append(" send-head=").append(queue.getClass().getMethod("firstLong").invoke(queue));
                    }
                    Object stages = field(loader, "chunkTicketStage");
                    detail.append(" stages0=");
                    for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                        long key = (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
                        detail.append(stages.getClass().getMethod("get", long.class).invoke(stages, key)).append(',');
                    }
                }
                player.sendMessage(detail.toString());
            } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Exact native viewer diagnostic unavailable: " + unavailable); }
            return true;
        }
        if (args.length == 5 && args[1].equals("chunk")) {
            int x = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
            if (Math.abs((long) x) > 64 || Math.abs((long) z) > 64) throw new IllegalArgumentException("Use the dedicated QA chunk range");
            World world = player.getWorld(); ChunkKey key = new ChunkKey(world.getUID(), x, z);
            boolean accepted = false;
            switch (args[4]) {
                case "status" -> { }
                case "load" -> {
                    if (loaded.size() >= 16 && !loaded.containsKey(key)) throw new IllegalStateException("Motion chunk fixture limit reached");
                    boolean already = world.isChunkLoaded(x, z);
                    accepted = world.loadChunk(x, z, true);
                    if (accepted && !already) loaded.put(key, player.getUniqueId());
                }
                case "unload" -> {
                    if (world.isChunkForceLoaded(x, z)) throw new IllegalStateException("Refusing to unload a forced fixture chunk");
                    accepted = world.unloadChunkRequest(x, z);
                }
                default -> throw new IllegalArgumentException("motion chunk <x> <z> status|load|unload");
            }
            player.sendMessage("QA chunk x=" + x + " z=" + z + " loaded=" + world.isChunkLoaded(x, z)
                    + " forced=" + world.isChunkForceLoaded(x, z) + " request=" + args[4] + " accepted=" + accepted);
            return true;
        }
        throw new IllegalArgumentException("motion expect|forget <UUID> | motion chunk <x> <z> status|load|unload | motion cleanup");
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void arrived(VirtualNpcWaypointEvent event) {
        Expected observer = expected.get(event.getNpcId());
        if (observer == null) return;
        if (!Bukkit.isPrimaryThread()) throw new AssertionError("Waypoint event was emitted away from the owner");
        if (plugin.getServer().getEntity(event.getNpcId()) != null) throw new AssertionError("Waypoint NPC has a world-registered body");
        observer.arrivals++;
        Player owner = plugin.getServer().getPlayer(observer.owner);
        if (owner == null || !owner.isOnline()) return;
        var point = event.getPosition().point();
        owner.sendMessage("QA waypoint npc=" + event.getNpcId() + " name=" + event.getNpcName() + " route=" + event.getRouteId()
                + " waypoint=" + event.getWaypoint() + " x=" + point.x() + " y=" + point.y() + " z=" + point.z()
                + " world-registered=0 arrivals=" + observer.arrivals + " fixture-tick=" + tick);
    }

    private void cleanup(UUID owner) {
        expected.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
        for (var entry : List.copyOf(loaded.entrySet())) {
            if (!entry.getValue().equals(owner)) continue;
            World world = plugin.getServer().getWorld(entry.getKey().world());
            if (world != null && !world.isChunkForceLoaded(entry.getKey().x(), entry.getKey().z()))
                world.unloadChunkRequest(entry.getKey().x(), entry.getKey().z());
            loaded.remove(entry.getKey());
        }
    }
    @Override public void close() { clock.cancel(); for (UUID owner : loaded.values().stream().distinct().toList()) cleanup(owner); expected.clear(); }
}
