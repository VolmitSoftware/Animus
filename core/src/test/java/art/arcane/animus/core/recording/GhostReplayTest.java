package art.arcane.animus.core.recording;

import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GhostReplayTest {
    private static final UUID RECORDER = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final NpcItem SWORD = new NpcItem("minecraft:iron_sword", 1, Optional.empty(), Optional.empty());

    /** A forward walk along x of {@code duration} ticks sampled every 2, sneaking from {@code sneakFrom}, with a swing at tick 10. */
    static RecordingDocument walk(int duration, int sneakFrom) {
        List<RecordingFrame> frames = new ArrayList<>();
        for (int tick = 0; tick <= duration; tick += 2)
            frames.add(new RecordingFrame(tick, tick * 0.1, 0, 0, 90, 0, RecordingFrame.ON_GROUND | (tick >= sneakFrom ? RecordingFrame.SNEAKING : 0)));
        List<RecordingEvent> events = List.of(new RecordingEvent.Equipment(0, EquipmentSlot.MAIN_HAND, Optional.of(SWORD)), new RecordingEvent.Swing(10));
        return new RecordingDocument(1, "walk", RECORDER, "world", new RecordingDocument.Origin(0.5, 64, 0.5, 90, 0), 2, duration, frames, events);
    }

    @Test void shouldSpawnMoveOncePerTickSwingDressAndDespawnAtTheEnd() {
        FakeTransport transport = new FakeTransport();
        GhostReplay<String, String> replay = new GhostReplay<>(transport, "viewer", walk(40, 1000), transport.bodies(), Runnable::run);
        int ticks = 0;
        while (!replay.finished() && ticks < 100) { replay.tick(); ticks++; }
        assertEquals(41, ticks, "a 40 tick stretch plays at recorded speed, tick 0 to 40");
        assertEquals(1, transport.count("spawn"));
        assertEquals(40, transport.count("move"));
        assertEquals(1, transport.count("swing"));
        assertEquals(1, transport.count("despawn"));
        assertEquals(List.of("main_hand=minecraft:iron_sword"), transport.equipment);
        assertEquals("spawn", transport.sent.getFirst().split(" ")[0]);
        assertEquals("despawn", transport.sent.getLast().split(" ")[0]);
        assertEquals(NpcPose.STANDING, transport.poses.getFirst());
        assertEquals(4.5, transport.last.x(), 1e-9);
    }

    @Test void shouldHoldMovesUntilTheSpawnLandsAndKeepOneMoveInFlight() {
        FakeTransport transport = new FakeTransport();
        transport.hold = true;
        GhostReplay<String, String> replay = new GhostReplay<>(transport, "viewer", walk(40, 1000), transport.bodies(), Runnable::run);
        for (int i = 0; i < 5; i++) replay.tick();
        assertEquals(List.of("spawn"), transport.kinds(), "nothing moves a body the client has not been sent");
        transport.release();
        replay.tick();
        replay.tick();
        replay.tick();
        assertEquals(1, transport.count("move"), "a move still in flight is not stacked behind another");
        transport.release();
        replay.tick();
        assertEquals(2, transport.count("move"));
        assertTrue(transport.peak <= 2);
    }

    @Test void shouldRespawnWithAFreshIdentityWhenTheRecordedPoseChanges() {
        FakeTransport transport = new FakeTransport();
        GhostReplay<String, String> replay = new GhostReplay<>(transport, "viewer", walk(40, 20), transport.bodies(), Runnable::run);
        while (!replay.finished()) replay.tick();
        assertEquals(2, transport.count("spawn"));
        assertEquals(List.of(NpcPose.STANDING, NpcPose.SNEAKING), transport.poses);
        List<String> spawned = transport.sent.stream().filter((String line) -> line.startsWith("spawn ")).toList();
        assertNotEquals(spawned.get(0), spawned.get(1));
        assertEquals(2, transport.equipment.size(), "the respawned body is dressed again");
    }

    @Test void shouldStopQuietlyWhenTheViewerIsNoLongerWatched() {
        FakeTransport transport = new FakeTransport();
        GhostReplay<String, String> replay = new GhostReplay<>(transport, "viewer", walk(40, 1000), transport.bodies(), Runnable::run);
        replay.tick();
        transport.watched = false;
        replay.tick();
        assertTrue(replay.finished());
        replay.close();
        replay.tick();
        assertEquals(0, transport.count("move"));
    }

    @Test void shouldCloseWithADespawn() {
        FakeTransport transport = new FakeTransport();
        GhostReplay<String, String> replay = new GhostReplay<>(transport, "viewer", walk(40, 1000), transport.bodies(), Runnable::run);
        replay.tick();
        replay.tick();
        replay.close();
        assertTrue(replay.finished());
        assertEquals("despawn", transport.sent.getLast().split(" ")[0]);
        replay.close();
        assertEquals(1, transport.count("despawn"));
    }

    /** The session's order each tick: sample, offer a ghost unless one runs, then tick the ghost. */
    @Test void shouldReplayBehindTheRecorderEveryIntervalAndSkipTheIntervalWhileOneRuns() {
        RecordingSettings settings = RecordingSettings.DEFAULT;
        FakeTransport transport = new FakeTransport();
        RecordingCapture capture = new RecordingCapture("ghost", RECORDER, 2, RecordingCaptureTest.at(100.5, 64, 0.5));
        GhostReplay<String, String> replay = null;
        List<Integer> appeared = new ArrayList<>();
        for (int tick = 1; tick <= 320; tick++) {
            if (capture.advance()) capture.sample(RecordingCaptureTest.at(100.5 + capture.tick() * 0.1, 64, 0.5), 12_000);
            boolean running = replay != null && !replay.finished();
            Optional<RecordingDocument> stretch = capture.ghost(settings.ghostIntervalTicks(), settings.ghostStretchTicks(), running);
            if (stretch.isPresent()) { replay = new GhostReplay<>(transport, "viewer", stretch.get(), transport.bodies(), Runnable::run); appeared.add(tick); }
            if (replay != null) replay.tick();
        }
        assertEquals(List.of(100, 300), appeared, "the replay started at 100 still plays its last tick at 200");
        assertEquals(1, capture.ghostsSkipped());
        assertEquals(2, transport.count("spawn"));
        assertEquals(1, transport.count("despawn"), "the second ghost is still walking");
        assertTrue(transport.last.x() < 100.5 + 32, "the ghost walks behind the recorder");
    }

    static final class FakeTransport implements BodyTransport<String, String> {
        final List<String> sent = new ArrayList<>(), equipment = new ArrayList<>();
        final List<NpcPose> poses = new ArrayList<>();
        final List<CompletableFuture<Void>> held = new ArrayList<>();
        boolean hold, watched = true;
        int inFlight, peak;
        BodyPose last;

        GhostReplay.Bodies<String> bodies() {
            return new GhostReplay.Bodies<>() {
                @Override public Body create(NpcPose pose) { poses.add(pose); return new FakeBody(UUID.randomUUID()); }
                @Override public String item(Optional<NpcItem> item) { return item.map(NpcItem::id).orElse("air"); }
                @Override public String world() { return "world-id"; }
            };
        }
        int count(String kind) { return (int) sent.stream().filter((String line) -> line.startsWith(kind + " ")).count(); }
        List<String> kinds() { return sent.stream().map((String line) -> line.split(" ")[0]).toList(); }
        void release() {
            List<CompletableFuture<Void>> waiting = new ArrayList<>(held);
            held.clear();
            for (CompletableFuture<Void> future : waiting) future.complete(null);
        }
        private CompletionStage<Void> send(String kind, Body body) {
            if (!watched) return CompletableFuture.failedFuture(new BodyTransport.ViewerUnwatched("not watched"));
            sent.add(kind + " " + body.uuid());
            CompletableFuture<Void> result = new CompletableFuture<>();
            inFlight++;
            peak = Math.max(peak, inFlight);
            result.whenComplete((Void nothing, Throwable failure) -> inFlight--);
            if (hold) held.add(result); else result.complete(null);
            return result;
        }
        @Override public Set<String> entityTypes() { return Set.of("minecraft:player"); }
        @Override public String riderUnavailable() { return "test"; }
        @Override public boolean presentationAvailable() { return true; }
        @Override public boolean equipmentAvailable() { return true; }
        @Override public Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) { return new FakeBody(uuid); }
        @Override public Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) { return new FakeBody(uuid); }
        @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) { return 0; }
        @Override public CompletionStage<Void> watch(String viewer, Consumer<Interaction> interactions) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> spawn(String viewer, Body body, BodyPose pose) { last = pose; return send("spawn", body); }
        @Override public CompletionStage<Void> move(String viewer, Body body, BodyPose pose) { last = pose; return send("move", body); }
        @Override public CompletionStage<Void> swing(String viewer, Body body) { return send("swing", body); }
        @Override public CompletionStage<Void> passengers(String viewer, Body vehicle, List<? extends Body> passengers, String realRider, boolean authoritative) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> dragonPhase(String viewer, Body dragon, int phase) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> equipment(String viewer, Body body, Map<EquipmentSlot, String> items) {
            for (Map.Entry<EquipmentSlot, String> item : items.entrySet()) equipment.add(item.getKey().key() + "=" + item.getValue());
            return send("equipment", body);
        }
        @Override public CompletionStage<Void> despawn(String viewer, Body body) { sent.add("despawn " + body.uuid()); return CompletableFuture.completedFuture(null); }
        @Override public void forget(String viewer) {}
        @Override public ControlSubscription subscribeControl(String viewer) { throw new IllegalStateException("no control"); }
        @Override public void placeRider(String rider, double x, double y, double z) {}
        @Override public void close() {}
    }

    record FakeBody(UUID uuid) implements BodyTransport.Body {
        @Override public int entityId() { return 1; }
        @Override public String typeKey() { return "minecraft:player"; }
        @Override public double width() { return 0.6; }
        @Override public double height() { return 1.8; }
        @Override public SeatGeometry seat() { return null; }
        @Override public int[] entityIds() { return new int[]{1}; }
    }
}
