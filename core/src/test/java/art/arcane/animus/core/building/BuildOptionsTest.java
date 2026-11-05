package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildOptionsTest {
    @Test void commandWordsChangeOnlyWhatTheyName() {
        BuildOptions options = BuildOptions.DEFAULT.with(Map.of("rotation", "180", "mirror", "z", "yoffset", "-2", "pattern", "reverselinear",
                "excavate", "true", "materials", "container", "container", "1,64,-3", "speed", "3.5", "interval", "2"));
        assertEquals(BlockTransform.Rotation.CLOCKWISE_180, options.transform().rotation());
        assertEquals(BlockTransform.Mirror.Z, options.transform().mirror());
        assertEquals(-2, options.offsetY());
        assertEquals(BuildOptions.Pattern.REVERSE_LINEAR, options.pattern());
        assertTrue(options.excavate());
        assertEquals(Optional.of(new BlockPos(1, 64, -3)), options.container());
        assertEquals(3.5, options.speed());
        assertEquals(2, options.placeIntervalTicks());
        assertEquals(BuildOptions.DEFAULT.reach(), options.reach());
    }

    @Test void jsonRoundTripKeepsEveryField() {
        BuildOptions options = BuildOptions.DEFAULT.with(Map.ofEntries(Map.entry("anchor", "schematic"), Map.entry("rotation", "90"),
                Map.entry("offset", "1,2,3"), Map.entry("layers", "3"), Map.entry("groupall", "true"), Map.entry("ignoreair", "true"),
                Map.entry("ignoreliquids", "true"), Map.entry("hold", "false"), Map.entry("silent", "true"), Map.entry("materials", "donate"),
                Map.entry("timeout", "30")));
        assertEquals(options, BuildOptions.fromJson(options.toJson(), "$.options"));
    }

    @Test void refusesUnknownAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.DEFAULT.with(Map.of("colour", "red")));
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.DEFAULT.with(Map.of("speed", "9")));
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.DEFAULT.with(Map.of("materials", "container")));
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.DEFAULT.with(Map.of("offset", "1,2")));
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.DEFAULT.with(Map.of("rotation", "45")));
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.DEFAULT.with(Map.of("excavate", "yes")));
        JsonObject unknown = BuildOptions.DEFAULT.toJson();
        unknown.addProperty("colour", "red");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> BuildOptions.fromJson(unknown, "$.options"));
        assertEquals("$.options.colour: unknown build option", failure.getMessage());
    }

    @Test void containerIsDroppedWhenAnotherSourceIsChosen() {
        BuildOptions container = BuildOptions.DEFAULT.with(Map.of("materials", "container", "container", "0,64,0"));
        assertEquals(Optional.empty(), container.with(Map.of("materials", "donate")).container());
    }
}
