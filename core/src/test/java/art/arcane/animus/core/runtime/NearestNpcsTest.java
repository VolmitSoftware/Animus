package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.virtual.Vec3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With more NPCs in range than {@code [npcs] maximumVisible}, a player is shown the nearest ones, ranked again as they
 * move, with a margin so NPCs at the edge do not flicker; an NPC that leaves their view is despawned for them and comes
 * back with a fresh spawn.
 */
class NearestNpcsTest {
    @TempDir Path directory;

    @Test
    void theNearestAreShownAndRankedAgainAsThePlayerMoves() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(3);
        npc(harness, "a", 40.5); npc(harness, "b", 30.5); npc(harness, "c", 2.5); npc(harness, "d", 20.5);
        npc(harness, "e", 6.5); npc(harness, "f", 10.5); npc(harness, "g", -12.5);
        reload(harness);
        harness.tick(12);
        assertEquals(ids("c", "e", "f"), shown(harness), harness.transport.sent.toString());
        assertTrue(harness.transport.sent.stream().noneMatch((String line) -> line.startsWith("despawn")), "the nearest were the first shown: " + harness.transport.sent);

        harness.standing = new Vec3(40.5, 64, 0.5);
        harness.tick(12);
        assertEquals(ids("a", "b", "d"), shown(harness), harness.transport.sent.toString());
        assertEquals(3, count(harness, "visible"));

