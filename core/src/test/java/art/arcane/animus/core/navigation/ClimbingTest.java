package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClimbingTest {
    private static final PathPlanner.Limits NORMAL = new PathPlanner.Limits(5000, 5000, 500);
    private static final MovementProfile CLIMBER = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 3, true, true, false);
    private static final MovementProfile WALKER = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 3, true);
    private static final Cell SCAFFOLD = CollisionCells.classify("SCAFFOLDING", List.of(), false);
    private static final Cell LADDER = CollisionCells.classify("LADDER", List.of(new Cell.Box(0, 0, .8125, 1, 1, 1)), false);

    /** Ground at y=0 and a column of {@code cell} at x=2,z=2 from y=1 up to {@code top}. */
    private static VoxelSnapshot column(Cell cell, int top) {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 6, 20, 6).fill(Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) builder.set(x, 0, z, Cell.SOLID);
        for (int y = 1; y <= top; y++) builder.set(2, y, 2, cell);
        return builder.build();
    }
    private static PathPlanner.Result plan(VoxelSnapshot snapshot, BlockPos start, BlockPos goal, MovementProfile profile) {
        return new PathPlanner().plan(snapshot, start, goal, profile, NORMAL, () -> false);
    }

    @Test void scaffoldingAndClimbablePlantsAreClimbableCellsAndScaffoldingHasATopToStandOn() {
        assertTrue(SCAFFOLD.climbable());
        assertEquals(1, SCAFFOLD.top(), 1e-9);
        assertTrue(LADDER.climbable());
        assertTrue(CollisionCells.classify("VINE", List.of(), false).climbable());
        assertTrue(CollisionCells.classify("WEEPING_VINES_PLANT", List.of(), false).climbable());
        assertFalse(Cell.SOLID.climbable());
        assertFalse(CollisionCells.classify("STONE", Cell.SOLID.boxes(), false).climbable());
    }

    @Test void aBodyInsideScaffoldingIsNotObstructedButStandsOnItsTop() {
        Vec3 inside = new Vec3(2.5, 1, 2.5), top = new Vec3(2.5, 6, 2.5);
        MotionValidator.Sampler cells = column(SCAFFOLD, 5)::cell;
        assertTrue(MotionValidator.check(inside, inside, .6, 1.8, MotionValidator.Medium.GROUND, true, 512, cells).clear());
        assertTrue(MotionValidator.check(top, top, .6, 1.8, MotionValidator.Medium.GROUND, true, 512, cells).clear());
        assertTrue(MotionValidator.check(new Vec3(2.5, 3, 2.5), new Vec3(2.5, 4, 2.5), .6, 1.8, MotionValidator.Medium.CLIMB, false, 512, cells).clear());
    }

    @Test void climbingNeedsSomethingToHoldOnto() {
        MotionValidator.Sampler empty = column(Cell.AIR, 0)::cell;
        assertFalse(MotionValidator.check(new Vec3(2.5, 3, 2.5), new Vec3(2.5, 4, 2.5), .6, 1.8, MotionValidator.Medium.CLIMB, false, 512, empty).clear());
        MotionValidator.Sampler ladder = column(LADDER, 8)::cell;
        assertTrue(MotionValidator.check(new Vec3(2.5, 3, 2.5), new Vec3(2.5, 4, 2.5), .6, 1.8, MotionValidator.Medium.CLIMB, false, 512, ladder).clear());
    }

    @Test void aClimbingBodyGoesUpAScaffoldColumnAndStandsOnTop() {
        PathPlanner.Result result = plan(column(SCAFFOLD, 8), new BlockPos(1, 1, 2), new BlockPos(2, 9, 2), CLIMBER);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        assertEquals(new BlockPos(2, 9, 2), result.path().getLast().feet());
        assertTrue(result.path().stream().filter((PathPlanner.Step step) -> step.motion() == PathPlanner.Motion.CLIMB).count() >= 8);
        assertTrue(plan(column(SCAFFOLD, 8), new BlockPos(1, 1, 2), new BlockPos(2, 9, 2), WALKER).status() != PathPlanner.Status.FOUND);
    }

    @Test void aClimbingBodyComesBackDownAndHangsOnALadder() {
        PathPlanner.Result down = plan(column(SCAFFOLD, 8), new BlockPos(2, 9, 2), new BlockPos(2, 1, 2), CLIMBER);
        assertEquals(PathPlanner.Status.FOUND, down.status());
        assertEquals(new BlockPos(2, 1, 2), down.path().getLast().feet());
        PathPlanner.Result ladder = plan(column(LADDER, 10), new BlockPos(1, 1, 2), new BlockPos(2, 6, 2), CLIMBER);
        assertEquals(PathPlanner.Status.FOUND, ladder.status());
        assertEquals(6, ladder.path().getLast().y(), 1e-9);
    }

    @Test void executionClimbsStraightUpInPlaceAtTheRequestedSpeed() {
        VoxelSnapshot snapshot = column(SCAFFOLD, 4);
        PathPlanner.Result result = plan(snapshot, new BlockPos(2, 1, 2), new BlockPos(2, 5, 2), CLIMBER);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        PathExecution execution = new PathExecution(new Vec3(2.5, 1, 2.5), result.path(), new Vec3(2.5, 5, 2.5), CLIMBER);
        int steps = 0;
        while (!execution.finished() && steps < 200) {
            PathExecution.Proposal proposal = execution.next(.1).orElseThrow();
            assertEquals(2.5, proposal.to().x(), 1e-9);
            assertEquals(MotionValidator.Medium.CLIMB, proposal.medium());
            assertTrue(MotionValidator.check(proposal.from(), proposal.to(), .6, 1.8, proposal.medium(), proposal.requireSupport(), 512, snapshot::cell).clear());
            execution.commit(proposal);
            steps++;
        }
        assertTrue(execution.finished());
        assertTrue(steps >= 40 && steps <= 44, "four blocks at a tenth of a block per step: " + steps);
    }

    @Test void aBodyHangingOnALadderDoesNotFall() {
        IdleFall.Step held = IdleFall.step(new Vec3(2.5, 5, 2.5), 0, .6, 1.8, false, column(LADDER, 8)::cell);
        assertEquals(IdleFall.Outcome.SUPPORTED, held.outcome());
        IdleFall.Step loose = IdleFall.step(new Vec3(2.5, 5, 2.5), 0, .6, 1.8, false, column(Cell.AIR, 0)::cell);
        assertEquals(IdleFall.Outcome.FALLING, loose.outcome());
    }

    @Test void walkingBodiesClimbByDefaultButArmorStandsAndFlyersDoNot() {
        assertTrue(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK).climbs());
        assertTrue(BodyMobility.forRoute("minecraft:villager", .6, 1.95, MovementProfile.Mode.SWIM).climbs());
        assertFalse(BodyMobility.forRoute("minecraft:armor_stand", .5, 2, MovementProfile.Mode.WALK).climbs());
        assertFalse(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.FLY).climbs());
        MovementProfile applied = MovementSettings.DEFAULT.apply(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK), "minecraft:player");
        assertTrue(applied.climbs());
    }

    @Test void aRouteClimbsALadderOntoAFloorAndWalksThroughADoor() {
        MovementProfile both = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 3, true, true, true);
        MovementProfile opener = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 3, true, false, true);
        Cell door = CollisionCells.classify("OAK_DOOR", List.of(new Cell.Box(0, 0, 0, 0.1875, 1, 1)), false,
                CollisionCells.openShape(CollisionCells.Opening.DOOR, "EAST", false));
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 10, 10, 6).fill(Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 10; x++) for (int z = 0; z < 6; z++) {
            builder.set(x, 0, z, Cell.SOLID);
            if (x >= 3) builder.set(x, 4, z, Cell.SOLID);
            if (x == 6) for (int y = 5; y <= 6; y++) builder.set(x, y, z, z == 2 ? door : Cell.SOLID);
        }
        for (int y = 1; y <= 5; y++) builder.set(2, y, 2, LADDER);
        VoxelSnapshot snapshot = builder.build();
        PathPlanner.Result result = plan(snapshot, new BlockPos(1, 1, 2), new BlockPos(8, 5, 2), both);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        assertTrue(result.path().stream().anyMatch((PathPlanner.Step step) -> step.motion() == PathPlanner.Motion.CLIMB));
        assertTrue(result.path().stream().anyMatch((PathPlanner.Step step) -> step.feet().x() == 6), "the route passes the doorway");
        assertTrue(plan(snapshot, new BlockPos(1, 1, 2), new BlockPos(8, 5, 2), CLIMBER).status() != PathPlanner.Status.FOUND, "a closed door stops a body that does not open it");
        assertTrue(plan(snapshot, new BlockPos(1, 1, 2), new BlockPos(8, 5, 2), opener).status() != PathPlanner.Status.FOUND, "the ladder stops a body that does not climb");
        assertTrue(door.openable() && !door.climbable() && LADDER.climbable() && !LADDER.openable());
    }
    /**
     * A ledge six blocks up with a vine down its side and no other way down a body with a fall of 1 may take: the body
     * steps off the ledge onto the vine, holding it, and climbs down, as a player does.
     */
    @Test void aBodyStepsOffALedgeOntoAVineAndClimbsDown() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 6, 12, 5).fill(Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 6; x++) for (int z = 0; z < 5; z++) builder.set(x, 0, z, Cell.SOLID);
        for (int x = 0; x < 2; x++) for (int y = 1; y < 6; y++) for (int z = 0; z < 5; z++) builder.set(x, y, z, Cell.SOLID);
        Cell vine = CollisionCells.classify("VINE", List.of(), false);
        for (int y = 1; y <= 6; y++) builder.set(2, y, 2, vine);
        MovementProfile fallOfOne = new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 1, true, true, false);
        VoxelSnapshot snapshot = builder.build();
        PathPlanner.Result result = plan(snapshot, new BlockPos(0, 6, 2), new BlockPos(3, 1, 2), fallOfOne);
        assertEquals(PathPlanner.Status.FOUND, result.status(), result.toString());
        PathExecution execution = new PathExecution(new Vec3(.5, 6, 2.5), result.path(), new Vec3(3.5, 1, 2.5), fallOfOne);
        MotionValidator.Sampler cells = snapshot::cell;
        for (int i = 0; i < 2000 && !execution.finished(); i++) {
            PathExecution.Proposal step = execution.next(.05).orElseThrow();
            assertTrue(MotionValidator.check(step.from(), step.to(), .6, 1.8, step.medium(), step.requireSupport(), 512, cells).clear(), step.toString());
            execution.commit(step);
        }
        assertTrue(execution.finished());
    }
}
