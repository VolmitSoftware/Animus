package art.arcane.animus.core.storage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
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
            assertTrue(report.transactions().isEmpty());
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
            assertEquals(1, report.transactions().size());
            JsonObject migrated = JsonDocuments.object(store.read("npcs/test/a.json"));
            assertEquals(2, migrated.get("schemaVersion").getAsInt());
            assertEquals("Bob", migrated.get("title").getAsString());
            assertFalse(migrated.has("name"));
            assertEquals(document(1, ""), store.read("behaviors/greet.json"));
            assertNotNull(store.read("backups/" + report.transactions().getFirst() + ".json"));
            store.rollback(report.transactions().getFirst());
            assertEquals(original, store.read("npcs/test/a.json"));
        }
    }

    @Test void apply_moreNpcsThanOneTransactionHolds_migratesInBatches() throws Exception {
        int count = 2_001;
        new DocumentStore(root).close();
        Path npcs = Files.createDirectories(root.resolve("npcs/bulk")), pointers = Files.createDirectories(root.resolve("state/npc-definitions/last-valid/npcs/bulk"));
        for (int i = 0; i < count; i++) {
            Files.writeString(npcs.resolve("n" + i + ".json"), document(1, ",\"name\":\"Bob\""));
            Files.writeString(pointers.resolve("n" + i + ".json"), "{}");
        }
        try (DocumentStore store = new DocumentStore(root)) {
            SchemaMigrations.Report report = synthetic(RENAME_NAME).apply(store);
            assertEquals(count, report.migrated().size());
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(2, report.transactions().size(), "2,001 documents take two batches of at most 2,000");
            assertEquals(2, JsonDocuments.object(store.read("npcs/bulk/n" + (count - 1) + ".json")).get("schemaVersion").getAsInt());
            assertNull(store.read("state/npc-definitions/last-valid/npcs/bulk/n0.json"), "a migrated source drops its stale recovery copy");
        }
    }

    @Test void apply_rerun_isIdempotent() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/test/a.json", "missing", document(1, ",\"name\":\"Bob\""))));
            SchemaMigrations migrations = synthetic(RENAME_NAME);
            migrations.apply(store);
            String once = store.read("npcs/test/a.json");
            SchemaMigrations.Report again = migrations.apply(store);
            assertTrue(again.transactions().isEmpty());
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
            assertTrue(report.transactions().isEmpty());
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

    @Test void standard_coversEveryVersionedDocumentKind() {
        SchemaMigrations standard = SchemaMigrations.standard();
        for (SchemaMigrations.Kind kind : List.of(SchemaMigrations.NPC, SchemaMigrations.BEHAVIOR, SchemaMigrations.SHOP, SchemaMigrations.ROUTE,
                SchemaMigrations.RECORDING, SchemaMigrations.GROUP, SchemaMigrations.INVENTORY, SchemaMigrations.TEXT_ANIMATION, SchemaMigrations.RIG,
                SchemaMigrations.ANIMATION, SchemaMigrations.SERVER_BEHAVIORS, SchemaMigrations.PACK))
            assertEquals(1, standard.current(kind), kind.name());
        assertEquals(3, standard.current(SchemaMigrations.HOLOGRAM), "holograms keep Gloss's version 3");
    }

    @Test void apply_everyKindFolderAndFile_isScannedFromItsFirstVersion() throws Exception {
        SchemaMigrations.Builder builder = SchemaMigrations.builder();
        for (SchemaMigrations.Kind kind : List.of(SchemaMigrations.ROUTE, SchemaMigrations.RECORDING, SchemaMigrations.GROUP, SchemaMigrations.INVENTORY,
                SchemaMigrations.TEXT_ANIMATION, SchemaMigrations.RIG, SchemaMigrations.ANIMATION, SchemaMigrations.SERVER_BEHAVIORS, SchemaMigrations.PACK))
            builder.identity(kind).step(kind, RENAME_NAME);
        SchemaMigrations migrations = builder.identity(SchemaMigrations.HOLOGRAM, 3).step(SchemaMigrations.HOLOGRAM, RENAME_NAME).build();
        assertEquals(4, migrations.current(SchemaMigrations.HOLOGRAM));
        List<String> paths = List.of("routes/patrol.json", "recordings/walk.json", "groups/town.json", "gui/inventories/main.json",
                "gui/text-animations/glow.json", "rigs/demo/bot.json", "animations/demo/wave.json", "server-behaviors.json", "packs/demo/pack.json");
        try (DocumentStore store = new DocumentStore(root)) {
            List<DocumentStore.Change> changes = new java.util.ArrayList<>();
            for (String path : paths) changes.add(new DocumentStore.Change(path, "missing", document(1, ",\"name\":\"n\"")));
            changes.add(new DocumentStore.Change("gui/holograms/sign.json", "missing", document(3, ",\"name\":\"n\"")));
            changes.add(new DocumentStore.Change("gui/holograms/old.json", "missing", document(2, ",\"name\":\"n\"")));
            store.commit(changes);
            SchemaMigrations.Report report = migrations.apply(store);
            List<String> expected = new java.util.ArrayList<>(paths);
            expected.add("gui/holograms/sign.json");
            assertEquals(new java.util.HashSet<>(expected), new java.util.HashSet<>(report.migrated()));
            assertEquals(expected.size(), report.migrated().size());
            assertEquals(2, JsonDocuments.object(store.read("routes/patrol.json")).get("schemaVersion").getAsInt());
            assertEquals(4, JsonDocuments.object(store.read("gui/holograms/sign.json")).get("schemaVersion").getAsInt());
            assertEquals(document(2, ",\"name\":\"n\""), store.read("gui/holograms/old.json"), "a version below the chain is left for the parser");
            assertEquals(List.of(), report.problems());
        }
    }

    @Test void builder_requiresIdentityFirst() {
        assertThrows(IllegalStateException.class, () -> SchemaMigrations.builder().step(SchemaMigrations.NPC, RENAME_NAME));
    }
}
