package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** A step that terrain blocks waits a few ticks before the leg is planned again, instead of planning on every tick. */
class ReplanBackoffTest {
    private static final String NPC = "walker";
    private static final UUID ID = UUID.fromString("6c1f0c52-6f0e-4f43-a3a3-9d1f8f8a0c15");
    @TempDir Path directory;

    @Test void aBlockedStepWaitsBeforeItPlansAgain() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve(NPC + ".json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "Walker", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:navigation": {"enabled": true, "speed": 2, "waitTicks": 0, "points": [{"x": 30, "y": 64, "z": 2}]}}}
                """.formatted(ID, NPC));
        runtime.npcs().reload();
        harness.service.refresh();
        until(harness, ": moving;");
        // A wall rises right in front of the walking body.
        double x = harness.service.state(ID).orElseThrow().position().point().x();
        int wall = (int) Math.floor(x) + 1;
        harness.terrain = (int cx, int y, int z) -> y < 64 || (cx == wall && y < 67) ? Cell.SOLID : Cell.AIR;
        until(harness, ": replanning;");
        for (int i = 1; i < RouteRules.REPLAN_BACKOFF_TICKS; i++) {
            harness.tick(1);
            String status = harness.service.navigationStatus(NPC);
            assertTrue(status.contains(": replanning;"), "tick " + i + " after the block: " + status);
        }
    }

    private static void until(ViewerWatchRaceTest.Harness harness, String status) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (harness.service.navigationStatus(NPC).contains(status)) return;
            if (harness.service.navigationStatus(NPC).contains("planning")) {
                try { Thread.sleep(2); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            harness.tick(1);
        }
        fail("no " + status + ": " + harness.service.navigationStatus(NPC));
    }
}
