package art.arcane.animus.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RollingWindowTest {
    @Test void emptyWindowReportsZeroRates() {
        RollingWindow window = new RollingWindow(2);
        assertEquals(0, window.covered(10));
        assertEquals(0, window.perSecond(0, 10));
        assertEquals(0, window.perTick(0, 10));
    }

    @Test void sumsCoverOnlyFilledSeconds() {
        RollingWindow window = new RollingWindow(1);
        window.push(new long[]{10}, 20);
        window.push(new long[]{30}, 20);
        assertEquals(30, window.total(0, 1));
        assertEquals(40, window.total(0, 10));
        assertEquals(2, window.covered(10));
        assertEquals(20.0, window.perSecond(0, 10));
        assertEquals(1.0, window.perTick(0, 10));
    }

    @Test void oldSecondsFallOutAfterSixty() {
        RollingWindow window = new RollingWindow(1);
        window.push(new long[]{1000}, 20);
        for (int i = 0; i < RollingWindow.SECONDS; i++) window.push(new long[]{1}, 20);
        assertEquals(60, window.total(0, 60));
        assertEquals(10, window.total(0, 10));
        assertEquals(1200, window.ticks(60));
    }

    @Test void rejectsWrongChannelCount() {
        RollingWindow window = new RollingWindow(3);
        assertThrows(IllegalArgumentException.class, () -> window.push(new long[2], 1));
    }
}
