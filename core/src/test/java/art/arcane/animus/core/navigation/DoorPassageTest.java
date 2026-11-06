package art.arcane.animus.core.navigation;

import static art.arcane.animus.core.navigation.MovementProfile.Mode.WALK;
import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoorPassageTest {
    private static final PathPlanner.Limits LIMITS = new PathPlanner.Limits(5000, 5000, 500);
    private static final MovementProfile WALKER = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 0, 3, true);
    private static final MovementProfile OPENER = new MovementProfile(Set.of(WALK), 0.6, 1.8, 0.6, 0, 3, true, false, true);
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    /** A closed door facing east: its panel spans the path along X. */
    private static final Cell DOOR = CollisionCells.classify("OAK_DOOR", List.of(new Cell.Box(0, 0, 0, 0.1875, 1, 1)), false,
            CollisionCells.openShape(CollisionCells.Opening.DOOR, "EAST", false));
    private static final Cell IRON = CollisionCells.classify("IRON_DOOR", List.of(new Cell.Box(0, 0, 0, 0.1875, 1, 1)), false, null);

    /** A corridor along X with a wall at x=4 holding one doorway at z=3. */
    private static VoxelSnapshot.Builder corridor(Cell doorway) {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 9, 5, 7).fill(Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 7; z++)
            if (x == 0 || x == 8 || z == 0 || z == 6 || y == 0 || y == 4 || x == 4) builder.set(x, y, z, Cell.SOLID);
        builder.set(4, 1, 3, doorway).set(4, 2, 3, doorway);
        return builder;
    }

    private static PathPlanner.Status plan(Cell doorway, MovementProfile profile) {
        return new PathPlanner().plan(corridor(doorway).build(), new BlockPos(2, 1, 3), new BlockPos(6, 1, 3), profile, LIMITS, () -> false).status();
    }

    @Test void onlyBodiesThatOpenDoorsPlanThroughAClosedDoor() {
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(DOOR, WALKER));
        assertEquals(PathPlanner.Status.FOUND, plan(DOOR, OPENER));
        assertEquals(PathPlanner.Status.UNREACHABLE, plan(IRON, OPENER), "iron doors stay closed");
    }

    @Test void handOpenedBlocksAreDoorsGatesAndTrapdoorsButNotIron() {
        assertEquals(Optional.of(CollisionCells.Opening.DOOR), CollisionCells.opening("DARK_OAK_DOOR"));
        assertEquals(Optional.of(CollisionCells.Opening.DOOR), CollisionCells.opening("COPPER_DOOR"));
        assertEquals(Optional.of(CollisionCells.Opening.GATE), CollisionCells.opening("BIRCH_FENCE_GATE"));
        assertEquals(Optional.of(CollisionCells.Opening.TRAPDOOR), CollisionCells.opening("SPRUCE_TRAPDOOR"));
        assertEquals(Optional.empty(), CollisionCells.opening("IRON_DOOR"));
        assertEquals(Optional.empty(), CollisionCells.opening("IRON_TRAPDOOR"));
        assertEquals(Optional.empty(), CollisionCells.opening("OAK_FENCE"));
        assertFalse(IRON.openable());
        assertTrue(DOOR.openable());
        assertFalse(DOOR.whenOpen().openable());
    }

    @Test void openShapesFollowVanillaGeometry() {
        assertEquals(List.of(), CollisionCells.openShape(CollisionCells.Opening.GATE, "NORTH", false));
        assertEquals(List.of(new Cell.Box(0, 0, 0, 1, 1, 0.1875)), CollisionCells.openShape(CollisionCells.Opening.DOOR, "EAST", false));
        assertEquals(List.of(new Cell.Box(0, 0, 0.8125, 1, 1, 1)), CollisionCells.openShape(CollisionCells.Opening.DOOR, "EAST", true));
        assertEquals(List.of(new Cell.Box(0.8125, 0, 0, 1, 1, 1)), CollisionCells.openShape(CollisionCells.Opening.DOOR, "SOUTH", false));
        assertEquals(List.of(new Cell.Box(0, 0, 0.8125, 1, 1, 1)), CollisionCells.openShape(CollisionCells.Opening.TRAPDOOR, "NORTH", false));
        assertEquals(List.of(new Cell.Box(0, 0, 0, 0.1875, 1, 1)), CollisionCells.openShape(CollisionCells.Opening.TRAPDOOR, "EAST", false));
    }

    /** Live doors: opening and closing replace the cell, as the server would. */
    private static final class World implements DoorPassage.Doors {
        final Map<BlockPos, Cell> cells = new HashMap<>();
        final List<String> log = new ArrayList<>();
        boolean refuse;
        Cell at(int x, int y, int z) { return cells.getOrDefault(new BlockPos(x, y, z), y <= 0 ? Cell.SOLID : Cell.AIR); }
        @Override public boolean open(UUID npc, String world, BlockPos at) {
            if (refuse) { log.add("refused " + at); return false; }
            cells.put(at, cells.get(at).whenOpen());
            log.add("open " + at);
            return true;
        }
        @Override public void close(String world, BlockPos at) {
            cells.put(at, DOOR);
            log.add("close " + at);
        }
    }

    @Test void theBodyOpensTheDoorAheadAndClosesItOncePast() {
        World world = new World();
        world.cells.put(new BlockPos(4, 1, 3), DOOR);
        world.cells.put(new BlockPos(4, 2, 3), DOOR);
        DoorPassage passage = new DoorPassage(NPC);
        assertEquals(Optional.empty(), passage.prepare("world", new Vec3(2.5, 1, 3.5), new Vec3(2.7, 1, 3.5), 0.6, 1.8, world::at, world));
        assertEquals(List.of(), world.log, "too far to reach the door");
        assertEquals(Optional.empty(), passage.prepare("world", new Vec3(3.4, 1, 3.5), new Vec3(3.6, 1, 3.5), 0.6, 1.8, world::at, world));
        assertEquals(List.of("open BlockPos[x=4, y=1, z=3]", "open BlockPos[x=4, y=2, z=3]"), world.log);
        assertEquals(2, passage.held());
        passage.passed(new Vec3(4.5, 1, 3.5), 0.6, world);
        assertEquals(2, passage.held(), "still in the doorway");
        passage.passed(new Vec3(5.9, 1, 3.5), 0.6, world);
        assertEquals(0, passage.held());
        assertEquals(List.of("open BlockPos[x=4, y=1, z=3]", "open BlockPos[x=4, y=2, z=3]", "close BlockPos[x=4, y=1, z=3]", "close BlockPos[x=4, y=2, z=3]"), world.log);
    }

    @Test void aRefusedDoorBlocksAndCancellingClosesEveryHeldDoor() {
        World world = new World();
        world.cells.put(new BlockPos(4, 1, 3), DOOR);
        world.refuse = true;
        DoorPassage passage = new DoorPassage(NPC);
        assertEquals(Optional.of(new BlockPos(4, 1, 3)), passage.prepare("world", new Vec3(3.4, 1, 3.5), new Vec3(3.6, 1, 3.5), 0.6, 1.8, world::at, world));
        assertEquals(0, passage.held());
        world.refuse = false;
        assertEquals(Optional.empty(), passage.prepare("world", new Vec3(3.4, 1, 3.5), new Vec3(3.6, 1, 3.5), 0.6, 1.8, world::at, world));
        assertEquals(1, passage.held());
        passage.closeAll(world);
        assertEquals(0, passage.held());
        assertEquals("close BlockPos[x=4, y=1, z=3]", world.log.getLast());
    }

    @Test void anAlreadyOpenDoorIsNeverClosedByThePassage() {
        World world = new World();
        world.cells.put(new BlockPos(4, 1, 3), DOOR.whenOpen());
        DoorPassage passage = new DoorPassage(NPC);
        assertEquals(Optional.empty(), passage.prepare("world", new Vec3(3.4, 1, 3.5), new Vec3(3.6, 1, 3.5), 0.6, 1.8, world::at, world));
        passage.closeAll(world);
        assertEquals(List.of(), world.log);
    }
}
