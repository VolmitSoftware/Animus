package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An NPC that cannot reach its home warns once for each run of failed tries, not on every check. */
class HomeWarningsTest {
    @TempDir Path directory;

    @Test void aHomeThatCannotBeReachedWarnsOncePerFailureStreak() throws Exception {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        // The home's chunk (6, 6) is not loaded, so every teleport home is refused until it is.
        harness.loaded = (Integer x, Integer z) -> x < 4 && z < 4;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("homebody.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "5b1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c13", "id": "homebody", "group": "test",
                 "name": "Homebody", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:home": {"x": 100.5, "y": 64, "z": 100.5, "delayTicks": 0, "return": "teleport"}}}
                """);
        runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(200);
        assertEquals(List.of("NPC homebody could not teleport home: the place is not loaded or not clear"), homeWarnings(harness));
        harness.loaded = (Integer x, Integer z) -> true;
        harness.tick(40);
        assertTrue(harness.service.inspect("homebody").contains("100.5"), harness.service.inspect("homebody"));
        assertEquals(1, homeWarnings(harness).size(), homeWarnings(harness).toString());
    }

    private static List<String> homeWarnings(ViewerWatchRaceTest.Harness harness) {
        return harness.warnings.stream().filter((String warning) -> warning.contains("home")).toList();
    }
}
