package art.arcane.animus.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void anotherBudgetRunningShortSlowsCosmeticWorkWithoutStoppingIt() {
        PresentationPressure pressure = new PresentationPressure();
        pressure.interval(4);
        pressure.busy(100);
        assertEquals(PresentationPressure.Level.DEGRADED, pressure.level(100));
        int allowed = 0;
        for (long tick = 100; tick < 116; tick++) {
            if (pressure.allows(PresentationPressure.Tier.COSMETIC, tick)) allowed++;
            assertTrue(pressure.allows(PresentationPressure.Tier.AUTHORITATIVE, tick), "authoritative work never slows");
        }
        assertEquals(4, allowed, "one cosmetic turn in four while degraded");
        assertTrue(pressure.describe(110).startsWith("degraded/"), pressure.describe(110));
        assertEquals(PresentationPressure.Level.NORMAL, pressure.level(101 + PresentationPressure.RECOVERY_TICKS));
    }

    @Test
    void packetShortageOutranksAnotherBudgetRunningShort() {
        PresentationPressure pressure = new PresentationPressure();
        pressure.interval(4);
        pressure.busy(10);
        pressure.shortOfBudget(10);
        assertEquals(PresentationPressure.Level.STRAINED, pressure.level(12));
        for (long tick = 10; tick < 20; tick++) assertFalse(pressure.allows(PresentationPressure.Tier.COSMETIC, tick));
    }

    @Test
    void aViewersOwnTurnsSpaceItsCosmeticWorkSoATurnScheduleCannotStarveIt() {
        PresentationPressure pressure = new PresentationPressure();
        pressure.interval(4);
        pressure.busy(29);
        int allowed = 0;
        // The viewer updates every fourth tick: global ticks 0, 4, 8... would all fall on the same phase.
        for (int turn = 0; turn < 8; turn++) if (pressure.allows(PresentationPressure.Tier.COSMETIC, turn * 4L + 1, turn)) allowed++;
        assertEquals(2, allowed);
    }

    @Test
    void distantMovesSpreadOutUnderPressureAndEveryBodyStillMoves() {
        PresentationPressure pressure = new PresentationPressure();
        pressure.interval(4);
        for (long turn = 0; turn < 4; turn++) assertTrue(pressure.distantMove(1, turn, 7), "no pressure: every turn");
        pressure.busy(10);
        for (long phase = 0; phase < 4; phase++) {
            int moves = 0;
            for (long turn = 0; turn < 8; turn++) if (pressure.distantMove(10, turn, phase)) moves++;
            assertEquals(2, moves, "phase " + phase);
        }
        pressure.interval(1);
        assertTrue(pressure.distantMove(10, 3, 2), "an interval of one turns spreading off");
    }
}
