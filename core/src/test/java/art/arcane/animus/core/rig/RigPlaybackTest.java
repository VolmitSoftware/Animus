package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.mount.SeatGeometry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class RigPlaybackTest {
    static final String RIG = """
            {"schemaVersion":1,"revision":1,"id":"town:golem","bones":[
              {"id":"root","pivot":[0,0,0]},
              {"id":"torso","parent":"root","pivot":[0,1,0],"visual":{"type":"block","block":"minecraft:stone"}},
              {"id":"head","parent":"torso","pivot":[0,1.5,0],"visual":{"type":"item","item":{"id":"minecraft:stick"}}}],
             "animations":{"idle":"town:golem/idle","walk":"town:golem/walk"}}""";
    static final String SLIDE = """
            {"schemaVersion":1,"revision":1,"id":"town:golem/slide","rig":"town:golem","durationTicks":20,"loop":"once",
             "tracks":[{"bone":"torso","target":"translation","keyframes":[{"tick":0,"value":[0,0,0]},{"tick":20,"value":[2,0,0]}]}]}""";
    static final String BOUNCE = """
            {"schemaVersion":1,"revision":1,"id":"town:golem/walk","rig":"town:golem","durationTicks":20,"loop":"loop",
             "tracks":[{"bone":"head","target":"rotation","keyframes":[{"tick":0,"value":[0,0,0],"easing":"ease_in_out"},{"tick":10,"value":[0,90,0],"easing":"ease_in_out"},{"tick":20,"value":[0,0,0]}]}]}""";
    static final String IDLE = """
            {"schemaVersion":1,"revision":1,"id":"town:golem/idle","rig":"town:golem","durationTicks":40,"loop":"loop",
             "tracks":[{"bone":"head","target":"visible","keyframes":[{"tick":0,"value":true},{"tick":20,"value":false}]}]}""";
    static final SeatGeometry SEAT = new SeatGeometry(0, 1.8, 0, 0);

    static RigPlayback playback() {
        RigDocument rig = RigDocument.parse("town:golem", RIG).document().orElseThrow();
        Map<String, AnimationDocument> clips = Map.of(
                "town:golem/slide", AnimationDocument.parse(null, SLIDE).document().orElseThrow(),
                "town:golem/walk", AnimationDocument.parse(null, BOUNCE).document().orElseThrow(),
                "town:golem/idle", AnimationDocument.parse(null, IDLE).document().orElseThrow());
        return new RigPlayback(rig, (String id) -> Optional.ofNullable(clips.get(id)), SEAT);
    }

    @Test
    void linearMotionMergesIntoLongSegments() {
        RigPlayback playback = playback();
        playback.play("town:golem/slide", 1, Optional.empty(), 1, 0);
        RigFrame frame = playback.plan(0, 0, 2, 0);
        assertNotNull(frame);
        // The idle clip hides the head at tick 20, so the segment stops one sample before that change.
        assertEquals(18, frame.durationTicks());
        DisplayTransform torso = frame.targets()[0];
        // The slide's position at the segment end, then the passenger point (1.8 blocks up) is taken back out.
        assertEquals(2 * 18 / 20.0, torso.tx(), 1e-5);
        assertEquals(1 - 1.8, torso.ty(), 1e-5);
    }

    @Test
    void aScaledBodyScalesItsBonesAboutItsFeet() {
        RigDocument rig = RigDocument.parse("town:golem", RIG).document().orElseThrow();
        Map<String, AnimationDocument> clips = Map.of("town:golem/slide", AnimationDocument.parse(null, SLIDE).document().orElseThrow());
        RigPlayback playback = new RigPlayback(rig, (String id) -> Optional.ofNullable(clips.get(id)), SEAT, 2);
        playback.play("town:golem/slide", 1, Optional.empty(), 1, 0);
        DisplayTransform torso = playback.plan(0, 0, 2, 0).targets()[0];
        DisplayTransform plain = playback().plan(0, 0, 2, 0).targets()[0];
        // The client raises the passenger point with the body, so the bone keeps its place relative to the scaled body.
        assertEquals(2 * 1 - 2 * 1.8, torso.ty(), 1e-5);
        assertEquals(2 * plain.sx(), torso.sx(), 1e-5);
        assertEquals(2 * plain.sy(), torso.sy(), 1e-5);
    }

    @Test
    void easedRotationIsSampledInShortSegments() {
        RigPlayback playback = playback();
        playback.moving(true, 0);
        RigFrame frame = playback.plan(0, 0, 2, 0);
        assertTrue(frame.durationTicks() <= 4, "eased rotation needs short segments, got " + frame.durationTicks());
        assertNull(playback.plan(1, 0, 2, 0), "a segment in progress is not replanned");
        assertNotNull(playback.plan(frame.durationTicks(), 0, 2, 0));
    }

    @Test
    void yawChangeReplansAtOnce() {
        RigPlayback playback = playback();
        RigFrame first = playback.plan(0, 0, 2, 0);
        assertNull(playback.plan(1, 0, 2, 0));
        RigFrame turned = playback.plan(1, 90, 2, 0);
        assertNotNull(turned);
        assertTrue(turned.revisions()[0] > first.revisions()[0]);
        // Facing -X: the passenger point offset is vertical, so the torso stays above the body.
        assertEquals(1 - 1.8, turned.targets()[0].ty(), 1e-5);
    }

    @Test
    void unchangedBonesKeepTheirRevision() {
        RigPlayback playback = playback();
        RigFrame first = playback.plan(0, 0, 2, 0);
        RigFrame second = playback.plan(first.durationTicks(), 0, 2, 0);
        assertEquals(first.revisions()[0], second.revisions()[0]);
    }

    @Test
    void onceClipFinishesAndReturnsToIdle() {
        RigPlayback playback = playback();
        playback.play("town:golem/slide", 2, Optional.empty(), 0, 0);
        assertEquals(List.of(), playback.drainFinished(9));
        assertEquals(List.of(new RigPlayback.Finished("town:golem/slide", 0)), playback.drainFinished(10));
        assertEquals(List.of(), playback.drainFinished(11));
        assertTrue(playback.status().contains("town:golem/idle"), playback.status());
    }

    @Test
    void walkingSwitchesTheAmbientClip() {
        RigPlayback playback = playback();
        assertTrue(playback.status().contains("town:golem/idle"));
        playback.moving(true, 5);
        assertTrue(playback.status().contains("town:golem/walk"));
        playback.moving(false, 6);
        assertTrue(playback.status().contains("town:golem/idle"));
    }

    @Test
    void hiddenBonesSnapAndShownBonesInterpolate() {
        RigPlayback playback = playback();
        RigFrame visible = playback.plan(0, 0, 2, 0);
        assertEquals(visible.durationTicks(), visible.durations()[1]);
        RigFrame hidden = playback.plan(20, 0, 2, 0);
        assertEquals(0, hidden.targets()[1].sx());
        assertEquals(0, hidden.durations()[1]);
    }

    @Test
    void stopFadesOutAndFiresNothing() {
        RigPlayback playback = playback();
        playback.play("town:golem/slide", 1, Optional.empty(), 2, 0);
        playback.stop(OptionalInt.of(2), 4, 0);
        assertTrue(playback.status().contains("fading"));
        playback.drainFinished(4);
        assertFalse(playback.status().contains("town:golem/slide"));
        assertEquals(List.of(), playback.drainFinished(30));
    }

    @Test
    void foreignAndUnknownClipsAreRefused() {
        RigPlayback playback = playback();
        assertThrows(IllegalArgumentException.class, () -> playback.play("town:other/clip", 1, Optional.empty(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> playback.play("town:golem/slide", 9, Optional.empty(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> playback.play("town:golem/slide", 1, Optional.empty(), 4, 0));
    }

    @Test
    void viewerSendsOnlyChangedBonesWithinItsBudget() {
        RigPlayback playback = playback();
        playback.moving(true, 0);
        RigFrame frame = playback.plan(0, 0, 2, 0);
        RigView view = new RigView(frame.targets().length);
        int[] all = view.due(frame, 8);
        assertEquals(2, all.length);
        view.sent(frame, all);
        assertEquals(0, view.due(frame, 8).length);
        RigFrame next = playback.plan(frame.durationTicks(), 0, 2, 0);
        int[] limited = view.due(next, 1);
        assertEquals(1, limited.length);
        assertEquals(1, limited[0], "only the rotating head changed");
    }

    @Test
    void remainingDurationShrinksWithTime() {
        RigPlayback playback = playback();
        playback.play("town:golem/slide", 1, Optional.empty(), 1, 0);
        RigFrame frame = playback.plan(0, 0, 2, 1_000_000_000L);
        assertEquals(frame.durationTicks(), frame.remaining(1_000_000_000L, 0));
        assertEquals(frame.durationTicks() - 3, frame.remaining(1_000_000_000L + 150_000_000L, 0));
        assertEquals(0, frame.remaining(Long.MAX_VALUE / 2, 0));
    }
}
