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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A viewer's outgoing packet queue stays bounded, a full queue holds work back instead of dropping the viewer, and
 * cosmetic updates such as arm swings go after every move and are coalesced. Attack swings go with moves.
 */
class PresentationQueueTest {
    @TempDir Path directory;

    @Test
    void aSlowConnectionHoldsMovesAtTheQueueBoundAndKeepsTheViewer() throws IOException {
        // Plenty of packet budget, a queue of eight: the queue is what binds.
        ViewerWatchRaceTest.Harness harness = harness(new PersistedNpcService.Settings(true, 48, 64, 256, 16, 64, 8, 32, 512, 8, 4));
        for (int i = 0; i < 12; i++) write(harness, "crowd-" + i, 2.5 + i);
        reload(harness);
        tickUntilSpawned(harness, 12);

        harness.transport.sent.clear();
        harness.transport.holdMoves = true;
        turnAll(harness, 12, 40);
        harness.tick(3);
        assertEquals(8, moves(harness), "no more moves than the queue holds: " + harness.transport.sent);
        assertEquals(0, harness.transport.forgotten.get(), "a full queue is not a failure");
        assertTrue(harness.service.status().contains("presentation=strained"), harness.service.status());

        harness.transport.holdMoves = false;
        for (CompletableFuture<Void> waiting : harness.transport.held) waiting.complete(null);
        harness.tick(3);
        assertEquals(12, moves(harness), "the rest follow once the queue drains: " + harness.transport.sent);
    }

    @Test
    void swingsWaitBehindMovesAndSeveralInOneTickShowAsOne() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(new PersistedNpcService.Settings(true, 48, 64, 256, 16, 2, 8, 32, 512, 24, 4));
        for (int i = 0; i < 3; i++) write(harness, "crowd-" + i, 2.5 + i);
        reload(harness);
        tickUntilSpawned(harness, 3);
        harness.tick(PresentationPressure.RECOVERY_TICKS + 2);

        harness.transport.sent.clear();
        int before = harness.transport.swings.get();
        harness.service.swing("crowd-0");
        turnAll(harness, 3, 40);
        harness.tick(1);
        assertEquals(2, moves(harness), "the tick's two packets go to moves");
        assertEquals(before, harness.transport.swings.get(), "the swing is cosmetic and waits");

        harness.tick(PresentationPressure.RECOVERY_TICKS + 2);
        before = harness.transport.swings.get();
        for (int i = 0; i < 3; i++) harness.service.swing("crowd-1");
        harness.tick(1);
        assertEquals(before + 1, harness.transport.swings.get(), "three swings in one tick are sent as one");
    }

    @Test
    void attackSwingsGoWithMovesWhileCosmeticWorkPauses() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(new PersistedNpcService.Settings(true, 48, 64, 256, 16, 2, 8, 32, 512, 24, 4));
        for (int i = 0; i < 3; i++) write(harness, "crowd-" + i, 2.5 + i);
        reload(harness);
        tickUntilSpawned(harness, 3);
        harness.tick(PresentationPressure.RECOVERY_TICKS + 2);

        turnAll(harness, 3, 40);
        harness.tick(1);
        assertTrue(harness.service.status().contains("presentation=strained"), harness.service.status());
        int before = harness.transport.swings.get();
        harness.service.attackSwing("crowd-0");
        harness.tick(1);
        assertEquals(before + 1, harness.transport.swings.get(), "an attack is shown on the next turn, not after the cosmetic pause");
        harness.tick(PresentationPressure.RECOVERY_TICKS + 2);
        assertEquals(before + 1, harness.transport.swings.get(), "one attack, one swing");
    }

    @Test
    void anAttackSwingHeldByAFullQueueIsSentOnceItDrains() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(new PersistedNpcService.Settings(true, 48, 64, 256, 16, 64, 8, 32, 512, 8, 4));
        for (int i = 0; i < 12; i++) write(harness, "crowd-" + i, 2.5 + i);
        reload(harness);
        tickUntilSpawned(harness, 12);

        harness.transport.holdMoves = true;
        turnAll(harness, 12, 40);
        harness.tick(1);
        int before = harness.transport.swings.get();
        harness.service.attackSwing("crowd-11");
        harness.tick(15);
        assertEquals(before, harness.transport.swings.get(), "the queue is full");
        harness.transport.holdMoves = false;
        for (CompletableFuture<Void> waiting : harness.transport.held) waiting.complete(null);
        harness.tick(3);
        assertEquals(before + 1, harness.transport.swings.get(), "a held attack is not dropped as stale");
    }

    @Test
    void aDrawnBowShowsAsTheLivingUseFlagAndIsLoweredAgain() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(new PersistedNpcService.Settings(true, 48, 64, 256, 16, 64, 8, 32, 512, 24, 4));
        harness.transport.presentation = true;
        write(harness, "archer", 2.5);
        reload(harness);
        tickUntilSpawned(harness, 1);

        harness.service.drawing("archer", true);
        harness.tick(2);
        assertTrue(harness.transport.looks.getLast().usingItem(), "drawn");
        assertEquals(0, harness.transport.looks.getLast().sharedFlags(false), "drawing sets no shared flag");
        harness.service.drawing("archer", false);
        harness.tick(2);
        assertFalse(harness.transport.looks.getLast().usingItem(), "lowered");
    }

    private ViewerWatchRaceTest.Harness harness(PersistedNpcService.Settings settings) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, settings);
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }

    private static void tickUntilSpawned(ViewerWatchRaceTest.Harness harness, int count) {
        for (int ticks = 0; harness.transport.spawned.get() < count && ticks < 40; ticks++) harness.tick(1);
        assertEquals(count, harness.transport.spawned.get());
        harness.tick(2);
    }

    private static void turnAll(ViewerWatchRaceTest.Harness harness, int count, double z) {
        for (int i = 0; i < count; i++) harness.service.lookAt("crowd-" + i, new WorldPosition("world", new Vec3(2.5 + i, 64, z)));
    }

    private static long moves(ViewerWatchRaceTest.Harness harness) {
        return harness.transport.sent.stream().filter((String line) -> line.startsWith("move ")).count();
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void write(ViewerWatchRaceTest.Harness harness, String id, double x) throws IOException {
        Files.writeString(harness.npcs.resolve(id + ".json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": %s, "y": 64, "z": 6.5, "yaw": 0, "pitch": 0}}
                """.formatted(uuid(id), id, id, x));
    }

    private static void reload(ViewerWatchRaceTest.Harness harness) throws IOException {
        harness.runtime.npcs().reload();
        harness.service.refresh();
    }
}
