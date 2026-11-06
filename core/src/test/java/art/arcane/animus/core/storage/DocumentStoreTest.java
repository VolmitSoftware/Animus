package art.arcane.animus.core.storage;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DocumentStoreTest {
    @TempDir Path root;
    @Test void transactionRollbackAndConflictsSurviveRestart() throws Exception {
        String tx;
        try (DocumentStore store = new DocumentStore(root)) {
            tx = store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", "{\"n\":1}"), new DocumentStore.Change("skins/a.json", "missing", "{}")));
            assertEquals("{\"n\":1}", store.read("npcs/test/a.json"));
            assertThrows(IOException.class, () -> store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", "{}"))));
        }
        try (DocumentStore store = new DocumentStore(root)) {
            store.rollback(tx);
            assertNull(store.read("npcs/test/a.json"));
            assertNull(store.read("skins/a.json"));
        }
    }
    @Test void crashDuringTransactionRollsForwardAndCreatesBackup() throws Exception {
        String id = "12345678-1234-1234-1234-123456789abc";
        JsonObject journal = new JsonObject(); journal.addProperty("id", id);
        JsonArray entries = new JsonArray();
        for (String name : List.of("a", "b")) {
            JsonObject entry = new JsonObject(); entry.addProperty("path", "npcs/test/" + name + ".json");
            entry.add("before", JsonNull.INSTANCE); entry.addProperty("after", "{}"); entries.add(entry);
        }
        journal.add("entries", entries);
        DocumentStore.atomic(root.resolve("state/pending.json"), JsonDocuments.write(journal));
        DocumentStore.atomic(root.resolve("npcs/test/a.json"), "{}");
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals("{}", store.read("npcs/test/b.json"));
            assertNull(store.read("state/pending.json"));
            assertNotNull(store.read("backups/" + id + ".json"));
        }
    }
    @Test void rollbackNeverOverwritesAnExternalEdit() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            String tx = store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", "{}")));
            Files.writeString(root.resolve("npcs/test/a.json"), "{\"external\":true}");
            assertThrows(IOException.class, () -> store.rollback(tx));
            assertEquals("{\"external\":true}", store.read("npcs/test/a.json"));
        }
    }
    @Test void recoveryConflictsLeaveEveryDocumentUntouched() throws Exception {
        String pending = """
            {"id":"12345678-1234-1234-1234-123456789abc","entries":[
              {"path":"npcs/a.json","before":null,"after":"{}"},
              {"path":"npcs/b.json","before":null,"after":"{}"}]}
            """;
        DocumentStore.atomic(root.resolve("state/pending.json"), pending);
        DocumentStore.atomic(root.resolve("npcs/b.json"), "external");
        assertThrows(IOException.class, () -> new DocumentStore(root));
        assertFalse(Files.exists(root.resolve("npcs/a.json")));
        assertEquals("external", Files.readString(root.resolve("npcs/b.json")));
        assertEquals(pending, Files.readString(root.resolve("state/pending.json")));
        Files.delete(root.resolve("npcs/b.json"));
        try (DocumentStore store = new DocumentStore(root)) { assertEquals("{}", store.read("npcs/a.json")); }
    }
    @Test void malformedJournalIsRejectedBeforeAnyWrites() throws Exception {
        DocumentStore.atomic(root.resolve("state/pending.json"), "{\"id\":\"bad\",\"entries\":[{\"path\":\"npcs/a.json\",\"before\":null,\"after\":\"{}\"}]}");
        assertThrows(IOException.class, () -> new DocumentStore(root));
        assertFalse(Files.exists(root.resolve("npcs/a.json")));
    }
    @Test void rejectsConcurrentStoreTraversalSymlinksAndDuplicateWrites() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertThrows(IOException.class, () -> new DocumentStore(root));
            assertThrows(IOException.class, () -> store.read("../outside.json"));
            assertThrows(IOException.class, () -> store.read("npcs/../outside.json"));
            Files.createSymbolicLink(root.resolve("skins"), root.getParent());
            assertThrows(IOException.class, () -> store.read("skins/outside.json"));
            var change = new DocumentStore.Change("npcs/a.json", "missing", "{}");
            assertThrows(IOException.class, () -> store.commit(List.of(change, change)));
            assertThrows(IOException.class, () -> store.commit(List.of(change, new DocumentStore.Change("npcs//a.json", "missing", "other"))));
            assertNull(store.read("npcs/a.json"));
        }
    }
    @Test void rotateBackups_keepsNewestRecentAndPinned_thenCollectsUnreferencedObjects() throws Exception {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");
        try (DocumentStore store = new DocumentStore(root)) {
            // Over 8 MiB of payload moves a transaction's contents into stored objects.
            String bigOld = "{\"pad\":\"" + "a".repeat(9 * 1024 * 1024) + "\"}", bigNew = "{\"pad\":\"" + "b".repeat(9 * 1024 * 1024) + "\"}";
            String oldLarge = store.commit(List.of(new DocumentStore.Change("npcs/test/large.json", "missing", bigOld)));
            String pinned = store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", "{\"n\":1}")));
            String dropped = store.commit(List.of(new DocumentStore.Change("npcs/test/b.json", "missing", "{\"n\":2}")));
            String newest = store.commit(List.of(new DocumentStore.Change("npcs/test/c.json", "missing", "{\"n\":3}")));
            String recent = store.commit(List.of(new DocumentStore.Change("npcs/test/large.json", JsonDocuments.hash(bigOld), bigNew)));
            age(store, oldLarge, now, 60); age(store, pinned, now, 50); age(store, dropped, now, 49); age(store, newest, now, 0); age(store, recent, now, 2);
            Path stray = store.resolve("state/objects/transaction-data/" + JsonDocuments.hash("{\"stray\":true}") + ".json");
            Files.writeString(stray, "{\"stray\":true}");
            Path config = store.resolve("backups/config-abc.json");
            Files.writeString(config, "{}");

            DocumentStore.Rotation rotation = store.rotateBackups(1, Duration.ofDays(30), Set.of(pinned), now);

            assertEquals(2, rotation.receipts(), "the old large and the unpinned old receipts go");
            assertNull(store.read("backups/" + oldLarge + ".json"));
            assertNull(store.read("backups/" + dropped + ".json"));
            for (String kept : List.of(pinned, newest, recent)) assertNotNull(store.read("backups/" + kept + ".json"), kept);
            assertTrue(Files.exists(config), "settings archives are not receipts");
            assertFalse(Files.exists(stray), "an object no receipt names is removed");
            assertEquals(1, rotation.objects());
            assertTrue(Files.exists(store.resolve("state/objects/transaction-data/" + JsonDocuments.hash(bigNew) + ".json")), "objects of kept receipts stay");
            assertTrue(Files.exists(store.resolve("state/objects/transaction-data/" + JsonDocuments.hash(bigOld) + ".json")), "the kept receipt still names it as its before");
            IOException gone = assertThrows(IOException.class, () -> store.rollback(dropped));
            assertTrue(gone.getMessage().contains("Unknown transaction"), gone.getMessage());
            store.rollback(recent);
            assertEquals(bigOld, store.read("npcs/test/large.json"));
        }
    }
    @Test void rotateBackups_refusesNonsenseBounds() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertThrows(IllegalArgumentException.class, () -> store.rotateBackups(0, Duration.ofDays(1), Set.of(), Instant.now()));
            assertThrows(IllegalArgumentException.class, () -> store.rotateBackups(1, Duration.ofDays(-1), Set.of(), Instant.now()));
            assertEquals(new DocumentStore.Rotation(0, 0), store.rotateBackups(1, Duration.ZERO, Set.of(), Instant.now()));
        }
    }
    private static void age(DocumentStore store, String transaction, Instant now, int days) throws IOException {
        Files.setLastModifiedTime(store.resolve("backups/" + transaction + ".json"), FileTime.from(now.minus(Duration.ofDays(days))));
    }
    @Test void strictJsonRejectsAmbiguousDocuments() {
        for (String invalid : List.of("{\"a\":1,\"a\":2}", "{} {}", "{\"n\":NaN}", "{x:1}", "{\"x\":01}", "{\"a\":'b'}", "{\"a\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}"))
            assertThrows(IOException.class, () -> JsonDocuments.object(invalid), invalid);
    }
}
