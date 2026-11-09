package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.npc.NpcItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On the main-thread runtime, a fault in one NPC stops that NPC and is recorded; every other NPC keeps ticking. */
class NpcIsolationTest {
    @TempDir Path directory;

    @Test
    void anNpcThatFailsToActivateDoesNotStopTheOthers() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        harness.items = (NpcItem item) -> { throw new IllegalStateException("item conversion broke"); };
        write(harness, "broken", 2.5, ", \"equipment\": {\"head\": {\"id\": \"minecraft:iron_helmet\"}}");
        write(harness, "fine", 4.5, "");
        harness.runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(6);

        assertTrue(harness.service.available(), harness.service.status());
        assertTrue(harness.service.state(uuid("fine")).isPresent(), "the other NPC is active");
        assertTrue(harness.transport.sent.contains("spawn " + uuid("fine")), harness.transport.sent.toString());
        assertTrue(harness.service.status().contains("item conversion broke"), harness.service.status());
    }

    @Test
    void anNpcWhoseIdleFallFailsStopsAloneWhileTheOthersLand() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        harness.terrain = (int x, int y, int z) -> {
            if (x < 10) throw new IllegalStateException("terrain read broke");
            return y < 60 ? Cell.SOLID : Cell.AIR;
        };
        write(harness, "broken", 2.5, "");
        write(harness, "falling", 20.5, "");
        harness.runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(3);
        harness.service.terrainChanged("world", new BlockPos(2, 63, 6));
        harness.service.terrainChanged("world", new BlockPos(20, 63, 6));
        harness.tick(40);

        assertTrue(harness.service.available(), harness.service.status());
        assertTrue(harness.service.state(uuid("broken")).isEmpty(), "the failing NPC is stopped");
        assertEquals(60.0, harness.service.state(uuid("falling")).orElseThrow().position().point().y(), 1e-6, "the other NPC lands");
        assertTrue(harness.service.status().contains("terrain read broke"), harness.service.status());
    }

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void write(ViewerWatchRaceTest.Harness harness, String id, double x, String extra) throws IOException {
        Files.writeString(harness.npcs.resolve(id + ".json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:player", "spawned": true,
                 "position": {"world": "world", "x": %s, "y": 64, "z": 6.5, "yaw": 0, "pitch": 0}%s}
                """.formatted(uuid(id), id, id, x, extra));
    }
}
