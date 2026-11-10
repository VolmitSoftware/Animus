package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A viewer whose packet callbacks overflowed is rebuilt from scratch once the backlog drains, not left without NPCs until it rejoins. */
class ViewerResyncTest {
    @TempDir Path directory;
    /** One viewer with one pending spawn: at most three packet callbacks may wait. */
    private static final PersistedNpcService.Settings SMALL = new PersistedNpcService.Settings(true, 48, 64, 1, 16, 32, 1, 32, 512, 24, 4);

    @Test
    void aViewerWhoseCallbacksOverflowedSeesTheNpcsAgain() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i);
        harness.runtime.npcs().reload();
        harness.service.refresh();
        for (int ticks = 0; harness.transport.spawned.get() < 6 && ticks < 60; ticks++) harness.tick(1);
        assertEquals(6, harness.transport.spawned.get());

        harness.transport.holdMoves = true;
        for (int i = 0; i < 6; i++) harness.service.lookAt("crowd-" + i, new WorldPosition("world", new Vec3(2.5 + i, 64, 40)));
        harness.tick(2);
        assertEquals(6, harness.transport.held.size(), harness.transport.sent.toString());
        // The network thread answers every held move at once: more callbacks than the queue holds.
        harness.transport.holdMoves = false;
        List.copyOf(harness.transport.held).forEach((CompletableFuture<Void> move) -> move.complete(null));

        harness.tick(60);
        assertEquals(2, harness.transport.watches.get(), "the viewer is watched again");
        assertEquals(12, harness.transport.spawned.get(), "every NPC is shown again: " + harness.service.status());
        assertTrue(harness.service.status().contains("Packet callback queue full"), harness.service.status());
    }

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, SMALL);
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void write(ViewerWatchRaceTest.Harness harness, String id, double x) throws IOException {
        Files.writeString(harness.npcs.resolve(id + ".json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": %s, "y": 64, "z": 6.5, "yaw": 0, "pitch": 0}}
                """.formatted(uuid(id), id, id, x));
    }
}
