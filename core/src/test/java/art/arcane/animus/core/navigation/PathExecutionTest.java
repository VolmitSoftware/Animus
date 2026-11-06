package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PathExecutionTest {
    private static final PathPlanner.Limits NORMAL = new PathPlanner.Limits(5000, 5000, 500);
    private static final MovementProfile WALKER = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 3, true);

    private static VoxelSnapshot.Builder ground() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 8, 8, 5).fill(Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 5; z++) builder.set(x, 0, z, Cell.SOLID);
        return builder;
    }
    private static List<PathPlanner.Step> plan(VoxelSnapshot snapshot, BlockPos start, BlockPos goal, MovementProfile profile) {
        PathPlanner.Result result = new PathPlanner().plan(snapshot, start, goal, profile, NORMAL, () -> false);
        assertEquals(PathPlanner.Status.FOUND, result.status(), result.toString());
        return result.path();
    }
    private static PathExecution walk(VoxelSnapshot snapshot, Vec3 origin, Vec3 goal) {
        return new PathExecution(origin, plan(snapshot, BlockPos.containing(origin), BlockPos.containing(goal), WALKER), goal, WALKER);
    }

    @Test void walkingCommitsOnlyValidatedBoundedProposalsAndFinishesAtExactGoal() {
        VoxelSnapshot snapshot = ground().build();
        PathExecution execution = walk(snapshot, new Vec3(.5, 1, 2.5), new Vec3(1.75, 1, 2.5));
        run(execution, snapshot, WALKER);
        assertEquals(new Vec3(1.75, 1, 2.5), execution.position());
    }
    /**
     * A step that reaches a path node short of the tick's distance carries the rest into the next step, so a walk over
     * many nodes keeps the configured speed: 6 blocks at 0.43 blocks a tick take 14 ticks, not one per node and more.
     */
    @Test void distanceLeftAtANodeCarriesIntoTheNextStep() {
        VoxelSnapshot snapshot = ground().build();
        PathExecution execution = walk(snapshot, new Vec3(.5, 1, 2.5), new Vec3(6.5, 1, 2.5));
        double perTick = 8.63 / 20;
        int ticks = 0;
        while (!execution.finished()) {
            PathExecution.Proposal step = execution.next(perTick).orElseThrow();
            assertTrue(step.from().distance(step.to()) <= 1 + 1e-9, "one step never exceeds a block");
            execution.commit(step);
            ticks++;
            assertTrue(ticks < 100);
        }
        assertEquals((int) Math.ceil(6 / perTick), ticks);
    }
    @Test void noOpDestinationDoesNotWiggleThroughTheVoxelCenter() {
        Vec3 position = new Vec3(.1, 1, 2.2);
        PathExecution execution = walk(ground().build(), position, position);
        assertTrue(execution.finished());
        assertTrue(execution.next(.2).isEmpty());
        assertEquals(position, execution.position());
    }
    @Test void executionFollowsRealSurfacesAndEndsOnTheSurfaceUnderTheGoal() {
        VoxelSnapshot.Builder builder = ground();
        for (int z = 0; z < 5; z++) { builder.set(3, 1, z, Cell.layer(.5)); builder.set(4, 1, z, Cell.layer(.5)); }
        VoxelSnapshot snapshot = builder.build();
        List<PathPlanner.Step> path = plan(snapshot, new BlockPos(1, 1, 2), new BlockPos(4, 1, 2), WALKER);
        PathExecution execution = new PathExecution(new Vec3(1.5, 1, 2.5), path, new Vec3(4.5, 1, 2.5), WALKER);
        run(execution, snapshot, WALKER);
        assertEquals(new Vec3(4.5, 1.5, 2.5), execution.position(), "the route ends standing on the slab");
    }
    @Test void stepAndJumpRiseBeforeCrossingTheEdge() {
        for (double height : List.of(.5, 1d)) {
            VoxelSnapshot.Builder builder = ground();
            for (int z = 0; z < 5; z++) { builder.set(2, 1, z, Cell.layer(height)); builder.set(3, 1, z, Cell.layer(height)); }
            VoxelSnapshot snapshot = builder.build();
            BlockPos landing = height == 1 ? new BlockPos(3, 2, 2) : new BlockPos(3, 1, 2);
            List<PathPlanner.Step> path = plan(snapshot, new BlockPos(1, 1, 2), landing, WALKER);
            assertEquals(height == 1 ? PathPlanner.Motion.JUMP : PathPlanner.Motion.STEP, path.get(1).motion());
            PathExecution execution = new PathExecution(new Vec3(1.5, 1, 2.5), path, new Vec3(3.5, 1 + height, 2.5), WALKER);
            run(execution, snapshot, WALKER);
            assertEquals(new Vec3(3.5, 1 + height, 2.5), execution.position());
        }
    }
    @Test void fallingLeavesLedgeBeforeDescendingAndChecksTheLanding() {
        VoxelSnapshot.Builder builder = ground();
        for (int z = 0; z < 5; z++) for (int y = 1; y < 3; y++) builder.set(0, y, z, Cell.SOLID);
        VoxelSnapshot snapshot = builder.build();
        List<PathPlanner.Step> path = plan(snapshot, new BlockPos(0, 3, 2), new BlockPos(1, 1, 2), WALKER);
        assertEquals(PathPlanner.Motion.FALL, path.getLast().motion());
        PathExecution execution = new PathExecution(new Vec3(.5, 3, 2.5), path, new Vec3(1.5, 1, 2.5), WALKER);
        run(execution, snapshot, WALKER);
        assertEquals(new Vec3(1.5, 1, 2.5), execution.position());
    }
    @Test void cancelledOrStaleProposalsCannotMoveTheAuthority() {
        PathExecution execution = walk(ground().build(), new Vec3(.5, 1, 2.5), new Vec3(1.5, 1, 2.5));
        PathExecution.Proposal first = execution.next(.1).orElseThrow();
        execution.commit(first);
        assertThrows(IllegalStateException.class, () -> execution.commit(first));
        PathExecution.Proposal next = execution.next(.1).orElseThrow();
        Vec3 stopped = execution.position();
        execution.cancel();
        assertTrue(execution.next(.1).isEmpty());
        assertThrows(IllegalStateException.class, () -> execution.commit(next));
        assertEquals(stopped, execution.position());
    }
    @Test void blockedLiveProbeDoesNotAdvanceThePendingStep() {
        PathExecution execution = walk(ground().build(), new Vec3(.5, 1, 2.5), new Vec3(1.5, 1, 2.5));
        PathExecution.Proposal proposal = execution.next(.25).orElseThrow();
        assertFalse(MotionValidator.check(proposal.from(), proposal.to(), .6, 1.8, proposal.medium(), proposal.requireSupport(), 128, (x, y, z) -> Cell.SOLID).clear());
        assertEquals(new Vec3(.5, 1, 2.5), execution.position());
        assertFalse(execution.finished());
    }
    @Test void swimmingAndFlyingExecuteVerticalRoutesWithExplicitMedia() {
        for (MovementProfile.Mode mode : List.of(MovementProfile.Mode.SWIM, MovementProfile.Mode.FLY)) {
            VoxelSnapshot snapshot = new VoxelSnapshot.Builder("world", 0, 0, 0, 6, 8, 5)
                    .fill(mode == MovementProfile.Mode.SWIM ? Cell.WATER : Cell.AIR).availableChunk(0, 0).build();
            MovementProfile profile = new MovementProfile(Set.of(mode), .6, 1.8, 0, 0, 0, false);
            List<PathPlanner.Step> path = plan(snapshot, new BlockPos(2, 1, 2), new BlockPos(2, 2, 2), profile);
            PathExecution execution = new PathExecution(new Vec3(2.5, 1, 2.5), path, new Vec3(2.5, 2.25, 2.5), profile);
            run(execution, snapshot, profile);
            assertEquals(new Vec3(2.5, 2.25, 2.5), execution.position());
        }
    }
    private static void run(PathExecution execution, VoxelSnapshot snapshot, MovementProfile profile) {
        MotionValidator.Sampler terrain = snapshot::cell;
        double carried = 0;
        for (int i = 0; i < 400 && !execution.finished(); i++) {
            PathExecution.Proposal step = execution.next(.2).orElseThrow();
            // A step goes the tick's distance plus what the step before had left at its node.
            assertTrue(step.from().distance(step.to()) <= .200001 + carried);
            carried = step.unused();
            assertTrue(MotionValidator.check(step.from(), step.to(), profile.width(), profile.height(), step.medium(), step.requireSupport(), 128, terrain).clear(), step.toString());
            if (step.landing()) assertTrue(MotionValidator.check(step.to(), step.to(), profile.width(), profile.height(), step.landingMedium(),
                    step.landingSupport(), 128, terrain).clear(), step.toString());
            execution.commit(step);
        }
        assertTrue(execution.finished());
    }
}
