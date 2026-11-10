package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSettingsTest {
    private static JsonObject definition(String home) {
        return JsonParser.parseString("{\"modules\":{\"animus:home\":" + home + "}}").getAsJsonObject();
    }

    @Test void parsesAndOnlyAnEnabledHomeRuns() {
        HomeSettings home = HomeSettings.parse(definition("{\"x\":1.5,\"y\":64,\"z\":-2,\"yaw\":90,\"delayTicks\":40,\"distance\":3,\"return\":\"walk\"}")).orElseThrow();
        assertEquals(new HomeSettings(new Vec3(1.5, 64, -2), 90f, null, 40, 3, HomeSettings.Return.WALK), home);
        assertEquals(HomeSettings.Return.TELEPORT, HomeSettings.parse(definition("{\"x\":0,\"y\":0,\"z\":0}")).orElseThrow().strategy());
        assertTrue(HomeSettings.parse(definition("{\"enabled\":false,\"x\":0,\"y\":0,\"z\":0}")).isEmpty());
        for (String bad : new String[]{"{\"x\":0,\"y\":0}", "{\"x\":0,\"y\":0,\"z\":0,\"return\":\"fly\"}", "{\"x\":0,\"y\":0,\"z\":0,\"delayTicks\":-1}",
                "{\"x\":0,\"y\":0,\"z\":0,\"distance\":300}", "{\"x\":0,\"y\":0,\"z\":0,\"world\":\"nether\"}"})
            assertThrows(IllegalArgumentException.class, () -> HomeSettings.parse(definition(bad)), bad);
    }

    @Test void dueAfterTheDelayAndBeyondTheDistance() {
        HomeSettings home = new HomeSettings(new Vec3(0, 64, 0), null, null, 40, 5, HomeSettings.Return.TELEPORT);
        assertFalse(home.due(new Vec3(10, 64, 0), 39), "not idle long enough");
        assertTrue(home.due(new Vec3(10, 64, 0), 40));
        assertFalse(home.due(new Vec3(4, 64, 0), 400), "inside the distance");
        assertFalse(new HomeSettings(new Vec3(0, 64, 0), null, null, 0, 0, HomeSettings.Return.TELEPORT).due(new Vec3(0.05, 64, 0), 400), "already home");
    }
}
