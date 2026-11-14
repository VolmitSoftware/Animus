package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A saved spot names its world as the NPC file does; the runtime knows that world by another key. */
class RelocateTest {
    private static final String KEY = "1b5c2a9e-0000-4000-8000-00000000a001";

    @TempDir Path directory;

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.worlds = (String reference) -> Optional.of("world".equals(reference) ? KEY : reference).filter(KEY::equals);
        harness.loadedWorlds = KEY::equals;
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        harness.spawnNpc();
        harness.tick(5);
        return harness;
    }

    @Test void theSavedWorldNameMovesTheNpcInItsLoadedWorld() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        Vec3 home = new Vec3(6.5, 64, 2.5);

        assertTrue(harness.service.relocate("race-npc", new WorldPosition("world", home)));

        assertEquals(new WorldPosition(KEY, home), harness.service.combat("race-npc").orElseThrow().state().snapshot().position());
    }

    @Test void aWorldThatIsNotLoadedLeavesTheNpcWhereItIs() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        WorldPosition before = harness.service.combat("race-npc").orElseThrow().state().snapshot().position();

        assertFalse(harness.service.relocate("race-npc", new WorldPosition("world_nether", new Vec3(6.5, 64, 2.5))));

        assertEquals(before, harness.service.combat("race-npc").orElseThrow().state().snapshot().position());
    }
}
