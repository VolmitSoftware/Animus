package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.building.BuildOptions;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.config.SettingsEdit;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import com.google.gson.JsonObject;
import java.io.IOException;
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

/** {@code [builds]} applies on a hotload: the switch, the build cap and the file limits, without a restart. */
class BuildServiceLiveSettingsTest {
    @TempDir Path directory;
    private AnimusRuntime runtime;
    private ViewerWatchRaceTest.Harness harness;
    private BuildService<ViewerWatchRaceTest.Player, Object> builds;

    @AfterEach void stop() {
        if (builds != null) builds.close();
        if (runtime != null) runtime.close();
    }

    private void start(boolean enabled) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        SettingsEdit.apply(runtime, "builds", (JsonObject builds) -> builds.addProperty("enabled", enabled));
        harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        harness.spawnNpc();
        Files.writeString(npcs.resolve("second.json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "1f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c22", "id": "second", "group": "test",
                 "name": "Second", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 4.5, "y": 64, "z": 4.5, "yaw": 0, "pitch": 0}}
                """);
        runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(5);
        Function<Runnable, AutoCloseable> ticks = (Runnable tick) -> { harness.ticks.add(tick); return () -> harness.ticks.remove(tick); };
        @SuppressWarnings("unchecked")
        BuildService.Platform<ViewerWatchRaceTest.Player, Object> platform = (BuildService.Platform<ViewerWatchRaceTest.Player, Object>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {BuildService.Platform.class}, (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> {
                    if (method.getReturnType() == Optional.class) return Optional.empty();
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        builds = new BuildService<>(runtime, harness.service, Runnable::run, platform, art.arcane.animus.core.building.BuildProtection.NONE, "none",
                ticks, () -> null, (String line) -> {}, null, null);
    }

    /** Planning runs on the build worker; its answer lands on a later owner tick. */
    private void tickUntil(java.util.function.BooleanSupplier done) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            harness.tick(1);
            try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
        assertTrue(done.getAsBoolean(), "the build worker did not answer in time");
    }

    private static BuildService.Start start(String npc) {
        return new BuildService.Start(npc, "missing.schem", Optional.empty(), BuildOptions.DEFAULT, Optional.empty());
    }

    @Test void turningBuildsOnAndOffAppliesWithoutARestart() throws IOException {
        start(false);
        assertEquals(Optional.of("disabled in config.toml"), builds.unavailable());
        assertThrows(IllegalStateException.class, builds::list);

        SettingsEdit.apply(runtime, "builds", (JsonObject table) -> table.addProperty("enabled", true));
        harness.tick(1);
        assertTrue(builds.available(), builds.status());
        assertTrue(builds.status().startsWith("Builds ready"), builds.status());

        SettingsEdit.apply(runtime, "builds", (JsonObject table) -> table.addProperty("enabled", false));
        harness.tick(1);
        assertFalse(builds.available());
        assertTrue(builds.status().contains("disabled in config.toml"), builds.status());
    }

    @Test void aLowerBuildCapHoldsNewBuildsBackUntilRunningOnesEnd() throws IOException {
        start(true);
        harness.tick(1);
        List<String> replies = new ArrayList<>();
        builds.start(start("race-npc"), replies::add);
        assertTrue(builds.status().contains("open=0/16"), builds.status());

        SettingsEdit.apply(runtime, "builds", (JsonObject table) -> table.addProperty("maximumBuilds", 1));
        assertTrue(builds.status().contains("open=0/1"), builds.status());
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> builds.start(start("second"), replies::add));
        assertTrue(refused.getMessage().contains("At most 1 builds"), refused.getMessage());

        tickUntil(() -> replies.size() == 1);
        assertTrue(replies.getFirst().contains("missing.schem"), replies.toString());
        builds.start(start("second"), replies::add);
        tickUntil(() -> replies.size() == 2);
    }

    @Test void aLowerFileLimitAppliesToTheNextRead() throws IOException {
        start(true);
        harness.tick(1);
        Files.createDirectories(directory.resolve(BuildService.SCHEMATICS));
        Files.write(directory.resolve(BuildService.SCHEMATICS).resolve("big.schem"), new byte[4096]);
        SettingsEdit.apply(runtime, "builds", (JsonObject table) -> table.addProperty("maximumFileBytes", 1024));
        List<List<String>> replies = new ArrayList<>();
        builds.inspect("big.schem", replies::add);
        tickUntil(() -> replies.size() == 1);
        assertTrue(replies.getFirst().getFirst().contains("the limit is 1024"), replies.toString());
    }
}
