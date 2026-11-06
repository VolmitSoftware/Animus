package art.arcane.animus.core.editor;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.behavior.schema.BehaviorSchemaGenerator;
import art.arcane.animus.core.behavior.schema.OfflineCapabilities;
import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopDocument;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The Java half of the contracts under schema/: generated schemas stay in sync, and the Java parsers agree with every fixture. */
class SharedSchemaContractTest {
    @Test
    void generatedSchemasMatchTheCheckedInCopies() throws Exception {
        Map<String, String> generated;
        try (OfflineCapabilities capabilities = new OfflineCapabilities()) {
            generated = BehaviorSchemaGenerator.generate(capabilities.capabilities());
        }
        Path directory = SharedFixtures.schemaDirectory().resolve("generated");
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> document : generated.entrySet()) {
            Path file = directory.resolve(Path.of(document.getKey()).getFileName().toString());
            if (!Files.isRegularFile(file) || !Files.readString(file).equals(document.getValue())) stale.add(file.getFileName().toString());
        }
        try (var listing = Files.list(directory)) {
            long files = listing.count();
            if (files != generated.size()) stale.add(files + " files present, " + generated.size() + " generated");
        }
        assertTrue(stale.isEmpty(), () -> "schema/generated is stale (" + stale + "); run ./gradlew syncEditorSchemas");
    }

    @Test
    void javaParsersAgreeWithEveryDocumentFixture() throws IOException {
        JsonObject index = SharedFixtures.object(SharedFixtures.fixture("index.json"));
        List<String> disagreements = new ArrayList<>();
        int checked = 0;
        for (JsonElement element : index.getAsJsonArray("documents")) {
            JsonObject entry = element.getAsJsonObject();
            JsonElement expected = entry.getAsJsonObject("expect").get("java");
            if (expected == null || expected.isJsonNull()) continue;
            String kind = entry.get("kind").getAsString();
            String id = entry.get("id").getAsString();
            String path = entry.get("path").getAsString();
            List<String> problems = problems(kind, id, SharedFixtures.text(SharedFixtures.fixture(path)));
            boolean accepted = problems.isEmpty();
            if (accepted != expected.getAsBoolean()) disagreements.add(path + " expected " + (expected.getAsBoolean() ? "accepted" : "refused") + " but " + problems);
            checked++;
        }
        assertTrue(disagreements.isEmpty(), () -> String.join("\n", disagreements));
        assertTrue(checked >= 16, "expected the index to list the Java-checked documents");
    }

    private static List<String> problems(String kind, String id, String source) {
        return switch (kind) {
            case "npc" -> {
                try { NpcDefinition.parse(source); yield List.of(); }
                catch (IOException invalid) { yield List.of(invalid.getMessage()); }
            }
            case "behavior" -> {
                JsonObject object;
                try { object = art.arcane.animus.core.storage.JsonDocuments.object(source); }
                catch (IOException invalid) { yield List.of(invalid.getMessage()); }
                JsonElement revision = object.remove("revision");
                if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber() || revision.getAsLong() < 1)
                    yield List.of("$.revision: expected integer of at least 1");
                // The catalog checks and removes the revision before compiling, as BehaviorCatalog does.
                BehaviorCompiler.Result result = BehaviorCompiler.compile(object.toString());
                yield result.program().isPresent() ? List.of() : result.diagnostics().stream().map(Object::toString).toList();
            }
            case "pack" -> {
                try { PackManifest.parse(source, id); yield List.of(); }
                catch (IllegalArgumentException invalid) { yield List.of(invalid.getMessage()); }
            }
            case "shop" -> ShopDocument.parse(id, source).problems();
            case "inventory" -> GuiDocument.parse(id, source).problems();
            case "hologram" -> art.arcane.animus.core.gui.HologramDocument.parse(id, source).problems();
            case "text-animation" -> art.arcane.animus.core.gui.TextAnimation.parse(id, source).problems();
            case "rig" -> art.arcane.animus.core.rig.RigDocument.parse(id, source).problems();
            case "animation" -> art.arcane.animus.core.rig.AnimationDocument.parse(id, source).problems();
            case "recording" -> {
                try {
                    art.arcane.animus.core.recording.RecordingDocument recording = art.arcane.animus.core.recording.RecordingDocument.parse(source,
                            art.arcane.animus.core.config.RecordingSettings.DEFAULT.maximumFileBytes());
                    yield recording.id().equals(id) ? List.of() : List.of("$.id: the recording id is " + recording.id() + " but the document is " + id);
                } catch (IOException invalid) { yield List.of(invalid.getMessage()); }
            }
            default -> { fail("No Java parser for fixture kind " + kind); yield List.of(); }
        };
    }

    @Test
    void endpointKindsAndScopesMatchTheJavaVocabulary() throws IOException {
        JsonObject endpoints = SharedFixtures.object(SharedFixtures.schemaDirectory().resolve("editor-v1.endpoints.json"));
        JsonObject kinds = endpoints.getAsJsonObject("kinds");
        assertEquals(DocumentKind.values().length, kinds.size());
        for (DocumentKind kind : DocumentKind.values()) {
            JsonObject entry = kinds.getAsJsonObject(kind.wireName());
            assertEquals(kind.folder(), entry.get("folder").getAsString(), kind.wireName());
            assertEquals(kind.applyScope().wireName(), entry.get("applyScope").getAsString(), kind.wireName());
        }
        List<String> defaults = new ArrayList<>();
        endpoints.getAsJsonObject("pairing").getAsJsonArray("defaultScopes").forEach((JsonElement scope) -> defaults.add(scope.getAsString()));
        assertEquals(defaults, EditorScope.DEFAULT_PAIRING.stream().map(EditorScope::wireName).toList());
        for (JsonElement element : endpoints.getAsJsonArray("endpoints")) {
            JsonElement scope = element.getAsJsonObject().get("scope");
            if (scope.isJsonNull() || scope.getAsString().equals("apply:*")) continue;
            assertTrue(EditorScope.parse(scope.getAsString()).isPresent(), scope.getAsString());
        }
        JsonObject schema = SharedFixtures.object(SharedFixtures.schemaDirectory().resolve("editor-v1.schema.json"));
        List<String> schemaScopes = new ArrayList<>();
        schema.getAsJsonObject("$defs").getAsJsonObject("scope").getAsJsonArray("enum").forEach((JsonElement scope) -> schemaScopes.add(scope.getAsString()));
        assertEquals(schemaScopes, java.util.Arrays.stream(EditorScope.values()).map(EditorScope::wireName).toList());
        List<String> schemaKinds = new ArrayList<>();
        schema.getAsJsonObject("$defs").getAsJsonObject("kind").getAsJsonArray("enum").forEach((JsonElement kind) -> schemaKinds.add(kind.getAsString()));
        assertEquals(schemaKinds, java.util.Arrays.stream(DocumentKind.values()).map(DocumentKind::wireName).toList());
    }
}
