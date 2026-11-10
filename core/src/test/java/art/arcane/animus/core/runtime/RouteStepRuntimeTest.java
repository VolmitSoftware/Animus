package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recording steps in routes in the single-owner runtime: the walk in to the segment's first frame, the segment played
 * once, the waypoint trigger, the next point from the segment's last frame, blocked steps, stop and resume, and hotload.
 */
class RouteStepRuntimeTest {
    private static final UUID RECORDER = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final UUID UUID_NPC = UUID.fromString("6c1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c13");
    private static final String NPC = "performer";
    private static final Pattern STEP = Pattern.compile("step=recording:walk cursor=(\\d+)/(\\d+) state=([a-z-]+)");
    private static final MotionValidator.Sampler FLAT = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
    /** A position point, a step over ticks 20..80 of {@link #line} (x 10.5 to 22.5), and another position point. */
    private static final String MIXED = """
            {"schemaVersion": 1, "waitTicks": 0, "points": [{"x": 2.5, "y": 64, "z": 6.5},
             {"recording": "walk", "fromTick": 20, "toTick": 80, "waitTicks": 5}, {"x": 22.5, "y": 64, "z": 8.5}]}
            """;
    @TempDir Path directory;

    /** Origin 6.5 64 2.5; walks 20 blocks east over the first half and back over the second. */
    private static RecordingDocument line(int duration) {
        return new RecordingDocument(1, "walk", RECORDER, "world", new RecordingDocument.Origin(6.5, 64, 2.5, -90, 0), 2, duration,
                List.of(new RecordingFrame(0, 0, 0, 0, -90, 0, RecordingFrame.ON_GROUND), new RecordingFrame(duration / 2, 20, 0, 0, -90, 0, RecordingFrame.ON_GROUND),
                        new RecordingFrame(duration, 0, 0, 0, 90, 0, RecordingFrame.ON_GROUND)), List.of());
    }

