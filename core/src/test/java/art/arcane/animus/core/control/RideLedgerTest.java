package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RideLedgerTest {
    private static final RideSettings SETTINGS = new RideSettings("animus.ride", 4, 16, 120);
    private static RideLedger.Admission admit(RideLedger ledger, UUID rider, UUID npc) { return ledger.admit(rider, npc, Vec3.ZERO, SETTINGS, 0); }

    @Test void onePlayerRidesOneNpcAndOneNpcCarriesOneRealRider() {
        RideLedger ledger = new RideLedger();
        UUID rider = UUID.randomUUID(), npc = UUID.randomUUID();
        RideLedger.Admission first = admit(ledger, rider, npc);
        assertNull(first.rejection());
        assertEquals(RideLedger.Rejection.ALREADY_RIDING, admit(ledger, rider, UUID.randomUUID()).rejection());
        assertEquals(RideLedger.Rejection.OCCUPIED, admit(ledger, UUID.randomUUID(), npc).rejection());
        assertSame(first.session(), ledger.ofRider(rider));
        assertSame(first.session(), ledger.ofNpc(npc));
    }
    @Test void capacityIsBoundedAndReleasedWhenARideEnds() {
        RideLedger ledger = new RideLedger();
        RideSession last = null;
        for (int i = 0; i < RideLedger.MAX_RIDERS; i++) last = admit(ledger, UUID.randomUUID(), UUID.randomUUID()).session();
        assertEquals(RideLedger.MAX_RIDERS, ledger.size());
        assertEquals(RideLedger.Rejection.CAPACITY, admit(ledger, UUID.randomUUID(), UUID.randomUUID()).rejection());
        assertTrue(ledger.remove(last));
        assertFalse(ledger.remove(last), "removal is idempotent");
        assertNull(admit(ledger, UUID.randomUUID(), UUID.randomUUID()).rejection());
    }
    @Test void removingAStaleSessionCannotEvictItsSuccessor() {
        RideLedger ledger = new RideLedger();
        UUID rider = UUID.randomUUID(), npc = UUID.randomUUID();
        RideSession stale = admit(ledger, rider, npc).session();
        ledger.remove(stale);
        RideSession current = admit(ledger, rider, npc).session();
        assertFalse(ledger.remove(stale));
        assertSame(current, ledger.ofRider(rider));
        assertSame(current, ledger.ofNpc(npc));
        assertEquals(1, ledger.size());
    }
}
