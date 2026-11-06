package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingFrame;
import art.arcane.animus.core.recording.RecordingPlayback;
import art.arcane.animus.core.recording.RecordingPose;
import art.arcane.animus.core.recording.RecordingRun;
import art.arcane.animus.core.recording.RecordingSource;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Folia playback through the real {@link RegionOwner}, {@link RegionBudget} and {@link RegionPlayback} on
 * {@link FakeRegions}: the walk to the first frame, playing turns charged to the region's motion cells, a catch-up over a
 * refused step, a handoff to another region that carries the cursor, mode and direction, and stop and resume.
 */
class RegionRecordingHandoffTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000001";
    private static final ChunkPosition A = new ChunkPosition(WORLD, 0, 0), B = new ChunkPosition(WORLD, 1, 0);
    /** Origin 6.5 64 0.5; 20 blocks east over the first 100 ticks and back, one swing at tick 10. */
    private static final RecordingDocument LINE = new RecordingDocument(1, "walk", UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), WORLD,
            new RecordingDocument.Origin(6.5, 64, 0.5, -90, 0), 2, 200,
            List.of(new RecordingFrame(0, 0, 0, 0, -90, 0, RecordingFrame.ON_GROUND), new RecordingFrame(100, 20, 0, 0, -90, 0, RecordingFrame.ON_GROUND),
                    new RecordingFrame(200, 0, 0, 0, 90, 0, RecordingFrame.ON_GROUND)), List.of(new RecordingEvent.Swing(10)));

    /** A region actor's body: owner callbacks, a straight-line walk for planned legs, and the route slot driving the playback. */
    private static final class Performer implements RegionOwner.Body, RegionPlayback.Actor {
        final FakeRegions regions;
        final RegionBudget budget;
        final RegionPlayback playback = new RegionPlayback(this);
        volatile WorldPosition at = new WorldPosition(WORLD, new Vec3(2.5, 64, 0.5));
        volatile MotionValidator.Sampler ground = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        final List<String> events = new CopyOnWriteArrayList<>();
        RecordingRun walking, playing;
        WorldPosition goal;
        long turns, due;
        int swings;
        String status = "";
        Performer(FakeRegions regions, RegionBudget budget) { this.regions = regions; this.budget = budget; }

        @Override public RegionOwner.Footprint footprint() { ChunkPosition chunk = anchor(); return new RegionOwner.Footprint(WORLD, chunk.x(), chunk.z(), chunk.x(), chunk.z()); }
        @Override public ChunkPosition anchor() { return at.chunk(); }
        @Override public boolean loaded(RegionOwner.Footprint footprint) { return regions.owns(footprint.world(), footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ()); }
        @Override public boolean addressable(ChunkPosition destination) { return true; }
        @Override public void handedOff() { events.add("handed-off"); }
        @Override public void rested() {}
        @Override public void owned(boolean woken) {
            events.add((woken ? "woken@" : "adopted@") + regions.currentRegion());
            if (!woken) { walking = null; playing = null; playback.adopted(); }
        }
        @Override public void released() { events.add("released"); }
        @Override public void suspended(String reason) { events.add("suspended " + reason); }
        @Override public void retired() {}
        @Override public ChunkPosition tick() {
            turns++;
            if (walking != null) {
                Vec3 to = goal.point(), from = at.point(), delta = to.subtract(from);
                double length = Math.hypot(delta.x(), delta.z());
                at = new WorldPosition(WORLD, length <= 0.5 ? to : from.add(delta.multiply(0.5 / length)));
                if (length <= 0.5) { RecordingRun arrived = walking; walking = null; playback.arrived(arrived); }
            } else if (playing != null && turns >= due && budget.takeRoute(0)) {
                if (!playback.turn(playing)) events.add("short of cells");
            }
            return anchor();
        }

        @Override public WorldPosition position() { return at; }
        @Override public boolean active() { return true; }
        @Override public long turns() { return turns; }
        @Override public double hullWidth() { return 0.6; }
        @Override public double hullHeight() { return 1.8; }
        @Override public double walkSpeed() { return RegionPlayback.WALK_SPEED; }
        @Override public boolean loaded(String world, int chunkX, int chunkZ) { return regions.owns(world, chunkX, chunkZ, chunkX, chunkZ); }
        @Override public int takeCells(int wanted) { return budget.take(RegionBudget.Kind.MOTION_CELLS, wanted); }
        @Override public void refundCells(int unused) { budget.refund(RegionBudget.Kind.MOTION_CELLS, unused); }
        @Override public int cellLimit() { return 4096; }
        @Override public MotionValidator.Sampler terrain(String world) {
            return (int x, int y, int z) -> regions.owns(world, x >> 4, z >> 4, x >> 4, z >> 4) ? ground.sample(x, y, z) : null;
        }
        @Override public void walk(RecordingRun run, WorldPosition target, double speed, long deadline) { walking = run; playing = null; goal = target; status = "walking"; }
        @Override public void play(RecordingRun run, int delay) { playing = run; walking = null; due = turns + delay; }
        @Override public void step(WorldPosition next, RecordingPose pose, boolean moved) { if (moved) at = next; }
        @Override public void event(String world, RecordingEvent event) { if (event instanceof RecordingEvent.Swing) swings++; }
        @Override public boolean teleportsWhenStuck() { return false; }
        @Override public void teleport(RecordingPose pose) { at = new WorldPosition(WORLD, new Vec3(pose.x(), pose.y(), pose.z())); }
        @Override public void status(String phase, String detail) { status = phase; }
        @Override public void retryIn(int ticks) { due = turns + ticks; }
        @Override public boolean watched() { return false; }
        @Override public void ended(String ended, boolean failed) { walking = null; playing = null; status = ended; }
        @Override public void stop(String reason) { walking = null; playing = null; playback.stopped(); }
        int cursor() { return playback.run != null ? playback.run.playback().cursor() : playback.held.playback().cursor(); }
    }

    private static RegionBudget budget(FakeRegions regions) {
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 4096);
        return new RegionBudget(regions, limits);
    }

    private static void tick(FakeRegions regions, int region, int count) { for (int i = 0; i < count; i++) regions.tick(region); }

    @Test void walksInPlaysCarriesTheCursorAcrossAHandoffAndResumesAfterAStop() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Performer body = new Performer(regions, budget(regions));
        RegionOwner owner = new RegionOwner(UUID.randomUUID(), regions, body.budget, 8, 8, body);
        List<Boolean> woke = new ArrayList<>();
        regions.execute(A, () -> { woke.add(owner.wake()); body.playback.start(new RecordingRun(new RecordingSource("walk", Playback.LOOP), LINE)); });
        regions.tick(1);
        assertEquals(List.of(true), woke);
        assertEquals(RecordingRun.Phase.WALKING_IN, body.playback.run.phase(), "the NPC walks to the first frame first");
        assertEquals(6.5, body.goal.point().x(), 1e-9);

        tick(regions, 1, 12);
        assertEquals(RecordingRun.Phase.PLAYING, body.playback.run.phase());
        assertEquals(6.5 + 20 * body.cursor() / 100.0, body.at.point().x(), 1e-9, "the walk ended on the first frame and the frames play on from it");
        int started = body.cursor();
        tick(regions, 1, 20);
        assertEquals(started + 20, body.cursor(), "one recorded tick per region turn");
        assertEquals(6.5 + 20 * body.cursor() / 100.0, body.at.point().x(), 1e-9);
        assertEquals(1, body.swings, "the recorded swing ran once");

        // Walk on into chunk B, which region 1 still holds, then a split gives B to region 2.
        while (body.at.point().x() < 16) regions.tick(1);
        RecordingPlayback.State carried = body.playback.run.state();
        regions.assign(2, B);
        regions.tick(1);
        assertEquals(NpcOwnership.Phase.TRANSFERRING, owner.ownership().state().phase(), body.events.toString());
        assertEquals(carried, body.playback.run.state(), "the source does not advance a cursor it handed off");
        // The body still overlaps chunk A; region 2 takes it too before adopting, as regions merge around a crossing.
        regions.assign(2, A);
        regions.tick(2);
        assertEquals(NpcOwnership.Phase.ACTIVE, owner.ownership().state().phase());
        assertTrue(body.events.contains("adopted@2"), body.events.toString());
        assertEquals(carried, body.playback.run.state(), "cursor, direction and mode arrive unchanged");
        assertEquals(RecordingRun.Phase.PLAYING, body.playback.run.phase(), "the body already stands on the pose, so the new owner plays at once");
        tick(regions, 1, 5);
        assertEquals(carried.cursor(), body.cursor(), "the old region never plays it again");
        tick(regions, 2, 5);
        assertTrue(body.cursor() > carried.cursor() && body.cursor() <= carried.cursor() + 5, "the new owner plays on from the carried cursor: " + carried.cursor() + " -> " + body.cursor());

        body.stop("cancelled by administrator");
        int stopped = body.cursor();
        assertNull(body.playback.run);
        tick(regions, 2, 10);
        assertEquals(stopped, body.cursor(), "a stopped cursor holds");
        assertTrue(body.playback.resume());
        tick(regions, 2, 3);
        assertEquals(RecordingRun.Phase.PLAYING, body.playback.run.phase());
        assertTrue(body.cursor() > stopped && body.cursor() <= stopped + 3, "resumed from the cursor: " + stopped + " -> " + body.cursor());
    }

    @Test void aRefusedStepCatchesUpFortyTicksAhead() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Performer body = new Performer(regions, budget(regions));
        RegionOwner owner = new RegionOwner(UUID.randomUUID(), regions, body.budget, 8, 8, body);
        regions.execute(A, () -> { owner.wake(); body.playback.start(new RecordingRun(new RecordingSource("walk", Playback.ONCE), LINE)); });
        tick(regions, 1, 20);
        assertEquals(RecordingRun.Phase.PLAYING, body.playback.run.phase());
        body.ground = (int x, int y, int z) -> y < 64 || (x == 12 && y < 70) ? Cell.SOLID : Cell.AIR;
        while (body.playback.run.phase() == RecordingRun.Phase.PLAYING) regions.tick(1);
        assertEquals(RecordingRun.Phase.CATCHING_UP, body.playback.run.phase());
        int refused = body.cursor();
        assertTrue(body.goal.point().x() > 12, "the catch-up goal lies past the wall: " + body.goal.point());
        while (body.playback.run.phase() != RecordingRun.Phase.PLAYING) regions.tick(1);
        assertEquals(refused + RecordingRun.CATCH_UP_TICKS, body.cursor());
    }

    @Test void aRouteStepCarriesItsSegmentAcrossAHandoffAndEndsOnToTick() {
        FakeRegions regions = new FakeRegions().assign(1, A, B);
        Performer body = new Performer(regions, budget(regions));
        RegionOwner owner = new RegionOwner(UUID.randomUUID(), regions, body.budget, 8, 8, body);
        regions.execute(A, () -> { owner.wake(); body.playback.start(RecordingRun.step(LINE, 1, 20, 140)); });
        regions.tick(1);
        assertEquals(10.5, body.goal.point().x(), 1e-9, "the walk in goes to the segment's first frame");
        while (body.playback.run.phase() != RecordingRun.Phase.PLAYING) regions.tick(1);
        while (body.at.point().x() < 16) regions.tick(1);
        RecordingPlayback.State carried = body.playback.run.state();
        regions.assign(2, B);
        regions.tick(1);
        regions.assign(2, A);
        regions.tick(2);
        assertTrue(body.events.contains("adopted@2"), body.events.toString());
        RecordingRun adopted = body.playback.run;
        assertTrue(adopted.step(), "the new owner plays a step, not the whole recording");
        assertEquals(1, adopted.point());
        assertEquals(140, adopted.playback().end());
        assertEquals(carried, adopted.state());
        assertEquals("step=recording:walk cursor=" + carried.cursor() + "/140 state=playing", adopted.describe());
        for (int i = 0; i < 400 && body.playback.run != null; i++) regions.tick(2);
        assertNull(body.playback.run, "the step ended");
        assertEquals("finished", body.status);
        assertEquals(140, body.playback.held.playback().cursor(), "the segment ends on toTick");
        assertEquals(18.5, body.at.point().x(), 1e-9, "the body stands on the frame at toTick");
    }
}
