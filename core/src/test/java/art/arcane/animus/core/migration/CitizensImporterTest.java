package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import org.junit.jupiter.api.Test;
import com.google.gson.*;
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
        assertEquals("UNSUPPORTED_PRESERVED", report.get("/traits/mystery/answer").getAsString());
        assertEquals("REQUIRES_INTEGRATION", report.get("/traits/assignment/assignment").getAsString());
        try (DocumentStore store = new DocumentStore(target)) {
            String tx = importer.apply(store, preview.id());
            assertEquals("already-applied", importer.apply(store, preview.id()));
            NpcDefinition definition = NpcDefinition.parse(store.read("npcs/world/citizens-1.json"));
            assertEquals("world", definition.json().getAsJsonObject("position").get("world").getAsString());
            assertTrue(definition.json().get("spawned").getAsBoolean(), "spawned as Citizens had it");
            assertTrue(definition.json().getAsJsonObject("modules").getAsJsonObject("animus:combat").get("enabled").getAsBoolean(), "converted combat is on");
            assertEquals(42, definition.json().getAsJsonObject("modules").getAsJsonObject("citizens:review").getAsJsonObject("mystery").get("answer").getAsInt());
            store.rollback(tx);
            assertNull(store.read("npcs/world/citizens-1.json"));
            assertNull(store.read("imports/" + preview.id() + "/receipt.json"));
            importer.apply(store, preview.id());
        }
    }
    @Test void aPreviewFromAnOlderConverterIsRefusedWithTheCommandThatMakesANewOne() throws Exception {
        Path target = root.resolve("out");
        CitizensImporter importer = new CitizensImporter();
        var preview = importer.preview(source("npc:\n" + npc("1")), target, Map.of());
        OlderPreviews.age(preview.directory());
        try (DocumentStore store = new DocumentStore(target)) {
            CitizensImporter.OutdatedPreview refused = assertThrows(CitizensImporter.OutdatedPreview.class, () -> importer.apply(store, preview.id()));
            assertTrue(refused.getMessage().startsWith(OlderPreviews.refusal(preview.id()) + "old/citizens-1.json is schemaVersion 1, this one writes 2; "), refused.getMessage());
            assertTrue(refused.getMessage().endsWith("; run /animus import preview <file> again and apply the import id it reports"), refused.getMessage());
            assertThrows(CitizensImporter.OutdatedPreview.class, () -> ImportApplications.stage(store, preview.id(), "reject", null), "staging it is refused the same way");
            assertNull(store.read("npcs/old/citizens-1.json"));
        }
    }

    @Test void mobBodiesKeepTheirCitizensSkinForReviewInsteadOfActivatingWithIt() throws Exception {
        String frog = npc("2").replace("type: PLAYER", "type: FROG").replace("textureRaw: abc", "textureRaw: frog");
        CitizensImporter.Preview preview = new CitizensImporter().preview(source("npc:\n" + npc("1") + frog), root.resolve("out"), Map.of("old", "world"));
        assertEquals(2, preview.valid()); assertEquals(1, preview.skins());
        JsonObject definition = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/world/citizens-2.json")));
        assertFalse(definition.has("skin"));
        JsonObject review = definition.getAsJsonObject("modules").getAsJsonObject("citizens:review");
        assertEquals("frog", review.getAsJsonObject("skintrait").get("textureRaw").getAsString());
        JsonObject report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/2.json")));
        assertEquals("UNSUPPORTED_PRESERVED", report.getAsJsonObject("fields").get("/traits/skintrait/textureRaw").getAsString());
        assertEquals("Citizens draws skins only on player NPCs, so this frog never showed it; kept for when the NPC becomes a player",
                report.getAsJsonObject("reasons").get("/traits/skintrait/textureRaw").getAsString());
        assertEquals(1, preview.summary().getAsJsonObject("conversions").get("skinsKept").getAsInt());
        assertTrue(preview.conversions().contains("skins on non-player NPCs kept unused 1"), preview.conversions());
        JsonObject player = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/world/citizens-1.json")));
        assertTrue(player.has("skin"));
        assertFalse(player.getAsJsonObject("modules").getAsJsonObject("citizens:review").has("skintrait"));
    }
    @Test void previewCountsADefinitionTheRuntimeWouldRefuseAsInvalid() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"frog","group":"test","name":"Frog",
                 "type":"minecraft:frog","spawned":false,"skin":"%s","position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}}
                """.formatted("a".repeat(64)));
        assertEquals(java.util.List.of("$.skin: textures require a player body"), CitizensImporter.validate(definition));
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
        Path staged = second.directory().resolve("npcs/another/citizens-1.json");
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

    @Test void remapsKnownNestedLocationsAndSeparatesHistoryWithoutChangingArchives() throws Exception {
        String extra = """
                  waypoints:
                    guided:
                      guides:
                        - location: {world: old, worldid: old-world-uuid, x: 2, y: 65, z: 3}
                    linear:
                      points:
                        '0':
                          location: {world: old, worldid: old-world-uuid, x: 4, y: 65, z: 5}
                  sittrait:
                    sittingAt: {world: old, worldid: old-world-uuid, x: 6, y: 65, z: 7}
                  unknown:
                    location: {world: old, worldid: preserve, x: 1, y: 2, z: 3}
                """;
        String input = "npc:\n" + npc("1").replace("sentinel: {health: 40, targets: [MONSTERS]}", "sentinel: {health: 40, stats_punches: 23, spawnPoint: {world: old, worldid: old-world-uuid, x: 8, y: 65, z: 9}}") + extra.indent(4);
        Path source = source(input);
        var preview = new CitizensImporter().preview(source, root.resolve("out"), Map.of("old", "new"));
        JsonObject candidate = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/new/citizens-1.json")));
        JsonObject reviewed = candidate.getAsJsonObject("modules").getAsJsonObject("citizens:review");
        JsonObject guide = reviewed.getAsJsonObject("waypoints").getAsJsonObject("guided").getAsJsonArray("guides").get(0).getAsJsonObject().getAsJsonObject("location");
        assertEquals("new", guide.get("world").getAsString()); assertFalse(guide.has("worldid"));
        assertEquals("new", reviewed.getAsJsonObject("sentinel").getAsJsonObject("spawnPoint").get("world").getAsString());
        assertEquals("old", reviewed.getAsJsonObject("unknown").getAsJsonObject("location").get("world").getAsString());
        assertEquals("new", candidate.getAsJsonObject("position").get("world").getAsString());
        JsonObject combat = candidate.getAsJsonObject("modules").getAsJsonObject("animus:combat");
        assertEquals(23, combat.getAsJsonObject("historicalStatistics").get("stats_punches").getAsInt());
        assertFalse(combat.getAsJsonObject("preservedSettings").has("stats_punches"));
        assertFalse(reviewed.getAsJsonObject("sentinel").has("stats_punches"));
        assertEquals(5, preview.summary().get("remappedLocations").getAsInt());
        assertEquals(input, Files.readString(preview.directory().resolve("source.yml")));
        JsonObject archived = JsonDocuments.object(Files.readString(preview.directory().resolve("records/1.json")));
        assertEquals(23, archived.getAsJsonObject("traits").getAsJsonObject("sentinel").get("stats_punches").getAsInt());
        assertEquals("old", archived.getAsJsonObject("traits").getAsJsonObject("location").get("world").getAsString());
    }

    @Test void resolvesForwardNumericGuardAndUuidMountReferencesAndReportsMissing() throws Exception {
        String input = "npc:\n" + npc("1").replace("sentinel: {health: 40, targets: [MONSTERS]}", "sentinel: {guardedNPC: 2}")
                + "      mounttrait: {mountedon: '00000000-0000-0000-0000-000000000002'}\n"
                + npc("2").replace("sentinel: {health: 40, targets: [MONSTERS]}", "sentinel: {guardedNPC: 999}");
        var preview = new CitizensImporter().preview(source(input), root.resolve("out"), Map.of());
        assertEquals(2, preview.summary().get("resolvedReferences").getAsInt());
        assertEquals(1, preview.summary().get("unresolvedReferences").getAsInt());
        JsonObject first = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/1.json")));
        for (JsonElement reference : first.getAsJsonArray("references")) {
            assertEquals("resolved", reference.getAsJsonObject().get("status").getAsString());
            assertEquals("citizens-2", reference.getAsJsonObject().get("npcId").getAsString());
        }
        assertTrue(preview.summary().getAsJsonArray("migrationWarnings").toString().contains("/traits/sentinel/guardedNPC"));
        assertEquals(2, preview.valid(), "Unresolved preserved reference needs review, not silent deletion");
    }

    @Test void jsonPointerReportsCannotCollideAndIncludeRootMetadata() throws Exception {
        String input = "last-created-npc-id: 1\nnpc:\n" + npc("1").replace("mystery: {answer: 42}", "mystery: {'a.b': 1, a: {b: 2}, 'a/b': 3, 'a~b': 4}");
        var preview = new CitizensImporter().preview(source(input), root.resolve("out"), Map.of());
        JsonObject fields = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/1.json"))).getAsJsonObject("fields");
        for (String key : new String[]{"/traits/mystery/a.b", "/traits/mystery/a/b", "/traits/mystery/a~1b", "/traits/mystery/a~0b"}) assertTrue(fields.has(key), key);
        assertEquals("UNSUPPORTED_PRESERVED", preview.summary().getAsJsonObject("sourceFields").get("/last-created-npc-id").getAsString());
    }

    @Test void sentinelNoGuardMarkerAndIdenticalWorldMappingAreNotChanged() throws Exception {
        String input = "npc:\n" + npc("1").replace("sentinel: {health: 40, targets: [MONSTERS]}", "sentinel: {guardedNPC: -1}").replace("world: old, x:", "world: old, worldid: original, x:");
        var preview = new CitizensImporter().preview(source(input), root.resolve("out"), Map.of("old", "old"));
        assertEquals(0, preview.summary().get("remappedLocations").getAsInt());
        assertEquals(0, preview.summary().get("unresolvedReferences").getAsInt());
        JsonObject definition = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/old/citizens-1.json")));
        assertEquals("original", definition.getAsJsonObject("modules").getAsJsonObject("citizens:review").getAsJsonObject("location").get("worldid").getAsString());
    }

    @Test void rejectsAnIncompleteRepeatApply() throws Exception {
        var importer = new CitizensImporter();
        var preview = importer.preview(source("npc:\n" + npc("1")), root.resolve("out"), Map.of());
        try (DocumentStore store = new DocumentStore(root.resolve("out"))) {
            importer.apply(store, preview.id());
            Files.delete(root.resolve("out/npcs/old/citizens-1.json"));
            assertTrue(assertThrows(IOException.class, () -> importer.apply(store, preview.id())).getMessage().contains("incomplete"));
        }
    }

    @Test void canReadAndApplyVersionOneBundlesWithoutReusingThemForVersionTwoPreview() throws Exception {
        var importer = new CitizensImporter();
        Path input = source("npc:\n" + npc("1"));
        Path out = root.resolve("out");
        var v2 = importer.preview(input, out, Map.of());
        String v1Id = JsonDocuments.hash("animus-citizens-v1\n" + JsonDocuments.hash(Files.readString(input)) + "\n{}");
        Path v1Directory = out.resolve("imports/" + v1Id);
        Files.move(v2.directory(), v1Directory);
        JsonObject summary = v2.summary(); summary.addProperty("converterVersion", 1); summary.addProperty("importId", v1Id);
        String name = "npcs/old/citizens-1.json";
        JsonObject definition = JsonDocuments.object(Files.readString(v1Directory.resolve(name)));
        definition.getAsJsonObject("migration").addProperty("importId", v1Id);
        JsonObject modules = definition.getAsJsonObject("modules"); modules.add("citizens:preserved", modules.remove("citizens:review"));
        String content = JsonDocuments.write(definition); Files.writeString(v1Directory.resolve(name), content);
        summary.getAsJsonObject("documents").addProperty(name, JsonDocuments.hash(content));
        Files.writeString(v1Directory.resolve("report.json"), JsonDocuments.write(summary));
        assertEquals(1, importer.readPreview(v1Directory, v1Id).records());
        var fresh = importer.preview(input, out, Map.of()); assertNotEquals(v1Id, fresh.id());
        assertEquals(CitizensImporter.CONVERTER_VERSION, fresh.summary().get("converterVersion").getAsInt());
        try (DocumentStore store = new DocumentStore(out)) { importer.apply(store, v1Id); }
    }
}
