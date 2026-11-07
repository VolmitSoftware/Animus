package art.arcane.animus.core.navigation;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static art.arcane.animus.core.navigation.PathPlanner.Status.*;
import static art.arcane.animus.core.navigation.PathPlanner.Motion.STEP;
import static art.arcane.animus.core.navigation.PathPlanner.Motion.JUMP;
import static art.arcane.animus.core.navigation.PathPlanner.Motion.FALL;
import static art.arcane.animus.core.navigation.MovementProfile.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class PathPlannerTest {
    private static final VoxelSnapshot.Cell AIR = VoxelSnapshot.Cell.AIR, SOLID = VoxelSnapshot.Cell.SOLID,
            WATER = VoxelSnapshot.Cell.WATER, LAVA = VoxelSnapshot.Cell.LAVA;
    private static final PathPlanner.Limits NORMAL = new PathPlanner.Limits(5000, 5000, 500);
    private static final MovementProfile FLAT = new MovementProfile(Set.of(WALK), 0.6, 2, 0, 0, 0, true);
    private static final BlockPos START = new BlockPos(2, 1, 2);
    private final PathPlanner planner = new PathPlanner();

    private static VoxelSnapshot.Builder room(int width, int height, int depth) {
        var builder = new VoxelSnapshot.Builder("world", 0, 0, 0, width, height, depth).fill(AIR);
        for (int cx = 0; cx <= (width - 1) / 16; cx++) for (int cz = 0; cz <= (depth - 1) / 16; cz++) builder.availableChunk(cx, cz);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++)
            if (x == 0 || x == width - 1 || z == 0 || z == depth - 1 || y == 0 || y == height - 1) builder.set(x, y, z, SOLID);
        return builder;
    }
    private PathPlanner.Result plan(VoxelSnapshot snapshot, BlockPos start, BlockPos goal, MovementProfile profile) {
        return planner.plan(snapshot, start, goal, profile, NORMAL, () -> false);
    }
    @Test void findsClearWalkingPathWithFiniteVoxelWaypoints() {
        var result = plan(room(10, 5, 10).build(), START, new BlockPos(7, 1, 7), FLAT);
        assertEquals(FOUND, result.status()); assertEquals(6, result.path().size());
        assertEquals(START, result.path().getFirst().feet()); assertEquals(PathPlanner.Motion.START, result.path().getFirst().motion());
        assertEquals(new BlockPos(7, 1, 7), result.path().getLast().feet());
        assertTrue(result.path().stream().skip(1).allMatch(s -> s.motion() == PathPlanner.Motion.WALK));
        assertEquals(7.5, result.path().getLast().position("world").point().x());
    }
    @Test void sealedBlockedRouteAndInsufficientHeadroomAreUnreachable() {
        var builder = room(9, 5, 7);
        for (int y = 1; y < 4; y++) for (int z = 1; z < 6; z++) builder.set(4, y, z, SOLID);
        var result = plan(builder.build(), START, new BlockPos(6, 1, 2), FLAT);
        assertEquals(UNREACHABLE, result.status()); assertTrue(result.path().isEmpty()); assertFalse(result.encounteredUnavailableTerrain());
        builder = room(7, 5, 7); builder.set(5, 2, 2, SOLID);
        assertEquals(UNREACHABLE, plan(builder.build(), START, new BlockPos(5, 1, 2), FLAT).status());
    }
    @Test void walkingDiagonalCannotCutSolidCorners() {
        var builder = room(5, 5, 5);
        builder.set(2, 1, 1, SOLID).set(2, 2, 1, SOLID).set(1, 1, 2, SOLID).set(1, 2, 2, SOLID);
        assertEquals(UNREACHABLE, plan(builder.build(), new BlockPos(1, 1, 1), new BlockPos(2, 1, 2), FLAT).status());
    }
    @Test void stepAndJumpCapabilitiesAreDistinctAndRequireVerticalClearance() {
        var builder = room(8, 7, 5); builder.set(3, 1, 2, SOLID);
        BlockPos goal = new BlockPos(3, 2, 2);
        var stepper = new MovementProfile(Set.of(WALK), 0.6, 2, 1, 0, 0, false);
        var jumper = new MovementProfile(Set.of(WALK), 0.6, 2, 0, 1, 0, false);
        assertEquals(STEP, plan(builder.build(), START, goal, stepper).path().getLast().motion());
        assertEquals(JUMP, plan(builder.build(), START, goal, jumper).path().getLast().motion());
        assertEquals(UNREACHABLE, plan(builder.build(), START, goal, FLAT).status());
        // Seal a single-cell corridor and put a low roof above the start of the ascent.
        for (int x = 1; x < 7; x++) for (int y = 1; y < 6; y++) { builder.set(x, y, 1, SOLID); builder.set(x, y, 3, SOLID); }
        builder.set(2, 3, 2, SOLID);
        assertEquals(UNREACHABLE, plan(builder.build(), START, goal, jumper).status());
    }
    @Test void fallingIsBoundedAndMustFindAClearLanding() {
        var builder = room(7, 8, 5);
        builder.set(2, 1, 2, SOLID).set(2, 2, 2, SOLID).set(2, 3, 2, SOLID);
        BlockPos from = new BlockPos(2, 4, 2), goal = new BlockPos(3, 1, 2);
        var faller = new MovementProfile(Set.of(WALK), 0.6, 2, 0, 0, 3, false);
        var safe = new MovementProfile(Set.of(WALK), 0.6, 2, 0, 0, 2, false);
        var result = plan(builder.build(), from, goal, faller);
        assertEquals(FOUND, result.status()); assertEquals(2, result.path().size()); assertEquals(FALL, result.path().getLast().motion());
        assertEquals(UNREACHABLE, plan(builder.build(), from, goal, safe).status());
    }
    /**
     * Citizens' fall distance counts levels, the blocks a body stands on, as the game's pathfinder does. With a fall of 1
     * a body drops from a ledge onto a slab or path one level down although it falls more than a block, and still
     * may not drop two levels to the floor or step off a raised slab to the floor below.
     */
    @Test void theFallLimitCountsLevelsNotTheExactDrop() {
        var fallOfOne = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 1.25, 1, false);
        BlockPos ledge = new BlockPos(3, 3, 2), floor = new BlockPos(9, 1, 2);
        for (VoxelSnapshot.Cell landing : List.of(VoxelSnapshot.Cell.layer(0.5), VoxelSnapshot.Cell.layer(0.9375))) {
            var builder = corridor();
            builder.set(6, 1, 2, landing);
            assertEquals(FOUND, plan(builder.build(), ledge, floor, fallOfOne).status(), "onto " + landing);
        }
        assertEquals(UNREACHABLE, plan(corridor().build(), ledge, floor, fallOfOne).status(), "two levels straight to the floor");
        var raisedSlab = corridor();
        for (int x = 1; x < 6; x++) raisedSlab.set(x, 2, 2, VoxelSnapshot.Cell.layer(0.5));
        assertEquals(UNREACHABLE, plan(raisedSlab.build(), ledge, floor, fallOfOne).status(), "off a raised slab: two levels");
    }
    /**
     * A surface lower than half a block, such as a closed trapdoor or a few snow layers, is part of the level of the block
     * it lies on, so stepping off a trapdoor on a one-block ledge to the floor is a fall of one level, as the way up was.
     */
    @Test void aLowSurfaceOnALedgeCountsAsTheLedgesLevel() {
        var fallOfOne = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 1.25, 1, false);
        var builder = room(12, 7, 5);
        for (int x = 1; x < 11; x++) for (int y = 1; y < 6; y++) { builder.set(x, y, 1, SOLID); builder.set(x, y, 3, SOLID); }
        for (int x = 1; x < 6; x++) { builder.set(x, 1, 2, SOLID); builder.set(x, 2, 2, VoxelSnapshot.Cell.layer(0.1875)); }
        assertEquals(FOUND, plan(builder.build(), new BlockPos(3, 2, 2), new BlockPos(9, 1, 2), fallOfOne).status());
    }
    /** A one-wide corridor along x: a two-block ledge for x 1..5, then the floor. */
    private static VoxelSnapshot.Builder corridor() {
        var builder = room(12, 7, 5);
        for (int x = 1; x < 11; x++) for (int y = 1; y < 6; y++) { builder.set(x, y, 1, SOLID); builder.set(x, y, 3, SOLID); }
        for (int x = 1; x < 6; x++) { builder.set(x, 1, 2, SOLID); builder.set(x, 2, 2, SOLID); }
        return builder;
    }
    @Test void unknownChunksOrUncapturedCellsSuspendInsteadOfBecomingAir() {
        var builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 32, 5, 5).fill(AIR).availableChunk(0, 0);
        for (int x = 0; x < 32; x++) for (int z = 0; z < 5; z++) builder.set(x, 0, z, SOLID);
        var result = plan(builder.build(), new BlockPos(14, 1, 2), new BlockPos(17, 1, 2), FLAT);
        assertEquals(UNAVAILABLE_TERRAIN, result.status()); assertEquals(0, result.visitedNodes());
        var unknown = new VoxelSnapshot.Builder("world", 0, 0, 0, 5, 5, 5).availableChunk(0, 0).build();
        assertEquals(UNAVAILABLE_TERRAIN, plan(unknown, START, new BlockPos(3, 1, 2), FLAT).status());
    }
    @Test void captureBoundaryIsUnreachableHereButFlaggedAndOnlyUnloadedChunksWait() {
        var builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 7, 5, 5).fill(AIR).availableChunk(0, 0);
        for (int x = 0; x < 7; x++) for (int z = 0; z < 5; z++) builder.set(x, 0, z, SOLID);
        for (int y = 1; y < 5; y++) for (int z = 0; z < 5; z++) builder.set(4, y, z, SOLID);
        var bounded = plan(builder.build(), START, new BlockPos(5, 1, 2), FLAT);
        assertEquals(UNREACHABLE, bounded.status()); assertTrue(bounded.encounteredUnavailableTerrain(), "a larger capture may still find a way");
        var unloaded = new VoxelSnapshot.Builder("world", 0, 0, 0, 20, 5, 5).fill(AIR).availableChunk(0, 0);
        for (int x = 0; x < 20; x++) for (int z = 0; z < 5; z++) unloaded.set(x, 0, z, SOLID);
        for (int y = 1; y < 5; y++) for (int z = 0; z < 5; z++) unloaded.set(4, y, z, SOLID);
        assertEquals(UNAVAILABLE_TERRAIN, plan(unloaded.build(), START, new BlockPos(5, 1, 2), FLAT).status());
    }
    @Test void aPartialSearchEndsAtTheReachablePlaceClosestToAGoalOutsideItsCapture() {
        var partial = new PathPlanner.Limits(5000, 5000, 500, true);
        var open = room(10, 5, 10).build();
        var result = planner.plan(open, START, new BlockPos(100, 1, 7), FLAT, partial, () -> false);
        assertEquals(PARTIAL, result.status()); assertEquals(new BlockPos(8, 1, 7), result.path().getLast().feet());
        assertEquals(FOUND, planner.plan(open, START, new BlockPos(7, 1, 7), FLAT, partial, () -> false).status(), "a goal inside is reached exactly");
        var sealed = room(10, 5, 10);
        for (int x = 1; x < 9; x++) for (int y = 1; y < 4; y++) sealed.set(x, y, 3, SOLID);
        for (int z = 1; z < 3; z++) for (int y = 1; y < 4; y++) sealed.set(3, y, z, SOLID);
        assertEquals(UNREACHABLE, planner.plan(sealed.build(), new BlockPos(2, 1, 1), new BlockPos(100, 1, 1), FLAT, partial, () -> false).status(),
                "no step gets closer: the way is blocked, not waiting for terrain");
    }
    @Test void narrowGapRequiresEnoughFootprintClearance() {
        var builder = room(11, 5, 11);
        for (int y = 1; y < 4; y++) for (int z = 1; z < 10; z++) if (z != 5) builder.set(5, y, z, SOLID);
        BlockPos from = new BlockPos(2, 1, 5), goal = new BlockPos(8, 1, 5);
        assertEquals(FOUND, plan(builder.build(), from, goal, FLAT).status());
        var wide = new MovementProfile(Set.of(WALK), 2, 2, 0, 0, 0, true);
        assertEquals(UNREACHABLE, plan(builder.build(), from, goal, wide).status());
    }
    @Test void swimmingAndFlightAreExplicitAndLavaRemainsBlocked() {
        var builder = room(8, 7, 5);
        for (int x = 1; x < 7; x++) for (int y = 1; y < 5; y++) builder.set(x, y, 2, WATER);
        var swimmer = new MovementProfile(Set.of(SWIM), 0.6, 2, 0, 0, 0, false);
        var swim = plan(builder.build(), START, new BlockPos(5, 3, 2), swimmer);
        assertEquals(FOUND, swim.status()); assertTrue(swim.path().stream().skip(1).allMatch(s -> s.motion() == PathPlanner.Motion.SWIM));
        assertEquals(UNREACHABLE, plan(builder.build(), START, new BlockPos(5, 1, 2), FLAT).status());
        builder.set(5, 3, 2, LAVA);
        assertEquals(UNREACHABLE, plan(builder.build(), START, new BlockPos(5, 3, 2), swimmer).status());
        var flyer = new MovementProfile(Set.of(FLY), 0.6, 2, 0, 0, 0, true);
        var fly = plan(room(8, 8, 8).build(), START, new BlockPos(5, 4, 5), flyer);
        assertEquals(FOUND, fly.status()); assertTrue(fly.path().stream().skip(1).allMatch(s -> s.motion() == PathPlanner.Motion.FLY));
    }
    @Test void flyingDiagonalCannotPassBetweenTouchingObstacles() {
        var builder = room(5, 5, 5);
        builder.set(2, 1, 1, SOLID).set(1, 2, 1, SOLID).set(1, 1, 2, SOLID);
        var flyer = new MovementProfile(Set.of(FLY), 0.6, 1, 0, 0, 0, true);
        assertEquals(UNREACHABLE, plan(builder.build(), new BlockPos(1, 1, 1), new BlockPos(2, 2, 2), flyer).status());
    }
    @Test void allSearchBudgetsAndCancellationAreObservable() {
        var snapshot = room(20, 5, 20).build(); BlockPos goal = new BlockPos(17, 1, 17);
        var visits = planner.plan(snapshot, START, goal, FLAT, new PathPlanner.Limits(1, 100, 100), () -> false);
        assertEquals(BUDGET_EXHAUSTED, visits.status()); assertEquals(1, visits.visitedNodes());
        var frontier = planner.plan(snapshot, START, goal, FLAT, new PathPlanner.Limits(100, 1, 100), () -> false);
        assertEquals(BUDGET_EXHAUSTED, frontier.status()); assertEquals(1, frontier.peakFrontier());
        var length = planner.plan(snapshot, START, goal, FLAT, new PathPlanner.Limits(1000, 1000, 2), () -> false);
        assertEquals(BUDGET_EXHAUSTED, length.status()); assertTrue(length.path().isEmpty());
        assertEquals(CANCELLED, planner.plan(snapshot, START, goal, FLAT, NORMAL, () -> true).status());
        AtomicInteger checks = new AtomicInteger();
        assertEquals(CANCELLED, planner.plan(snapshot, START, goal, FLAT, NORMAL, () -> checks.incrementAndGet() > 30).status());
    }
    @Test void cancellationDuringTheLastExpansionDoesNotBecomeUnreachable() {
        AtomicInteger checks = new AtomicInteger();
        var result = planner.plan(room(8, 5, 8).build(), START, new BlockPos(5, 1, 5), FLAT, NORMAL,
                () -> checks.incrementAndGet() > 3);
        assertEquals(CANCELLED, result.status()); assertTrue(result.path().isEmpty());
    }
    @Test void independentPlansCanShareOneImmutableSnapshotAcrossWorkers() throws Exception {
        var snapshot = room(20, 5, 20).build(); BlockPos goal = new BlockPos(17, 1, 17);
        List<PathPlanner.Step> expected = plan(snapshot, START, goal, FLAT).path();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<PathPlanner.Result>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) tasks.add(() -> plan(snapshot, START, goal, FLAT));
            for (Future<PathPlanner.Result> result : executor.invokeAll(tasks)) {
                assertEquals(FOUND, result.get().status()); assertEquals(expected, result.get().path());
            }
        }
    }
}
