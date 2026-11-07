package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.DoorPassage;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recording playback in the single-owner runtime: the walk to the first frame, one recorded tick per turn, catch-up
 * over a refused step, the stuck rules, events, poses, pausing, stop and resume, and hotload of the recording file.
 */
class RecordingPlaybackRuntimeTest {
    private static final UUID RECORDER = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final String NPC = "performer";
    private static final Pattern CURSOR = Pattern.compile("cursor=(\\d+)/(\\d+) state=([a-z-]+)");
    private static final MotionValidator.Sampler FLAT = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
    @TempDir Path directory;

    /** Origin 6.5 64 2.5; walks 20 blocks east over the first half and back over the second. */
    private static RecordingDocument line(int duration, List<RecordingFrame> extra, List<RecordingEvent> events) {
        List<RecordingFrame> frames = new ArrayList<>();
        frames.add(new RecordingFrame(0, 0, 0, 0, -90, 0, RecordingFrame.ON_GROUND));
        frames.addAll(extra);
        frames.add(new RecordingFrame(duration / 2, 20, 0, 0, -90, 0, RecordingFrame.ON_GROUND));
        frames.add(new RecordingFrame(duration, 0, 0, 0, 90, 0, RecordingFrame.ON_GROUND));
        frames.sort(java.util.Comparator.comparingInt(RecordingFrame::tick));
        return new RecordingDocument(1, "walk", RECORDER, "world", new RecordingDocument.Origin(6.5, 64, 2.5, -90, 0), 2, duration, frames, events);
    }
    private static RecordingDocument line(int duration) { return line(duration, List.of(), List.of()); }

