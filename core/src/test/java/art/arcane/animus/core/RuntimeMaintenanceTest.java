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
            gone(object);
        }
    }

    @Test void oldReceiptsArePrunedOnTheFileLaneAfterStartReturns() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) { store.read("config.toml"); }
        int aged = 205;
        java.util.List<Path> receipts = new java.util.ArrayList<>();
        Files.createDirectories(root.resolve("backups"));
        for (int index = 0; index < aged; index++) {
            Path receipt = root.resolve("backups/" + java.util.UUID.nameUUIDFromBytes(new byte[]{(byte) index, (byte) (index >> 8)}) + ".json");
            Files.writeString(receipt, "{}");
            Files.setLastModifiedTime(receipt, FileTime.from(Instant.now().minus(Duration.ofDays(30)).plusSeconds(index)));
            receipts.add(receipt);
        }
        try (AnimusRuntime runtime = runtime()) {
            // Maintenance takes the runtime's lock on the file lane, so while this thread holds it nothing can have pruned yet.
            synchronized (runtime) {
                runtime.start();
                assertEquals(aged, receipts.stream().filter(Files::exists).count(), "start itself removes nothing");
            }
            for (Path oldest : receipts.subList(0, aged - 200)) gone(oldest);
            assertEquals(200, receipts.stream().filter(Files::exists).count(), "the newest backupsKept stay");
        }
    }

    @Test void aTooDeepFolderOrALinkUnderNpcsIsSkippedAndTheRestStarts() throws Exception {
        Path guard = Files.createDirectories(root.resolve("npcs/town")).resolve("guard.json");
        Files.writeString(guard, "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"12345678-1234-1234-1234-123456789abc\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"Guard\","
                + "\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}");
        Path deep = Files.createDirectories(root.resolve("npcs/a/b/c/d/e/f/g/h/i/j"));
        Files.writeString(deep.resolve("lost.json"), "{}");
        Files.createSymbolicLink(root.resolve("npcs/link.json"), guard);
        try (AnimusRuntime runtime = runtime()) {
            runtime.start();
            assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
            assertEquals(1, runtime.npcs().all().size());
        }
    }

    private static void gone(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Files.exists(file)) {
            if (System.nanoTime() > deadline) fail(file + " was not removed by maintenance");
            Thread.sleep(10);
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
