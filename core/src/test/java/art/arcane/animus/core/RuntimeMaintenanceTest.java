package art.arcane.animus.core;

import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeMaintenanceTest {
    @TempDir Path root;

    private AnimusRuntime runtime() { return new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root)); }

    private Path orphan(String content) throws Exception {
        String hash;
        try (DocumentStore store = new DocumentStore(root)) { hash = store.putImmutableJson("npc-definitions", content); }
        Path object = root.resolve("state/objects/npc-definitions/" + hash + ".json");
        Files.setLastModifiedTime(object, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        return object;
    }

    @Test void startupSweepsUnfinishedImportPreviewsAndUnnamedObjects() throws Exception {
        Path object = orphan("{\"old\":1}");
        Files.createDirectories(root.resolve("imports/.preview-42"));
        Files.writeString(root.resolve("imports/.preview-42/source.yml"), "npc: {}");
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            assertFalse(Files.exists(root.resolve("imports/.preview-42")));
            assertFalse(Files.exists(object));
        }
    }

    @Test void maintenanceWhileRunningRemovesObjectsNothingNames() throws Exception {
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            String hash = runtime.documents().putImmutableJson("rig-catalog", "{\"later\":1}");
            Path object = root.resolve("state/objects/rig-catalog/" + hash + ".json");
            runtime.maintain();
            assertTrue(Files.exists(object), "a new object may still be waiting for its pointer");
            Files.setLastModifiedTime(object, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
            runtime.maintain();
            assertFalse(Files.exists(object));
        }
    }
}