    private ViewerWatchRaceTest.Harness harness(RecordingDocument recording, String navigation, String movement) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        writeRecording(recording);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = FLAT;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve(NPC + ".json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "6c1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c13", "id": "%s", "group": "test",
                 "name": "Performer", "type": "minecraft:player", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},%s
                 "modules": {"animus:navigation": %s}}
                """.formatted(NPC, movement, navigation));
        runtime.npcs().reload();
        harness.service.refresh();
        return harness;
    }
    private ViewerWatchRaceTest.Harness looping(RecordingDocument recording) throws IOException {
        return harness(recording, "{\"enabled\": true, \"recording\": \"walk\", \"playback\": \"loop\"}", "");
    }
    private void writeRecording(RecordingDocument recording) throws IOException {
        Files.createDirectories(directory.resolve("recordings"));
        Files.writeString(directory.resolve("recordings/walk.json"), recording.serialize());
    }

    private static Matcher cursor(ViewerWatchRaceTest.Harness harness) {
        String inspect = harness.service.inspect(NPC);
        Matcher matcher = CURSOR.matcher(inspect);
        assertTrue(matcher.find(), inspect);
        return matcher;
    }
    private static int tick(ViewerWatchRaceTest.Harness harness) { return Integer.parseInt(cursor(harness).group(1)); }
    private static String state(ViewerWatchRaceTest.Harness harness) { return cursor(harness).group(3); }
    private static double x(ViewerWatchRaceTest.Harness harness) {
        return harness.service.state(UUID.fromString("6c1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c13")).orElseThrow().position().point().x();
    }
    /**
     * Ticks until the condition holds, at most {@code limit} ticks. Path plans run on planner threads, so a tick that only
     * waits for a plan does not count against the limit while a busy machine finishes it (up to 20 seconds).
     */
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
    private static boolean shows(ViewerWatchRaceTest.Harness harness, String text) { return harness.service.inspect(NPC).contains(text); }

    @Test void walksToTheFirstFrameThenPlaysOneRecordedTickPerTick() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 10, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=walking-in"), "walking in");
        assertTrue(shows(harness, "recording=walk playback=loop cursor=0/200"), harness.service.inspect(NPC));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        assertEquals(6.5, x(harness), 0.5, "the walk ends on the first frame");
        int start = tick(harness);
        harness.tick(10);
        assertEquals(start + 10, tick(harness));
        assertEquals(6.5 + 20 * (start + 10) / 100.0, x(harness), 1e-6, "the body stands on the interpolated pose");
        assertTrue(harness.service.status().contains("recordings=1"), harness.service.status());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test void aRefusedStepCatchesUpToThePoseFortyTicksAhead() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        // A short wall across the line at x=12; the planned walk goes round it.
        harness.terrain = (int x, int y, int z) -> y < 64 || (x == 12 && y < 67 && z >= 1 && z <= 3) ? Cell.SOLID : Cell.AIR;
        until(harness, 200, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=catching-up"), "catching up");
        int refusedAt = tick(harness);
        until(harness, 600, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing again");
        assertTrue(tick(harness) >= refusedAt + 40, "the cursor jumped ahead: " + harness.service.inspect(NPC));
        assertTrue(x(harness) > 12, "the body is past the wall: " + x(harness));
    }

    private static MotionValidator.Sampler wall() { return (int x, int y, int z) -> y < 64 || (x == 12 && y < 72) ? Cell.SOLID : Cell.AIR; }

    @Test void anUnreachableOncePlaybackSkipsFiveTimesThenEndsBlocked() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(line(400), "{\"enabled\": true, \"recording\": \"walk\", \"playback\": \"once\"}", "");
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.terrain = wall();
        until(harness, 6000, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).startsWith("Navigation " + NPC + ": blocked"), "blocked");
        assertTrue(shows(harness, "state=stopped"), harness.service.inspect(NPC));
        assertTrue(x(harness) < 12, "the body never passed the wall");
    }

    @Test void aLoopingPlaybackThatCannotCatchUpWaitsBlockedAndTriesAgain() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(400));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.terrain = wall();
        until(harness, 6000, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=blocked"), "blocked");
        until(harness, 120, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=catching-up"), "trying again after the wait");
    }

    @Test void aStuckPlaybackThatTeleportsPlaysOnFromTheGoal() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(line(400), "{\"enabled\": true, \"recording\": \"walk\", \"playback\": \"loop\"}", """

                 "movement": {"stuck": {"afterTicks": 40, "teleport": true}},""");
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.terrain = wall();
        until(harness, 6000, (ViewerWatchRaceTest.Harness h) -> x(h) > 12.5, "teleported past the wall");
        until(harness, 5, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing after the teleport");
    }

    @Test void eventsSwingEquipAndUseDoorsInOrder() throws IOException {
        NpcItem sword = new NpcItem("minecraft:iron_sword", 1, Optional.empty(), Optional.empty());
        List<String> doors = new ArrayList<>();
        RecordingDocument recording = line(200, List.of(), List.of(new RecordingEvent.Swing(4), new RecordingEvent.Equipment(6, EquipmentSlot.MAIN_HAND, Optional.of(sword)),
                new RecordingEvent.Use(8, 10, 64, 5, "minecraft:oak_door"), new RecordingEvent.Use(12, 10, 64, 5, "minecraft:oak_door")));
        ViewerWatchRaceTest.Harness harness = looping(recording);
        harness.transport.equipment = true;
        harness.doors = new DoorPassage.Doors() {
            @Override public boolean open(UUID npc, String world, BlockPos at) { doors.add("open " + at.x() + " " + at.y() + " " + at.z()); return true; }
            @Override public void close(String world, BlockPos at) { doors.add("close " + at.x() + " " + at.y() + " " + at.z()); }
        };
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        int equippedBefore = harness.transport.equipped.get();
        until(harness, 30, (ViewerWatchRaceTest.Harness h) -> tick(h) >= 14, "past the events");
        harness.tick(2);
        assertEquals(1, harness.transport.swings.get(), "one swing reached the viewer");
        assertTrue(harness.transport.equipped.get() > equippedBefore, "the recorded item was sent");
        assertEquals(List.of("open 10 64 5", "close 10 64 5"), doors);
    }

    @Test void aRefusedDoorIsIgnoredAndNeverClosed() throws IOException {
        List<String> doors = new ArrayList<>();
        RecordingDocument recording = line(200, List.of(), List.of(new RecordingEvent.Use(8, 10, 64, 5, "minecraft:oak_door"), new RecordingEvent.Use(12, 10, 64, 5, "minecraft:oak_door")));
        ViewerWatchRaceTest.Harness harness = looping(recording);
        harness.doors = new DoorPassage.Doors() {
            @Override public boolean open(UUID npc, String world, BlockPos at) { doors.add("refused"); return false; }
            @Override public void close(String world, BlockPos at) { doors.add("close"); }
        };
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        until(harness, 30, (ViewerWatchRaceTest.Harness h) -> tick(h) >= 14, "past the events");
        assertEquals(List.of("refused", "refused"), doors);
    }

    @Test void sneakingAndSprintingShowAsTheBodyLook() throws IOException {
        RecordingDocument recording = line(200, List.of(new RecordingFrame(10, 2, 0, 0, -90, 0, RecordingFrame.ON_GROUND | RecordingFrame.SNEAKING),
                new RecordingFrame(20, 4, 0, 0, -90, 0, RecordingFrame.ON_GROUND | RecordingFrame.SPRINTING)), List.of());
        ViewerWatchRaceTest.Harness harness = looping(recording);
        harness.transport.presentation = true;
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        until(harness, 40, (ViewerWatchRaceTest.Harness h) -> tick(h) >= 24, "past the poses");
        harness.tick(2);
        List<BodyLook> looks = harness.transport.looks;
        assertTrue(looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.SNEAKING && look.motion() == 0), looks.toString());
        assertTrue(looks.stream().anyMatch((BodyLook look) -> look.pose() == NpcPose.STANDING && look.motion() == BodyLook.SPRINTING), looks.toString());
    }

    @Test void stoppingKeepsTheCursorAndResumingContinuesFromIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.tick(15);
        assertTrue(harness.service.stopNavigation(NPC));
        int stopped = tick(harness);
        assertTrue(shows(harness, "state=stopped"), harness.service.inspect(NPC));
        harness.tick(20);
        assertEquals(stopped, tick(harness), "a stopped playback holds its cursor");
        assertTrue(harness.service.resumePatrol(NPC));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing") && tick(h) > stopped, "playing on");
        assertTrue(tick(harness) <= stopped + 3, "resumed from the cursor, not the first frame: " + harness.service.inspect(NPC));
    }

    @Test void aManualRouteStopsThePlaybackAndResumePatrolContinuesIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.tick(20);
        int stopped = tick(harness);
        harness.service.navigate(NPC, new art.arcane.animus.core.virtual.WorldPosition("world", new art.arcane.animus.core.virtual.Vec3(4.5, 64, 6.5)),
                art.arcane.animus.core.navigation.MovementProfile.Mode.WALK, 4);
        assertTrue(shows(harness, "state=stopped"), harness.service.inspect(NPC));
        assertFalse(harness.service.resumePatrol(NPC), "a manual route still moves the NPC");
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> h.service.navigationStatus(NPC).contains("arrived"), "the manual route arrives");
        assertTrue(harness.service.resumePatrol(NPC));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing") && tick(h) > stopped, "playing on after the walk back");
        assertTrue(tick(harness) <= stopped + 3, harness.service.inspect(NPC));
    }

    @Test void anUnchangedRecordingKeepsPlayingAndAChangedOneRestarts() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.tick(30);
        writeRecording(line(200));
        harness.service.refresh();
        harness.tick(3);
        assertTrue(tick(harness) > 30, "an unchanged file keeps the cursor: " + harness.service.inspect(NPC));
        writeRecording(line(240));
        harness.service.refresh();
        until(harness, 10, (ViewerWatchRaceTest.Harness h) -> shows(h, "/240"), "the changed recording loaded");
        assertTrue(tick(harness) < 5, "a changed recording starts from the first frame: " + harness.service.inspect(NPC));
    }

    @Test void anInvalidEditKeepsTheLastValidRecording() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        Files.writeString(directory.resolve("recordings/walk.json"), "{\"schemaVersion\": 1}");
        harness.service.refresh();
        harness.tick(3);
        String inspect = harness.service.inspect(NPC);
        assertTrue(inspect.contains("route-error=") && inspect.contains("last-valid-recording-retained=true"), inspect);
        assertTrue(inspect.contains("state=playing"), inspect);
    }

    @Test void aMissingRecordingIsReportedAndTheNpcStaysPut() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(line(200), "{\"enabled\": true, \"recording\": \"absent\"}", "");
        harness.tick(20);
        String navigation = harness.service.navigationStatus(NPC);
        assertTrue(navigation.contains("route-error=") && navigation.contains("unavailable: recording absent"), navigation);
        assertEquals(2.5, x(harness), 1e-9);
    }

    private static RecordingDocument madeIn(String world) {
        RecordingDocument walk = line(200);
        return new RecordingDocument(walk.revision(), walk.id(), walk.recordedBy(), world, walk.origin(), walk.sampleTicks(), walk.durationTicks(), walk.frames(), walk.events());
    }

    @Test void aRecordingNamingTheNpcWorldByDimensionKeyPlays() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(madeIn("minecraft:overworld"));
        harness.worlds = (String reference) -> reference.equals("minecraft:overworld") || reference.equals("world") ? Optional.of("world") : Optional.empty();
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
    }

    @Test void aRecordingMadeInAnotherWorldIsRefusedAndTheNpcStaysPut() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(madeIn("world_nether"));
        harness.tick(20);
        String navigation = harness.service.navigationStatus(NPC);
        assertTrue(navigation.contains("route-error=recording walk was made in world world_nether, not the NPC's world"), navigation);
        assertEquals(2.5, x(harness), 1e-9);
    }

    @Test void aRecordingWhoseWorldIsNotLoadedIsRefused() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(madeIn("skylands"));
        harness.worlds = (String reference) -> reference.equals("skylands") ? Optional.empty() : Optional.of(reference);
        harness.tick(20);
        String navigation = harness.service.navigationStatus(NPC);
        assertTrue(navigation.contains("route-error=recording walk names world skylands, which is not loaded"), navigation);
        assertEquals(2.5, x(harness), 1e-9);
    }

    @Test void noPlayerNearSleepsAndFreezesTheCursor() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.playersAway = true;
        until(harness, 60, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=sleeping"), "sleeping");
        int asleep = tick(harness);
        harness.tick(40);
        assertEquals(asleep, tick(harness));
        harness.playersAway = false;
        until(harness, 80, (ViewerWatchRaceTest.Harness h) -> tick(h) > asleep, "awake again");
    }

    @Test void theNextFrameInAnUnloadedChunkPausesTheCursor() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        harness.loaded = (Integer x, Integer z) -> x < 1;
        until(harness, 100, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=unavailable"), "unavailable");
        int paused = tick(harness);
        assertTrue(x(harness) < 16, "the body never entered the unloaded chunk");
        harness.tick(40);
        assertEquals(paused, tick(harness));
        harness.loaded = (Integer x, Integer z) -> true;
        until(harness, 60, (ViewerWatchRaceTest.Harness h) -> tick(h) > paused, "playing on once loaded");
    }

    @Test void disablingRecordingsStopsThePlaybackAndRefusesNewOnes() throws IOException {
        ViewerWatchRaceTest.Harness harness = looping(line(200));
        until(harness, 400, (ViewerWatchRaceTest.Harness h) -> shows(h, "state=playing"), "playing");
        art.arcane.animus.core.config.PluginSettings base = art.arcane.animus.core.config.PluginSettings.load(harness.runtime.documents());
        art.arcane.animus.core.config.RecordingSettings on = base.recordings();
        art.arcane.animus.core.config.RecordingSettings off = new art.arcane.animus.core.config.RecordingSettings(false, on.sampleTicks(), on.maximumTicks(),
                on.maximumFileBytes(), on.maximumSessions(), on.ghostEnabled(), on.ghostIntervalTicks(), on.ghostStretchTicks(), on.ghostGlow(), on.ghostColor(), on.maximumPreviews());
        art.arcane.animus.core.config.PluginSettings next = new art.arcane.animus.core.config.PluginSettings(base.packetProofEnabled(), base.npcs(),
                base.files(), base.modules(), base.behaviors(), base.combat(), base.builds(), base.shops(), base.relay(), base.editor(), base.rigs(), base.navigation(),
                base.hotload(), off);
        Thread lane = new Thread(() -> harness.runtime.applySettings(next), "file-lane");
        lane.start();
        try { lane.join(); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
        harness.tick(3);
        String inspect = harness.service.inspect(NPC);
        assertTrue(inspect.contains("route-error=recordings are disabled in config.toml") && inspect.contains("state=stopped"), inspect);
        assertTrue(harness.service.status().contains("recordings=0"), harness.service.status());
        assertThrows(IllegalStateException.class, () -> harness.service.playRecording(NPC, "walk", Optional.of(Playback.ONCE)));
    }

    @Test void aWaitedOncePlaybackCompletesWhenItEnds() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(line(40), "{\"enabled\": false}", "");
        harness.tick(2);
        harness.service.playRecording(NPC, "walk", Optional.of(Playback.ONCE));
        CompletableFuture<Void> finished = harness.service.awaitRoute(NPC);
        until(harness, 800, (ViewerWatchRaceTest.Harness h) -> finished.isDone(), "finished");
        assertFalse(finished.isCompletedExceptionally(), harness.service.navigationStatus(NPC));
        assertTrue(shows(harness, "cursor=40/40 state=finished"), harness.service.inspect(NPC));
    }

    @Test void stoppingAWaitedPlaybackFailsTheWait() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(line(200), "{\"enabled\": false}", "");
        harness.tick(2);
        harness.service.playRecording(NPC, "walk", Optional.of(Playback.ONCE));
        CompletableFuture<Void> finished = harness.service.awaitRoute(NPC);
        harness.tick(5);
        harness.service.stopNavigation(NPC);
        assertTrue(finished.isCompletedExceptionally());
    }

    @Test void waitingOnALoopingPlaybackIsRefused() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(line(200), "{\"enabled\": false}", "");
        harness.tick(2);
        harness.service.playRecording(NPC, "walk", Optional.of(Playback.LOOP));
        CompletableFuture<Void> finished = harness.service.awaitRoute(NPC);
        harness.tick(3);
        assertTrue(finished.isCompletedExceptionally());
    }
}
