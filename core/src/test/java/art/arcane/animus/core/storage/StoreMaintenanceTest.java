package art.arcane.animus.core.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StoreMaintenanceTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final String POINTER = DocumentStore.DEFINITION_POINTERS + "npcs/town/guard.json";

    private Path object(String namespace, String hash) { return root.resolve("state/objects/" + namespace + "/" + hash + ".json"); }

    private void age(Duration age) throws Exception {
        try (Stream<Path> files = Files.walk(root.resolve("state/objects"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) Files.setLastModifiedTime(file, FileTime.from(NOW.minus(age)));
        }
    }

    private static String pointer(String objectHash) {
        return "{\"schemaVersion\":1,\"path\":\"npcs/town/guard.json\",\"sourceHash\":\"" + "0".repeat(64) + "\",\"objectHash\":\"" + objectHash + "\"}";
    }

    @Test void unreferencedGeneratedObjectsArePrunedAndEveryNamedOneStays() throws Exception {
        String current, undoable, orphan, fresh, plan, stale;
        try (DocumentStore store = new DocumentStore(root)) {
            undoable = store.putImmutableJson("npc-definitions", "{\"v\":1}");
            store.commit(List.of(new DocumentStore.Change(POINTER, "missing", pointer(undoable))));
            current = store.putImmutableJson("npc-definitions", "{\"v\":2}");
            store.commit(List.of(new DocumentStore.Change(POINTER, store.hash(POINTER), pointer(current))));
            orphan = store.putImmutableJson("npc-definitions", "{\"v\":3}");
            stale = store.putImmutableJson("rig-catalog", "{\"rig\":1}");
            plan = store.putImmutableJson("import-applications", "{\"candidate\":1}");
            Files.createDirectories(root.resolve("imports/" + "a".repeat(64) + "/applications/" + "b".repeat(64)));
            Files.writeString(root.resolve("imports/" + "a".repeat(64) + "/applications/" + "b".repeat(64) + "/plan.json"), "{\"objectHash\":\"" + plan + "\"}");
            age(Duration.ofDays(2));
            fresh = store.putImmutableJson("behavior-catalog", "{\"fresh\":1}");
            Files.setLastModifiedTime(object("behavior-catalog", fresh), FileTime.from(NOW));

            assertEquals(2, store.collectObjects(Duration.ofDays(1), NOW));
        }
        assertTrue(Files.exists(object("npc-definitions", current)), "the live recovery copy stays");
        assertTrue(Files.exists(object("npc-definitions", undoable)), "a rollback receipt still names the older copy");
        assertTrue(Files.exists(object("import-applications", plan)), "an import plan still names its candidate");
        assertTrue(Files.exists(object("behavior-catalog", fresh)), "a new object may belong to a commit still in progress");
        assertFalse(Files.exists(object("npc-definitions", orphan)));
        assertFalse(Files.exists(object("rig-catalog", stale)));
    }

    @Test void objectsOnlyAnOldReceiptNamedGoWithIt() throws Exception {
        String old;
        try (DocumentStore store = new DocumentStore(root)) {
            old = store.putImmutableJson("npc-definitions", "{\"v\":1}");
            String first = store.commit(List.of(new DocumentStore.Change(POINTER, "missing", pointer(old))));
            String current = store.putImmutableJson("npc-definitions", "{\"v\":2}");
            String second = store.commit(List.of(new DocumentStore.Change(POINTER, store.hash(POINTER), pointer(current))));
            store.commit(List.of(new DocumentStore.Change("npcs/town/other.json", "missing", "{}")));
            for (String receipt : List.of(first, second)) Files.setLastModifiedTime(root.resolve("backups/" + receipt + ".json"), FileTime.from(NOW.minus(Duration.ofDays(30))));
            age(Duration.ofDays(30));
            assertEquals(0, store.collectObjects(Duration.ofDays(1), NOW));
            assertEquals(2, store.rotateBackups(1, Duration.ofDays(7), java.util.Set.of(), NOW).receipts());
            assertEquals(1, store.collectObjects(Duration.ofDays(1), NOW));
            assertTrue(Files.exists(object("npc-definitions", current)));
        }
        assertFalse(Files.exists(object("npc-definitions", old)));
    }

    @Test void stalePreviewFoldersAreSweptAndPublishedImportsStay() throws Exception {
        Files.createDirectories(root.resolve("imports/.preview-123/nested"));
        Files.writeString(root.resolve("imports/.preview-123/nested/source.yml"), "npc: {}");
        Files.createDirectories(root.resolve("imports/" + "c".repeat(64)));
        Files.writeString(root.resolve("imports/" + "c".repeat(64) + "/report.json"), "{}");
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(1, store.removeStalePreviews());
        }
        assertFalse(Files.exists(root.resolve("imports/.preview-123")));
        assertTrue(Files.exists(root.resolve("imports/" + "c".repeat(64) + "/report.json")));
    }
}
