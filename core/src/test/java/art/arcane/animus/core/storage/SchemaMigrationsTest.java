package art.arcane.animus.core.storage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationsTest {
    @TempDir Path root;

    /** A synthetic second version, test code only: v2 renames {@code name} to {@code title}. */
    private static final SchemaMigrations.Step RENAME_NAME = (JsonObject document) -> {
        JsonObject next = document.deepCopy();
        if (next.has("name")) next.add("title", next.remove("name"));
        return next;
    };

    private static SchemaMigrations synthetic(SchemaMigrations.Step second) {
        return SchemaMigrations.builder()
                .identity(SchemaMigrations.NPC).step(SchemaMigrations.NPC, second)
                .identity(SchemaMigrations.BEHAVIOR).identity(SchemaMigrations.SHOP).build();
    }

    private static String document(int version, String extra) { return "{\"schemaVersion\":" + version + ",\"id\":\"a\"" + extra + "}\n"; }

    @Test void standard_chainIsVersionOneIdentity_rewritesNothing() throws Exception {
        SchemaMigrations standard = SchemaMigrations.standard();
        assertEquals(1, standard.current(SchemaMigrations.NPC));
        assertEquals(1, standard.current(SchemaMigrations.BEHAVIOR));
        assertEquals(1, standard.current(SchemaMigrations.SHOP));
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", document(1, ",\"name\":\"Bob\""))));
            SchemaMigrations.Report report = standard.apply(store);
            assertNull(report.transaction());
            assertTrue(report.migrated().isEmpty());
            assertTrue(report.problems().isEmpty());
            assertEquals(document(1, ",\"name\":\"Bob\""), store.read("npcs/test/a.json"));
        }
    }

    @Test void apply_syntheticV2_migratesThroughJournalWithRollbackReceipt() throws Exception {
        String original = document(1, ",\"name\":\"Bob\"");
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", original),
                    new DocumentStore.Change("behaviors/greet.json", "missing", document(1, ""))));
            SchemaMigrations.Report report = synthetic(RENAME_NAME).apply(store);
            assertEquals(List.of("npcs/test/a.json"), report.migrated());
            assertNotNull(report.transaction());
            JsonObject migrated = JsonDocuments.object(store.read("npcs/test/a.json"));
            assertEquals(2, migrated.get("schemaVersion").getAsInt());
            assertEquals("Bob", migrated.get("title").getAsString());
            assertFalse(migrated.has("name"));
            assertEquals(document(1, ""), store.read("behaviors/greet.json"));
            assertNotNull(store.read("backups/" + report.transaction() + ".json"));
            store.rollback(report.transaction());
            assertEquals(original, store.read("npcs/test/a.json"));
        }
    }

    @Test void apply_rerun_isIdempotent() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", document(1, ",\"name\":\"Bob\""))));
            SchemaMigrations migrations = synthetic(RENAME_NAME);
            migrations.apply(store);
            String once = store.read("npcs/test/a.json");
            SchemaMigrations.Report again = migrations.apply(store);
            assertNull(again.transaction());
            assertTrue(again.migrated().isEmpty());
            assertEquals(once, store.read("npcs/test/a.json"));
        }
    }

    @Test void apply_futureVersion_isRefusedAndLeftUntouched() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("shops/market.json", "missing", document(7, ""))));
            SchemaMigrations.Report report = SchemaMigrations.standard().apply(store);
            assertTrue(report.migrated().isEmpty());
            assertEquals(1, report.problems().size());
            assertTrue(report.problems().getFirst().contains("shops/market.json"));
            assertTrue(report.problems().getFirst().contains("newer"));
            assertEquals(document(7, ""), store.read("shops/market.json"));
        }
        assertThrows(IOException.class, () -> SchemaMigrations.standard().migrate(SchemaMigrations.SHOP, JsonDocuments.object(document(7, ""))));
    }

    @Test void apply_failingStep_leavesOriginalUntouched() throws Exception {
        String original = document(1, ",\"name\":\"Bob\"");
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", original)));
            SchemaMigrations failing = synthetic((JsonObject document) -> { throw new IOException("synthetic step failed"); });
            SchemaMigrations.Report report = failing.apply(store);
            assertNull(report.transaction());
            assertTrue(report.migrated().isEmpty());
            assertEquals(1, report.problems().size());
            assertTrue(report.problems().getFirst().contains("synthetic step failed"));
            assertEquals(original, store.read("npcs/test/a.json"));
        }
    }

    @Test void apply_packBehaviorsAndShops_areScanned() throws Exception {
        SchemaMigrations migrations = SchemaMigrations.builder()
                .identity(SchemaMigrations.NPC).identity(SchemaMigrations.BEHAVIOR).step(SchemaMigrations.BEHAVIOR, RENAME_NAME)
                .identity(SchemaMigrations.SHOP).step(SchemaMigrations.SHOP, RENAME_NAME).build();
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("packs/demo/behaviors/wave.json", "missing", document(1, "")),
                    new DocumentStore.Change("packs/demo/pack.json", "missing", document(1, "")),
                    new DocumentStore.Change("shops/market.json", "missing", document(1, ""))));
            SchemaMigrations.Report report = migrations.apply(store);
            assertEquals(List.of("packs/demo/behaviors/wave.json", "shops/market.json"), report.migrated());
            assertEquals(document(1, ""), store.read("packs/demo/pack.json"));
        }
    }

    @Test void migrate_missingOrMalformedVersion_isLeftForTheParser() throws Exception {
        JsonObject missing = JsonDocuments.object("{\"id\":\"a\"}");
        assertSame(missing, SchemaMigrations.standard().migrate(SchemaMigrations.NPC, missing));
        JsonObject text = JsonDocuments.object("{\"schemaVersion\":\"one\"}");
        assertSame(text, SchemaMigrations.standard().migrate(SchemaMigrations.NPC, text));
    }

    @Test void builder_requiresIdentityFirst() {
        assertThrows(IllegalStateException.class, () -> SchemaMigrations.builder().step(SchemaMigrations.NPC, RENAME_NAME));
    }
}
