package art.arcane.animus.core.recording;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingRunTest {
    private static final RecordingDocument LINE = new RecordingDocument(1, "walk", UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), "world",
            new RecordingDocument.Origin(0, 64, 0, 0, 0), 2, 1000,
            List.of(new RecordingFrame(0, 0, 0, 0, 0, 0, RecordingFrame.ON_GROUND), new RecordingFrame(1000, 100, 0, 0, 0, 0, RecordingFrame.ON_GROUND)), List.of());
    private static final RecordingSource ONCE = new RecordingSource("walk", java.util.Optional.of(Playback.ONCE));

    @Test void aRunWalksInToItsCursorThenPlays() {
        RecordingRun run = new RecordingRun(ONCE, LINE);
        assertEquals(RecordingRun.Phase.WALKING_IN, run.phase());
        assertTrue(run.walking());
        assertEquals(0, run.goal().x(), 1e-9);
        run.arrived();
        assertEquals(RecordingRun.Phase.PLAYING, run.phase());
        assertEquals("recording=walk playback=once cursor=0/1000 state=playing", run.describe());
    }

    @Test void aRefusedStepAimsFortyTicksAheadAndFiveFurtherSkipsThenStuck() {
        RecordingRun run = new RecordingRun(ONCE, LINE);
        run.arrived();
        for (int i = 0; i < 100; i++) run.playback().advance();
        run.refused();
        assertEquals(RecordingRun.Phase.CATCHING_UP, run.phase());
        assertEquals(14, run.goal().x(), 1e-9, "40 ticks after cursor 100");
        assertEquals(100, run.playback().cursor(), "the cursor waits while catching up");
        for (int skip = 1; skip <= RecordingRun.MAXIMUM_SKIPS; skip++) {
            assertEquals(RecordingRun.Failure.RETRY_FURTHER, run.walkFailed());
            assertEquals(14 + 4 * skip, run.goal().x(), 1e-9);
        }
        assertEquals(RecordingRun.Failure.STUCK, run.walkFailed());
        run.teleported();
        assertEquals(RecordingRun.Phase.PLAYING, run.phase());
        assertEquals(340, run.playback().cursor(), "the cursor jumps to the goal");
    }

    @Test void aBlockedRunCatchesUpAgainOnRetry() {
        RecordingRun run = new RecordingRun(ONCE, LINE);
        run.arrived();
        run.refused();
        run.blocked();
        assertEquals(RecordingRun.Phase.BLOCKED, run.phase());
        run.retry();
        assertEquals(RecordingRun.Phase.CATCHING_UP, run.phase());
        assertEquals(0, run.skips());
    }

    @Test void aPausedRunReturnsToWhatItWasDoing() {
        RecordingRun run = new RecordingRun(ONCE, LINE);
        run.paused(RecordingRun.Phase.SLEEPING);
        assertEquals("sleeping", run.phase().key());
        run.retry();
        assertEquals(RecordingRun.Phase.WALKING_IN, run.phase());
        run.arrived();
        run.paused(RecordingRun.Phase.UNAVAILABLE);
        run.paused(RecordingRun.Phase.SLEEPING);
        run.retry();
        assertEquals(RecordingRun.Phase.PLAYING, run.phase());
    }

    @Test void resumeContinuesFromAStateAndStartsOverOnAnotherMode() {
        RecordingRun run = new RecordingRun(ONCE, LINE);
        run.arrived();
        for (int i = 0; i < 250; i++) run.playback().advance();
        RecordingRun resumed = RecordingRun.resume(ONCE, LINE, Optional.of(run.state()));
        assertEquals(250, resumed.playback().cursor());
        assertEquals(RecordingRun.Phase.WALKING_IN, resumed.phase());
        assertEquals(25, resumed.goal().x(), 1e-9);
        assertEquals(0, RecordingRun.resume(new RecordingSource("walk", java.util.Optional.of(Playback.LOOP)), LINE, Optional.of(run.state())).playback().cursor());
        assertEquals(0, RecordingRun.resume(ONCE, LINE, Optional.empty()).playback().cursor());
    }

    @Test void rewindPutsTheCursorBack() {
        RecordingRun run = new RecordingRun(ONCE, LINE);
        run.arrived();
        RecordingPlayback.State before = run.state();
        run.playback().advance();
        run.rewind(before);
        assertEquals(0, run.playback().cursor());
        assertFalse(run.finished());
    }

    @Test void aStepPlaysItsSegmentOnceAndKeepsTheEndThroughCatchUpRewindAndResume() {
        RecordingRun run = RecordingRun.step(LINE, 3, 100, 300);
        assertTrue(run.step()); assertEquals(3, run.point());
        assertEquals(10, run.goal().x(), 1e-9, "the walk in goes to the segment's first frame");
        assertEquals("step=recording:walk cursor=100/300 state=walking-in", run.describe());
        run.arrived();
        for (int i = 0; i < 180; i++) run.playback().advance();
        run.refused();
        assertEquals(30, run.goal().x(), 1e-9, "catch-up stops at the segment's end, not 40 ticks on");
        run.arrived();
        assertEquals(300, run.playback().cursor());
        assertTrue(run.finished());
        run.playback().advance();
        assertEquals(300, run.playback().cursor(), "a step never plays past toTick");
        RecordingRun stopped = RecordingRun.step(LINE, 3, 100, 300);
        stopped.arrived();
        for (int i = 0; i < 20; i++) stopped.playback().advance();
        RecordingRun resumed = stopped.resumed();
        assertEquals(RecordingRun.Phase.WALKING_IN, resumed.phase()); assertEquals(120, resumed.playback().cursor());
        assertEquals(300, resumed.playback().end()); assertEquals(3, resumed.point());
        assertThrows(IllegalArgumentException.class, () -> RecordingRun.step(LINE, 0, 300, 300));
        assertThrows(IllegalArgumentException.class, () -> RecordingRun.step(LINE, 0, 0, 1001));
        assertFalse(new RecordingRun(ONCE, LINE).step());
    }
}
