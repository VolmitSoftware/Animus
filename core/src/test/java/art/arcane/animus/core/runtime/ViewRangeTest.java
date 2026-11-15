package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A player sees an NPC by the rule vanilla's entity tracker applies to a real entity, as a Citizens NPC would be seen:
 * horizontal distance within {@code [npcs] viewDistance}, clamped to the player's view distance in chunks, with the
 * NPC's chunk sent. Height does not count, so NPCs on a floor above or in a cave below show.
 */
class ViewRangeTest {
    @TempDir Path directory;

    @Test
    void anNpcAboveOrBelowShowsWithinTheHorizontalRange() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        // 40 blocks away and 30 down is 50 blocks in a straight line, past the 48-block range.
        npc(harness, "cave", 40.5, 64, 0.5);
        npc(harness, "edge", 0.5, 64, 48.5);
        npc(harness, "beyond", -48.5, 64, -20.5);
        harness.standing = new Vec3(0.5, 94, 0.5);
        reload(harness);
        harness.tick(12);
        assertEquals(ids("cave", "edge"), shown(harness), harness.transport.sent.toString());

        harness.standing = new Vec3(0.5, 34, 0.5);
        harness.tick(12);
        assertEquals(ids("cave", "edge"), shown(harness), "30 blocks up shows as 30 down does: " + harness.transport.sent);
        assertFalse(harness.transport.sent.stream().anyMatch((String line) -> line.startsWith("despawn")), harness.transport.sent.toString());
    }

    @Test
    void anNpcAPlayerSeesFromAboveLooksAroundForItsSensedTriggers() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        npc(harness, "cave", 40.5, 64, 0.5);
        npc(harness, "beyond", -48.5, 64, -20.5);
        harness.standing = new Vec3(0.5, 94, 0.5);
        reload(harness);
        harness.tick(12);
        assertEquals(ids("cave"), shown(harness), harness.transport.sent.toString());
        assertTrue(harness.service.watched(uuid("cave")), "the player sees it, so its sensed triggers look around");
        assertFalse(harness.service.watched(uuid("beyond")));
    }

    @Test
    void theRangeIsClampedToThePlayersViewDistance() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        npc(harness, "near", 30.5, 64, 0.5);
        npc(harness, "far", 0.5, 64, 40.5);
        harness.viewDistance = 2;
        reload(harness);
        harness.tick(12);
        assertEquals(ids("near"), shown(harness), "a 2-chunk view distance tracks entities within 32 blocks: " + harness.transport.sent);

        harness.viewDistance = 3;
        harness.tick(12);
        assertEquals(ids("near", "far"), shown(harness), harness.transport.sent.toString());

        harness.viewDistance = 2;
        harness.tick(12);
        assertEquals(ids("near"), shown(harness), harness.transport.sent.toString());
    }

    @Test
    void anNpcInAChunkNotSentToThePlayerWaits() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        npc(harness, "sent", 10.5, 64, 0.5);
        npc(harness, "unsent", 0.5, 64, 40.5);
        harness.sent = (Integer x, Integer z) -> z < 2;
        reload(harness);
        harness.tick(12);
        assertEquals(ids("sent"), shown(harness), harness.transport.sent.toString());
        harness.sent = (Integer x, Integer z) -> true;
        harness.tick(3 * NearestSelection.RECHECK_TURNS);
        assertEquals(ids("sent", "unsent"), shown(harness), harness.transport.sent.toString());
    }

    @Test
    void aPatrolBelowAPlayerWhoSeesItKeepsWalking() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        Files.writeString(harness.npcs.resolve("patrol.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "patrol", "group": "test",
                 "name": "Patrol", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:navigation": {"enabled": true, "mode": "walk", "speed": 1.72, "order": "sequence",
                   "loop": true, "waitTicks": 0, "points": [{"x": 6.5, "y": 64, "z": 2.5}, {"x": 2.5, "y": 64, "z": 2.5}]}}}
                """.formatted(uuid("patrol")));
        harness.standing = new Vec3(2.5, 94, 42.5);
        reload(harness);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            harness.tick(1);
            String navigation = harness.service.navigationStatus("patrol");
            if (navigation.contains("sleeping")) fail("the patrol slept while a player 40 blocks away saw it: " + navigation);
            if (navigation.contains("arrivals=") && !navigation.contains("arrivals=0")) break;
            if (System.nanoTime() > deadline) fail("expected an arrival within 30 s; navigation: " + navigation);
            try { TimeUnit.MILLISECONDS.sleep(1); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail("interrupted"); }
        }
        assertEquals(ids("patrol"), shown(harness), harness.transport.sent.toString());
    }

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        return harness;
    }

    /** The bodies the transport has on the client now: spawned and not despawned since. */
    private static Set<UUID> shown(ViewerWatchRaceTest.Harness harness) {
        Set<UUID> shown = new HashSet<>();
        for (String line : harness.transport.sent) {
            UUID body = UUID.fromString(line.substring(line.indexOf(' ') + 1));
            if (line.startsWith("spawn ")) shown.add(body);
            else if (line.startsWith("despawn ")) shown.remove(body);
        }
        return shown;
    }

    private static Set<UUID> ids(String... names) {
        Set<UUID> ids = new HashSet<>();
        for (String name : names) ids.add(uuid(name));
        return ids;
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void npc(ViewerWatchRaceTest.Harness harness, String id, double x, double y, double z) throws IOException {
        Files.writeString(harness.npcs.resolve(id + ".json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:player", "spawned": true,
                 "position": {"world": "world", "x": %s, "y": %s, "z": %s, "yaw": 0, "pitch": 0}}
                """.formatted(uuid(id), id, id, x, y, z));
    }

    private static void reload(ViewerWatchRaceTest.Harness harness) throws IOException {
        harness.runtime.npcs().reload();
        harness.service.refresh();
    }
}
