package art.arcane.animus.core.recording;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingPlaybackTest {
    private static final UUID RECORDER = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final NpcItem SWORD = new NpcItem("minecraft:iron_sword", 1, Optional.empty(), Optional.empty());
    private static final NpcItem AXE = new NpcItem("minecraft:iron_axe", 1, Optional.empty(), Optional.empty());

    private static RecordingDocument document(List<RecordingEvent> events) {
        return new RecordingDocument(1, "walk", RECORDER, "world", new RecordingDocument.Origin(10, 64, 10, 170, 0), 2, 4,
                List.of(new RecordingFrame(0, 0, 0, 0, 170, 0, RecordingFrame.ON_GROUND), new RecordingFrame(4, 4, 2, 0, -170, 20, RecordingFrame.SNEAKING)),
                events);
    }

    private static RecordingDocument simple() {
        return document(List.of(new RecordingEvent.Swing(0), new RecordingEvent.Swing(2), new RecordingEvent.Use(4, 1, 64, 1, "minecraft:oak_door")));
    }

    @Test void poseInterpolatesPositionLinearlyAndYawOnTheShortArc() {
        RecordingPlayback playback = new RecordingPlayback(simple(), Playback.ONCE, 0);
        RecordingPose start = playback.pose();
        assertEquals(10, start.x());
        assertEquals(64, start.y());
        assertEquals(170, start.yaw());
        assertTrue(start.onGround());
        playback.advance();
        RecordingPose quarter = playback.pose();
        assertEquals(11, quarter.x(), 1e-9);
        assertEquals(64.5, quarter.y(), 1e-9);
        assertEquals(175, quarter.yaw(), 1e-9);
        assertEquals(5, quarter.pitch(), 1e-9);
        playback.advance();
        assertEquals(-180, playback.pose().yaw(), 1e-9);
        playback.advance();
        RecordingPose late = playback.pose();
        assertEquals(-175, late.yaw(), 1e-9);
        assertEquals(RecordingFrame.ON_GROUND, late.flags(), "flags hold the earlier frame's");
        playback.advance();
        RecordingPose end = playback.pose();
        assertEquals(14, end.x(), 1e-9);
        assertEquals(-170, end.yaw(), 1e-9);
        assertTrue(end.sneaking());
        assertFalse(end.onGround());
    }

    @Test void onceStopsAtTheEndAndFinishesWhenItsEventsAreTaken() {
        RecordingPlayback playback = new RecordingPlayback(simple(), Playback.ONCE, 0);
        assertEquals(List.of(new RecordingEvent.Swing(0)), playback.due());
        assertEquals(List.of(), playback.due());
        playback.advance();
        playback.advance();
        assertEquals(List.of(new RecordingEvent.Swing(2)), playback.due());
        playback.advance();
        playback.advance();
        assertEquals(4, playback.cursor());
        assertFalse(playback.finished(), "the use at the last tick is still due");
        assertEquals(List.of(new RecordingEvent.Use(4, 1, 64, 1, "minecraft:oak_door")), playback.due());
        assertTrue(playback.finished());
        playback.advance();
        assertEquals(4, playback.cursor());
        assertEquals(List.of(), playback.due());
    }

    @Test void loopReturnsToTheFirstFrameAndRepeatsItsEvents() {
        RecordingPlayback playback = new RecordingPlayback(simple(), Playback.LOOP, 3);
        assertEquals(List.of(), playback.due());
        playback.advance();
        playback.advance();
        assertEquals(0, playback.cursor());
        assertEquals(RecordingPlayback.Direction.FORWARD, playback.direction());
        assertEquals(List.of(new RecordingEvent.Use(4, 1, 64, 1, "minecraft:oak_door"), new RecordingEvent.Swing(0)), playback.due());
        assertFalse(playback.finished());
    }

    @Test void pingpongTurnsAtBothEnds() {
        RecordingPlayback playback = new RecordingPlayback(simple(), Playback.PINGPONG, 0);
        List<Integer> cursors = new ArrayList<>();
        for (int i = 0; i < 10; i++) { playback.advance(); cursors.add(playback.cursor()); }
        assertEquals(List.of(1, 2, 3, 4, 3, 2, 1, 0, 1, 2), cursors);
        assertEquals(RecordingPlayback.Direction.FORWARD, playback.direction());
        playback.advance();
        playback.advance();
        playback.advance();
        assertEquals(3, playback.cursor());
        assertEquals(RecordingPlayback.Direction.BACKWARD, playback.direction());
        assertFalse(playback.finished());
    }

    @Test void backwardEquipmentRestoresTheSlotsEarlierItem() {
        RecordingDocument document = document(List.of(new RecordingEvent.Equipment(1, EquipmentSlot.MAIN_HAND, Optional.of(SWORD)),
                new RecordingEvent.Equipment(3, EquipmentSlot.MAIN_HAND, Optional.of(AXE)), new RecordingEvent.Swing(3)));
        RecordingPlayback playback = new RecordingPlayback(document, Playback.PINGPONG, 4);
        playback.advance();
        assertEquals(3, playback.cursor());
        assertEquals(List.of(new RecordingEvent.Swing(3), new RecordingEvent.Equipment(3, EquipmentSlot.MAIN_HAND, Optional.of(SWORD))), playback.due());
        playback.advance();
        playback.advance();
        assertEquals(1, playback.cursor());
        assertEquals(List.of(), playback.due(), "no earlier item in the slot, so nothing to restore");
    }

    @Test void dueHandsOutAtMostEightEventsAndKeepsTheRest() {
        List<RecordingEvent> events = new ArrayList<>();
        for (int i = 0; i < 11; i++) events.add(new RecordingEvent.Swing(1));
        RecordingPlayback playback = new RecordingPlayback(document(events), Playback.ONCE, 0);
        playback.advance();
        assertEquals(RecordingPlayback.EVENTS_PER_CALL, playback.due().size());
        playback.advance();
        assertEquals(3, playback.due().size());
        assertEquals(List.of(), playback.due());
    }

    @Test void skipCarriesOnlyTheLastEquipmentPerSlot() {
        RecordingDocument document = document(List.of(new RecordingEvent.Equipment(1, EquipmentSlot.MAIN_HAND, Optional.of(SWORD)),
                new RecordingEvent.Equipment(2, EquipmentSlot.HEAD, Optional.empty()), new RecordingEvent.Swing(2),
                new RecordingEvent.Equipment(3, EquipmentSlot.MAIN_HAND, Optional.of(AXE)), new RecordingEvent.Use(3, 0, 64, 0, "minecraft:oak_door")));
        RecordingPlayback playback = new RecordingPlayback(document, Playback.ONCE, 0);
        playback.skip(3);
        assertEquals(3, playback.cursor());
        assertEquals(List.of(new RecordingEvent.Equipment(2, EquipmentSlot.HEAD, Optional.empty()),
                new RecordingEvent.Equipment(3, EquipmentSlot.MAIN_HAND, Optional.of(AXE))), playback.due());
        playback.skip(40);
        assertEquals(4, playback.cursor());
        assertTrue(playback.finished());
        RecordingPlayback looping = new RecordingPlayback(simple(), Playback.LOOP, 0);
        looping.skip(5 * 1000 + 2);
        assertEquals(2, looping.cursor());
        assertThrows(IllegalArgumentException.class, () -> looping.skip(-1));
    }

    @Test void stateSurvivesAHandoff() {
        RecordingPlayback playback = new RecordingPlayback(simple(), Playback.PINGPONG, 0);
        for (int i = 0; i < 6; i++) playback.advance();
        RecordingPlayback.State state = playback.state();
        assertEquals(new RecordingPlayback.State(2, RecordingPlayback.Direction.BACKWARD, Playback.PINGPONG), state);
        RecordingPlayback resumed = RecordingPlayback.resume(simple(), state);
        assertEquals(2, resumed.cursor());
        assertEquals(RecordingPlayback.Direction.BACKWARD, resumed.direction());
        assertEquals(Playback.PINGPONG, resumed.mode());
        assertEquals(List.of(), resumed.due(), "events at the cursor already ran on the old owner");
        resumed.advance();
        playback.advance();
        assertEquals(playback.pose(), resumed.pose());
    }

    @Test void invalidStartsAndStatesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingPlayback(simple(), Playback.LOOP, 5));
        assertThrows(IllegalArgumentException.class, () -> new RecordingPlayback(simple(), Playback.LOOP, -1));
        assertThrows(IllegalArgumentException.class, () -> RecordingPlayback.resume(simple(), new RecordingPlayback.State(1, RecordingPlayback.Direction.BACKWARD, Playback.ONCE)));
        assertThrows(IllegalArgumentException.class, () -> RecordingPlayback.resume(simple(), new RecordingPlayback.State(9, RecordingPlayback.Direction.FORWARD, Playback.LOOP)));
    }

    @Test void playbackKeysParse() {
        assertEquals(Optional.of(Playback.PINGPONG), Playback.parse("pingpong"));
        assertEquals(Optional.empty(), Playback.parse("PINGPONG"));
        assertEquals("loop", Playback.LOOP.key());
        assertEquals(Playback.LOOP, Playback.DEFAULT);
    }
}
