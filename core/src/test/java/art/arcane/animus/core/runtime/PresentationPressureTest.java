package art.arcane.animus.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationPressureTest {
    @Test
    void cosmeticWorkYieldsAfterAShortTickUntilTheBudgetKeepsUp() {
        PresentationPressure pressure = new PresentationPressure();
        assertTrue(pressure.allows(PresentationPressure.Tier.COSMETIC, 1));

        pressure.shortOfBudget(10);
        for (long tick = 10; tick <= 10 + PresentationPressure.RECOVERY_TICKS; tick++) {
            assertFalse(pressure.allows(PresentationPressure.Tier.COSMETIC, tick), "tick " + tick);
            assertTrue(pressure.allows(PresentationPressure.Tier.AUTHORITATIVE, tick));
            assertTrue(pressure.allows(PresentationPressure.Tier.DESPAWN, tick));
        }
        assertTrue(pressure.allows(PresentationPressure.Tier.COSMETIC, 11 + PresentationPressure.RECOVERY_TICKS));
        assertTrue(pressure.describe(11 + PresentationPressure.RECOVERY_TICKS).startsWith("normal/cosmetic-deferred=21"));
    }

    @Test
    void anotherShortTickExtendsTheWait() {
        PresentationPressure pressure = new PresentationPressure();
        pressure.shortOfBudget(10);
        pressure.shortOfBudget(25);
        pressure.shortOfBudget(5);
        assertFalse(pressure.allows(PresentationPressure.Tier.COSMETIC, 25 + PresentationPressure.RECOVERY_TICKS));
        assertTrue(pressure.allows(PresentationPressure.Tier.COSMETIC, 26 + PresentationPressure.RECOVERY_TICKS));
    }
}
