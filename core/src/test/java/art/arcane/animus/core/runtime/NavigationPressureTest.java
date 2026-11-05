package art.arcane.animus.core.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NavigationPressureTest {
    @Test void shouldLogOnceWhenPressureStartsAndOnceWhenItEnds() {
        NavigationPressure pressure = new NavigationPressure();
        List<String> logged = new ArrayList<>();
        for (long tick = 0; tick < 200; tick++) pressure.observe(tick, 40, "routesPerTick=128").ifPresent(logged::add);
        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("40"), logged.get(0));
        assertTrue(logged.get(0).contains("routesPerTick=128"), logged.get(0));
        assertTrue(pressure.pressured());
        for (long tick = 200; tick < 400; tick++) pressure.observe(tick, 0, "routesPerTick=128").ifPresent(logged::add);
        assertEquals(2, logged.size(), logged.toString());
        assertTrue(logged.get(1).contains("ended"), logged.get(1));
        assertFalse(pressure.pressured());
    }

    @Test void shouldIgnoreAShortBurst() {
        NavigationPressure pressure = new NavigationPressure();
        List<String> logged = new ArrayList<>();
        for (long tick = 0; tick < 300; tick++) pressure.observe(tick, tick % 40 < 5 ? 10 : 0, "").ifPresent(logged::add);
        assertTrue(logged.isEmpty(), logged.toString());
        assertFalse(pressure.pressured());
    }

    @Test void shouldDescribeThePressureForStatus() {
        NavigationPressure pressure = new NavigationPressure();
        assertEquals("none", pressure.describe());
        for (long tick = 0; tick < 30; tick++) pressure.observe(tick, 12, "");
        assertTrue(pressure.describe().startsWith("12 routes waiting"), pressure.describe());
    }
}
