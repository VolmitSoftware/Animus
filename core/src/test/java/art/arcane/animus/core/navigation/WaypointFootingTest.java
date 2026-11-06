package art.arcane.animus.core.navigation;

import static art.arcane.animus.core.navigation.MovementProfile.Mode.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A Citizens waypoint names the block a player clicked: the ground under the feet, not the feet. Each case builds one
 * ordinary piece of terrain and asks for a short hop to a goal named both ways; both must reach the surface a body
 * stands on there.
 */
class WaypointFootingTest {
    private static final PathPlanner.Limits LIMITS = new PathPlanner.Limits(5000, 5000, 500);
    /** An imported villager: ordinary step, jump and fall, opens doors and gates, climbs. */
    private static final MovementProfile VILLAGER = new MovementProfile(Set.of(WALK), 0.6, 1.95, 0.6, 1.25, 3, true, true, true);
    private static final BlockPos START = new BlockPos(2, 1, 3);
    private static final Cell TOP_SLAB = Cell.of(List.of(new Cell.Box(0, 0.5, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    /** Rises towards +X. */
    private static final Cell STAIR = Cell.of(List.of(new Cell.Box(0, 0, 0, 1, 0.5, 1), new Cell.Box(0.5, 0.5, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    private static final Cell CARPET = Cell.layer(0.0625);
    /** Two snow layers; one layer has no collision at all. */
    private static final Cell SNOW = Cell.layer(0.125);
    private static final Cell PATH = Cell.layer(0.9375);
    private static final Cell TRAPDOOR = Cell.layer(0.1875);
    /** A fence post with rails along Z, and a closed gate across X that opens to nothing. */
    private static final Cell FENCE = CollisionCells.classify("OAK_FENCE", List.of(new Cell.Box(0.375, 0, 0, 0.625, 1.5, 1)), false);
    private static final Cell GATE = CollisionCells.classify("OAK_FENCE_GATE", List.of(new Cell.Box(0.375, 0, 0, 0.625, 1.5, 1)), false, List.of());
    private static final Cell DOOR = CollisionCells.classify("OAK_DOOR", List.of(new Cell.Box(0, 0, 0, 0.1875, 1, 1)), false,
            CollisionCells.openShape(CollisionCells.Opening.DOOR, "EAST", false));

    /** A closed room with a full-block floor at y=0, so walking feet are at y=1. */
    private static VoxelSnapshot.Builder room() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 12, 8, 7).fill(Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 12; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 7; z++)
            if (x == 0 || x == 11 || z == 0 || z == 6 || y == 0 || y == 7) builder.set(x, y, z, Cell.SOLID);
        return builder;
    }
    private static void row(VoxelSnapshot.Builder builder, int x, int y, Cell cell) { for (int z = 1; z <= 5; z++) builder.set(x, y, z, cell); }

    /** Plans to the goal named by its feet block and by the block under it; both must stand at {@code feet}. */
    private static void reaches(String terrain, Consumer<VoxelSnapshot.Builder> course, BlockPos start, int x, int ground, int z, double feet) {
        VoxelSnapshot.Builder builder = room();
        course.accept(builder);
        VoxelSnapshot snapshot = builder.build();
        for (int y : new int[] {ground, ground + 1}) {
            PathPlanner.Result result = new PathPlanner().plan(snapshot, start, new BlockPos(x, y, z), VILLAGER, LIMITS, () -> false);
            String named = terrain + " goal named at y=" + y + ": " + result;
            assertEquals(PathPlanner.Status.FOUND, result.status(), named);
            assertEquals(feet, result.path().getLast().y(), 1e-9, named);
        }
    }

    @Test void flatGroundNamedByTheGroundBlockIsStoodOn() {
        reaches("flat ground", builder -> {}, START, 6, 0, 3, 1);
    }
    @Test void slabsAreStoodOnAtTheirRealHeight() {
        reaches("bottom slab", builder -> { row(builder, 5, 1, Cell.layer(0.5)); row(builder, 6, 1, Cell.layer(0.5)); }, START, 6, 0, 3, 1.5);
        reaches("top slab floor", builder -> { row(builder, 6, 0, TOP_SLAB); row(builder, 7, 0, TOP_SLAB); }, START, 7, 0, 3, 1);
    }
    @Test void stairsUpToAPlatformReachAGoalOnThePlatform() {
        reaches("stairs", builder -> { row(builder, 5, 1, STAIR); for (int x = 6; x <= 9; x++) row(builder, x, 1, Cell.SOLID); }, START, 8, 1, 3, 2);
        reaches("stair as goal", builder -> { row(builder, 5, 1, STAIR); for (int x = 6; x <= 9; x++) row(builder, x, 1, Cell.SOLID); }, START, 5, 1, 3, 2);
    }
    @Test void carpetsSnowAndTrapdoorsAreThinFloors() {
        reaches("carpet", builder -> { for (int x = 4; x <= 7; x++) row(builder, x, 1, CARPET); }, START, 6, 0, 3, 1.0625);
        reaches("snow", builder -> { for (int x = 4; x <= 7; x++) row(builder, x, 1, SNOW); }, START, 6, 0, 3, 1.125);
        reaches("bottom trapdoor", builder -> { for (int x = 4; x <= 7; x++) row(builder, x, 1, TRAPDOOR); }, START, 6, 0, 3, 1.1875);
    }
    @Test void pathAndFarmlandSitBelowAFullBlock() {
        reaches("dirt path", builder -> { for (int x = 4; x <= 7; x++) row(builder, x, 0, PATH); }, START, 6, 0, 3, 0.9375);
    }
    @Test void aFenceLineIsCrossedThroughItsGate() {
        reaches("fence gate", builder -> { row(builder, 5, 1, FENCE); builder.set(5, 1, 3, GATE); }, START, 8, 0, 3, 1);
    }
    @Test void aWallIsCrossedThroughItsDoor() {
        reaches("door", builder -> {
            for (int y = 1; y <= 3; y++) row(builder, 5, y, Cell.SOLID);
            builder.set(5, 1, 3, DOOR).set(5, 2, 3, DOOR);
        }, START, 8, 0, 3, 1);
    }
    @Test void aStepDownOnTheDiagonalIsWalked() {
        reaches("step down diagonal", builder -> { for (int x = 1; x <= 4; x++) row(builder, x, 1, Cell.SOLID); }, new BlockPos(4, 2, 3), 5, 0, 4, 1);
    }
    @Test void shallowWaterIsWaded() {
        reaches("shallow water", builder -> { row(builder, 4, 1, Cell.WATER); row(builder, 5, 1, Cell.WATER); }, START, 7, 0, 3, 1);
    }
}