    private ViewerWatchRaceTest.Harness harness(String route, String navigation) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        writeRecording(line(200));
        Files.createDirectories(directory.resolve("routes"));
        Files.writeString(directory.resolve("routes/mixed.json"), route);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = FLAT;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve(NPC + ".json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "Performer", "type": "minecraft:player", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:navigation": %s}}
                """.formatted(UUID_NPC, NPC, navigation));
        runtime.npcs().reload();
        harness.service.refresh();
        return harness;
    }
    private ViewerWatchRaceTest.Harness shared(String route) throws IOException { return harness(route, "{\"enabled\": true, \"route\": \"mixed\"}"); }
    private void writeRecording(RecordingDocument recording) throws IOException {
        Files.createDirectories(directory.resolve("recordings"));
        Files.writeString(directory.resolve("recordings/walk.json"), recording.serialize());
    }

    private static Matcher step(ViewerWatchRaceTest.Harness harness) {
        String inspect = harness.service.inspect(NPC);
        Matcher matcher = STEP.matcher(inspect);
        assertTrue(matcher.find(), inspect);
        return matcher;
    }
    private static int cursor(ViewerWatchRaceTest.Harness harness) { return Integer.parseInt(step(harness).group(1)); }
    private static double x(ViewerWatchRaceTest.Harness harness) { return harness.service.state(UUID_NPC).orElseThrow().position().point().x(); }
    private static double z(ViewerWatchRaceTest.Harness harness) { return harness.service.state(UUID_NPC).orElseThrow().position().point().z(); }
    private static boolean shows(ViewerWatchRaceTest.Harness harness, String text) { return harness.service.inspect(NPC).contains(text); }
    /** Ticks until the condition holds; ticks spent waiting on a planner thread do not count against the limit. */
    private static void until(ViewerWatchRaceTest.Harness harness, int limit, Predicate<ViewerWatchRaceTest.Harness> condition, String what) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        for (int i = 0; i < limit; i++) {
            if (condition.test(harness)) return;
            if (harness.service.navigationStatus(NPC).contains(": planning;") && System.nanoTime() < deadline) {
                try { Thread.sleep(2); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                i--;
            }
            harness.tick(1);
        }
        fail(what + " within " + limit + " ticks: " + harness.service.inspect(NPC) + " | " + harness.service.navigationStatus(NPC));
    }

    @Test void aStepWalksInPlaysItsSegmentAndTheRouteGoesOnFromItsLastFrame() throws IOException {
        ViewerWatchRaceTest.Harness harness = shared(MIXED);
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.reached.contains(0), "the first point");
        until(harness, 40, (ViewerWatchRaceTest.Harness h) -> shows(h, "step=recording:walk cursor=20/80 state=walking-in"), "walking in to the segment");
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing the segment");
        assertEquals(10.5, x(harness), 0.5, "the walk in ends on the segment's first frame");
        int start = cursor(harness);
        harness.tick(10);
        assertEquals(start + 10, cursor(harness));
        assertEquals(6.5 + 20 * (start + 10) / 100.0, x(harness), 1e-6, "the body stands on the interpolated pose");
        until(harness, 100, (ViewerWatchRaceTest.Harness h) -> h.reached.contains(1), "the step reached as waypoint 1");
        assertEquals(22.5, x(harness), 1e-6, "the step ends on the frame at toTick");
        assertEquals(2.5, z(harness), 1e-6);
        assertFalse(shows(harness, "step=recording:walk"), "nothing is held after a finished step: " + harness.service.inspect(NPC));
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.reached.contains(2), "the last point");
        assertEquals(List.of(0, 1, 2), harness.reached);
        assertEquals(8.5, z(harness), 0.5, "the next leg went on from the segment's last frame");
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test void anInlineRouteReadsItsStepRecordingsBeforeItStarts() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("{}", """
                {"enabled": true, "waitTicks": 0, "points": [{"recording": "walk", "fromTick": 100, "toTick": 120}, {"x": 2.5, "y": 64, "z": 6.5}]}""");
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> shows(h, "step=recording:walk cursor=") && shows(h, "/120 state=playing"), "playing the inline step");
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.reached.contains(1), "the point after the step");
        assertEquals(List.of(0, 1), harness.reached);
    }

    @Test void aStepWhoseRecordingCannotBeReadIsABlockedLeg() throws IOException {
        ViewerWatchRaceTest.Harness harness = shared(MIXED.replace("\"recording\": \"walk\"", "\"recording\": \"absent\""));
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).contains("route-error=recording step 1: recording absent"), "the blocked step");
        assertTrue(harness.service.navigationStatus(NPC).contains("Navigation " + NPC + ": blocked:"), harness.service.navigationStatus(NPC));
        harness.tick(40);
        assertEquals(List.of(0), harness.reached, "a sequence halts at a blocked step");
    }

    @Test void aStepRangeTheRecordingDoesNotHoldIsABlockedLeg() throws IOException {
        ViewerWatchRaceTest.Harness harness = shared(MIXED.replace("\"toTick\": 80", "\"toTick\": 400"));
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC)
                .contains("route-error=recording step 1: $.points[1].toTick: 400 is past the durationTicks 200 of recording walk"), "the refused range");
    }

    @Test void aStepRecordedInAnotherWorldIsABlockedLeg() throws IOException {
        ViewerWatchRaceTest.Harness harness = shared(MIXED);
        RecordingDocument walk = line(200);
        writeRecording(new RecordingDocument(walk.revision(), walk.id(), walk.recordedBy(), "world_nether", walk.origin(), walk.sampleTicks(),
                walk.durationTicks(), walk.frames(), walk.events()));
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).contains("route-error=recording step 1: recording walk was made in world world_nether"),
                "the refused world");
    }

    @Test void stoppingMidSegmentAndResumingContinuesTheStepThenTheRoute() throws IOException {
        ViewerWatchRaceTest.Harness harness = shared(MIXED);
        until(harness, 1200, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing the segment");
        harness.tick(15);
        assertTrue(harness.service.stopNavigation(NPC));
        int stopped = cursor(harness);
        assertTrue(shows(harness, "state=stopped"), harness.service.inspect(NPC));
        harness.tick(20);
        assertEquals(stopped, cursor(harness), "a stopped step holds its cursor");
        assertTrue(harness.service.resumePatrol(NPC));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing") && cursor(h) > stopped, "playing on");
        assertTrue(cursor(harness) <= stopped + 3, "resumed from the cursor: " + harness.service.inspect(NPC));
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> h.reached.contains(2), "the route goes on after the resumed step");
        assertEquals(List.of(0, 1, 2), harness.reached);
    }

    @Test void aChangedRecordingRestartsAPlayingStep() throws IOException {
        ViewerWatchRaceTest.Harness harness = shared(MIXED);
        until(harness, 1200, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing the segment");
        harness.tick(20);
        writeRecording(line(200));
        harness.service.refresh();
        harness.tick(3);
        assertTrue(cursor(harness) > 35, "an unchanged recording keeps the step: " + harness.service.inspect(NPC));
        writeRecording(line(240));
        harness.service.refresh();
        until(harness, 20, (ViewerWatchRaceTest.Harness h) -> cursor(h) < 25, "the step restarted from fromTick");
        assertTrue(shows(harness, "cursor=20/80") || cursor(harness) <= 22, harness.service.inspect(NPC));
    }
}
