package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import org.junit.jupiter.api.Test;

/** The log line and summary kind of a blocked route leg tell terrain that blocks it from a plan that found no way. */
class BlockedLegLogTest {
    private static final Vec3 FROM = new Vec3(1.5, 64, 2.5);
    private static final WorldPosition TO = new WorldPosition("world", new Vec3(20.5, 65, -3.5));

    @Test void terrainAndThePlannerReadDifferently() {
        assertEquals("NPC guard teleported: terrain blocks its route leg to waypoint 3 from 1 64 2 to 20 65 -4 in world (failed: UNREACHABLE); later blocked legs of this NPC are not logged",
                PersistedNpcService.blockedLeg("guard", 3, FROM, TO, "failed: UNREACHABLE", true, true));
        assertEquals("NPC guard stopped: the planner found no path for its route leg from 1 64 2 to 20 65 -4 in world (failed: PARTIAL); later blocked legs of this NPC are not logged",
                PersistedNpcService.blockedLeg("guard", -1, FROM, TO, "failed: PARTIAL", false, false));
        assertEquals("NPCs hit route legs blocked by terrain", PersistedNpcService.blockedLegs(true));
        assertEquals("NPCs hit route legs the planner could not path", PersistedNpcService.blockedLegs(false));
    }
}
