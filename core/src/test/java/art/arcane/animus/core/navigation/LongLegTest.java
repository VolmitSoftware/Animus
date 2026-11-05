package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Long legs against synthetic terrain: every segment is one bounded capture planned in it, then walked step by step with
 * the same live collision check the runtimes use.
 */
class LongLegTest {
    private static final double WIDTH = .6, HEIGHT = 1.8;
    private static final MovementProfile WALKER = BodyMobility.forRoute("minecraft:villager", WIDTH, HEIGHT, MovementProfile.Mode.WALK);
    private static final MovementProfile SWIMMER = BodyMobility.forRoute("minecraft:villager", WIDTH, HEIGHT, MovementProfile.Mode.SWIM);
    private static final MovementProfile FLIER = BodyMobility.forRoute("minecraft:bee", WIDTH, HEIGHT, MovementProfile.Mode.FLY);

    /** A world as a block function, with an optional set of unloaded chunks. */
    private interface World { Cell at(int x, int y, int z); }
    private interface Loaded { boolean chunk(int x, int z); }
    private record Run(LongLeg.Next outcome, Vec3 at, int plans, List<CaptureBounds> captures) {}

    /** Ground below {@code height(x, z)}, air above. */
    private static World ground(IntBinaryOperator height) {
        return (int x, int y, int z) -> y < height.applyAsInt(x, z) ? Cell.SOLID : Cell.AIR;
    }
    private static VoxelSnapshot capture(World world, Loaded loaded, CaptureBounds bounds) {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", bounds.minX(), bounds.minY(), bounds.minZ(), bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        for (int cx = Math.floorDiv(bounds.minX(), 16); cx <= Math.floorDiv(bounds.minX() + bounds.sizeX() - 1, 16); cx++)
            for (int cz = Math.floorDiv(bounds.minZ(), 16); cz <= Math.floorDiv(bounds.minZ() + bounds.sizeZ() - 1, 16); cz++)
                if (loaded.chunk(cx, cz)) builder.availableChunk(cx, cz);
        for (int x = bounds.minX(); x < bounds.minX() + bounds.sizeX(); x++)
            for (int y = bounds.minY(); y < bounds.minY() + bounds.sizeY(); y++)
                for (int z = bounds.minZ(); z < bounds.minZ() + bounds.sizeZ(); z++) builder.set(x, y, z, world.at(x, y, z));
        return builder.build();
    }

