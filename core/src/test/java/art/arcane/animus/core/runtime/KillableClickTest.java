package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.presentation.BodyTransport;
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
 * A killable NPC takes a player's hit while the platform's interaction event runs: its health falls and its runtime
 * state moves on before the click is handed to triggers. That hit is still the same click on the same NPC, so attack
 * behaviors and listeners must hear it.
 */
class KillableClickTest {
    @TempDir Path directory;

    @Test
    void anAttackThatHurtsTheNpcInTheInteractionEventStillReachesTriggers() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        Files.writeString(npcs.resolve("target.json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0c22", "id": "target", "group": "test",
                 "name": "Target", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 0.5, "y": 64, "z": 2.5, "yaw": 180, "pitch": 0}}
                """);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        assertTrue(harness.service.available(), harness.service.status());
        harness.tick(6);

        List<Boolean> accepted = new ArrayList<>();
        harness.service.observeInteractions((UUID npc, UUID viewer, boolean attack, double distance, boolean preview) -> accepted.add(attack));
        // The combat listener of the interaction event deals the hit, as the platform's attack handling does.
        harness.allowed = (UUID npc, RuntimeHost.Interaction action) -> {
            PersistedNpcService.CombatBinding combat = harness.service.combat("target").orElseThrow();
            combat.changed().accept(combat.state().damage(1));
            return true;
        };
        BodyTransport.Body body = harness.transport.lastSpawned;
        assertNotNull(body, "the NPC reached the viewer");
        harness.transport.interactions.accept(new BodyTransport.Interaction(body, BodyTransport.Action.ATTACK, "none", false, System.nanoTime()));

        assertEquals(List.of(true), accepted, "the hit counts as an accepted attack: " + harness.service.status());
    }
}
