package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport can end a viewer's watch on its own (the connection closed on the network thread) before the server
 * reports the player gone. Body work for that viewer must be dropped, not recorded as a runtime error, and the viewer
 * must not be locked out; real transport errors are logged once at WARN with their context.
 */
class ViewerWatchRaceTest {
    @TempDir Path directory;

    @Test
    void bodyWorkAfterTheTransportReplacedTheWatchIsDroppedAndTheViewerIsWatchedAgain() throws IOException {
        Harness harness = harness();
        harness.tick(3);
        assertEquals(1, harness.transport.watches.get());

        // The transport retired the watch on its own; the connection is still open.
        harness.transport.watched = false;
        harness.spawnNpc();
        harness.tick(6);

        String status = harness.service.status();
        assertFalse(status.contains("last-error"), status);
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        assertEquals(2, harness.transport.watches.get(), "the viewer is watched again, not locked out");
        assertTrue(harness.transport.spawned.get() >= 1, "the NPC reaches the viewer once its watch is back");
    }

    @Test
    void bodyWorkAfterTheConnectionClosedIsDroppedUntilThePlayerReturns() throws IOException {
        Harness harness = harness();
        harness.tick(3);

        // Teleport plus reconnect: the network thread closes the connection before the server reports the quit.
        harness.transport.connected = false; harness.transport.watched = false;
        harness.spawnNpc();
        harness.tick(6);
        String status = harness.service.status();
        assertFalse(status.contains("last-error"), status);
        assertEquals(1, harness.warnings.size(), "the closed connection is logged once with the player and reason: " + harness.warnings);
        assertTrue(harness.warnings.getFirst().contains("Watcher") && harness.warnings.getFirst().contains("Viewer is not connected"), harness.warnings.getFirst());
        assertEquals(0, harness.transport.spawned.get());

        harness.service.quit(harness.player);
        harness.transport.connected = true;
        harness.service.joined(harness.player);
        harness.tick(6);
        assertTrue(harness.transport.spawned.get() >= 1, "the returning player sees the NPC");
        assertFalse(harness.service.status().contains("last-error"));
        assertEquals(1, harness.warnings.size(), harness.warnings.toString());
    }

    @Test
    void aRealTransportErrorIsRecordedAndLoggedOnceWithItsContext() throws IOException {
        Harness harness = harness();
        harness.tick(3);
        harness.transport.spawnFailure = "Viewer presentation ID limit reached";
        harness.spawnNpc();
        harness.tick(3);
        harness.service.quit(harness.player);
        harness.tick(4);

        assertTrue(harness.service.status().contains("last-error=IllegalStateException: Viewer presentation ID limit reached"), harness.service.status());
        List<String> logged = harness.warnings.stream().filter((String line) -> line.contains("Viewer presentation ID limit reached")).toList();
        assertEquals(1, logged.size(), harness.warnings.toString());
        assertTrue(logged.getFirst().contains("spawn") && logged.getFirst().contains("Watcher") && logged.getFirst().contains("race-npc"), logged.getFirst());
    }