        harness.standing = new Vec3(0.5, 64, 0.5);
        harness.tick(12);
        assertEquals(ids("c", "e", "f"), shown(harness), harness.transport.sent.toString());
        assertEquals(3, count(harness, "visible"));
        assertEquals(2, harness.transport.sent.stream().filter(("spawn " + uuid("c"))::equals).count(), "c left and came back with a fresh spawn");
        assertEquals(1, harness.transport.sent.stream().filter(("despawn " + uuid("c"))::equals).count());
    }

    @Test
    void anNpcAtTheEdgeKeepsItsPlaceUntilAnotherIsClearlyNearer() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(1);
        npc(harness, "east", 4.5); npc(harness, "west", -5.5);
        reload(harness);
        harness.tick(12);
        assertEquals(ids("east"), shown(harness));

        harness.standing = new Vec3(-1.5, 64, 0.5);
        harness.tick(3 * NearestSelection.RECHECK_TURNS);
        assertEquals(ids("east"), shown(harness), "west is nearer by less than the margin: " + harness.transport.sent);

        harness.standing = new Vec3(-3.5, 64, 0.5);
        harness.tick(12);
        assertEquals(ids("west"), shown(harness), harness.transport.sent.toString());

        int sent = harness.transport.sent.size();
        for (int i = 0; i < 4; i++) {
            harness.standing = new Vec3(i % 2 == 0 ? 0.5 : -1.5, 64, 0.5);
            harness.tick(NearestSelection.RECHECK_TURNS + 2);
        }
        assertEquals(ids("west"), shown(harness), "small moves back and forth swap nothing");
        assertTrue(harness.transport.sent.subList(sent, harness.transport.sent.size()).stream().noneMatch((String line) -> line.startsWith("spawn") || line.startsWith("despawn")),
                harness.transport.sent.toString());
    }

    @Test
    void aPlaceThatOpensGoesToTheNextNearestAtOnce() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(2);
        npc(harness, "a", 2.5); npc(harness, "b", 4.5); npc(harness, "c", 6.5); npc(harness, "d", 8.5);
        reload(harness);
        harness.tick(12);
        assertEquals(ids("a", "b"), shown(harness));

        Files.writeString(harness.npcs.resolve("a.json"), Files.readString(harness.npcs.resolve("a.json"))
                .replace("\"revision\": 1", "\"revision\": 2").replace("\"spawned\": true", "\"spawned\": false"));
        reload(harness);
        harness.tick(3);
        assertEquals(ids("b", "c"), shown(harness), "c takes a's place without waiting for the next ranking: " + harness.transport.sent);
    }

    @Test
    void aPlayerArrivingWhileChunksStillStreamInIsFirstShownTheNearest() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(3);
        // Scan order runs from the far west corner, so a scan that ignored distance would fill the cap with these two first.
        npc(harness, "far-west", -40.5); npc(harness, "west", -30.5);
        npc(harness, "a", 2.5); npc(harness, "b", 6.5); npc(harness, "c", 10.5);
        harness.sent = (Integer x, Integer z) -> false;
        reload(harness);
        harness.tick(3);
        assertTrue(shown(harness).isEmpty(), "no chunk has reached the player yet");
        harness.sent = (Integer x, Integer z) -> true;
        harness.tick(3 * NearestSelection.RECHECK_TURNS);
        assertEquals(ids("a", "b", "c"), shown(harness), harness.transport.sent.toString());
        assertTrue(harness.transport.sent.stream().noneMatch((String line) -> line.startsWith("despawn")), "nothing was shown only to be swapped out: " + harness.transport.sent);
    }

    @Test
    void aCapLoweredWhileSpawnsAreInFlightRetiresNothingAndHoldsBackNewNpcs() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(3);
        npc(harness, "a", 2.5); npc(harness, "b", 6.5); npc(harness, "c", 10.5); npc(harness, "d", 14.5);
        harness.transport.holdSpawns = true;
        reload(harness);
        harness.tick(3);
        assertEquals(ids("a", "b", "c"), shown(harness));
        harness.service.settings = PersistedNpcService.Settings.from(new NpcRuntimeSettings(true, 48, 1, 256, 16, 64, 8, 32, 512, 24, 4));
        harness.tick(3);
        harness.transport.holdSpawns = false;
        List.copyOf(harness.transport.held).forEach((java.util.concurrent.CompletableFuture<Void> pending) -> pending.complete(null));
        harness.tick(3 * NearestSelection.RECHECK_TURNS);
        assertEquals(ids("a", "b", "c"), shown(harness), "bodies already on their way stay: " + harness.transport.sent);
        Files.writeString(harness.npcs.resolve("a.json"), Files.readString(harness.npcs.resolve("a.json"))
                .replace("\"revision\": 1", "\"revision\": 2").replace("\"spawned\": true", "\"spawned\": false"));
        reload(harness);
        harness.tick(3 * NearestSelection.RECHECK_TURNS);
        assertEquals(ids("b", "c"), shown(harness), "d waits while two are shown under a cap of one: " + harness.transport.sent);
    }

    @Test
    void aCrowdIsShownNearestFirstWithoutAnyNotice() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(2);
        harness.admins.add(harness.player.id());
        for (int i = 0; i < 4; i++) npc(harness, "crowd-" + i, 2.5 + 3 * i);
        reload(harness);
        harness.tick(40);
        assertEquals(ids("crowd-0", "crowd-1"), shown(harness), "the nearest two: " + harness.transport.sent);
        assertTrue(harness.warnings.stream().noneMatch((String line) -> line.contains("maximumVisible")), harness.warnings.toString());
        assertTrue(harness.told.isEmpty(), "no admin is told: " + harness.told);
    }

    private ViewerWatchRaceTest.Harness harness(int maximumVisible) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run,
                PersistedNpcService.Settings.from(new NpcRuntimeSettings(true, 48, maximumVisible, 256, 16, 64, 8, 32, 512, 24, 4)));
        assertTrue(harness.service.available(), harness.service.status());
        return harness;
    }

    /** The bodies the transport has on the client now: spawned and not despawned since. A second spawn or a stray despawn fails. */
    private static Set<UUID> shown(ViewerWatchRaceTest.Harness harness) {
        Set<UUID> shown = new HashSet<>();
        for (String line : harness.transport.sent) {
            UUID body = UUID.fromString(line.substring(line.indexOf(' ') + 1));
            if (line.startsWith("spawn ")) assertTrue(shown.add(body), "spawned twice: " + harness.transport.sent);
            else if (line.startsWith("despawn ")) assertTrue(shown.remove(body), "despawned while not shown: " + harness.transport.sent);
        }
        return shown;
    }

    private static Set<UUID> ids(String... names) {
        Set<UUID> ids = new HashSet<>();
        for (String name : names) ids.add(uuid(name));
        return ids;
    }

    private static int count(ViewerWatchRaceTest.Harness harness, String field) {
        Matcher matcher = Pattern.compile("\\| " + field + "=(\\d+)").matcher(harness.service.status());
        assertTrue(matcher.find(), harness.service.status());
        return Integer.parseInt(matcher.group(1));
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void npc(ViewerWatchRaceTest.Harness harness, String id, double x) throws IOException {
        Files.writeString(harness.npcs.resolve(id + ".json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": %s, "y": 64, "z": 0.5, "yaw": 0, "pitch": 0}}
                """.formatted(uuid(id), id, id, x));
    }

    private static void reload(ViewerWatchRaceTest.Harness harness) throws IOException {
        harness.runtime.npcs().reload();
        harness.service.refresh();
    }
}
