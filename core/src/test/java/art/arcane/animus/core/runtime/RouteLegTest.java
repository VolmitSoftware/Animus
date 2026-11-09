package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteLegTest {
    private static WorldPosition at(double x, double y, double z) { return new WorldPosition("world", new Vec3(x, y, z)); }

    @Test void aWalkStartsFromThePartialSurfaceAnEarlierRouteEndedOn() {
        // The planner rests a walker on dirt paths, farmland and slabs, so the next leg begins at a fractional height.
        assertDoesNotThrow(() -> RouteRules.validateLeg(at(10.5, 63.9375, 0.5), at(16.5, 64, 0.5), MovementProfile.Mode.WALK, 6, .6, 1.8));
        assertDoesNotThrow(() -> RouteRules.validateLeg(at(10.5, 64.5, 0.5), at(4.5, 63, 0.5), MovementProfile.Mode.WALK, 6, .6, 1.8));
    }

    @Test void aLegOfAnyLengthIsAcceptedAndOtherRulesStillApply() {
        assertDoesNotThrow(() -> RouteRules.validateLeg(at(0, 64, 0), at(200, 94, -150), MovementProfile.Mode.WALK, 6, .6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> RouteRules.validateLeg(at(0, 64, 0), at(40_000_000, 64, 0), MovementProfile.Mode.WALK, 6, .6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> RouteRules.validateLeg(at(0, 64, 0), new WorldPosition("nether", new Vec3(1, 64, 0)), MovementProfile.Mode.WALK, 6, .6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> RouteRules.validateLeg(at(0, 64, 0), at(1, 64.5, 0), MovementProfile.Mode.WALK, 6, .6, 1.8), "goals name a block");
    }
}