    private Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        Harness harness = new Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }

    record Player(UUID id, String name) {}

    static final class Harness {
        final AnimusRuntime runtime;
        final Path npcs;
        final Player player = new Player(UUID.randomUUID(), "Watcher");
        /** Every online player; the first is {@link #player}. */
        final List<Player> online = new ArrayList<>(List.of(player));
        final List<String> warnings = new ArrayList<>();
        final FakeTransport transport = new FakeTransport();
        final FakeHost host = new FakeHost(this);
        final List<Runnable> ticks = new ArrayList<>();
        /** The waypoint indexes the host was told were reached, in order. */
        final List<Integer> reached = new ArrayList<>();
        /** Lifecycle calls the host received, as "spawned id", "despawned id" or "died id", in order. */
        final List<String> lifecycle = new ArrayList<>();
        MotionValidator.Sampler terrain;
        volatile boolean playersAway;
        /** What {@code resolveWorld} answers; every reference names itself by default. */
        java.util.function.Function<String, Optional<String>> worlds = Optional::of;
        /** Chunks the host reports loaded; every chunk by default. */
        volatile java.util.function.BiPredicate<Integer, Integer> loaded = (Integer x, Integer z) -> true;
        art.arcane.animus.core.navigation.DoorPassage.Doors doors = art.arcane.animus.core.navigation.DoorPassage.NONE;
        /** What {@code item} answers for each definition item. */
        java.util.function.Function<NpcItem, Object> items = (NpcItem item) -> new Object();
        PersistedNpcService<Player, Object> service;
        /** Whether the calling thread counts as the owner; while false, {@code execute} queues work in {@link #scheduled}. */
        volatile boolean ownerThread = true;
        final List<Runnable> scheduled = new ArrayList<>();
        Harness(AnimusRuntime runtime, Path npcs) { this.runtime = runtime; this.npcs = npcs; }
        /** Runs the work queued while off the owner thread, as the owner would on its next tick. */
        void runScheduled() {
            boolean was = ownerThread;
            ownerThread = true;
            List<Runnable> work = List.copyOf(scheduled);
            scheduled.clear();
            work.forEach(Runnable::run);
            ownerThread = was;
        }
        void tick(int count) { for (int i = 0; i < count; i++) for (Runnable tick : List.copyOf(ticks)) tick.run(); }
        void spawnNpc() {
            try {
                Files.writeString(npcs.resolve("race-npc.json"), """
                        {"schemaVersion": 1, "revision": 1, "uuid": "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11", "id": "race-npc", "group": "test",
                         "name": "Race", "type": "minecraft:villager", "spawned": true,
                         "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0}}
                        """);
                runtime.npcs().reload();
            } catch (IOException failure) { throw new IllegalStateException(failure); }
            service.refresh();
        }
    }

    static final class FakeBody implements BodyTransport.Body {
        private static final AtomicInteger IDS = new AtomicInteger(1000);
        private final int entityId = IDS.getAndAdd(4);
        private final UUID uuid;
        private final String type;
        FakeBody(UUID uuid, String type) { this.uuid = uuid; this.type = type; }
        @Override public int entityId() { return entityId; }
        @Override public UUID uuid() { return uuid; }
        @Override public String typeKey() { return type; }
        @Override public double width() { return 0.6; }
        @Override public double height() { return 1.8; }
        @Override public SeatGeometry seat() { return new SeatGeometry(0, 1.8, 0, 0); }
        @Override public int[] entityIds() { return new int[] {entityId}; }
    }

    /** Mirrors the packet adapters: once the watch is gone, body packets refuse synchronously. */
    static final class FakeTransport implements BodyTransport<Player, Object> {
        final AtomicInteger watches = new AtomicInteger(), spawned = new AtomicInteger(), forgotten = new AtomicInteger(), closed = new AtomicInteger();
        /** Body packets in the order the runtime sent them: "spawn", "move" or "despawn", a space and the body UUID. */
        final List<String> sent = new ArrayList<>();
        volatile boolean watched, connected = true, equipment, presentation;
        volatile String spawnFailure;
        final AtomicInteger swings = new AtomicInteger(), equipped = new AtomicInteger();
        /** While set, moves stay unfinished in {@link #held}, as on a slow connection. */
        volatile boolean holdMoves;
        final List<CompletableFuture<Void>> held = new ArrayList<>();
        /** The name and appearance of each body created with an appearance, and the text rows of each relabel, in order. */
        final List<NpcPresentation.Label> labels = new ArrayList<>();
        final List<List<String>> relabels = new ArrayList<>();
        /** Looks sent with {@link #look}, in order. */
        final List<art.arcane.animus.core.presentation.BodyLook> looks = new ArrayList<>();
        private void requireWatch() { if (!watched) throw new BodyTransport.ViewerUnwatched("Viewer must be watched before sending body packets"); }
        @Override public Set<String> entityTypes() { return Set.of("minecraft:player", "minecraft:villager"); }
        @Override public String riderUnavailable() { return "test"; }
        @Override public boolean presentationAvailable() { return presentation; }
        @Override public boolean equipmentAvailable() { return equipment; }
        @Override public Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) { return new FakeBody(uuid, typeKey); }
        @Override public Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) {
            labels.add(new NpcPresentation.Label(uuid, typeKey, name, appearance));
            return new FakeBody(uuid, typeKey);
        }
        @Override public CompletionStage<Void> relabel(Player viewer, Body body, List<String> rows) { relabels.add(rows); return CompletableFuture.completedFuture(null); }
        @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) { return 0; }
        @Override public CompletionStage<Void> watch(Player viewer, Consumer<Interaction> interactions) {
            watches.incrementAndGet();
            if (!connected) throw new BodyTransport.ViewerUnwatched("Viewer is not connected");
            watched = true;
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> spawn(Player viewer, Body body, BodyPose pose) {
            requireWatch();
            if (spawnFailure != null) return CompletableFuture.failedFuture(new IllegalStateException(spawnFailure));
            spawned.incrementAndGet(); sent.add("spawn " + body.uuid());
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> move(Player viewer, Body body, BodyPose pose) {
            requireWatch(); sent.add("move " + body.uuid());
            if (!holdMoves) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> waiting = new CompletableFuture<>(); held.add(waiting);
            return waiting;
        }
        @Override public CompletionStage<Void> passengers(Player viewer, Body vehicle, List<? extends Body> passengers, Player realRider, boolean authoritative) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> dragonPhase(Player viewer, Body dragon, int phase) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> equipment(Player viewer, Body body, Map<EquipmentSlot, Object> items) { equipped.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> swing(Player viewer, Body body) { swings.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> look(Player viewer, Body body, art.arcane.animus.core.presentation.BodyLook look) { looks.add(look); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> despawn(Player viewer, Body body) { sent.add("despawn " + body.uuid()); return CompletableFuture.completedFuture(null); }
        @Override public void forget(Player viewer) { forgotten.incrementAndGet(); }
        @Override public ControlSubscription subscribeControl(Player viewer) { throw new IllegalStateException("no control"); }
        @Override public void placeRider(Player rider, double x, double y, double z) {}
        @Override public void close() { closed.incrementAndGet(); }
    }

    static final class FakeHost implements RuntimeHost<Player, Object> {
        private final Harness harness;
        FakeHost(Harness harness) { this.harness = harness; }
        @Override public boolean worldLoaded(String world) { return true; }
        @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) { return harness.loaded.test(chunkX, chunkZ); }
        @Override public MotionValidator.Sampler collision(String world) { return harness.terrain; }
        @Override public boolean ownerThread() { return harness.ownerThread; }
        @Override public boolean execute(Runnable work) {
            if (harness.ownerThread) work.run(); else harness.scheduled.add(work);
            return true;
        }
        @Override public AutoCloseable everyTick(Runnable tick) { harness.ticks.add(tick); return () -> harness.ticks.remove(tick); }
        @Override public void warn(String message) { harness.warnings.add(message); }
        @Override public BodyTransport<Player, Object> openTransport() { return harness.transport; }
        @Override public String chunkDelivery() { return "test"; }
        @Override public Collection<? extends Player> onlinePlayers() { return List.copyOf(harness.online); }
        @Override public Optional<Player> player(UUID id) { return harness.online.stream().filter((Player player) -> player.id().equals(id)).findFirst(); }
        @Override public UUID id(Player player) { return player.id(); }
        @Override public String name(Player player) { return player.name(); }
        @Override public boolean online(Player player) { return true; }
        @Override public boolean dead(Player player) { return false; }
        @Override public boolean sleeping(Player player) { return false; }
        @Override public boolean spectator(Player player) { return false; }
        @Override public boolean sneaking(Player player) { return false; }
        @Override public boolean inVehicle(Player player) { return false; }
        @Override public int entityId(Player player) { return 1; }
        @Override public WorldPosition position(Player player) { return new WorldPosition("world", new Vec3(harness.playersAway ? 5000.5 : 0.5, 64, 0.5)); }
        @Override public Vec3 eye(Player player) { return new Vec3(0.5, 65.62, 0.5); }
        @Override public Vec3 direction(Player player) { return new Vec3(0, 0, 1); }
        @Override public float yaw(Player player) { return 0; }
        @Override public float pitch(Player player) { return 0; }
        @Override public boolean permitted(Player player, String node) { return false; }
        @Override public void tell(Player player, String message) {}
        @Override public boolean teleport(Player player, WorldPosition destination, float yaw, float pitch) { return false; }
        @Override public Optional<String> resolveWorld(String reference) { return harness.worlds.apply(reference); }
        @Override public boolean chunkSent(Player player, int chunkX, int chunkZ) { return true; }
        @Override public boolean clearSight(Player player, Vec3 eye, Vec3 direction, double distance) { return true; }
        @Override public art.arcane.animus.core.navigation.DoorPassage.Doors doors() { return harness.doors; }
        @Override public boolean solid(String world, int x, int y, int z) { return y < 64; }
        @Override public boolean registeredEntity(UUID uuid) { return false; }
        @Override public Object item(NpcItem item, String path, List<String> errors) { return harness.items.apply(item); }
        @Override public Object copy(Object item) { return item; }
        @Override public boolean interactionAllowed(Player player, UUID npc, Interaction action, double distance) { return true; }
        @Override public void waypointReached(UUID npc, String id, UUID route, int waypoint, WorldPosition at) { harness.reached.add(waypoint); }
        @Override public void npcSpawned(UUID npc, String id, WorldPosition at) { harness.lifecycle.add("spawned " + id); }
        @Override public void npcDespawned(UUID npc, String id) { harness.lifecycle.add("despawned " + id); }
        @Override public void npcDied(UUID npc, String id, WorldPosition at, Optional<art.arcane.animus.core.behavior.trigger.TriggerEvent.Attacker> killer) {
            harness.lifecycle.add("died " + id + killer.map((art.arcane.animus.core.behavior.trigger.TriggerEvent.Attacker who) -> " by " + who.kind()).orElse(""));
        }
    }
}
