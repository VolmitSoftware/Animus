package art.arcane.animus.core.navigation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutePauseTest {
    private static JsonObject definition(String pause) {
        return JsonParser.parseString("{\"modules\":{\"animus:navigation\":{\"enabled\":true,\"pause\":" + pause + "}}}").getAsJsonObject();
    }

    @Test void parsesWithDefaultsAndNamesBadFields() {
        assertEquals(new RoutePause(6, true, 20, 0), RoutePause.parse(definition("{\"playerRange\":6,\"onClick\":true}")).orElseThrow());
        assertTrue(RoutePause.parse(JsonParser.parseString("{\"modules\":{}}").getAsJsonObject()).isEmpty());
        for (String bad : new String[]{"{\"playerRange\":65}", "{\"ticks\":0}", "{\"ticks\":1.5}", "{\"onClick\":\"yes\"}", "{\"after\":3}", "[]"})
            assertThrows(IllegalArgumentException.class, () -> RoutePause.parse(definition(bad)), bad);
    }

    @Test void aPauseHoldsForItsTicksAndTheLockoutBlocksANewOne() {
        RoutePause pause = new RoutePause(4, true, 20, 60);
        RoutePause.Clock clock = new RoutePause.Clock();
        assertFalse(clock.paused(0));
        assertTrue(clock.pause(pause, 100));
        assertTrue(clock.paused(119)); assertFalse(clock.paused(120));
        assertFalse(clock.pause(pause, 150), "inside the lockout");
        assertTrue(clock.pause(pause, 160));
        assertTrue(clock.checkDue(0)); assertFalse(clock.checkDue(4)); assertTrue(clock.checkDue(5));
    }
}
