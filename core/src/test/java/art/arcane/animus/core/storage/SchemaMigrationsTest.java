package art.arcane.animus.core.storage;

import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test void standard_currentDocuments_areUntouched() throws Exception {
        SchemaMigrations standard = SchemaMigrations.standard();
        assertEquals(NpcDefinition.SCHEMA_VERSION, standard.current(SchemaMigrations.NPC));
        assertEquals(GroupDocument.SCHEMA_VERSION, standard.current(SchemaMigrations.GROUP));
        assertEquals(1, standard.current(SchemaMigrations.BEHAVIOR));
        assertEquals(1, standard.current(SchemaMigrations.SHOP));
        String npc = current(IMPORTED_NPC), group = current(IMPORTED_GROUP);
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/citizens-9.json", "missing", npc),
                    new DocumentStore.Change("groups/guards.json", "missing", group)));
            SchemaMigrations.Report report = standard.apply(store);
            assertTrue(report.transactions().isEmpty());
            assertTrue(report.migrated().isEmpty());
            assertTrue(report.problems().isEmpty());
            assertEquals(npc, store.read("npcs/town/citizens-9.json"));
            assertEquals(group, store.read("groups/guards.json"));
        }
    }

    /** A Citizens import written before version 2: every reviewable module carries its review flag, true or false. */
    private static final String IMPORTED_NPC = """
            {"schemaVersion":1,"revision":3,"uuid":"0a1b2c3d-0000-4000-8000-000000000901","id":"citizens-9","group":"town","name":"Guard",
             "type":"minecraft:player","spawned":true,"migration":{"importId":"%s","citizensId":9},
             "position":{"world":"world","x":0.5,"y":64.0,"z":0.5,"yaw":0.0,"pitch":0.0},
             "modules":{
              "animus:combat":{"enabled":true,"reviewAccepted":true,"targeting":{"fightBack":true}},
              "animus:navigation":{"enabled":true,"reviewAccepted":false,"route":"market-loop"},
              "animus:shop":{"shop":"town:bakery","openOn":"right","reviewAccepted":false,"imported":{"source":"dtlTraders"}},
              "animus:riding":{"enabled":true,"reviewAccepted":true},
              "animus:builder":{"schematic":"hall.schem","reviewAccepted":false}}}
            """.formatted("b9d1c92d1f83ee36c92bc8a4e748154c66ab0090897e96d3bb3f9eea90b0e660");
    private static final String IMPORTED_SCHEDULE = """
            {"schemaVersion":1,"revision":1,"uuid":"0a1b2c3d-0000-4000-8000-000000000902","id":"citizens-10","group":"town","name":"Baker",
             "type":"minecraft:player","spawned":false,"migration":{"importId":"%s","citizensId":10},
             "position":{"world":"world","x":0.5,"y":64.0,"z":0.5,"yaw":0.0,"pitch":0.0},
             "modules":{"animus:schedule":{"enabled":true,"reviewAccepted":false,"locations":[{"time":1000,"x":10,"y":64,"z":4}]}}}
            """.formatted("b9d1c92d1f83ee36c92bc8a4e748154c66ab0090897e96d3bb3f9eea90b0e660");
    private static final String IMPORTED_GROUP = """
            {"schemaVersion":1,"revision":1,"id":"guards","values":{"tags":["guard"],
             "modules":{"animus:combat":{"enabled":true,"reviewAccepted":false,"targeting":{"fightBack":true}},"animus:riding":{"enabled":true,"reviewAccepted":true}}}}
            """;

    /** The document as version 2 writes it: the same fields without any review flag. */
    private static String current(String old) throws IOException {
        JsonObject document = JsonDocuments.object(old);
        document.addProperty("schemaVersion", 2);
        JsonObject holder = document.has("values") ? document.getAsJsonObject("values") : document;
        for (java.util.Map.Entry<String, JsonElement> module : holder.getAsJsonObject("modules").entrySet()) module.getValue().getAsJsonObject().remove("reviewAccepted");
        return JsonDocuments.write(document);
    }

    @Test void standard_importedVersionOneDocuments_loseTheirReviewFlagAndLoad() throws Exception {
        assertFalse(NpcDefinition.validate(JsonDocuments.object(IMPORTED_NPC)).isEmpty(), "version 1 with review flags is refused as written");
        Map<String, String> originals = Map.of("npcs/town/citizens-9.json", IMPORTED_NPC, "npcs/town/citizens-10.json", IMPORTED_SCHEDULE,
                "groups/guards.json", IMPORTED_GROUP);
        try (DocumentStore store = new DocumentStore(root)) {
            List<DocumentStore.Change> changes = new java.util.ArrayList<>();
            originals.forEach((String path, String text) -> changes.add(new DocumentStore.Change(path, "missing", text)));
            store.commit(changes);
            SchemaMigrations.Report report = SchemaMigrations.standard().apply(store);
            assertEquals(List.of(), report.problems());
            assertEquals(Set.of("npcs/town/citizens-9.json", "npcs/town/citizens-10.json", "groups/guards.json"), Set.copyOf(report.migrated()));
            assertEquals(1, report.transactions().size());
            for (Map.Entry<String, String> original : originals.entrySet()) {
                String migrated = store.read(original.getKey());
                assertFalse(migrated.contains("reviewAccepted"), migrated);
                assertEquals(JsonDocuments.object(current(original.getValue())), JsonDocuments.object(migrated), "every other field is kept");
            }
            NpcDefinition guard = NpcDefinition.read("npcs/town/citizens-9.json", store.read("npcs/town/citizens-9.json"));
            assertEquals(2, guard.json().get("schemaVersion").getAsInt());
            NpcDefinition.read("npcs/town/citizens-10.json", store.read("npcs/town/citizens-10.json"));
            GroupDocument.Result group = GroupDocument.parse("guards", store.read("groups/guards.json"));
            assertTrue(group.group().isPresent(), group.problems().toString());
            assertNotNull(store.read("backups/" + report.transactions().getFirst() + ".json"), "a rollback receipt holds the originals");
            store.rollback(report.transactions().getFirst());
            for (Map.Entry<String, String> original : originals.entrySet()) assertEquals(original.getValue(), store.read(original.getKey()));
        }
    }

    /** schema/fixtures/upgrades: the browser editor's copy of this step is held to the same files byte for byte. */
    @Test void standard_sharedUpgradeFixture_writesTheExpectedFilesExactly() throws Exception {
        Path before = art.arcane.animus.core.editor.SharedFixtures.fixture("upgrades/before"), after = art.arcane.animus.core.editor.SharedFixtures.fixture("upgrades/after");
        List<String> paths;
        try (java.util.stream.Stream<Path> walk = Files.walk(before)) {
            paths = walk.filter(Files::isRegularFile).map((Path file) -> before.relativize(file).toString().replace('\\', '/')).sorted().toList();
        }
        assertEquals(List.of("groups/guards.json", "npcs/town/citizens-10.json", "npcs/town/citizens-9.json"), paths);
        try (DocumentStore store = new DocumentStore(root)) {
            List<DocumentStore.Change> changes = new java.util.ArrayList<>();
            for (String path : paths) changes.add(new DocumentStore.Change(path, "missing", Files.readString(before.resolve(path))));
            store.commit(changes);
            SchemaMigrations.Report report = SchemaMigrations.standard().apply(store, List.of(SchemaMigrations.NPC, SchemaMigrations.GROUP));
            assertEquals(Set.copyOf(paths), Set.copyOf(report.migrated()));
            for (String path : paths) assertEquals(Files.readString(after.resolve(path)), store.read(path), path);
        }
    }

    @Test void upgrade_namedDocuments_commitsOnlyTheStaleOnesAgainstTheTextRead() throws Exception {
        String group = current(IMPORTED_GROUP);
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/citizens-9.json", "missing", IMPORTED_NPC), new DocumentStore.Change("groups/guards.json", "missing", group)));
            SchemaMigrations standard = SchemaMigrations.standard();
            assertTrue(standard.stale(SchemaMigrations.NPC, JsonDocuments.object(IMPORTED_NPC)));
            assertFalse(standard.stale(SchemaMigrations.GROUP, JsonDocuments.object(group)));
            SchemaMigrations.Report report = standard.upgrade(store, List.of(new SchemaMigrations.Document(SchemaMigrations.NPC, "npcs/town/citizens-9.json", IMPORTED_NPC),
                    new SchemaMigrations.Document(SchemaMigrations.GROUP, "groups/guards.json", group)));
            assertEquals(List.of("npcs/town/citizens-9.json"), report.migrated());
            assertEquals(1, report.transactions().size());
            assertEquals(JsonDocuments.object(current(IMPORTED_NPC)), JsonDocuments.object(store.read("npcs/town/citizens-9.json")));
            assertNotNull(store.read("backups/" + report.transactions().getFirst() + ".json"));
            assertThrows(IOException.class, () -> standard.upgrade(store, List.of(new SchemaMigrations.Document(SchemaMigrations.NPC, "npcs/town/citizens-9.json", IMPORTED_NPC.replace("Guard", "Watch")))),
                    "a document that changed since it was read is not overwritten");
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
        for (SchemaMigrations.Kind kind : List.of(SchemaMigrations.BEHAVIOR, SchemaMigrations.SHOP, SchemaMigrations.ROUTE,
                SchemaMigrations.RECORDING, SchemaMigrations.INVENTORY, SchemaMigrations.TEXT_ANIMATION, SchemaMigrations.RIG,
                SchemaMigrations.ANIMATION, SchemaMigrations.SERVER_BEHAVIORS, SchemaMigrations.PACK))
            assertEquals(1, standard.current(kind), kind.name());
        assertEquals(2, standard.current(SchemaMigrations.NPC), "version 2 drops the import review flag");
        assertEquals(2, standard.current(SchemaMigrations.GROUP), "version 2 drops the import review flag");
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
