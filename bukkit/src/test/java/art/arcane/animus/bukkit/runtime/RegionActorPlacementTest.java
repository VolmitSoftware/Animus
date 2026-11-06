package art.arcane.animus.bukkit.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A combat teleport into another region lands through the same guard as one inside the NPC's own region. */
class RegionActorPlacementTest {
    @Test void shouldPlaceAnActiveNpcWithoutARider() {
        ActorRide idle = new ActorRide(null, null);
        assertFalse(idle.active());
        assertTrue(RegionActor.placeable(true, false, idle), "an idle ride must not block a teleport");
    }

    @Test void shouldNotPlaceASeatedOrInactiveNpc() {
        ActorRide idle = new ActorRide(null, null);
        assertFalse(RegionActor.placeable(true, true, idle));
        assertFalse(RegionActor.placeable(false, false, idle));
    }
}
