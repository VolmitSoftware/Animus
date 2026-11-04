package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CitizensImporterTest {
    @TempDir Path root;
    static final String NPC = """
              '%s':
                uuid: %s
                name: Guard
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: old, x: 1.25, y: 64, z: -2, yaw: 0, pitch: 0}
                  skintrait: {textureRaw: abc, signature: signed}
                  sentinel: {health: 40, targets: [MONSTERS]}
                  assignment: {assignment: sample_script}
                  mystery: {answer: 42}
            """;
    Path source(String content) throws Exception { return Files.writeString(root.resolve("saves.yml"), content); }
    String npc(String id) { return NPC.formatted(id, "00000000-0000-0000-0000-" + String.format("%012d", Integer.parseInt(id))); }
    @Test void roundTripPreservesUnknownsDeduplicatesSkinsAndRequiresExplicitApply() throws Exception {
        Path source = source("npc:\n" + npc("1") + npc("2"));
        Path target = root.resolve("out");
        CitizensImporter importer = new CitizensImporter();
        var preview = importer.preview(source, target, Map.of("old", "world"));
        assertEquals(2, preview.records()); assertEquals(2, preview.valid()); assertEquals(1, preview.skins());
        assertFalse(Files.exists(target.resolve("npcs")));
        assertEquals(Files.readString(source), Files.readString(preview.directory().resolve("source.yml")));
        assertEquals(preview.id(), importer.preview(source, target, Map.of("old", "world")).id());
        var report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/1.json"))).getAsJsonObject("fields");
        assertEquals("UNSUPPORTED_PRESERVED", report.get("$.traits.mystery.answer").getAsString());
        assertEquals("REQUIRES_INTEGRATION", report.get("$.traits.assignment.assignment").getAsString());
        try (DocumentStore store = new DocumentStore(target)) {
            String tx = importer.apply(store, preview.id());
            assertEquals("already-applied", importer.apply(store, preview.id()));
            NpcDefinition definition = NpcDefinition.parse(store.read("npcs/citizens/citizens-1.json"));
            assertEquals("world", definition.json().getAsJsonObject("position").get("world").getAsString());
            assertFalse(definition.json().get("spawned").getAsBoolean());
            assertFalse(definition.json().getAsJsonObject("modules").getAsJsonObject("animus:combat").get("enabled").getAsBoolean());
            assertEquals(42, definition.json().getAsJsonObject("modules").getAsJsonObject("citizens:preserved").getAsJsonObject("mystery").get("answer").getAsInt());
            store.rollback(tx);
            assertNull(store.read("npcs/citizens/citizens-1.json"));
            assertNull(store.read("imports/" + preview.id() + "/receipt.json"));
            importer.apply(store, preview.id());
        }
    }
    @Test void rejectsDuplicateUnsafeRecursiveAndOversizedYaml() throws Exception {
        var importer = new CitizensImporter();
        for (String text : new String[]{"npc: {}\nnpc: {}", "npc: !!java.net.URL [http://localhost]", "npc: &x {a: *x}", "npc: {a: &x text, b: *x}", "npc: " + "[".repeat(70) + "0" + "]".repeat(70), "npc: {1: {}, '1': {}}"})
            assertThrows(IOException.class, () -> importer.preview(source(text), root.resolve("out"), Map.of()));
        assertThrows(IOException.class, () -> importer.preview(source(" ".repeat(JsonDocuments.MAX_BYTES + 1)), root.resolve("out"), Map.of()));
    }
    @Test void invalidLocationsAreReportedWithoutLosingRecords() throws Exception {
        var preview = new CitizensImporter().preview(source("npc:\n" + npc("1").replace("x: 1.25", "x: broken")), root.resolve("out"), Map.of());
        assertEquals(1, preview.records()); assertEquals(0, preview.valid());
        assertTrue(preview.summary().getAsJsonArray("issues").toString().contains("$.position.x"));
        try (DocumentStore store = new DocumentStore(root.resolve("out"))) {
            assertThrows(IOException.class, () -> new CitizensImporter().apply(store, preview.id()));
        }
    }
    @Test void detectsChangedPreviewsAndIdentityCollisions() throws Exception {
        var importer = new CitizensImporter();
        Path input = source("npc:\n" + npc("1"));
        Path out = root.resolve("out");
        var first = importer.preview(input, out, Map.of());
        var second = importer.preview(input, out, Map.of("old", "another"));
        try (DocumentStore store = new DocumentStore(out)) {
            importer.apply(store, first.id());
            assertThrows(IOException.class, () -> importer.apply(store, second.id()));
        }
        Path staged = second.directory().resolve("npcs/citizens/citizens-1.json");
        Files.writeString(staged, Files.readString(staged).replace("Guard", "Changed"));
        try (DocumentStore store = new DocumentStore(out)) {
            assertTrue(assertThrows(IOException.class, () -> importer.apply(store, second.id())).getMessage().contains("Preview changed"));
        }
    }
    @Test void missingUuidIsStableAcrossImportsAndDifferentWorldOptions() throws Exception {
        var importer = new CitizensImporter();
        Path source = source("npc:\n" + npc("1").replaceAll("(?m)^\\s*uuid:.*\\n", ""));
        var first = importer.preview(source, root.resolve("out"), Map.of());
        var second = importer.preview(source, root.resolve("out"), Map.of("old", "new"));
        assertEquals(first.summary().get("idMap"), second.summary().get("idMap"));
    }
}
