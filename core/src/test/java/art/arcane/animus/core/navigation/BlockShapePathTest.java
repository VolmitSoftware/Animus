package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player body walks a one-wide street over the blocks towns are built from, both ways, with its own fall limit and with
 * the fall of 1 many Citizens saves carry. Shapes are the game's collision boxes.
 */
class BlockShapePathTest {
    private static final Cell AIR = Cell.AIR, SOLID = Cell.SOLID;
    private static final Cell SLAB = Cell.layer(0.5), CARPET = Cell.layer(0.0625), PATH = Cell.layer(0.9375), SNOW = Cell.layer(0.125);
    private static final Cell TOP_SLAB = Cell.of(List.of(new Box(0, 0.5, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    private static final List<Box> STAIR_UP = List.of(new Box(0, 0, 0, 1, 0.5, 1), new Box(0.5, 0.5, 0, 1, 1, 1));
    private static final Cell STAIR = Cell.of(STAIR_UP, Cell.Fluid.NONE, false);
    private static final Cell WATERLOGGED_STAIR = CollisionCells.classify("OAK_STAIRS", STAIR_UP, true);
    private static final Cell WATERLOGGED_SLAB = CollisionCells.classify("OAK_SLAB", List.of(new Box(0, 0, 0, 1, 0.5, 1)), true);
    private static final Cell CLOSED_FLOOR_TRAPDOOR = Cell.layer(0.1875);
    private static final Cell OPEN_TRAPDOOR = Cell.of(List.of(new Box(0, 0, 0.8125, 1, 1, 1)), Cell.Fluid.NONE, false);
    private static final Cell OPEN_DOOR = Cell.of(CollisionCells.openShape(CollisionCells.Opening.DOOR, "EAST", false), Cell.Fluid.NONE, false);
    private static final Cell LANTERN = Cell.of(List.of(new Box(0.3125, 0, 0.3125, 0.6875, 0.5625, 0.6875)), Cell.Fluid.NONE, false);
    private static final BlockPos START = new BlockPos(2, 1, 1), END = new BlockPos(13, 1, 1);

    private static VoxelSnapshot.Builder street() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 16, 8, 3).fill(AIR).availableChunk(0, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 3; z++) builder.set(x, 0, z, SOLID);
        for (int x = 0; x < 16; x++) for (int y = 1; y < 8; y++) { builder.set(x, y, 0, SOLID); builder.set(x, y, 2, SOLID); }
        return builder;
    }

    /** A wall across the street with a two-high opening whose lower cell is {@code lower}. */
    private static Consumer<VoxelSnapshot.Builder> doorway(Cell lower, Cell upper) {
        return (VoxelSnapshot.Builder b) -> {
            for (int y = 1; y < 8; y++) b.set(7, y, 1, SOLID);
            b.set(7, 1, 1, lower); b.set(7, 2, 1, upper);
        };
    }

    /** Raises x 5..10 by one block, reached by {@code up} at x 4 and left by {@code down} at x 11. */
    private static Consumer<VoxelSnapshot.Builder> raised(Cell up, Cell down) {
        return (VoxelSnapshot.Builder b) -> {
            for (int x = 5; x < 11; x++) b.set(x, 1, 1, SOLID);
            b.set(4, 1, 1, up); b.set(11, 1, 1, down);
        };
    }

    @Test
    void everydayBlocksAreWalkedBothWays() {
        Cell stairDown = Cell.of(List.of(new Box(0, 0, 0, 1, 0.5, 1), new Box(0, 0.5, 0, 0.5, 1, 1)), Cell.Fluid.NONE, false);
        Map<String, Consumer<VoxelSnapshot.Builder>> streets = new java.util.LinkedHashMap<>();
        streets.put("stairs", raised(STAIR, stairDown));
        streets.put("slabs", raised(SLAB, SLAB));
        streets.put("waterlogged stairs and slab", raised(WATERLOGGED_STAIR, WATERLOGGED_SLAB));
        streets.put("top slab and path", raised(TOP_SLAB, PATH));
        streets.put("plain block", raised(AIR, AIR));
        streets.put("carpet, snow and path floor", (VoxelSnapshot.Builder b) -> { b.set(5, 1, 1, CARPET); b.set(6, 1, 1, SNOW); b.set(7, 1, 1, PATH); b.set(8, 1, 1, PATH); });
        streets.put("floor trapdoor", (VoxelSnapshot.Builder b) -> b.set(7, 1, 1, CLOSED_FLOOR_TRAPDOOR));
        streets.put("open trapdoor", (VoxelSnapshot.Builder b) -> b.set(7, 1, 1, OPEN_TRAPDOOR));
        streets.put("open door", doorway(OPEN_DOOR, OPEN_DOOR));
        streets.put("open fence gate", doorway(AIR, AIR));
        streets.put("lantern", (VoxelSnapshot.Builder b) -> b.set(7, 1, 1, LANTERN));
        List<MovementProfile> bodies = List.of(BodyMobility.forRoute("minecraft:player", 0.6, 1.8, MovementProfile.Mode.WALK),
                new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 0.6, 1.25, 1, true, true, false));
        PathPlanner planner = new PathPlanner();
        List<String> blocked = new ArrayList<>();
        for (Map.Entry<String, Consumer<VoxelSnapshot.Builder>> street : streets.entrySet()) {
            VoxelSnapshot.Builder builder = street();
            street.getValue().accept(builder);
            VoxelSnapshot snapshot = builder.build();
            for (MovementProfile body : bodies) {
                PathPlanner.Result there = planner.plan(snapshot, START, END, body, new PathPlanner.Limits(5000, 5000, 500), () -> false);
                PathPlanner.Result back = planner.plan(snapshot, END, START, body, new PathPlanner.Limits(5000, 5000, 500), () -> false);
                if (there.status() != PathPlanner.Status.FOUND || back.status() != PathPlanner.Status.FOUND)
                    blocked.add(street.getKey() + " (fall " + body.maximumFall() + "): " + there.status() + " / " + back.status());
            }
        }
        assertTrue(blocked.isEmpty(), String.join("\n", blocked));
    }
}
