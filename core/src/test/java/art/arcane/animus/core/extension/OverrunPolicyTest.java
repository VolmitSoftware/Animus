package art.arcane.animus.core.extension;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverrunPolicyTest {
    private static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    @Test void suspendsOnlyAfterRepeatedOverrunsWithinTheWindow() {
        OverrunPolicy policy = new OverrunPolicy();
        assertTrue(policy.overran(0).isEmpty());
        assertTrue(policy.overran(seconds(10)).isEmpty());
        assertEquals(OptionalLong.of(OverrunPolicy.FIRST_COOL_OFF), policy.overran(seconds(20)));
    }

    @Test void overrunsOlderThanTheWindowDoNotCount() {
        OverrunPolicy policy = new OverrunPolicy();
        long spacing = OverrunPolicy.WINDOW / 2 + 1;
        for (int call = 0; call < 10; call++) assertTrue(policy.overran(call * spacing).isEmpty(), "call " + call);
    }

    @Test void eachSuspensionDoublesTheCoolOffUpToTheCap() {
        OverrunPolicy policy = new OverrunPolicy();
        long now = 0;
        long expected = OverrunPolicy.FIRST_COOL_OFF;
        for (int suspension = 0; suspension < 8; suspension++) {
            OptionalLong coolOff = OptionalLong.empty();
            for (int call = 0; call < OverrunPolicy.LIMIT; call++) coolOff = policy.overran(now += seconds(1));
            assertEquals(OptionalLong.of(Math.min(expected, OverrunPolicy.MAXIMUM_COOL_OFF)), coolOff, "suspension " + suspension);
            assertFalse(policy.due(now + coolOff.getAsLong() - 1));
            assertTrue(policy.due(now + coolOff.getAsLong()));
            now += coolOff.getAsLong();
            policy.resumed();
            assertFalse(policy.due(now));
            expected *= 2;
        }
        assertEquals(OverrunPolicy.MAXIMUM_COOL_OFF, TimeUnit.MINUTES.toNanos(15));
    }

    @Test void aQuietHourOrAManualResumeResetsTheBackoff() {
        OverrunPolicy quiet = new OverrunPolicy();
        for (int call = 0; call < OverrunPolicy.LIMIT; call++) quiet.overran(seconds(call));
        quiet.resumed();
        long later = seconds(2) + OverrunPolicy.FORGET + 1;
        OptionalLong coolOff = OptionalLong.empty();
        for (int call = 0; call < OverrunPolicy.LIMIT; call++) coolOff = quiet.overran(later + seconds(call));
        assertEquals(OptionalLong.of(OverrunPolicy.FIRST_COOL_OFF), coolOff);

        OverrunPolicy manual = new OverrunPolicy();
        for (int call = 0; call < OverrunPolicy.LIMIT; call++) manual.overran(seconds(call));
        manual.cleared();
        assertFalse(manual.due(seconds(1000)));
        for (int call = 0; call < OverrunPolicy.LIMIT; call++) coolOff = manual.overran(seconds(100 + call));
        assertEquals(OptionalLong.of(OverrunPolicy.FIRST_COOL_OFF), coolOff);
    }
}
