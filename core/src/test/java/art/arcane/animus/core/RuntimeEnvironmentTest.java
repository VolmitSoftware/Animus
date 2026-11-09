package art.arcane.animus.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeEnvironmentTest {
    @TempDir
    Path directory;

    @org.junit.jupiter.api.Test
    void aSymlinkedDataFolderResolvesOnceSoBehaviorsStillLoad() throws Exception {
        Path volume = java.nio.file.Files.createDirectories(directory.resolve("volume/Animus"));
        Path linked = java.nio.file.Files.createSymbolicLink(java.nio.file.Files.createDirectories(directory.resolve("plugins")).resolve("Animus"), volume);
        Path resolved = RuntimeEnvironment.resolveDataDirectory(linked);
        assertEquals(volume.toRealPath(), resolved);
        java.nio.file.Files.createDirectories(resolved.resolve("behaviors"));
        art.arcane.animus.core.behavior.catalog.BehaviorCatalog catalog = new art.arcane.animus.core.behavior.catalog.BehaviorCatalog(resolved,
                art.arcane.animus.core.behavior.catalog.BehaviorCatalog.Limits.defaults(), request -> art.arcane.animus.core.behavior.catalog.BehaviorCatalog.Capability.present(),
                art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints.disabled());
        assertEquals(0, catalog.snapshot().generation());
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.isDirectory(RuntimeEnvironment.resolveDataDirectory(directory.resolve("plugins/fresh"))),
                "a missing folder is created");
    }

    @ParameterizedTest
    @CsvSource({
            "1.20.1-R0.1-SNAPSHOT,1.20.1",
            "1.21.1-R0.1-SNAPSHOT,1.21.1",
            "26.2-R0.1-SNAPSHOT,26.2",
            "26.1.2.build.74-stable,26.1.2",
            "26.3.build.9-alpha,26.3",
            "26.3-R0.1-SNAPSHOT,26.3",
            "26.2,26.2"
    })
    void extractsMinecraftReleaseFromBukkitServerVersion(String serverVersion, String minecraftVersion) {
        RuntimeEnvironment environment = RuntimeEnvironment.bukkit(serverVersion, directory);

        assertAll(
                () -> assertEquals(Platform.BUKKIT, environment.platform()),
                () -> assertEquals(minecraftVersion, environment.minecraftVersion()),
                () -> assertEquals(directory, environment.dataDirectory())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Paper 26.2", " 26.2", "unknown", "-26.2"})
    void rejectsServerVersionWithoutLeadingRelease(String serverVersion) {
        assertThrows(IllegalArgumentException.class, () -> RuntimeEnvironment.bukkit(serverVersion, directory));
    }

    @Test void theServerNameIsWhatStatusShows() {
        assertEquals("Paper", RuntimeEnvironment.bukkit("26.3-R0.1-SNAPSHOT", directory, "Paper").server());
        assertEquals("NeoForge", new RuntimeEnvironment(Platform.NEOFORGE, "26.3", directory).server());
        assertEquals("Bukkit", RuntimeEnvironment.bukkit("26.3", directory).server());
    }
}
