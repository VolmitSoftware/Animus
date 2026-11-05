package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelperLedgerTest {
    private static HelperLedger.Shot shot(UUID helper, UUID owner) {
        return new HelperLedger.Shot(helper, owner, Optional.empty(), 1, 0, true, Optional.empty(), 10, Vec3.ZERO);
    }
    @Test void aShotCanBeClaimedOnlyOnce() {
        HelperLedger ledger = new HelperLedger(4, 2);
        UUID helper = UUID.randomUUID();
        ledger.track(shot(helper, UUID.randomUUID()));
        assertTrue(ledger.claim(helper).isPresent());
        assertTrue(ledger.claim(helper).isEmpty());
        assertFalse(ledger.tracks(helper));
    }
    @Test void capacityIsBoundedGloballyAndPerOwner() {
        HelperLedger ledger = new HelperLedger(3, 2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(ledger.hasCapacity(a));
        ledger.track(shot(UUID.randomUUID(), a));
        ledger.track(shot(UUID.randomUUID(), a));
        assertFalse(ledger.hasCapacity(a));
        assertTrue(ledger.hasCapacity(b));
        ledger.track(shot(UUID.randomUUID(), b));
        assertFalse(ledger.hasCapacity(b));
    }
    @Test void retiringAnOwnerReturnsExactlyItsHelpers() {
        HelperLedger ledger = new HelperLedger(4, 4);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        ledger.track(shot(gone, a));
        ledger.track(shot(kept, b));
        assertEquals(List.of(gone), ledger.retireOwner(a));
        assertTrue(ledger.hasCapacity(a));
        assertTrue(ledger.tracks(kept));
        assertEquals(List.of(kept), ledger.retireAll());
        assertEquals(0, ledger.size());
    }
    @Test void rotationVisitsEveryShotFairly() {
        HelperLedger ledger = new HelperLedger(4, 4);
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledger.track(shot(first, owner));
        ledger.track(shot(second, owner));
        assertEquals(first, ledger.next().orElseThrow().helper());
        assertEquals(second, ledger.next().orElseThrow().helper());
        assertEquals(first, ledger.next().orElseThrow().helper());
    }
}
