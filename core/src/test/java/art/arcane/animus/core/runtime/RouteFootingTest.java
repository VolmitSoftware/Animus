package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An imported patrol can stand above the ground it walks on (the terrain under a Citizens NPC changed, or it was placed
 * on a different world). A walking body falls to the ground before its first leg instead of failing every leg from the air.
 */
class RouteFootingTest {
    @TempDir Path directory;

    @Test
    void aPatrolStandingInTheAirFallsToTheGroundBeforeItsFirstLeg() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("floating-patrol.json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "5b1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c12", "id": "floating-patrol", "group": "test",
                 "name": "Patrol", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 67, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:navigation": {"enabled": true, "reviewAccepted": true, "mode": "walk", "speed": 1.72, "order": "sequence",
                   "loop": true, "waitTicks": 0, "points": [{"x": 6.5, "y": 64, "z": 2.5}, {"x": 2.5, "y": 64, "z": 2.5}]}}}
                """);
        runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(80);

        String navigation = harness.service.navigationStatus("floating-patrol");
        assertTrue(!navigation.contains("UNREACHABLE"), navigation);
        assertTrue(!navigation.contains("y=67.0, z=2.5]; tick"), "the body left the air: " + navigation);
        assertEquals(0, harness.warnings.size(), harness.warnings.toString());
    }
}
