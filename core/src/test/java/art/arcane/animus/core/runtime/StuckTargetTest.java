package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import org.junit.jupiter.api.Test;

/** Where a stuck waypoint leg's teleport puts a walking body. */
class StuckTargetTest {
    /** Solid up to and including {@code top}, air above. */
    private static MotionValidator.Sampler ground(int top) { return (int x, int y, int z) -> y <= top ? Cell.SOLID : Cell.AIR; }
    private static double feet(double waypointY, int top) {
        WorldPosition goal = new WorldPosition("world", new Vec3(.5, waypointY, .5));
        return RouteRules.stuckTarget(goal, MovementProfile.Mode.WALK, .6, 1.8, ground(top)).point().y();
    }

    @Test void aWaypointOnTheGroundOrNamingItKeepsItsFooting() {
        assertEquals(64, feet(64, 63), 1e-9);
        assertEquals(64, feet(63, 63), 1e-9);
    }
    @Test void aWaypointBuriedByLaterTerrainLiftsToTheFirstPlaceTheBodyFits() {
        assertEquals(67, feet(62, 66), 1e-9);
        assertEquals(71, feet(62, 70), 1e-9);
    }
    @Test void aWaypointBuriedDeeperThanCitizensLooksStaysWhereItIs() {
        assertEquals(40, feet(40, 70), 1e-9);
    }
}
