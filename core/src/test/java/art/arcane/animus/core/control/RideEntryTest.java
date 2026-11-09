package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RideEntryTest {
    private static WorldPosition at(double x, double z) { return new WorldPosition("world", new Vec3(x, 64, z)); }

    @Test void asksOncePerNewBlockAndNeverForTheStartingBlock() {
        List<WorldPosition> asked = new ArrayList<>();
        RideEntry entry = new RideEntry((UUID rider, WorldPosition to) -> { asked.add(to); return true; }, UUID.randomUUID(), at(0.5, 0.5));
        assertTrue(entry.allows(at(0.9, 0.9)));
        assertTrue(entry.allows(at(1.2, 0.5)));
        assertTrue(entry.allows(at(1.8, 0.5)));
        assertEquals(List.of(at(1.2, 0.5)), asked);
    }

    @Test void aDeniedBlockStaysDeniedAndAFailingRuleDenies() {
        RideEntry denying = new RideEntry((UUID rider, WorldPosition to) -> to.point().x() < 2, UUID.randomUUID(), at(0.5, 0.5));
        assertTrue(denying.allows(at(1.5, 0.5)));
        assertFalse(denying.allows(at(2.5, 0.5)));
        assertFalse(denying.allows(at(2.6, 0.5)));
        RideEntry failing = new RideEntry((UUID rider, WorldPosition to) -> { throw new IllegalStateException("WorldGuard is disabled"); }, UUID.randomUUID(), at(0.5, 0.5));
        assertFalse(failing.allows(at(1.5, 0.5)));
    }
}
