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

    @Test void aLookedForRadiusPublishesTheServerCapAndOtherRadiiKeepTheirOwn() {
        JsonObject triggers = BehaviorSchemaGenerator.completions(withModules()).getAsJsonObject("triggers");
        JsonObject definitions = BehaviorSchemaGenerator.behaviorSchema(withModules()).getAsJsonObject("$defs");
        for (String sensed : List.of("entity_proximity", "item_dropped", "leash_pass", "npc_proximity")) {
            JsonObject radius = triggers.getAsJsonObject(sensed).getAsJsonObject("fields").getAsJsonObject("radius");
            assertEquals(32, radius.get("maximum").getAsInt(), sensed);
            assertTrue(radius.get("description").getAsString().contains("behaviors.triggers.maximumEntityRadius (16 by default)"), radius.toString());
            assertEquals(32, definitions.getAsJsonObject("trigger-" + sensed).getAsJsonObject("properties").getAsJsonObject("radius").get("maximum").getAsInt(), sensed);
        }
        for (String own : List.of("proximity", "npc_speech")) {
            JsonObject radius = triggers.getAsJsonObject(own).getAsJsonObject("fields").getAsJsonObject("radius");
            assertEquals(64, radius.get("maximum").getAsInt(), own);
            assertFalse(radius.get("description").getAsString().contains("maximumEntityRadius"), own);
        }
    }

    @Test void everyEventPublishesTheContextARunReadsWithItsTypes() {
        JsonObject triggers = BehaviorSchemaGenerator.completions(withModules()).getAsJsonObject("triggers");
        for (TriggerKind kind : TriggerKind.values()) {
            JsonObject trigger = triggers.getAsJsonObject(kind.jsonName());
            List<String> keys = new ArrayList<>();
            for (JsonElement key : trigger.getAsJsonArray("context")) keys.add(key.getAsString());
            assertEquals(List.copyOf(kind.context().keySet()), keys, kind.jsonName());
            assertEquals(kind.context().keySet(), trigger.getAsJsonObject("contextTypes").keySet(), kind.jsonName());
            for (Map.Entry<String, String> type : kind.context().entrySet())
                assertEquals(type.getValue(), trigger.getAsJsonObject("contextTypes").get(type.getKey()).getAsString(), kind.jsonName() + " " + type.getKey());
        }
        assertEquals(List.of("action", "sneaking", "hand"), List.copyOf(TriggerKind.INTERACTION.context().keySet()));
        assertEquals("boolean", TriggerKind.INTERACTION.context().get("sneaking"));
        assertEquals("list", TriggerKind.COMMAND.context().get("args"));
        assertEquals("number", TriggerKind.ITEM_DROPPED.context().get("amount"));
        assertEquals(Map.of(), TriggerKind.SPAWN.context());
    }

    @Test void everyPublishedFunctionIsDocumentedWithItsArityAndNoDocNamesAMissingOne() {
        JsonObject expressions = BehaviorSchemaGenerator.completions(withModules()).getAsJsonObject("language").getAsJsonObject("expressions");
        Set<String> published = new java.util.TreeSet<>(names(expressions.getAsJsonArray("functions")));
        published.addAll(names(expressions.getAsJsonArray("standardFunctions")));
        JsonObject docs = expressions.getAsJsonObject("functionDocs");
        assertEquals(published, new java.util.TreeSet<>(docs.keySet()));
        assertEquals(published, new java.util.TreeSet<>(ExpressionDocs.FUNCTIONS.keySet()));
        for (Map.Entry<String, JsonElement> entry : docs.entrySet()) {
            String name = entry.getKey();
            JsonObject doc = entry.getValue().getAsJsonObject();
            assertEquals(Set.of("call", "returns", "about"), doc.keySet(), name);
            String call = doc.get("call").getAsString();
            assertTrue(call.startsWith(name + "(") && call.endsWith(")"), call);
            assertTrue(Set.of("boolean", "number", "text", "list", "map", "any").contains(doc.get("returns").getAsString()), name);
            String about = doc.get("about").getAsString();
            assertTrue(!about.isBlank() && about.endsWith(".") && !about.contains("—"), name + ": " + about);
            String inside = call.substring(name.length() + 1, call.length() - 1).strip();
            List<String> parameters = inside.isEmpty() ? List.of() : List.of(inside.split(",\\s*"));
            int required = (int) parameters.stream().filter((String parameter) -> !parameter.startsWith("[")).count();
            assertTrue(parameters.subList(0, required).stream().noneMatch((String parameter) -> parameter.startsWith("[")), "optional parameters come last: " + call);
            for (int count = required; count <= parameters.size(); count++) assertFalse(arityRefused(name, count), call + " refuses " + count + " arguments");
            if (required > 0) assertTrue(arityRefused(name, required - 1), call + " accepts " + (required - 1) + " arguments");
            assertTrue(arityRefused(name, parameters.size() + 1), call + " accepts " + (parameters.size() + 1) + " arguments");
        }
    }
    /** Whether calling the function with that many arguments fails for the count itself rather than their values. */
    private static boolean arityRefused(String name, int count) {
        String source = name + "(" + String.join(", ", java.util.Collections.nCopies(count, "1")) + ")";
        try { art.arcane.animus.core.behavior.BehaviorExpression.compile(source, Set.of(), "$.expression").evaluate(ENVIRONMENT); return false; }
        catch (RuntimeException failed) {
            String message = String.valueOf(failed.getMessage());
            return message.startsWith(name + " takes ") || message.startsWith(name + " expects ");
        }
    }
    private static final art.arcane.animus.core.behavior.BehaviorExpression.Environment ENVIRONMENT = new art.arcane.animus.core.behavior.BehaviorExpression.Environment() {
        @Override public art.arcane.animus.core.behavior.BehaviorProgram.Value variable(String name) { return null; }
        @Override public art.arcane.animus.core.behavior.BehaviorFacts facts() { return art.arcane.animus.core.behavior.BehaviorFacts.NONE; }
        @Override public java.util.Optional<java.util.UUID> player() { return java.util.Optional.empty(); }
        @Override public java.util.UUID npc() { return new java.util.UUID(0, 1); }
        @Override public Map<String, Object> context() { return Map.of(); }
        @Override public java.util.Optional<art.arcane.animus.core.behavior.BehaviorProgram.Value> flag(String scope, String name) { return java.util.Optional.empty(); }
        @Override public long quest(String scope, String name) { return 0; }
        @Override public java.util.random.RandomGenerator random() { return new java.util.Random(7); }
    };

    @Test void everyFactIsTypedAndFormatsNameRealFacts() {
        JsonObject expressions = BehaviorSchemaGenerator.completions(withModules()).getAsJsonObject("language").getAsJsonObject("expressions");
        JsonObject types = expressions.getAsJsonObject("factTypes");
        assertEquals(Set.of("player", "npc", "server"), types.keySet());
        Map<String, List<String>> facts = Map.of("player", art.arcane.animus.core.behavior.BehaviorExpression.PLAYER_FACTS, "npc", art.arcane.animus.core.behavior.BehaviorExpression.NPC_FACTS,
                "server", art.arcane.animus.core.behavior.BehaviorExpression.SERVER_FACTS);
        for (Map.Entry<String, List<String>> root : facts.entrySet()) {
            assertEquals(root.getValue(), List.copyOf(types.getAsJsonObject(root.getKey()).keySet()), root.getKey());
            for (Map.Entry<String, JsonElement> type : types.getAsJsonObject(root.getKey()).entrySet())
                assertTrue(Set.of("boolean", "number", "text", "list", "map").contains(type.getValue().getAsString()), root.getKey() + "." + type.getKey());
        }
        assertEquals("list", types.getAsJsonObject("server").get("players").getAsString());
        assertEquals("list", types.getAsJsonObject("npc").get("behaviors").getAsString());
        JsonObject formats = expressions.getAsJsonObject("factFormats");
        assertEquals("x,y,z,world", formats.get("player.location").getAsString());
        assertEquals("x,y,z,world", formats.get("npc.location").getAsString());
        for (String fact : formats.keySet()) {
            String[] parts = fact.split("\\.", 2);
            assertTrue(facts.containsKey(parts[0]) && facts.get(parts[0]).contains(parts[1]), fact);
        }
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
