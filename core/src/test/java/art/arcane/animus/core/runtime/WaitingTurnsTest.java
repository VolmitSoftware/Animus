package art.arcane.animus.core.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaitingTurnsTest {
    /** Folia runs each NPC's task in the same order every tick; the turns must still reach the last ones. */
    @Test void shouldServeEveryCallerWhenTheyAlwaysAskInTheSameOrder() {
        WaitingTurns turns = new WaitingTurns();
        int callers = 300, budget = 16;
        int[] waited = new int[callers], served = new int[callers], longest = new int[callers];
        for (long tick = 0; tick < 400; tick++) {
            int granted = 0;
            turns.begin(tick);
            for (int i = 0; i < callers; i++) {
                if (turns.admits(waited[i]) && granted < budget) { granted++; served[i]++; waited[i] = 0; }
                else { turns.refused(waited[i]); waited[i]++; longest[i] = Math.max(longest[i], waited[i]); }
            }
            assertTrue(granted <= budget);
        }
        for (int i = 0; i < callers; i++) {
            assertTrue(served[i] >= 15, "caller " + i + " served " + served[i]);
            assertTrue(longest[i] <= 2 * ((callers + budget - 1) / budget) + 2, "caller " + i + " waited " + longest[i]);
        }
        assertTrue(turns.lastRefused() > 0);
    }

    @Test void shouldAdmitEveryoneWithoutPressure() {
        WaitingTurns turns = new WaitingTurns();
        for (long tick = 0; tick < 5; tick++) {
            turns.begin(tick);
            for (int i = 0; i < 10; i++) assertTrue(turns.admits(0));
        }
        assertEquals(0, turns.lastRefused());
    }
}
