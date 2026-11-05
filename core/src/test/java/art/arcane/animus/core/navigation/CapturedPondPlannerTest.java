package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import org.junit.jupiter.api.Test;

/** The exact bounded capture a live route takes: nothing outside it is available, including the bottom of deep water. */
class CapturedPondPlannerTest {
    private static final PathPlanner.Limits LIMITS = new PathPlanner.Limits(4096, 4096, 256);

    /** A one-block lane between three-high walls on a one-block floor, as a route from (0,61,3) to (4,61,3) captures it. */
    private static VoxelSnapshot.Builder lane() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", -2, 57, 1, 9, 10, 5).fill(Cell.AIR).availableChunk(-1, 0).availableChunk(0, 0);
        for (int x = -2; x <= 6; x++) for (int z = 1; z <= 5; z++) builder.set(x, 60, z, Cell.SOLID);
        for (int x = -1; x <= 5; x++) for (int z = 2; z <= 4; z++) for (int y = 61; y <= 63; y++) builder.set(x, y, z, Cell.SOLID);
        for (int x = 0; x <= 4; x++) for (int y = 61; y <= 63; y++) builder.set(x, y, 3, Cell.AIR);
        return builder;
    }
    private static PathPlanner.Result plan(VoxelSnapshot.Builder builder, MovementProfile.Mode mode) {
        return new PathPlanner().plan(builder.build(), new BlockPos(0, 61, 3), new BlockPos(4, 61, 3),
                BodyMobility.forRoute("minecraft:player", 0.6, 1.8, mode), LIMITS, () -> false);
    }

    @Test void walkingBodySwimsAcrossWaterWhoseBottomLiesBelowTheCapture() {
        VoxelSnapshot.Builder builder = lane();
        for (int x = 1; x <= 3; x++) for (int y = 57; y <= 60; y++) builder.set(x, y, 3, Cell.WATER);
        PathPlanner.Result result = plan(builder, MovementProfile.Mode.SWIM);
        assertEquals(PathPlanner.Status.FOUND, result.status(), result.toString());
        assertTrue(result.path().stream().filter(PathPlanner.Step::swimming).allMatch(step -> step.y() == 60), result.path().toString());
        assertTrue(result.path().stream().anyMatch(PathPlanner.Step::swimming), result.path().toString());
        assertEquals(61, result.path().getLast().y(), 1e-9);
    }
    @Test void walkingRouteStillRefusesThatWater() {
        VoxelSnapshot.Builder builder = lane();
        for (int x = 1; x <= 3; x++) for (int y = 57; y <= 60; y++) builder.set(x, y, 3, Cell.WATER);
        assertTrue(plan(builder, MovementProfile.Mode.WALK).status() != PathPlanner.Status.FOUND);
    }
}
