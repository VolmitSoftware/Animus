package art.arcane.animus.bukkit.recording;

import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingFrame;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordingCaptureTest {
    private static final UUID RECORDER = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final NpcItem SWORD = new NpcItem("minecraft:iron_sword", 1, Optional.empty(), Optional.empty());

    static Map<EquipmentSlot, Optional<NpcItem>> empty() {
        Map<EquipmentSlot, Optional<NpcItem>> slots = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : RecordingEvent.SLOTS) slots.put(slot, Optional.empty());
        return slots;
    }

    static RecordingCapture.Sample at(double x, double y, double z) { return at("world", x, y, z, false, true, empty()); }

    static RecordingCapture.Sample at(String world, double x, double y, double z, boolean sneaking, boolean onGround, Map<EquipmentSlot, Optional<NpcItem>> equipment) {
        return new RecordingCapture.Sample(world, x, y, z, 90, 0, onGround, sneaking, false, false, false, equipment);
    }

    /** Runs {@code ticks} ticks, sampling a walk along x whenever a sample is due. Returns the end, if one came. */
    static Optional<RecordingCapture.End> walk(RecordingCapture capture, int ticks, int maximumTicks) {
        for (int i = 0; i < ticks; i++) {
            if (!capture.advance()) continue;
            Optional<RecordingCapture.End> end = capture.sample(at(100.5 + capture.tick() * 0.1, 64, 0.5), maximumTicks);
            if (end.isPresent()) return end;
        }
        return Optional.empty();
    }

    @Test void shouldSampleEverySampleTicksRelativeToTheOriginWhenWalking() {
        RecordingCapture capture = new RecordingCapture("walk", RECORDER, 2, at(100.5, 64, 0.5));
        assertEquals(Optional.empty(), walk(capture, 40, 12_000));
        assertEquals(21, capture.frames());
        RecordingDocument document = capture.finish().orElseThrow().document(1);
        assertEquals(40, document.durationTicks());
        assertEquals(new RecordingDocument.Origin(100.5, 64, 0.5, 90, 0), document.origin());
        assertEquals(0, document.frames().getFirst().tick());
        RecordingFrame last = document.frames().getLast();
        assertEquals(40, last.tick());
        assertEquals(4.0, last.dx(), 1e-9);
        assertTrue(document.frames().size() < 21, "a straight walk simplifies to fewer frames");
        assertEquals(6, document.events().size(), "the starting equipment is written at tick 0 for every slot");
        for (RecordingEvent event : document.events()) assertEquals(0, event.tick());
    }

    @Test void shouldRecordFlagsAndAJumpWhenTheRecorderLeavesTheGroundRising() {
        RecordingCapture capture = new RecordingCapture("jump", RECORDER, 1, at(0.5, 64, 0.5));
        capture.advance();
        capture.sample(at("world", 0.5, 64, 0.5, true, true, empty()), 12_000);
        capture.advance();
        capture.sample(at("world", 0.5, 64.4, 0.5, false, false, empty()), 12_000);
        List<RecordingFrame> frames = capture.rawFrames();
        assertTrue(frames.get(1).sneaking());
        assertTrue(frames.get(1).onGround());
        assertTrue(frames.get(2).jumping());
        assertFalse(frames.get(2).onGround());
    }

    @Test void shouldAddSwingUseAndEquipmentChangesAtTheSessionTick() {
        RecordingCapture capture = new RecordingCapture("events", RECORDER, 2, at(100.5, 64, 0.5));
        capture.advance();
        capture.swing();
        capture.use(3, 64, 1, "minecraft:oak_door");
        capture.advance();
        Map<EquipmentSlot, Optional<NpcItem>> armed = empty();
        armed.put(EquipmentSlot.MAIN_HAND, Optional.of(SWORD));
        capture.sample(at("world", 100.6, 64, 0.5, false, true, armed), 12_000);
        walk(capture, 30, 12_000);
        RecordingDocument document = capture.finish().orElseThrow().document(1);
        List<RecordingEvent> late = document.events().stream().filter((RecordingEvent event) -> event.tick() > 0 && event.tick() <= 2).toList();
        assertEquals(List.of(new RecordingEvent.Swing(1), new RecordingEvent.Use(1, 3, 64, 1, "minecraft:oak_door"),
                new RecordingEvent.Equipment(2, EquipmentSlot.MAIN_HAND, Optional.of(SWORD))), late);
        assertTrue(document.frames().stream().anyMatch((RecordingFrame frame) -> frame.tick() == 2), "a frame is kept at every event tick");
    }

    @Test void shouldEndAtMaximumTicksWithTheLastFrameInsideTheLimit() {
        RecordingCapture capture = new RecordingCapture("limit", RECORDER, 3, at(100.5, 64, 0.5));
        assertEquals(Optional.of(RecordingCapture.End.LIMIT), walk(capture, 1000, 200));
        RecordingDocument document = capture.finish().orElseThrow().document(1);
        assertTrue(document.durationTicks() <= 200);
        assertTrue(document.durationTicks() > 200 - 3);
    }

    @Test void shouldEndWithoutTheFrameWhenTheRecorderTeleportsOrChangesWorld() {
        RecordingCapture capture = new RecordingCapture("jumped", RECORDER, 2, at(100.5, 64, 0.5));
        walk(capture, 30, 12_000);
        int frames = capture.frames();
        capture.advance(); capture.advance();
        assertEquals(Optional.of(RecordingCapture.End.TELEPORT), capture.sample(at(40, 64, 0.5), 12_000));
        assertEquals(frames, capture.frames());
        RecordingCapture other = new RecordingCapture("nether", RECORDER, 2, at(0.5, 64, 0.5));
        other.advance(); other.advance();
        assertEquals(Optional.of(RecordingCapture.End.WORLD), other.sample(at("world_nether", 0.5, 64, 0.5, false, true, empty()), 12_000));
    }

    @Test void shouldDiscardARecordingShorterThanTwentyTicks() {
        RecordingCapture capture = new RecordingCapture("short", RECORDER, 2, at(100.5, 64, 0.5));
        walk(capture, 19, 12_000);
        assertTrue(capture.finish().isEmpty());
        walk(capture, 1, 12_000);
        assertTrue(capture.finish().isPresent());
    }

    @Test void shouldClampLateEventsToTheLastFrame() {
        RecordingCapture capture = new RecordingCapture("late", RECORDER, 4, at(100.5, 64, 0.5));
        walk(capture, 24, 12_000);
        capture.advance();
        capture.swing();
        RecordingDocument document = capture.finish().orElseThrow().document(1);
        assertEquals(new RecordingEvent.Swing(24), document.events().getLast());
    }

    @Test void shouldStopAddingEventsAtTheDocumentLimit() {
        RecordingCapture capture = new RecordingCapture("busy", RECORDER, 2, at(0.5, 64, 0.5));
        for (int i = 0; i < RecordingDocument.MAXIMUM_EVENTS + 10; i++) capture.swing();
        assertEquals(RecordingDocument.MAXIMUM_EVENTS, capture.events());
        assertEquals(16, capture.dropped());
    }

    @Test void shouldOfferAGhostEveryIntervalStartingTheStretchBehindAndSkipWhileOneRuns() {
        RecordingSettings settings = RecordingSettings.DEFAULT;
        RecordingCapture capture = new RecordingCapture("ghost", RECORDER, 2, at(100.5, 64, 0.5));
        int offered = 0, skipped = 0;
        Optional<RecordingDocument> first = Optional.empty();
        for (int tick = 1; tick <= 300; tick++) {
            if (capture.advance()) capture.sample(at(100.5 + capture.tick() * 0.1, 64, 0.5), 12_000);
            boolean running = offered == 1;
            Optional<RecordingDocument> ghost = capture.ghost(settings.ghostIntervalTicks(), settings.ghostStretchTicks(), running);
            if (ghost.isPresent()) { offered++; if (first.isEmpty()) first = ghost; assertEquals(100, tick, "the first ghost comes one interval in"); }
            else if (tick % 100 == 0) skipped++;
        }
        assertEquals(1, offered, "the replay still running at ticks 200 and 300 is not doubled");
        assertEquals(2, skipped);
        assertEquals(2, capture.ghostsSkipped());
        RecordingDocument stretch = first.orElseThrow();
        assertEquals(100, stretch.durationTicks());
        assertEquals(0.0, stretch.frames().getFirst().dx(), 1e-9, "the stretch starts at the frame 100 ticks ago, the start here");
        assertEquals(10.0, stretch.frames().getLast().dx(), 1e-9);
    }

    @Test void shouldStartTheStretchAtTheFrameStretchTicksAgoWithTheEquipmentWornThen() {
        RecordingCapture capture = new RecordingCapture("ghost", RECORDER, 2, at(100.5, 64, 0.5));
        Map<EquipmentSlot, Optional<NpcItem>> armed = empty();
        armed.put(EquipmentSlot.MAIN_HAND, Optional.of(SWORD));
        Optional<RecordingDocument> ghost = Optional.empty();
        for (int tick = 1; tick <= 200 && ghost.isEmpty(); tick++) {
            if (capture.advance()) capture.sample(at("world", 100.5 + capture.tick() * 0.1, 64, 0.5, false, true, tick >= 10 ? armed : empty()), 12_000);
            if (tick > 100) ghost = capture.ghost(150, 60, false);
        }
        RecordingDocument stretch = ghost.orElseThrow();
        assertEquals(60, stretch.durationTicks());
        assertEquals(9.0, stretch.frames().getFirst().dx(), 1e-9, "tick 90 is 9 blocks from the origin");
        assertEquals(new RecordingEvent.Equipment(0, EquipmentSlot.MAIN_HAND, Optional.of(SWORD)), stretch.events().getFirst());
    }
}
