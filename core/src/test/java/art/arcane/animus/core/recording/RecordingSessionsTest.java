package art.arcane.animus.core.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.virtual.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared session wiring every platform backs, driven through a fake host whose owner is the test thread. */
class RecordingSessionsTest {
    static final class Player {
        final UUID id = UUID.randomUUID();
        boolean online = true, dead, sneaking;
        String world = "minecraft:overworld";
        double x = 0.5, y = 64, z = 0.5;
    }

    static final class Host implements RecordingHost<Player, String> {
        final List<Player> players = new ArrayList<>();
        final List<Runnable> ticking = new CopyOnWriteArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        @Override public UUID id(Player player) { return player.id; }
        @Override public String name(Player player) { return "Recorder"; }
        @Override public boolean online(Player player) { return player.online; }
        @Override public boolean dead(Player player) { return player.dead; }
        @Override public String world(Player player) { return player.world; }
        @Override public Vec3 position(Player player) { return new Vec3(player.x, player.y, player.z); }
        @Override public boolean admin(Player player) { return true; }
        @Override public Supplier<RecordingCapture.Sample> sampler(Player player) {
            return () -> new RecordingCapture.Sample(player.world, player.x, player.y, player.z, 90, 0, true, player.sneaking, false, false, false, Map.of());
        }
        @Override public Optional<BodySkin> skin(Player player) { return Optional.empty(); }
        @Override public Optional<Player> player(UUID id) { return players.stream().filter((Player player) -> player.id.equals(id) && player.online).findFirst(); }
        @Override public Optional<String> knownName(UUID id) { return Optional.empty(); }
        @Override public void later(Player player, Runnable work, Runnable gone) { later.add(work); }
        @Override public AutoCloseable everyTick(Player player, Runnable tick, Runnable retired) { ticking.add(tick); return () -> ticking.remove(tick); }
        @Override public Optional<String> ghostsUnavailable() { return Optional.of("no packets in tests"); }
        @Override public GhostReplay<Player, String> ghost(Player viewer, RecordingDocument document, String name, Optional<BodySkin> skin, RecordingSettings settings) {
            throw new IllegalStateException("ghosts are unavailable");
        }
        @Override public void release(Player viewer) {}
        @Override public void closeGhosts() {}
        @Override public void info(String message) { log.add(message); }
        @Override public void warn(String message) { log.add(message); }

        void tick() {
            for (Runnable tick : ticking) tick.run();
            List<Runnable> due = new ArrayList<>(later);
            later.clear();
            due.forEach(Runnable::run);
        }
    }

    static final class Actor implements CommandActor {
        final List<String> lines = new ArrayList<>();
        final Optional<UUID> player;
        Actor(Optional<UUID> player) { this.player = player; }
        @Override public boolean permitted(String node) { return true; }
        @Override public void tell(String message) { lines.add(message); }
        @Override public Optional<UUID> player() { return player; }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
        String last() { return lines.getLast(); }
    }

    @TempDir Path root;
    private AnimusRuntime runtime;
    private final Host host = new Host();
    private RecordingSessions<Player, String> sessions;

    @BeforeEach void start() throws Exception {
        runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root));
        runtime.start();
        sessions = new RecordingSessions<>(host, runtime, Runnable::run);
    }

    @AfterEach void stop() {
        sessions.close();
        runtime.close();
    }

    private Player join() { Player player = new Player(); host.players.add(player); return player; }

    private Path saved(String id) throws java.io.IOException { return runtime.documents().resolve("recordings").resolve(id + ".json"); }

    @Test void shouldSaveFramesSwingsAndDoorUseWhenStopped() throws Exception {
        Player player = join();
        Actor actor = new Actor(Optional.of(player.id));
        sessions.start(actor, "demo");
        assertTrue(actor.last().startsWith("Recording demo: sampling every 2 ticks"), actor.last());
        for (int tick = 0; tick < 40; tick++) {
            player.x += 0.2;
            player.sneaking = tick >= 20;
            if (tick == 10) sessions.swung(player);
            if (tick == 12) sessions.used(player, 3, 64, 0, "minecraft:oak_door");
            host.tick();
        }
        sessions.stop(actor);
        host.tick();
        assertTrue(actor.last().startsWith("Recording demo saved: 40 ticks"), actor.last());
        RecordingDocument document = RecordingDocument.parse(Files.readString(saved("demo")), RecordingSettings.DEFAULT.maximumFileBytes());
        assertEquals(player.id, document.recordedBy());
        assertEquals("minecraft:overworld", document.world());
        assertTrue(document.frames().size() >= 2);
        assertTrue(document.events().stream().anyMatch((RecordingEvent event) -> event instanceof RecordingEvent.Swing swing && swing.tick() == 10));
        assertTrue(document.events().stream().anyMatch((RecordingEvent event) -> event instanceof RecordingEvent.Use use && use.block().equals("minecraft:oak_door")));
        assertTrue(document.frames().stream().anyMatch((RecordingFrame frame) -> (frame.flags() & RecordingFrame.SNEAKING) != 0));
        assertTrue(host.ticking.isEmpty(), "the session stops ticking once saved");
    }

    @Test void shouldSaveWhatExistsWhenTheRecorderQuitsAndDiscardShortTakes() throws Exception {
        Player player = join();
        Actor actor = new Actor(Optional.of(player.id));
        sessions.start(actor, "walk");
        for (int tick = 0; tick < 30; tick++) { player.x += 0.1; host.tick(); }
        player.online = false;
        host.tick();
        host.tick();
        assertTrue(Files.isRegularFile(saved("walk")));
        assertTrue(host.log.stream().anyMatch((String line) -> line.contains("saved (the recorder left)")), host.log.toString());

        Player other = join();
        Actor second = new Actor(Optional.of(other.id));
        sessions.start(second, "short");
        for (int tick = 0; tick < 4; tick++) host.tick();
        sessions.stop(second);
        assertTrue(second.last().contains("discarded: under 20 ticks"), second.last());
        assertFalse(Files.exists(saved("short")));
    }

    @Test void shouldEndOnALongTeleportAndRefuseTheConsoleAndASecondSession() {
        Player player = join();
        Actor actor = new Actor(Optional.of(player.id));
        sessions.start(actor, "jump");
        sessions.start(actor, "again");
        assertTrue(actor.last().contains("already recording"), actor.last());
        for (int tick = 0; tick < 30; tick++) { player.x += 0.1; host.tick(); }
        player.x += 20;
        host.tick();
        host.tick();
        host.tick();
        assertTrue(actor.last().contains("moved more than 8 blocks"), actor.last());

        Actor console = new Actor(Optional.empty());
        sessions.start(console, "console");
        assertEquals("Only a player can start a recording.", console.last());
        assertTrue(sessions.status(console).getFirst().startsWith("Recordings enabled | sessions 0/4"), sessions.status(console).toString());
    }

    @Test void shouldRefusePreviewsWithoutGhosts() {
        Player player = join();
        Actor actor = new Actor(Optional.of(player.id));
        sessions.preview(actor, "demo");
        assertEquals("Previews are unavailable: no packets in tests", actor.last());
    }
}
