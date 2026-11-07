package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.BehaviorNodeType;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BehaviorSchemaGeneratorTest {
    private static Capabilities.Availability module(String name, Capabilities.Kind kind, String schema) {
        Capabilities.Descriptor descriptor = new Capabilities.Descriptor(new Capabilities.Key("mod", name), kind, "A module " + name, schema, Set.of(Platform.values()), false);
        return new Capabilities.Availability(descriptor, true, "available");
    }
    private static final String ARGUMENTS = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"amount\"],\"properties\":{\"amount\":{\"type\":\"integer\",\"minimum\":1},\"mode\":{\"enum\":[\"a\",\"b\"]}}}";

    private static List<Capabilities.Availability> withModules() {
        try (OfflineCapabilities offline = new OfflineCapabilities()) {
            List<Capabilities.Availability> all = new ArrayList<>(offline.capabilities());
            all.add(module("record", Capabilities.Kind.ACTION, ARGUMENTS));
            all.add(module("allowed", Capabilities.Kind.CONDITION, "{\"type\":\"object\"}"));
            all.add(module("event", Capabilities.Kind.TRIGGER, "{\"type\":\"object\"}"));
            return all;
        }
    }
    private static Set<String> names(JsonArray values) {
        Set<String> result = new HashSet<>();
        for (JsonElement value : values) result.add(value.getAsString());
        return result;
    }
    private static String action(String capability, String arguments) {
        return "{\"schemaVersion\":1,\"revision\":1,\"id\":\"sample:test\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"" + capability + "\",\"arguments\":" + arguments + "}}}";
    }

    @Test void moduleCapabilityArgumentsAreEnforcedBySchema() throws Exception {
        JsonSchemaCheck schema = new JsonSchemaCheck(BehaviorSchemaGenerator.behaviorSchema(withModules()));
        assertEquals(List.of(), schema.validate(JsonDocuments.object(action("mod:record", "{\"amount\":2,\"mode\":\"a\"}"))));
        assertFalse(schema.validate(JsonDocuments.object(action("mod:record", "{\"amount\":0}"))).isEmpty());
        assertFalse(schema.validate(JsonDocuments.object(action("mod:record", "{\"amount\":1,\"mode\":\"c\"}"))).isEmpty());
    }

    @Test void schemaAcceptsTheLanguageAdditionsThatCompile() throws Exception {
        JsonSchemaCheck schema = new JsonSchemaCheck(BehaviorSchemaGenerator.behaviorSchema(withModules()));
        String vars = "\"variables\":{\"items\":{\"type\":\"list\",\"initial\":[1,2]},\"stats\":{\"type\":\"map\",\"initial\":{\"hp\":1}},\"item\":{\"type\":\"any\",\"initial\":0},\"i\":{\"type\":\"integer\",\"initial\":0},\"amount\":{\"type\":\"integer\",\"initial\":1}},";
        String main = "{\"type\":\"sequence\",\"steps\":["
                + "{\"type\":\"condition\",\"expression\":\"i < 3\",\"then\":{\"type\":\"set\",\"variable\":\"i\",\"value\":{\"expression\":\"i + 1\"}}},"
                + "{\"type\":\"foreach\",\"list\":{\"variable\":\"items\"},\"as\":\"item\",\"index\":\"i\",\"maximum\":10,\"body\":{\"type\":\"continue\"}},"
                + "{\"type\":\"while\",\"expression\":\"i > 0\",\"body\":{\"type\":\"break\"}},"
                + "{\"type\":\"repeat\",\"count\":{\"variable\":\"amount\"},\"index\":\"i\",\"body\":{\"type\":\"sequence\",\"steps\":[]}},"
                + "{\"type\":\"action\",\"capability\":\"mod:record\",\"arguments\":{\"amount\":null},\"expressions\":{\"/amount\":\"amount * 2\"}},"
                + "{\"type\":\"call\",\"routine\":\"other\",\"arguments\":{\"amount\":3},\"result\":\"item\"}]}";
        String source = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"sample:test\",\"entry\":\"main\"," + vars + "\"routines\":{\"main\":" + main + ",\"other\":{\"type\":\"return\",\"value\":{\"template\":\"x{{ amount }}\"}}}}";
        assertTrue(art.arcane.animus.core.behavior.BehaviorCompiler.compile(source.replace("\"revision\":1,", "")).program().isPresent());
        assertEquals(List.of(), schema.validate(JsonDocuments.object(source)));
        String both = source.replace("{\"type\":\"condition\",\"expression\"", "{\"type\":\"condition\",\"capability\":\"mod:allowed\",\"expression\"");
        assertFalse(schema.validate(JsonDocuments.object(both)).isEmpty());
        String neither = source.replace("\"expression\":\"i < 3\",", "");
        assertFalse(schema.validate(JsonDocuments.object(neither)).isEmpty());
    }

    @Test void completionsListEveryCapabilityArgumentEventNodeAndScope() {
        JsonObject completions = BehaviorSchemaGenerator.completions(withModules());
        Set<String> capabilities = new HashSet<>();
        JsonObject record = null;
        for (JsonElement entry : completions.getAsJsonArray("capabilities")) {
            String id = entry.getAsJsonObject().get("id").getAsString();
            capabilities.add(id);
            if (id.equals("mod:record")) record = entry.getAsJsonObject();
        }
        assertTrue(capabilities.containsAll(List.of("animus:say", "animus:flag-state", "mod:record", "mod:allowed", "mod:event")), capabilities.toString());
        assertNotNull(record);
        assertEquals("action", record.get("kind").getAsString());
        assertEquals("A module record", record.get("description").getAsString());
        JsonObject arguments = record.getAsJsonObject("arguments");
        assertEquals("integer", arguments.getAsJsonObject("amount").get("type").getAsString());
        assertTrue(arguments.getAsJsonObject("amount").get("required").getAsBoolean());
        assertEquals(1, arguments.getAsJsonObject("amount").get("minimum").getAsInt());
        assertFalse(arguments.getAsJsonObject("mode").get("required").getAsBoolean());
        assertEquals(Set.of("a", "b"), names(arguments.getAsJsonObject("mode").getAsJsonArray("enum")));

        JsonObject language = completions.getAsJsonObject("language");
        for (BehaviorNodeType node : BehaviorNodeType.values()) assertTrue(language.getAsJsonObject("nodes").has(node.jsonName()), node.jsonName());
        for (TriggerKind kind : TriggerKind.values()) assertTrue(completions.getAsJsonObject("triggers").has(kind.jsonName()), kind.jsonName());
        assertEquals(Set.of("execution", "npc", "player", "relationship", "global"), names(language.getAsJsonArray("variableScopes")));
        assertEquals(Set.of("transient", "persistent"), names(language.getAsJsonArray("variableLifetimes")));
        assertEquals(Set.of("boolean", "integer", "decimal", "string", "list", "map", "any"), names(language.getAsJsonArray("variableTypes")));
        assertEquals(Set.copyOf(art.arcane.animus.core.behavior.trigger.TriggerCompiler.ACTIONS), names(completions.getAsJsonObject("triggers").getAsJsonObject("interaction").getAsJsonObject("fields").getAsJsonObject("action").getAsJsonArray("enum")));
    }

    @Test void schemaCarriesEveryLanguageElement() {
        String behavior = BehaviorSchemaGenerator.behaviorSchema(withModules()).toString();
        for (BehaviorNodeType node : BehaviorNodeType.values()) assertTrue(behavior.contains("\"const\":\"" + node.jsonName() + "\""), node.jsonName());
        for (TriggerKind kind : TriggerKind.values()) assertTrue(behavior.contains("\"const\":\"" + kind.jsonName() + "\""), kind.jsonName());
        for (BehaviorProgram.Scope scope : BehaviorProgram.Scope.values()) assertTrue(behavior.contains("\"" + scope.name().toLowerCase(Locale.ROOT) + "\""), scope.name());
        assertTrue(behavior.contains("animus:say"));
        assertTrue(behavior.contains("mod:record"));
    }

    @Test void generatedDocumentsAreStrictJsonWithStablePaths() throws Exception {
        Map<String, String> documents = BehaviorSchemaGenerator.generate(withModules());
        assertEquals(Set.of("schemas/behavior.schema.json", "schemas/npc.schema.json", "schemas/route.schema.json", "schemas/pack.schema.json", "schemas/completions.json"), documents.keySet());
        for (String text : documents.values()) assertNotNull(JsonDocuments.object(text));
        assertEquals(documents, BehaviorSchemaGenerator.generate(withModules()), "same registry, same bytes");
        assertEquals("https://json-schema.org/draft/2020-12/schema", JsonDocuments.object(documents.get("schemas/behavior.schema.json")).get("$schema").getAsString());
    }

    @Test void oversizedOutputIsRefusedRatherThanWritten() {
        List<Capabilities.Availability> huge = new ArrayList<>();
        String description = "{\"type\":\"object\",\"description\":\"" + "x".repeat(60_000) + "\"}";
        for (int index = 0; index < 200; index++) huge.add(module("capability-" + index, Capabilities.Kind.ACTION, description));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> BehaviorSchemaGenerator.generate(huge));
        assertTrue(refused.getMessage().contains("exceeds"), refused.getMessage());
    }

    @Test void documentationNamesEveryBuiltInCapabilityEventAndNode() throws Exception {
        Path page = RepositoryFiles.wikiPage("07-behaviors.md");
        assumeTrue(Files.isRegularFile(page), "docs checkout not beside the repository");
        String documentation = Files.readString(page);
        try (OfflineCapabilities offline = new OfflineCapabilities()) {
            for (Capabilities.Availability capability : offline.capabilities())
                assertTrue(documentation.contains("`" + capability.descriptor().key() + "`"), capability.descriptor().key() + " is undocumented");
        }
        for (TriggerKind kind : TriggerKind.values()) assertTrue(documentation.contains("| `" + kind.jsonName() + "` |"), kind.jsonName() + " is undocumented");
        for (String action : art.arcane.animus.core.behavior.trigger.TriggerCompiler.ACTIONS) assertTrue(documentation.contains("| `" + action + "` |"), "interaction action " + action + " is undocumented");
        for (BehaviorNodeType node : BehaviorNodeType.values()) assertTrue(documentation.contains("| `" + node.jsonName() + "` |"), node.jsonName() + " is undocumented");
    }
}
