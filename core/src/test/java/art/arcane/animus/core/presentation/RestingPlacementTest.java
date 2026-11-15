package art.arcane.animus.core.presentation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Seats and resting poses land where GSit puts a player in the same pose. */
class RestingPlacementTest {
    private static final Cell.Box STEP = new Cell.Box(0, 0, 0, 1, 0.5, 1);
    /** A stair rising to the east: the back half at x 0.5..1 is full height. */
    private static final Cell EAST_STAIR = Cell.of(List.of(STEP, new Cell.Box(0.5, 0.5, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    /** The same stair split the other way, as a merged collision shape may come. */
    private static final Cell EAST_STAIR_SPLIT = Cell.of(List.of(new Cell.Box(0, 0, 0, 0.5, 0.5, 1), new Cell.Box(0.5, 0, 0, 1, 1, 1)), Cell.Fluid.NONE, false);
    /** An outer corner: only the north-east quarter is full height. */
    private static final Cell OUTER = Cell.of(List.of(STEP, new Cell.Box(0.5, 0.5, 0, 1, 1, 0.5)), Cell.Fluid.NONE, false);

    private static RestingPlacement.Seat seatOn(Cell feetCell, Cell belowCell, Vec3 feet) {
        return RestingPlacement.seat(feet, (int x, int y, int z) -> y == 64 ? feetCell : y == 63 ? belowCell : Cell.AIR);
    }

    @Test void aSeatOnTheFloorLiftsAsGSitDoes() {
        RestingPlacement.Seat seat = seatOn(Cell.AIR, Cell.SOLID, new Vec3(2.3, 64, 5.7));
        assertEquals(new Vec3(2.3, 64.025, 5.7), seat.at());
        assertFalse(seat.turned());
    }

    @Test void aSeatOnABottomSlabSitsOnItsTop() {
        assertEquals(64.525, seatOn(Cell.layer(0.5), Cell.SOLID, new Vec3(2.5, 64, 5.5)).at().y(), 1e-9);
        assertEquals(64.525, seatOn(Cell.layer(0.5), Cell.SOLID, new Vec3(2.5, 64.5, 5.5)).at().y(), 1e-9);
    }

    @Test void aStairSeatMovesToTheStepAndFacesAwayFromTheBack() {
        for (Cell stair : List.of(EAST_STAIR, EAST_STAIR_SPLIT)) {
            RestingPlacement.Seat seat = seatOn(stair, Cell.SOLID, new Vec3(2.9, 64.5, 5.2));
            assertEquals(2 + 0.5 - RestingPlacement.STAIR_INSET, seat.at().x(), 1e-9);
            assertEquals(64.525, seat.at().y(), 1e-9);
            assertEquals(5.5, seat.at().z(), 1e-9);
            assertTrue(seat.turned());
            assertEquals(90, seat.yaw(), 1e-4, "faces west, away from the raised east half");
        }
        RestingPlacement.Seat standingOnTop = seatOn(Cell.AIR, EAST_STAIR, new Vec3(2.8, 64, 5.5));
        assertEquals(63.525, standingOnTop.at().y(), 1e-9, "feet on the raised half still sit on the step");
        RestingPlacement.Seat corner = seatOn(OUTER, Cell.SOLID, new Vec3(2.5, 64.5, 5.5));
        assertEquals(2 + 0.5 - RestingPlacement.STAIR_INSET, corner.at().x(), 1e-9);
        assertEquals(5 + 0.5 + RestingPlacement.STAIR_INSET, corner.at().z(), 1e-9);
        assertEquals(45, corner.yaw(), 1e-4, "an outer corner faces diagonally away from the raised quarter");
    }

    @Test void noSurfaceOrUnloadedTerrainLeavesTheSeatAtTheFeet() {
        Vec3 feet = new Vec3(1.5, 70, 1.5);
        assertEquals(feet, seatOn(Cell.AIR, Cell.AIR, feet).at());
        assertEquals(feet, RestingPlacement.seat(feet, (int x, int y, int z) -> Cell.UNAVAILABLE).at());
    }

    @Test void poseOffsetsFollowGSit() {
        BodyPose base = new BodyPose("world", 1, 64, 1, 30, 30, 12, true);
        BodyPose belly = RestingPlacement.shown(NpcPose.BELLYFLOP, base, 2, null);
        assertEquals(64 - 2 * RestingPlacement.BELLYFLOP_DROP, belly.y(), 1e-9);
        assertEquals(0, belly.pitch());
        assertEquals(-90, RestingPlacement.shown(NpcPose.SPINNING, base, 1, null).pitch());
        assertEquals(64 + RestingPlacement.LAY_LIFT, RestingPlacement.shown(NpcPose.SLEEPING, base, 1, null).y(), 1e-9, "lying on the ground, not half in it, as GSit's lay");
        assertEquals(base, RestingPlacement.shown(NpcPose.CRAWLING, base, 1, null));
        BodyPose seated = RestingPlacement.shown(NpcPose.SITTING, base, 1, new RestingPlacement.Seat(new Vec3(1.377, 64.525, 1.5), true, 90));
        assertEquals(90, seated.yaw());
        assertEquals(64.525, seated.y(), 1e-9);
    }
}
