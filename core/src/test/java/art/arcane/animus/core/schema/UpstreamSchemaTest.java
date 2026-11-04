package art.arcane.animus.core.schema;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Unmodified, pinned upstream fixtures. Wrap each value in a property to meet the module object-root contract. */
class UpstreamSchemaTest {
    @Test void supportedProfileMatchesPinnedUpstreamExamples() throws Exception {
        Path fixtures = Path.of("src/test/resources/capability-schema/upstream");
        JsonObject manifest = JsonParser.parseString(Files.readString(fixtures.resolve("manifest.json"))).getAsJsonObject();
        List<JsonObject> excluded = new ArrayList<>();
        int groups = 0, checked = 0;
        for (JsonElement entry : manifest.getAsJsonArray("files")) {
            JsonObject file = entry.getAsJsonObject();
            Path path = fixtures.resolve(file.get("local").getAsString());
            assertEquals(file.get("sha256").getAsString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            if (!path.toString().endsWith(".json")) continue;
            for (JsonElement rawCase : JsonParser.parseString(Files.readString(path)).getAsJsonArray()) {
                JsonObject testCase = rawCase.getAsJsonObject();
                JsonObject wrapper = new JsonObject(), properties = new JsonObject();
                JsonElement subschema = testCase.get("schema").deepCopy();
                // Lifting the dialect declaration preserves its resource-root meaning when wrapping values.
                if (subschema.isJsonObject() && subschema.getAsJsonObject().has("$schema"))
                    wrapper.add("$schema", subschema.getAsJsonObject().remove("$schema"));
                properties.add("value", subschema); wrapper.add("properties", properties);
                CapabilitySchema schema;
                try { schema = CapabilitySchema.compile(wrapper.toString()); }
                catch (CapabilitySchema.SchemaException unsupported) {
                    assertEquals("schema-keyword-unavailable", unsupported.diagnostics().getFirst().code(), () -> path + ": " + testCase.get("description"));
                    JsonObject exclusion = new JsonObject();
                    exclusion.addProperty("file", path.getFileName().toString());
                    exclusion.add("description", testCase.get("description"));
                    exclusion.addProperty("field", unsupported.diagnostics().getFirst().field());
                    exclusion.addProperty("tests", testCase.getAsJsonArray("tests").size());
                    excluded.add(exclusion); continue;
                }
                groups++;
                for (JsonElement rawTest : testCase.getAsJsonArray("tests")) {
                    JsonObject test = rawTest.getAsJsonObject(), instance = new JsonObject();
                    instance.add("value", test.get("data"));
                    var errors = schema.validate(instance.toString());
                    assertEquals(test.get("valid").getAsBoolean(), errors.isEmpty(), () -> path + ": " + testCase.get("description") + ": " + test.get("description") + ": " + errors);
                    checked++;
                }
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("commit", manifest.get("commit").getAsString());
        report.addProperty("groups", groups); report.addProperty("examples", checked);
        JsonArray exclusions = new JsonArray(); excluded.forEach(exclusions::add); report.add("unsupportedGroups", exclusions);
        Path output = Path.of("build/upstream-report.json"); Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardCharsets.UTF_8);
        JsonElement expected = JsonParser.parseString(Files.readString(Path.of("src/test/resources/capability-schema/upstream-profile.json")));
        assertEquals(expected, report, "Pinned profile coverage or explicit exclusions changed; review rather than silently skip");
    }
}
