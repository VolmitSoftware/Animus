package art.arcane.animus.core.navigation;

import static art.arcane.animus.core.navigation.MovementProfile.Mode.FLY;
import static art.arcane.animus.core.navigation.MovementProfile.Mode.SWIM;
import static art.arcane.animus.core.navigation.MovementProfile.Mode.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PartialTerrainPlannerTest {
    private static final PathPlanner.Limits NORMAL = new PathPlanner.Limits(5000, 5000, 500);
    private static final MovementProfile ORDINARY = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 0, 3, true);
    private static final MovementProfile JUMPER = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 1.25, 3, true);
    private static final MovementProfile SWIMMER = new MovementProfile(Set.of(WALK, SWIM), 0.6, 1.8, 0.6, 1.25, 3, true);
    private static final MovementProfile LARGE = new MovementProfile(Set.of(WALK), 1.4, 1.6, 1, 0, 3, true);
    private static final MovementProfile FLIER = new MovementProfile(Set.of(FLY), 0.6, 1.8, 0, 0, 0, true);
    private static final BlockPos START = new BlockPos(2, 1, 3);
    /** Rises towards +X: the low half faces a climber coming from -X. */
    private static final Cell STAIR = Cell.of(List.of(new Cell.Box(0, 0, 0, 1, 0.5, 1), new Cell.Box(0.5, 0.5, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    /** A post with rails running along Z, as in a connected fence line. */
    private static final Cell FENCE = Cell.of(List.of(new Cell.Box(0.375, 0, 0, 0.625, 1.5, 1)), Cell.Fluid.NONE, false);
    private static final Cell GRASS = Cell.of(List.of(), Cell.Fluid.NONE, false);
    private static final Cell FIRE = Cell.of(List.of(), Cell.Fluid.NONE, true);
    private static final Cell MAGMA = Cell.of(List.of(new Cell.Box(0, 0, 0, 1, 1, 1)), Cell.Fluid.NONE, true);

    /** A closed room: floor, ceiling and walls are full blocks, so nothing reaches unavailable terrain. */
    private static VoxelSnapshot.Builder room(int width, int height, int depth) {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, width, height, depth).fill(Cell.AIR);
        for (int cx = 0; cx <= (width - 1) / 16; cx++) for (int cz = 0; cz <= (depth - 1) / 16; cz++) builder.availableChunk(cx, cz);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++)
            if (x == 0 || x == width - 1 || z == 0 || z == depth - 1 || y == 0 || y == height - 1) builder.set(x, y, z, Cell.SOLID);
        return builder;
    }
    private static VoxelSnapshot.Builder room() { return room(9, 7, 7); }
    private static void row(VoxelSnapshot.Builder builder, int x, int y, Cell cell) {
        for (int z = 1; z <= 5; z++) builder.set(x, y, z, cell);
    }
    private static PathPlanner.Result plan(VoxelSnapshot.Builder builder, BlockPos goal, MovementProfile profile) {
        return plan(builder, START, goal, profile);
    }
    private static PathPlanner.Result plan(VoxelSnapshot.Builder builder, BlockPos start, BlockPos goal, MovementProfile profile) {
        return new PathPlanner().plan(builder.build(), start, goal, profile, NORMAL, () -> false);
    }

    @Test void grassAndOtherNonCollidingBlocksAreWalkedThrough() {
        VoxelSnapshot.Builder builder = room();
        row(builder, 4, 1, GRASS);
        PathPlanner.Result result = plan(builder, new BlockPos(6, 1, 3), ORDINARY);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        assertEquals(1, result.path().getLast().y(), 1e-9);
        assertTrue(result.path().stream().skip(1).allMatch(step -> step.motion() == PathPlanner.Motion.WALK), result.path().toString());
    }
    @Test void slabIsSteppedOntoAndTheBodyStandsAtItsRealHeight() {
        VoxelSnapshot.Builder builder = room();
        row(builder, 4, 1, Cell.layer(0.5));
        row(builder, 5, 1, Cell.layer(0.5));
        PathPlanner.Result result = plan(builder, new BlockPos(5, 1, 3), ORDINARY);
        assertEquals(PathPlanner.Status.FOUND, result.status(), result.toString());
        assertEquals(1.5, result.path().getLast().y(), 1e-9);
        assertEquals(1.5, result.path().getLast().position("world").point().y(), 1e-9);
        assertTrue(result.path().stream().anyMatch(step -> step.motion() == PathPlanner.Motion.STEP));
    }
    @Test void stepHeightDecidesBetweenAClimbAndAWall() {
        VoxelSnapshot.Builder low = room();
        row(low, 4, 1, Cell.layer(0.5));
        assertEquals(PathPlanner.Status.FOUND, plan(low, new BlockPos(4, 1, 3), ORDINARY).status());
        VoxelSnapshot.Builder high = room();
        row(high, 4, 1, Cell.layer(0.75));
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(high, new BlockPos(4, 1, 3), ORDINARY).status(), "0.75 exceeds a 0.6 step");
        assertEquals(PathPlanner.Status.FOUND, plan(high, new BlockPos(4, 1, 3), LARGE).status(), "a larger body has a larger step");
        VoxelSnapshot.Builder full = room();
        row(full, 4, 1, Cell.SOLID);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(full, new BlockPos(4, 2, 3), ORDINARY).status(), "an ordinary body cannot step a full block");
        assertEquals(PathPlanner.Status.FOUND, plan(full, new BlockPos(4, 2, 3), LARGE).status());
    }
    @Test void jumpCapabilityClimbsWhatTheStepCannot() {
        VoxelSnapshot.Builder full = room();
        row(full, 4, 1, Cell.SOLID);
        PathPlanner.Result result = plan(full, new BlockPos(4, 2, 3), JUMPER);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        assertEquals(PathPlanner.Motion.JUMP, result.path().getLast().motion());
    }
    @Test void stairsClimbFromTheLowSideAndRefuseTheBack() {
        VoxelSnapshot.Builder builder = room();
        row(builder, 4, 1, STAIR);
        row(builder, 5, 1, Cell.SOLID);
        row(builder, 5, 2, STAIR);
        row(builder, 6, 1, Cell.SOLID);
        row(builder, 6, 2, Cell.SOLID);
        PathPlanner.Result up = plan(builder, new BlockPos(6, 3, 3), ORDINARY);
        assertEquals(PathPlanner.Status.FOUND, up.status(), up.toString());
        assertEquals(3, up.path().getLast().y(), 1e-9);
        VoxelSnapshot.Builder back = room();
        row(back, 4, 1, STAIR);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(back, new BlockPos(6, 1, 3), new BlockPos(4, 2, 3), ORDINARY).status(),
                "the back of a stair is a full block");
        assertEquals(PathPlanner.Status.FOUND, plan(back, new BlockPos(6, 1, 3), new BlockPos(4, 2, 3), JUMPER).status());
    }
    @Test void slabDescentIsWalkedDownWithoutAFallCapability() {
        VoxelSnapshot.Builder builder = room();
        row(builder, 4, 1, Cell.layer(0.5));
        MovementProfile noFall = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 0, 0, true);
        PathPlanner.Result result = plan(builder, new BlockPos(4, 1, 3), new BlockPos(6, 1, 3), noFall);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        assertEquals(1, result.path().getLast().y(), 1e-9);
    }
    @Test void fencesAndWallsAreOneAndAHalfHighSoNeitherStepNorJumpClearsThem() {
        VoxelSnapshot.Builder fence = room();
        row(fence, 4, 1, FENCE);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(fence, new BlockPos(6, 1, 3), JUMPER).status());
        VoxelSnapshot.Builder wall = room();
        row(wall, 4, 1, Cell.layer(1.5));
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(wall, new BlockPos(6, 1, 3), JUMPER).status());
        PathPlanner.Result above = plan(wall, new BlockPos(6, 3, 3), FLIER);
        assertEquals(PathPlanner.Status.FOUND, above.status());
        assertTrue(above.path().stream().filter(step -> step.feet().x() == 4).allMatch(step -> step.y() >= 1.5), "flight clears the overhang above the voxel");
    }
    @Test void openAndClosedGatesFollowTheirCollisionShape() {
        VoxelSnapshot.Builder closed = room();
        row(closed, 4, 1, Cell.layer(1.5));
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(closed, new BlockPos(6, 1, 3), ORDINARY).status());
        VoxelSnapshot.Builder open = room();
        row(open, 4, 1, Cell.layer(1.5));
        open.set(4, 1, 3, Cell.AIR);
        assertEquals(PathPlanner.Status.FOUND, plan(open, new BlockPos(6, 1, 3), ORDINARY).status());
    }
    @Test void aDoorPanelOnlyBlocksTheAxisItCrosses() {
        Cell closedPanel = Cell.of(List.of(new Cell.Box(0, 0, 0, 0.1875, 1, 1)), Cell.Fluid.NONE, false);
        VoxelSnapshot.Builder crossing = room();
        row(crossing, 4, 1, closedPanel);
        row(crossing, 4, 2, closedPanel);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(crossing, new BlockPos(6, 1, 3), ORDINARY).status(), "closed: the panel spans the path");
        Cell openPanel = Cell.of(List.of(new Cell.Box(0, 0, 0, 1, 1, 0.1875)), Cell.Fluid.NONE, false);
        VoxelSnapshot.Builder parallel = room();
        row(parallel, 4, 1, openPanel);
        row(parallel, 4, 2, openPanel);
        assertEquals(PathPlanner.Status.FOUND, plan(parallel, new BlockPos(6, 1, 3), ORDINARY).status(), "open: the panel lies along the path");
    }
    @Test void bodiesAvoidLavaFireAndHazardousGround() {
        for (Cell hazard : List.of(Cell.LAVA, FIRE)) {
            VoxelSnapshot.Builder builder = room();
            row(builder, 4, 1, hazard);
            assertEquals(PathPlanner.Status.UNREACHABLE, plan(builder, new BlockPos(6, 1, 3), ORDINARY).status(), hazard.toString());
        }
        VoxelSnapshot.Builder magma = room();
        row(magma, 4, 0, MAGMA);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(magma, new BlockPos(6, 1, 3), ORDINARY).status(), "magma is not walked on");
        VoxelSnapshot.Builder detour = room();
        for (int z = 1; z <= 4; z++) detour.set(4, 1, z, FIRE);
        assertEquals(PathPlanner.Status.FOUND, plan(detour, new BlockPos(6, 1, 3), ORDINARY).status(), "a gap in the hazard is used");
        VoxelSnapshot.Builder standing = room();
        standing.set(2, 1, 3, FIRE);
        assertEquals(PathPlanner.Status.FOUND, plan(standing, new BlockPos(6, 1, 3), ORDINARY).status(), "a body already in a hazard can leave it");
    }
    @Test void walkersWadeShallowWaterButNeedSwimmingForDeepWater() {
        VoxelSnapshot.Builder shallow = room();
        row(shallow, 4, 1, Cell.WATER);
        assertEquals(PathPlanner.Status.FOUND, plan(shallow, new BlockPos(6, 1, 3), ORDINARY).status());
        VoxelSnapshot.Builder deep = room();
        for (int y = 1; y <= 4; y++) row(deep, 4, y, Cell.WATER);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(deep, new BlockPos(6, 1, 3), ORDINARY).status());
    }
    @Test void swimmersCrossAPondAtTheSurfaceAndClimbOutOntoTheBank() {
        VoxelSnapshot.Builder builder = room(10, 8, 7);
        for (int y = 1; y <= 3; y++) { row(builder, 1, y, Cell.SOLID); row(builder, 7, y, Cell.SOLID); row(builder, 8, y, Cell.SOLID); }
        for (int x = 2; x <= 6; x++) { row(builder, x, 1, Cell.SOLID); row(builder, x, 2, Cell.WATER); row(builder, x, 3, Cell.WATER); }
        PathPlanner.Result result = plan(builder, new BlockPos(1, 4, 3), new BlockPos(8, 4, 3), SWIMMER);
        assertEquals(PathPlanner.Status.FOUND, result.status(), result.toString());
        assertTrue(result.path().stream().anyMatch(PathPlanner.Step::swimming));
        assertTrue(result.path().stream().filter(PathPlanner.Step::swimming).allMatch(step -> step.y() == 3),
                "the body stays in the top water block: " + result.path());
        assertEquals(4, result.path().getLast().y(), 1e-9);
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(builder, new BlockPos(1, 4, 3), new BlockPos(8, 4, 3), JUMPER).status());
    }
    @Test void goalNamedByTheBlockAboveALowSurfaceResolvesToThatSurface() {
        VoxelSnapshot.Builder builder = room();
        row(builder, 4, 1, Cell.layer(0.5));
        row(builder, 5, 1, Cell.layer(0.875));
        row(builder, 6, 1, Cell.layer(0.875));
        PathPlanner.Result result = plan(builder, new BlockPos(6, 2, 3), ORDINARY);
        assertEquals(PathPlanner.Status.FOUND, result.status());
        assertEquals(1.875, result.path().getLast().y(), 1e-9);
    }
    @Test void largeBodiesNeedRoomForTheirWholeHull() {
        VoxelSnapshot.Builder builder = room();
        for (int y = 1; y <= 4; y++) for (int z = 1; z <= 5; z++) if (z != 3) builder.set(4, y, z, Cell.SOLID);
        assertEquals(PathPlanner.Status.FOUND, plan(builder, new BlockPos(6, 1, 3), ORDINARY).status());
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(builder, new BlockPos(6, 1, 3), LARGE).status());
    }
    @Test void aCaptureStaysCompactWithManyShapes() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 16, 32, 16).availableChunk(0, 0);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) builder.set(x, y, z, Cell.layer((1 + (x + y + z) % 16) / 16d));
        VoxelSnapshot snapshot = builder.build();
        assertEquals(8192, snapshot.cellCount());
        assertEquals(17, snapshot.paletteSize(), "shapes are shared, not stored per voxel");
        assertEquals(Cell.layer(1 / 16d), snapshot.cell(0, 0, 0));
        assertEquals(Cell.SOLID, Cell.layer(1));
    }
}
