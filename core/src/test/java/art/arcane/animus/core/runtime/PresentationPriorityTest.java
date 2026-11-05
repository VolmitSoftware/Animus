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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With too little packet budget for one tick, despawns go out before moves, and cosmetic look-close turns wait until
 * the budget has kept up again.
 */
class PresentationPriorityTest {
    @TempDir Path directory;
    private static final PersistedNpcService.Settings TIGHT = new PersistedNpcService.Settings(true, 48, 64, 256, 16, 4, 8, 32);

    @Test
    void despawnsAreSentBeforeMovesWhenTheTickIsShortOfBudget() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i, false, true);
        reload(harness);
        harness.tick(6);
        assertEquals(6, harness.transport.spawned.get(), harness.transport.sent.toString());

        harness.transport.sent.clear();
        for (int i = 0; i < 3; i++) write(harness, "crowd-" + i, 2.5 + i, false, false);
        for (int i = 3; i < 6; i++) harness.service.lookAt("crowd-" + i, new WorldPosition("world", new Vec3(2.5 + i, 64, 40)));
        reload(harness);
        harness.tick(1);

        List<String> first = List.copyOf(harness.transport.sent);
        assertEquals(4, first.size(), "one tick spends at most packetsPerTick: " + first);
        assertEquals(List.of("despawn", "despawn", "despawn", "move"), first.stream().map((String line) -> line.split(" ")[0]).toList());
        assertTrue(harness.service.status().contains("presentation=strained"), harness.service.status());

        harness.tick(2);
        assertEquals(3, harness.transport.sent.stream().filter((String line) -> line.startsWith("move ")).count(), "the waiting moves follow on later ticks");
    }

    @Test
    void lookCloseTurnsWaitWhileTheBudgetIsShortAndResumeAfterward() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i, false, true);
        write(harness, "looker", 6.5, true, true);
        reload(harness);
        // Seven spawns at four a tick: the spawn ticks use the whole budget.
        int ticks = 0;
        while (harness.transport.spawned.get() < 7 && ticks++ < 20) harness.tick(1);
        assertEquals(7, harness.transport.spawned.get());
        String looker = "move " + uuid("looker");

        harness.tick(PresentationPressure.RECOVERY_TICKS - 5);
        assertFalse(harness.transport.sent.contains(looker), "a look-close turn is cosmetic and waits: " + harness.transport.sent);
        assertTrue(harness.service.status().contains("presentation=strained"), harness.service.status());

        harness.tick(PresentationPressure.RECOVERY_TICKS);
        assertTrue(harness.transport.sent.contains(looker), "the turn is sent once the budget keeps up: " + harness.transport.sent);
        assertTrue(harness.service.status().contains("presentation=normal"), harness.service.status());
    }

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, TIGHT);
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void write(ViewerWatchRaceTest.Harness harness, String id, double x, boolean lookClose, boolean spawned) throws IOException {
        Files.writeString(harness.npcs.resolve(id + ".json"), """
                {"schemaVersion": 1, "revision": %d, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:villager", "spawned": %s,
                 "position": {"world": "world", "x": %s, "y": 64, "z": 6.5, "yaw": 0, "pitch": 0}%s}
                """.formatted(spawned ? 1 : 2, uuid(id), id, id, spawned, x,
                lookClose ? ", \"modules\": {\"animus:look-close\": {\"range\": 16}}" : ""));
    }

    private static void reload(ViewerWatchRaceTest.Harness harness) throws IOException {
        harness.runtime.npcs().reload();
        harness.service.refresh();
    }
}
