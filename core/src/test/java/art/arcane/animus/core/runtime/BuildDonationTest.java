package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.config.SettingsEdit;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A regionized donation hands back the very stack the player held, never a fresh item rebuilt from its id. */
class BuildDonationTest {
    @TempDir Path directory;
    private AnimusRuntime runtime;
    private BuildService<ViewerWatchRaceTest.Player, Object> builds;

    @AfterEach void stop() {
        if (builds != null) builds.close();
        if (runtime != null) runtime.close();
    }

    @Test void aRefusedDonationOnARegionizedServerReturnsTheOriginalStack() throws Exception {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        SettingsEdit.apply(runtime, "builds", (JsonObject table) -> table.addProperty("enabled", true));
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        harness.spawnNpc();
        harness.tick(5);
        Object worn = new Object();
        List<Object> handed = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        @SuppressWarnings("unchecked")
        BuildService.Platform<ViewerWatchRaceTest.Player, Object> platform = (BuildService.Platform<ViewerWatchRaceTest.Player, Object>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {BuildService.Platform.class}, (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> {
                    switch (method.getName()) {
                        case "held" -> { return Optional.of(new BuildService.Platform.Held("minecraft:diamond_pickaxe", 1)); }
                        case "removeHeld" -> { return worn; }
                        case "giveBack" -> { handed.add(arguments[1]); counts.add((Integer) arguments[2]); return null; }
                        default -> {
                            if (method.getReturnType() == Optional.class) return Optional.empty();
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        }
                    }
                });
        BuildService.Regions regions = new BuildService.Regions() {
            @Override public boolean run(String world, int chunkX, int chunkZ, Runnable work) { work.run(); return true; }
            @Override public String unreachable(String world, int chunkX, int chunkZ) { return null; }
        };
        Function<Runnable, AutoCloseable> ticks = (Runnable tick) -> { harness.ticks.add(tick); return () -> harness.ticks.remove(tick); };
        builds = new BuildService<>(runtime, harness.service, Runnable::run, platform, art.arcane.animus.core.building.BuildProtection.NONE, "none",
                ticks, () -> null, (String line) -> {}, regions, null);
        harness.tick(1);
        List<String> replies = new ArrayList<>();
        builds.donateHeld("race-npc", harness.player.id(), replies::add);
        harness.tick(2);
        assertEquals(1, replies.size(), replies.toString());
        assertTrue(replies.getFirst().contains("No build for race-npc"), replies.toString());
        assertEquals(1, handed.size(), "the stack goes back once");
        assertSame(worn, handed.getFirst(), "the original stack, with its damage and components, goes back");
        assertEquals(List.of(1), counts);
    }
}
