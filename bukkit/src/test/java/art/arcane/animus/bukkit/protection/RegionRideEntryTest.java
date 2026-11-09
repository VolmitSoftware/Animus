package art.arcane.animus.bukkit.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.bukkit.protection.RegionFlags.State;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegionRideEntryTest {
    private static final UUID RIDER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static WorldPosition at(double x) { return new WorldPosition("world", new Vec3(x, 61, 3.5)); }

    @Test void theRiderMayEnterUnlessTheEntryFlagDeniesThem() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 3, Flag.ENTRY, State.DENY);
        RegionRideEntry entry = new RegionRideEntry(flags);
        assertTrue(entry.mayEnter(RIDER, at(4.5)));
        assertFalse(entry.mayEnter(RIDER, at(20.5)));
        FakeRegionFlags.Query query = flags.queries.getLast();
        assertEquals(List.of(Flag.ENTRY), query.flags());
        assertEquals(RIDER, query.player(), "the rider's own membership decides, as when they walk in");
    }

    @Test void aBypassingRiderEntersAndAFailedQueryThrowsForTheRideToDeny() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 3, Flag.ENTRY, State.DENY).bypassing(RIDER);
        assertTrue(new RegionRideEntry(flags).mayEnter(RIDER, at(20.5)));
        FakeRegionFlags failing = new FakeRegionFlags();
        failing.failure = new IllegalStateException("WorldGuard is disabled");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new RegionRideEntry(failing).mayEnter(RIDER, at(4.5)));
    }
}
