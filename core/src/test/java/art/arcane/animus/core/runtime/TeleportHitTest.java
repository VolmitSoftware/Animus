package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A teleport inside one world leaves the client's entities where they are, so the NPCs a player sees stay shown under the
 * same entity ids and a hit on one lands at once. Leaving the area still takes them away, as walking off does.
 */
class TeleportHitTest {
    @TempDir Path directory;

    @Test
    void aHitRightAfterAShortTeleportLandsOnTheSameBody() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        BodyTransport.Body body = harness.transport.lastSpawned;
        assertNotNull(body, "the NPC reached the viewer");
        int spawned = harness.transport.spawned.get();
        List<Boolean> accepted = new ArrayList<>();
        harness.service.observeInteractions((UUID npc, UUID viewer, boolean attack, double distance, boolean preview) -> accepted.add(attack));

        harness.service.teleported(harness.player);
        harness.transport.interactions.accept(new BodyTransport.Interaction(body, BodyTransport.Action.ATTACK, "none", false, System.nanoTime()));

        assertEquals(List.of(true), accepted, "the hit lands on the body the client still shows: " + harness.service.status());
        harness.tick(6);
        assertEquals(spawned, harness.transport.spawned.get(), "the NPC is not shown again under a new id");
    }

    @Test
    void aTeleportAwayStillTakesTheNpcAway() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        BodyTransport.Body body = harness.transport.lastSpawned;
        assertNotNull(body, "the NPC reached the viewer");

        harness.standing = new Vec3(5000.5, 64, 0.5);
        harness.service.teleported(harness.player);
        harness.tick(6);

        assertTrue(harness.transport.sent.contains("despawn " + body.uuid()), "out of range, the body leaves: " + harness.transport.sent);
    }

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        Files.writeString(npcs.resolve("target.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0c22", "id": "target", "group": "test",
                 "name": "Target", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 0.5, "y": 64, "z": 2.5, "yaw": 180, "pitch": 0}}
                """);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        assertTrue(harness.service.available(), harness.service.status());
        harness.tick(6);
        return harness;
    }
}