    /** Plans and walks one leg until it arrives, is blocked or waits for terrain; every step is checked against the live world. */
    private static Run walk(World world, Loaded loaded, Vec3 start, Vec3 goal, MovementProfile profile) {
        LongLeg leg = new LongLeg(start, goal, WIDTH, HEIGHT);
        Vec3 at = start;
        List<CaptureBounds> captures = new ArrayList<>();
        MotionValidator.Sampler live = (int x, int y, int z) -> loaded.chunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) ? world.at(x, y, z) : Cell.UNAVAILABLE;
        for (int plans = 1; plans <= 200; plans++) {
            LongLeg.Request request = leg.request(at);
            CaptureBounds bounds = request.bounds();
            assertTrue(bounds.voxelCount() <= NavigationLimits.DEFAULT.maximumCaptureVoxels(), bounds.toString());
            BlockPos feet = BlockPos.containing(at);
            assertTrue(feet.x() >= bounds.minX() && feet.x() < bounds.minX() + bounds.sizeX() && feet.z() >= bounds.minZ() && feet.z() < bounds.minZ() + bounds.sizeZ(), "capture holds the feet");
            captures.add(bounds);
            PathPlanner.Result result = new PathPlanner().plan(capture(world, loaded, bounds), feet, BlockPos.containing(goal), profile, request.limits(), () -> false);
            if (result.status() == PathPlanner.Status.UNAVAILABLE_TERRAIN) return new Run(null, at, plans, captures);
            LongLeg.Next next = leg.planned(result);
            if (next == LongLeg.Next.BLOCKED) return new Run(next, at, plans, captures);
            if (next == LongLeg.Next.RETRY) continue;
            Vec3 end = next == LongLeg.Next.FINAL ? goal : result.path().getLast().point();
            PathExecution execution = new PathExecution(at, result.path(), end, profile);
            boolean blocked = false;
            while (!execution.finished()) {
                PathExecution.Proposal step = execution.next(.25).orElseThrow();
                MotionValidator.Result check = MotionValidator.check(step.from(), step.to(), WIDTH, HEIGHT, step.medium(), step.requireSupport(), 4096, live);
                if (!check.clear()) { blocked = true; break; }
                execution.commit(step);
            }
            at = execution.position();
            if (blocked) { if (leg.blocked() == LongLeg.Next.BLOCKED) return new Run(LongLeg.Next.BLOCKED, at, plans, captures); continue; }
            if (next == LongLeg.Next.FINAL) return new Run(next, at, plans, captures);
        }
        throw new AssertionError("A leg must end within 200 plans");
    }
    private static Run walk(World world, Vec3 start, Vec3 goal, MovementProfile profile) {
        return walk(world, (int x, int z) -> true, start, goal, profile);
    }
    private static void arrived(Run run, Vec3 goal) {
        assertEquals(LongLeg.Next.FINAL, run.outcome(), "ended at " + run.at());
        assertEquals(goal.x(), run.at().x(), 1e-9); assertEquals(goal.z(), run.at().z(), 1e-9);
        assertEquals(goal.y(), run.at().y(), 1e-9);
    }

    private static int hills(int x, int z) { return 64 + (int) Math.round(6 * Math.sin(x / 17.0) + 3 * Math.cos(z / 13.0)); }

    @Test void aLegTwoHundredBlocksAwayOverHillsIsWalkedInBoundedSegments() {
        World world = ground(LongLegTest::hills);
        Vec3 start = new Vec3(.5, hills(0, 0), .5), goal = new Vec3(200.5, hills(200, 0), .5);
        Run run = walk(world, start, goal, WALKER);
        arrived(run, goal);
        assertTrue(run.plans() >= 8, "a 200 block leg needs several captures, not one: " + run.plans());
        assertTrue(run.plans() <= 40, "progress is steady: " + run.plans());
    }
    @Test void aDiagonalLegAcrossOpenGroundArrives() {
        World world = ground((int x, int z) -> 64);
        Vec3 start = new Vec3(.5, 64, .5), goal = new Vec3(-120.5, 64, 90.5);
        arrived(walk(world, start, goal, WALKER), goal);
    }
    @Test void aThirtyBlockClimbUpAHillsideIsTakenInSteppedSegments() {
        IntBinaryOperator slope = (int x, int z) -> 64 + Math.max(0, Math.min(30, x - 10));
        World world = ground(slope);
        Vec3 start = new Vec3(.5, 64, .5), goal = new Vec3(50.5, 94, .5);
        arrived(walk(world, start, goal, WALKER), goal);
        Run down = walk(world, goal, start, WALKER);
        arrived(down, start);
    }
    @Test void aFlierRisesThirtyBlocksStraightUp() {
        World world = ground((int x, int z) -> 64);
        Vec3 start = new Vec3(.5, 64, .5), goal = new Vec3(.5, 94, .5);
        arrived(walk(world, start, goal, FLIER), goal);
    }
    @Test void aSwimmingRouteCrossesALakeThatAWalkingRouteCannot() {
        World lake = (int x, int y, int z) -> {
            boolean water = x >= 30 && x <= 110 && z >= -60 && z <= 60;
            if (!water) return y < 64 ? Cell.SOLID : Cell.AIR;
            return y < 56 ? Cell.SOLID : y < 64 ? Cell.WATER : Cell.AIR;
        };
        Vec3 start = new Vec3(.5, 64, .5), goal = new Vec3(140.5, 64, .5);
        arrived(walk(lake, start, goal, SWIMMER), goal);
        Run walking = walk(lake, start, goal, WALKER);
        assertEquals(LongLeg.Next.BLOCKED, walking.outcome(), "a walking route stays on land; the lake is wider than any detour");
    }
    @Test void aBlockedSegmentReplansAroundTheWall() {
        // A wall across the straight line, open only beyond its end at z=12: the straight capture stops at the wall.
        World world = (int x, int y, int z) -> {
            if (y < 64) return Cell.SOLID;
            if (x == 30 && z <= 11 && y < 70) return Cell.SOLID;
            return Cell.AIR;
        };
        Vec3 start = new Vec3(.5, 64, .5), goal = new Vec3(60.5, 64, .5);
        Run run = walk(world, start, goal, WALKER);
        arrived(run, goal);
    }
    @Test void aSegmentBlockedWhileWalkedIsPlannedAgainThenGivenUp() {
        LongLeg leg = new LongLeg(new Vec3(.5, 64, .5), new Vec3(100.5, 64, .5), WIDTH, HEIGHT);
        CaptureBounds first = leg.request(new Vec3(.5, 64, .5)).bounds();
        assertEquals(LongLeg.Next.RETRY, leg.blocked());
        CaptureBounds second = leg.request(new Vec3(.5, 64, .5)).bounds();
        assertFalse(first.equals(second), "a retry looks elsewhere");
        LongLeg.Next next = LongLeg.Next.RETRY;
        int retries = 1;
        while (next == LongLeg.Next.RETRY) { next = leg.blocked(); retries++; }
        assertEquals(LongLeg.Next.BLOCKED, next);
        assertEquals(LongLeg.ATTEMPTS, retries);
    }
    @Test void anEnclosedGoalIsReportedBlockedAfterBoundedWork() {
        World world = (int x, int y, int z) -> {
            if (y < 64) return Cell.SOLID;
            boolean ring = Math.max(Math.abs(x - 80), Math.abs(z)) == 6;
            return ring && y < 72 ? Cell.SOLID : Cell.AIR;
        };
        Run run = walk(world, new Vec3(.5, 64, .5), new Vec3(80.5, 64, .5), WALKER);
        assertEquals(LongLeg.Next.BLOCKED, run.outcome());
        assertTrue(run.plans() < 40, "bounded: " + run.plans());
        assertTrue(run.at().x() > 60, "it walked as far as the terrain allowed: " + run.at());
    }
    @Test void unloadedTerrainPausesTheLegAndItResumesOnceLoaded() {
        World world = ground((int x, int z) -> 64);
        Vec3 start = new Vec3(.5, 64, .5), goal = new Vec3(150.5, 64, .5);
        Run paused = walk(world, (int x, int z) -> x != 5, start, goal, WALKER);
        assertEquals(null, paused.outcome(), "waits for terrain instead of failing");
        assertTrue(paused.at().x() > 60 && paused.at().x() < 80, "walked to the edge of loaded terrain: " + paused.at());
        arrived(walk(world, paused.at(), goal, WALKER), goal);
    }
    @Test void aLegThatFitsOneCaptureIsPlannedExactlyAsBefore() {
        LongLeg leg = new LongLeg(new Vec3(.5, 64, .5), new Vec3(20.5, 66, 10.5), WIDTH, HEIGHT);
        LongLeg.Request request = leg.request(new Vec3(.5, 64, .5));
        assertTrue(request.direct());
        assertFalse(request.limits().partial());
        assertEquals(LongLeg.exact(new Vec3(.5, 64, .5), new Vec3(20.5, 66, 10.5), WIDTH, HEIGHT), request.bounds());
        LongLeg far = new LongLeg(new Vec3(.5, 64, .5), new Vec3(200.5, 64, .5), WIDTH, HEIGHT);
        assertTrue(far.request(new Vec3(.5, 64, .5)).limits().partial());
    }
}
