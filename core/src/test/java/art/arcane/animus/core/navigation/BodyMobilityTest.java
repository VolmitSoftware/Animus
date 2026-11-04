package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BodyMobilityTest {
    @Test void ordinaryBodiesStepSixTenthsAndLargerBodiesAWholeBlock() {
        assertEquals(0.6, BodyMobility.forBody("minecraft:player", 0.6, 1.8).stepHeight(), 1e-9);
        assertEquals(1, BodyMobility.forBody("minecraft:horse", 1.4, 1.6).stepHeight(), 1e-9);
    }
    @Test void capabilitiesFollowTheBodyType() {
        MovementProfile dragon = BodyMobility.forBody("minecraft:ender_dragon", 4, 8);
        assertEquals(Set.of(MovementProfile.Mode.FLY), dragon.modes());
        assertTrue(BodyMobility.forBody("minecraft:phantom", 0.9, 0.5).supports(MovementProfile.Mode.FLY));
        MovementProfile horse = BodyMobility.forBody("minecraft:horse", 1.4, 1.6);
        assertTrue(horse.surfaceSwimmer());
        assertFalse(horse.supports(MovementProfile.Mode.FLY));
        assertEquals(Set.of(MovementProfile.Mode.SWIM), BodyMobility.forBody("minecraft:dolphin", 0.9, 0.6).modes());
        assertEquals(Set.of(MovementProfile.Mode.WALK), BodyMobility.forBody("minecraft:armor_stand", 0.5, 2).modes());
    }
    @Test void aRouteUsesTheRequestedModeWithTheBodysClimb() {
        assertEquals(Set.of(MovementProfile.Mode.FLY), BodyMobility.forRoute("minecraft:player", 0.6, 1.8, MovementProfile.Mode.FLY).modes());
        assertEquals(Set.of(MovementProfile.Mode.WALK), BodyMobility.forRoute("minecraft:horse", 1.4, 1.6, MovementProfile.Mode.WALK).modes());
        MovementProfile swim = BodyMobility.forRoute("minecraft:horse", 1.4, 1.6, MovementProfile.Mode.SWIM);
        assertEquals(Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM), swim.modes());
        assertEquals(1, swim.stepHeight(), 1e-9);
        assertEquals(Set.of(MovementProfile.Mode.SWIM), BodyMobility.forRoute("minecraft:dolphin", 0.9, 0.6, MovementProfile.Mode.SWIM).modes());
    }
    @Test void profilesRejectImpossibleHulls() {
        assertThrows(IllegalArgumentException.class, () -> new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0, 1.8, 0.6, 0, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 9, 0.6, 0, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new MovementProfile(Set.of(MovementProfile.Mode.WALK), 0.6, 1.8, 4, 0, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new MovementProfile(Set.of(MovementProfile.Mode.WALK), Double.NaN, 1.8, 0.6, 0, 3, true));
    }
}
