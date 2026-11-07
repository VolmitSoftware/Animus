package art.arcane.animus.core.migration;

import art.arcane.animus.api.*;
import art.arcane.animus.core.extension.ModuleHooks;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Module converters produce the same reviewed preview bundle the built-in import commands apply. */
class ModuleImportsTest {
    private static final ModuleHooks.Owner OWNER = new ModuleHooks.Owner() {
        public String id() { return "legacy"; }
        public boolean available() { return true; }
        public String reason() { return "available"; }
        public long deadlineNanos() { return Long.MAX_VALUE; }
        public void overran(String hook) {}
    };
    private static final String NPC = "{\"type\":\"minecraft:villager\",\"name\":\"%s\",\"group\":\"legacy\",\"spawned\":true,"
            + "\"position\":{\"world\":\"world\",\"x\":%d,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}%s}";

    private static ModuleHooks.Converter converter(String key, boolean recognizes, Function<ImportConverters.Source, ImportConverters.Conversion> convert) {
        String[] parts = key.split(":");
        return new ModuleHooks.Converter(new ImportConverters.Descriptor(new Capabilities.Key(parts[0], parts[1]), "Legacy", "LegacyNpcs", Set.of(Platform.PAPER)),
                new Version(1, 2, 0), OWNER,
                (ImportConverters.Source source) -> CompletableFuture.completedFuture(Outcome.ok(new ImportConverters.Detection(recognizes, recognizes ? "legacy format" : "not legacy"))),
                (ImportConverters.Source source) -> CompletableFuture.completedFuture(Outcome.ok(convert.apply(source))));
    }
    private static ImportConverters.Conversion twoGuards(ImportConverters.Source source) {
        String combat = ",\"modules\":{\"animus:combat\":{\"enabled\":true,\"reviewAccepted\":true}}";
        return new ImportConverters.Conversion(List.of(
                new ImportConverters.Record(4, NPC.formatted("Gate guard", 1, combat), List.of("hat dropped")),
                new ImportConverters.Record(9, NPC.formatted("Smith", 5, ""), List.of())), List.of("one global note"));
    }
    private static ModuleHooks hooks(ModuleHooks.Converter... converters) {
        ModuleHooks hooks = new ModuleHooks();
        hooks.commit(OWNER, new ModuleHooks.Staged(Optional.empty(), Optional.empty(), List.of(converters)));
        return hooks;
    }
    private static final ImportConverters.Source SOURCE = new ImportConverters.Source("legacy.txt", "legacy npcs v2");

    @Test void previewWritesADormantReviewedBundleThatImportApplyInstalls(@TempDir Path root) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            CitizensImporter.Preview preview = ModuleImports.preview(store, SOURCE, converter("legacy:npcs", true, ModuleImportsTest::twoGuards));
            assertEquals(2, preview.records());
            assertEquals(2, preview.valid());
            JsonObject summary = preview.summary();
            assertEquals("legacy:npcs", summary.get("converter").getAsString());
            assertEquals("1.2.0", summary.get("converterModuleVersion").getAsString());
            assertEquals(List.of("one global note", "legacy-npcs-4: hat dropped"), summary.getAsJsonArray("migrationWarnings").asList().stream().map((com.google.gson.JsonElement e) -> e.getAsString()).toList());
            NpcDefinition guard = NpcDefinition.parse(JsonDocuments.read(preview.directory().resolve("npcs/legacy/legacy-npcs-4.json")));
            assertEquals("legacy-npcs-4", guard.id());
            JsonObject data = guard.json();
            assertFalse(data.get("spawned").getAsBoolean());
            assertTrue(data.getAsJsonObject("migration").get("originalSpawned").getAsBoolean());
            assertFalse(data.getAsJsonObject("modules").getAsJsonObject("animus:combat").get("enabled").getAsBoolean());
            assertEquals(summary.getAsJsonObject("idMap").get("4").getAsString(), guard.uuid().toString());

