package art.arcane.animus.core.storage;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
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
    @Test void strictJsonRejectsAmbiguousDocuments() {
        for (String invalid : List.of("{\"a\":1,\"a\":2}", "{} {}", "{\"n\":NaN}", "{x:1}", "{\"x\":01}", "{\"a\":'b'}", "{\"a\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}"))
            assertThrows(IOException.class, () -> JsonDocuments.object(invalid), invalid);
    }
}
