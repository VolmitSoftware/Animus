package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host hears when an NPC becomes active, dies, comes back and goes away, so platforms can raise their own events. */
class NpcLifecycleHostTest {
    @TempDir Path directory;

    @Test
    void theHostHearsSpawnDeathReviveAndDespawn() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        try {
            harness.spawnNpc();
            harness.tick(2);
            assertEquals(List.of("spawned race-npc"), harness.lifecycle);

            PersistedNpcService.CombatBinding binding = harness.service.combat("race-npc").orElseThrow();
            VirtualNpcState.Snapshot dead = binding.state().damage(1000);
            binding.changed().accept(dead);
            assertEquals(List.of("spawned race-npc", "died race-npc"), harness.lifecycle);

            assertTrue(harness.service.revive(UUID.fromString("8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11"), new WorldPosition("world", new Vec3(2.5, 64, 2.5))));
            assertEquals("spawned race-npc", harness.lifecycle.getLast());

            Files.delete(npcs.resolve("race-npc.json"));
            runtime.npcs().reload();
            harness.service.refresh();
            harness.tick(2);
            assertEquals("despawned race-npc", harness.lifecycle.getLast(), harness.lifecycle.toString());
        } finally {
            harness.service.close();
            runtime.close();
        }
    }
}