            assertEquals(preview.id(), ModuleImports.preview(store, SOURCE, converter("legacy:npcs", true, ModuleImportsTest::twoGuards)).id(), "Same source and converter reuse the preview");
            assertNotEquals("already-applied", new CitizensImporter().apply(store, preview.id()));
            assertTrue(Files.isRegularFile(root.resolve("npcs/legacy/legacy-npcs-9.json")));
            assertEquals("already-applied", new CitizensImporter().apply(store, preview.id()));
        }
    }

    @Test void anNpcForTheRootFolderImportsIntoNpcsItself(@TempDir Path root) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            CitizensImporter.Preview preview = ModuleImports.preview(store, SOURCE, converter("legacy:npcs", true, (ImportConverters.Source source) ->
                    new ImportConverters.Conversion(List.of(new ImportConverters.Record(3, NPC.formatted("Root", 1, "").replace("\"group\":\"legacy\"", "\"group\":\"\""), List.of())), List.of())));
            assertEquals(1, preview.valid(), preview.summary().toString());
            assertTrue(Files.isRegularFile(preview.directory().resolve("npcs/legacy-npcs-3.json")));
            new CitizensImporter().apply(store, preview.id());
            NpcDefinition installed = NpcDefinition.parse(JsonDocuments.read(root.resolve("npcs/legacy-npcs-3.json")));
            assertEquals("", installed.group());
        }
    }

    @Test void stagedApplicationsDuplicateModuleImportsLikeCitizensImports(@TempDir Path root) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            CitizensImporter.Preview preview = ModuleImports.preview(store, SOURCE, converter("legacy:npcs", true, ModuleImportsTest::twoGuards));
            new CitizensImporter().apply(store, preview.id());
            assertThrows(IOException.class, () -> ImportApplications.stage(store, preview.id(), "reject", null));
            ImportApplications.Preview copy = ImportApplications.stage(store, preview.id(), "duplicate", "copy");
            assertEquals(2, copy.records());
            ImportApplications.apply(store, preview.id(), copy.applicationId());
            assertTrue(Files.isRegularFile(root.resolve("npcs/copy-copy/legacy-npcs-4-copy.json")));
        }
    }

    @Test void invalidRecordsAreReportedAndBlockApply(@TempDir Path root) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            CitizensImporter.Preview preview = ModuleImports.preview(store, SOURCE, converter("legacy:npcs", true, (ImportConverters.Source source) ->
                    new ImportConverters.Conversion(List.of(new ImportConverters.Record(1, "{\"name\":\"No body\"}", List.of()),
                            new ImportConverters.Record(2, "not json", List.of())), List.of())));
            assertEquals(2, preview.records());
            assertEquals(0, preview.valid());
            assertEquals(2, preview.summary().getAsJsonArray("issues").size());
            assertThrows(IOException.class, () -> new CitizensImporter().apply(store, preview.id()));
        }
    }

    @Test void duplicateSourceIdsAreRejected(@TempDir Path root) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            ModuleHooks.Converter duplicate = converter("legacy:npcs", true, (ImportConverters.Source source) -> new ImportConverters.Conversion(List.of(
                    new ImportConverters.Record(1, NPC.formatted("A", 1, ""), List.of()), new ImportConverters.Record(1, NPC.formatted("B", 2, ""), List.of())), List.of()));
            assertThrows(IOException.class, () -> ModuleImports.preview(store, SOURCE, duplicate));
            try (var entries = Files.list(root.resolve("imports"))) { assertEquals(0, entries.count(), "A failed preview leaves nothing staged"); }
        }
    }

    @Test void selectionUsesTheNamedOrTheSingleRecognizingConverter() throws Exception {
        ModuleHooks.Converter yes = converter("legacy:npcs", true, ModuleImportsTest::twoGuards), no = converter("legacy:other", false, ModuleImportsTest::twoGuards);
        assertSame(yes, ModuleImports.select(hooks(yes, no), SOURCE, Optional.empty()));
        assertSame(no, ModuleImports.select(hooks(yes, no), SOURCE, Optional.of("legacy:other")));
        IOException none = assertThrows(IOException.class, () -> ModuleImports.select(hooks(no), SOURCE, Optional.empty()));
        assertTrue(none.getMessage().contains("not legacy"));
        assertThrows(IOException.class, () -> ModuleImports.select(hooks(yes, converter("legacy:again", true, ModuleImportsTest::twoGuards)), SOURCE, Optional.empty()));
        assertThrows(IOException.class, () -> ModuleImports.select(hooks(yes), SOURCE, Optional.of("legacy:missing")));
    }
}
