package art.arcane.animus.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimusRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void createsDefaultConfigurationAndStarts() throws IOException {
        Path dataDirectory = directory.resolve("nested/data");
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", dataDirectory));

        assertFalse(runtime.isRunning());
        runtime.start();

        assertAll(
                () -> assertTrue(runtime.isRunning()),
                () -> assertTrue(runtime.status().startsWith(BuildIdentity.NAME + " " + BuildIdentity.VERSION + " | "), runtime.status()),
                () -> assertFalse(Files.readString(dataDirectory.resolve("config.toml")).contains("statusMessage"))
        );
    }

    @ParameterizedTest
    @EnumSource(Platform.class)
    void reportsPlatformAndBuildIdentity(Platform platform) throws IOException {
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(platform, "26.2", directory));
        runtime.start();

        String status = runtime.status();

        assertAll(
                () -> assertTrue(status.contains(BuildIdentity.NAME)),
                () -> assertTrue(status.contains(BuildIdentity.VERSION)),
                () -> assertTrue(status.contains(runtime.environment().server()), status),
                () -> assertTrue(status.contains("26.2"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"26.1.2", "26.2", "26.3"})
    void acceptsSupportedMinecraftVersions(String minecraftVersion) throws IOException {
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.FABRIC, minecraftVersion, directory));

        runtime.start();

        assertTrue(runtime.isRunning());
        assertTrue(runtime.status().contains(minecraftVersion));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.20.1", "1.21.1", "1.20", "1.20.2", "1.21", "1.21.2", "26.1", "26.1.1", "26.2.1", "26.3.1", "26.4", "1.21.11"})
    void rejectsUnsupportedMinecraftBeforeWriting(String minecraftVersion) {
        Path dataDirectory = directory.resolve("unsupported");
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.FORGE, minecraftVersion, dataDirectory));

        IOException failure = assertThrows(IOException.class, runtime::start);

        assertAll(
                () -> assertTrue(failure.getMessage().contains(minecraftVersion)),
                () -> assertFalse(runtime.isRunning()),
                () -> assertFalse(Files.exists(dataDirectory))
        );
    }

    @Test
    void preservesExistingSettingsAndCachesStatus() throws IOException {
        Path config = directory.resolve("config.toml");
        String original = "# Keep my comments\n[npcs]\nviewDistance = 40\n";
        Files.writeString(config, original);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.NEOFORGE, "26.3", directory));

        runtime.start();

        assertEquals(original, Files.readString(config));
        Files.delete(config);
        assertEquals(40, runtime.npcSettings().viewDistance());
        assertFalse(Files.exists(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "{\"packetProofEnabled\":null}",
            "{\"packetProofEnabled\":\"\"}",
            "{\"packetProofEnabled\":42}",
            "{\"packetProofEnabled\":[]}",
            "{\"packetProofEnabled\":\"broken\"",
            "{packetProofEnabled:\"unquoted key\"}",
            "{\"packetProofEnabled\":'single quotes'}",
            "{\"packetProofEnabled\":true} trailing",
            "{\"packetProofEnabled\":true,}",
            "// comment\n{\"packetProofEnabled\":true}",
            "[]",
            ""
    })
    void rejectsInvalidConfigWithoutOverwriting(String content) throws IOException {
        Path config = directory.resolve("config.json");
        Files.writeString(config, content);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", directory));

        IOException failure = assertThrows(IOException.class, runtime::start);

        assertAll(
                () -> assertFalse(runtime.isRunning()),
                () -> assertEquals(content, Files.readString(config)),
                () -> assertTrue(failure.getMessage().contains("config.json"))
        );
    }

    @Test
    void refusesSecondStartWithoutDiscardingRunningSettings() throws IOException {
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.FABRIC, "26.2", directory));
        runtime.start();
        String status = runtime.status();

        assertThrows(IllegalStateException.class, runtime::start);

        assertTrue(runtime.isRunning());
        assertEquals(status, runtime.status());
    }

    @Test
    void closeIsIdempotentAndRestartReadsCurrentConfiguration() throws Exception {
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.FORGE, "26.2", directory));
        runtime.close();
        runtime.start();

        runtime.close();
        runtime.close();

        assertFalse(runtime.isRunning());
        assertThrows(IllegalStateException.class, runtime::status);
        Files.writeString(directory.resolve("config.toml"), "[npcs]\nviewDistance = 40\n");
        assertTrue(runtime.awaitFileQuiescence(java.time.Duration.ofSeconds(5)));
        runtime.start();
        assertTrue(runtime.isRunning());
        assertEquals(40, runtime.npcSettings().viewDistance());
    }

    @Test
    void canStartAfterConfigurationFailureIsCorrected() throws IOException {
        Path config = directory.resolve("config.json");
        Files.writeString(config, "[]");
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.NEOFORGE, "26.2", directory));
        assertThrows(IOException.class, runtime::start);
        Files.writeString(config, "{\"npcs\":{\"viewDistance\":40}}");

        runtime.start();

        assertTrue(runtime.isRunning());
        assertEquals(40, runtime.npcSettings().viewDistance());
    }

    @Test
    void filesystemFailureLeavesRuntimeStopped() throws IOException {
        Path dataDirectory = directory.resolve("file");
        Files.writeString(dataDirectory, "existing file");
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", dataDirectory));

        assertThrows(IOException.class, runtime::start);

        assertFalse(runtime.isRunning());
        assertEquals("existing file", Files.readString(dataDirectory));
    }

    @Test
    void environmentRejectsMissingValues() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new RuntimeEnvironment(null, "26.2", directory)),
                () -> assertThrows(NullPointerException.class, () -> new RuntimeEnvironment(Platform.BUKKIT, null, directory)),
                () -> assertThrows(NullPointerException.class, () -> new RuntimeEnvironment(Platform.BUKKIT, "26.2", null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuntimeEnvironment(Platform.BUKKIT, " ", directory)),
                () -> assertThrows(NullPointerException.class, () -> new AnimusRuntime(null))
        );
    }

    @Test
    void fileLimitsFollowAHotloadedConfigWithoutRestart() throws Exception {
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        try {
            String text = Files.readString(directory.resolve("config.toml")).replaceFirst("maximumOperations = 32", "maximumOperations = 1");
            runtime.applySettings(art.arcane.animus.core.config.PluginSettings.parse(text, directory.resolve("config.toml")));
            java.util.concurrent.Executor lane = runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {});
            java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
            lane.execute(() -> { try { hold.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> lane.execute(() -> {}));
            hold.countDown();
        } finally { runtime.close(); }
    }
}
