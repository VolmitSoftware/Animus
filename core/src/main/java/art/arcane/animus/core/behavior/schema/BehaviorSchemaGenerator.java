package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorNodeType;
import art.arcane.animus.core.behavior.BehaviorExpression;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorValues;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerBinding;
import art.arcane.animus.core.behavior.trigger.TriggerCompiler;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.gui.GuiExpression;
import art.arcane.animus.core.schema.CapabilitySchema;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema 2020-12 documents and editor completion metadata, generated from the live capability registry and the
 * compiler's own language tables. The compiler stays authoritative: some rules, such as routine references and
 * capability semantic checks, need the whole document and are left to it.
 */
public final class BehaviorSchemaGenerator {
    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    public static final int MAXIMUM_DOCUMENT_BYTES = 2 * 1024 * 1024, MAXIMUM_TOTAL_BYTES = 4 * 1024 * 1024;
    public static final String BEHAVIOR = "schemas/behavior.schema.json", NPC = "schemas/npc.schema.json", PACK = "schemas/pack.schema.json", ROUTE = "schemas/route.schema.json", COMPLETIONS = "schemas/completions.json";
    private static final String IDENTIFIER = "^[a-z0-9][a-z0-9_-]{0,63}$";
    private static final String KEY = "^[a-z0-9][a-z0-9_-]{0,63}:[a-z0-9][a-z0-9_-]{0,63}$";
    private static final String BEHAVIOR_ID = "^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$";
    private static final String NONBLANK = "\\S";
    private record Field(JsonObject schema, String completion, boolean required) {}
    private BehaviorSchemaGenerator() {}

    /** Every generated document by its path under the plugin data folder. Refuses output beyond the size bounds. */
    public static Map<String, String> generate(List<Capabilities.Availability> capabilities) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(BEHAVIOR, JsonDocuments.write(behaviorSchema(capabilities)));
        documents.put(NPC, JsonDocuments.write(NpcSchemaGenerator.schema()));
        documents.put(ROUTE, JsonDocuments.write(NpcSchemaGenerator.routeSchema()));
        documents.put(PACK, JsonDocuments.write(packSchema()));
        documents.put(COMPLETIONS, JsonDocuments.write(completions(capabilities)));
        long total = 0;
        for (Map.Entry<String, String> document : documents.entrySet()) {
            int bytes = document.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAXIMUM_DOCUMENT_BYTES) throw new IllegalArgumentException(document.getKey() + " exceeds " + MAXIMUM_DOCUMENT_BYTES + " bytes");
            total += bytes;
        }
        if (total > MAXIMUM_TOTAL_BYTES) throw new IllegalArgumentException("Generated schemas exceed " + MAXIMUM_TOTAL_BYTES + " bytes");
        return documents;
    }

    public static JsonObject behaviorSchema(List<Capabilities.Availability> capabilities) {
        JsonObject root = new JsonObject();
        root.addProperty("$schema", DIALECT);
        root.addProperty("title", "Animus behavior document");
        root.addProperty("description", "A JSON behavior under behaviors/<namespace>/<path>.json or packs/<pack>/behaviors/<namespace>/<path>.json. The plugin's compiler is authoritative.");
        root.addProperty("type", "object");
        root.addProperty("additionalProperties", false);
        root.add("required", strings("schemaVersion", "revision", "id", "entry", "routines"));
        JsonObject properties = new JsonObject();
        properties.add("schemaVersion", described(constant(1), "Always 1."));
        properties.add("revision", described(range("integer", 1, Long.MAX_VALUE), "Raise this whenever the file changes. Changed bytes need a revision above the last accepted one."));
        for (String field : BehaviorCompiler.DOCUMENT_FIELDS) {
            switch (field) {
                case "schemaVersion" -> { }
                case "id" -> {
                    JsonObject id = described(pattern(BEHAVIOR_ID), "namespace:path, matching this file's location.");
                    id.addProperty("maxLength", 256);
                    id.add("not", pattern("^animus:(flags|quests)(/|$)"));
                    properties.add("id", id);
                }
                case "entry" -> properties.add("entry", described(reference("identifier"), "The routine a run starts with unless a trigger names another."));
                case "variables" -> {
                    JsonObject variables = described(type("object"), "Typed variables, at most " + BehaviorCompiler.MAX_VARIABLES + ".");
                    variables.addProperty("maxProperties", BehaviorCompiler.MAX_VARIABLES);
                    variables.add("propertyNames", reference("identifier"));
                    variables.add("additionalProperties", reference("variable"));
                    properties.add("variables", variables);
                }
                case "routines" -> {
                    JsonObject routines = described(type("object"), "Named routines, 1 to " + BehaviorCompiler.MAX_ROUTINES + ".");
                    routines.addProperty("minProperties", 1);
                    routines.addProperty("maxProperties", BehaviorCompiler.MAX_ROUTINES);
                    routines.add("propertyNames", reference("identifier"));
                    routines.add("additionalProperties", reference("node"));
                    properties.add("routines", routines);
                }
                case "server" -> properties.add("server", described(type("boolean"),
                        "true makes a server-wide behavior: it has no NPC, uses server events only, declares only execution, player and global variables, and runs only when listed in server-behaviors.json."));
                case "triggers" -> {
                    JsonObject triggers = described(type("array"), "Automatic starts, at most " + TriggerCompiler.MAX_TRIGGERS + ".");
                    triggers.addProperty("maxItems", TriggerCompiler.MAX_TRIGGERS);
                    triggers.add("items", reference("trigger"));
                    properties.add("triggers", triggers);
                }
                default -> throw new IllegalStateException("No schema for document field " + field);
            }
        }
        root.add("properties", properties);
        JsonArray serverRules = new JsonArray();
        serverRules.add(serverRule());
        root.add("allOf", serverRules);
        JsonObject definitions = new JsonObject();
        definitions.add("identifier", described(pattern(IDENTIFIER), "1-64 lowercase letters, digits, _ or -, starting with a letter or digit."));
        definitions.add("module-capability", moduleCapability());
        definitions.add("actions", capabilityList(capabilities, Capabilities.Kind.ACTION));
        definitions.add("conditions", capabilityList(capabilities, Capabilities.Kind.CONDITION));
        definitions.add("variable", variable());
        definitions.add("bindings", bindings());
        definitions.add("operand", operand());
        definitions.add("value", value());
        definitions.add("node", node());
        for (BehaviorNodeType node : BehaviorNodeType.values()) definitions.add("node-" + node.jsonName(), node(node, capabilities));
        definitions.add("trigger", trigger());
        for (TriggerKind kind : TriggerKind.values()) definitions.add("trigger-" + kind.jsonName(), trigger(kind));
        root.add("$defs", definitions);
        JsonArray examples = new JsonArray();
        JsonObject example = new JsonObject();
        example.addProperty("schemaVersion", 1);
        example.addProperty("revision", 1);
        example.addProperty("id", "town:greeter");
        example.addProperty("entry", "main");
        JsonObject routines = new JsonObject();
        JsonObject say = new JsonObject();
        say.addProperty("type", "action");
        say.addProperty("capability", "animus:say");
        JsonObject text = new JsonObject();
        text.addProperty("text", "Welcome to town.");
        say.add("arguments", text);
        routines.add("main", say);
        example.add("routines", routines);
        JsonArray triggers = new JsonArray();
        JsonObject click = new JsonObject();
        click.addProperty("id", "click");
        click.addProperty("event", "interaction");
        triggers.add(click);
        example.add("triggers", triggers);
        examples.add(example);
        root.add("examples", examples);
        return root;
    }

    /**
     * What {@code "server": true} changes, as the compiler checks it: only server events, a timer of at least
     * {@link TriggerCompiler#MIN_SERVER_INTERVAL_TICKS} ticks, and no npc or relationship variables. Without it, only NPC events.
     */
    private static JsonObject serverRule() {
        JsonObject condition = property("server", constant(true));
        condition.add("required", strings("server"));
        JsonObject server = new JsonObject();
        JsonObject serverProperties = new JsonObject();
        JsonObject scope = described(choices(List.of("execution", "player", "global")), "A server behavior has no NPC: execution, player or global.");
        JsonObject variable = new JsonObject();
        variable.add("properties", property("scope", scope).getAsJsonObject("properties"));
        JsonObject variables = new JsonObject();
        variables.add("additionalProperties", variable);
        serverProperties.add("variables", variables);
        JsonObject serverTrigger = property("event", described(choices(TriggerCompiler.names(true)), "A server behavior uses server events only."));
        JsonArray timer = new JsonArray();
        timer.add(when("event", TriggerKind.TIMER.jsonName(), property("intervalTicks",
                described(range("integer", TriggerCompiler.MIN_SERVER_INTERVAL_TICKS, TriggerCompiler.MAX_INTERVAL_TICKS), "Server behaviors use at least " + TriggerCompiler.MIN_SERVER_INTERVAL_TICKS + " ticks."))));
        serverTrigger.add("allOf", timer);
        JsonObject serverTriggers = new JsonObject();
        serverTriggers.add("items", serverTrigger);
        serverProperties.add("triggers", serverTriggers);
        server.add("properties", serverProperties);
        JsonObject npcTriggers = new JsonObject();
        npcTriggers.add("items", property("event", described(choices(TriggerCompiler.names(false)), "Server events need \"server\": true.")));
        JsonObject npc = property("triggers", npcTriggers);
        JsonObject rule = new JsonObject();
        rule.add("if", condition);
        rule.add("then", server);
        rule.add("else", npc);
        return rule;
    }

    /** Registered capabilities of one kind, each with its description for editors. */
    private static JsonElement capabilityList(List<Capabilities.Availability> capabilities, Capabilities.Kind kind) {
        JsonArray options = new JsonArray();
        for (Capabilities.Availability capability : capabilities) {
            if (capability.descriptor().kind() != kind) continue;
            options.add(described(constant(capability.descriptor().key().toString()), capability.descriptor().description()));
        }
        if (options.isEmpty()) return new com.google.gson.JsonPrimitive(false);
        JsonObject list = new JsonObject();
        list.add("anyOf", options);
        return list;
    }
    private static JsonObject moduleCapability() {
        JsonObject module = described(pattern(KEY), "A capability of a Java module. Its arguments are checked when that module is installed.");
        module.add("not", pattern("^animus:"));
        return module;
    }

    private static JsonObject variable() {
        JsonObject variable = type("object");
        variable.addProperty("additionalProperties", false);
        variable.add("required", strings("type", "initial"));
        JsonObject properties = new JsonObject();
        List<String> types = new ArrayList<>();
        for (BehaviorProgram.Type type : BehaviorProgram.Type.values()) types.add(type.name().toLowerCase(Locale.ROOT));
        properties.add("type", described(choices(types), "The value type. any accepts every value; expressions and templates are converted to the declared type when written."));
        properties.add("initial", described(new JsonObject(), "The value until something writes it, of the declared type: a JSON array for list, an object for map, any JSON but null for any."));
        properties.add("scope", described(choices(scopes()), "Who shares the value: one run (execution, the default), one NPC, one player, one NPC and player pair, or everyone."));
        properties.add("lifetime", described(choices(lifetimes()), "Required for every scope except execution: transient values last until the server stops, persistent values are stored."));
        variable.add("properties", properties);
        JsonArray rules = new JsonArray();
        for (BehaviorProgram.Type type : BehaviorProgram.Type.values()) {
            JsonObject initial = switch (type) {
                case BOOLEAN -> type("boolean");
                case INTEGER -> range("integer", Long.MIN_VALUE, Long.MAX_VALUE);
                case DECIMAL -> type("number");
                case STRING -> { JsonObject text = type("string"); text.addProperty("maxLength", BehaviorCompiler.MAX_STRING_BYTES); yield text; }
                case LIST -> { JsonObject list = type("array"); list.addProperty("maxItems", BehaviorValues.MAX_ITEMS); yield list; }
                case MAP -> { JsonObject map = type("object"); map.addProperty("maxProperties", BehaviorValues.MAX_ITEMS); yield map; }
                case ANY -> { JsonObject any = new JsonObject(); any.add("not", type("null")); yield any; }
            };
            rules.add(when("type", type.name().toLowerCase(Locale.ROOT), property("initial", initial)));
        }
        JsonObject execution = new JsonObject();
        JsonObject scope = new JsonObject();
        scope.add("scope", constant("execution"));
        JsonObject condition = new JsonObject();
        condition.add("properties", scope);
        execution.add("if", condition);
        JsonObject noLifetime = new JsonObject();
        JsonObject lifetimeRequired = new JsonObject();
        lifetimeRequired.add("required", strings("lifetime"));
        noLifetime.add("not", lifetimeRequired);
        execution.add("then", noLifetime);
        execution.add("else", lifetimeRequired.deepCopy());
        rules.add(execution);
        variable.add("allOf", rules);
        return variable;
    }

    private static JsonObject bindings() {
        JsonObject bindings = described(type("object"), "Fills null placeholders in arguments from declared variables, keyed by JSON Pointer.");
        bindings.addProperty("maxProperties", 32);
        JsonObject name = type("string");
        name.addProperty("maxLength", 4096);
        bindings.add("propertyNames", name);
        bindings.add("additionalProperties", variableReference());
        return bindings;
    }
    private static JsonObject variableReference() {
        JsonObject reference = type("object");
        reference.addProperty("additionalProperties", false);
        reference.add("required", strings("variable"));
        reference.add("properties", property("variable", pattern(NONBLANK)).getAsJsonObject("properties"));
        return reference;
    }

    private static JsonObject node() {
        JsonObject node = described(type("object"), "One step of a routine.");
        node.add("required", strings("type"));
        node.add("properties", property("type", choices(BehaviorNodeType.names())).getAsJsonObject("properties"));
        JsonArray rules = new JsonArray();
        for (BehaviorNodeType type : BehaviorNodeType.values()) rules.add(when("type", type.jsonName(), reference("node-" + type.jsonName())));
        node.add("allOf", rules);
        return node;
    }

    private static JsonObject node(BehaviorNodeType type, List<Capabilities.Availability> capabilities) {
        JsonObject node = described(type("object"), type.description());
        node.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        properties.add("type", constant(type.jsonName()));
        List<String> required = new ArrayList<>(List.of("type"));
        for (String name : type.fields()) {
            Field field = nodeField(type, name);
            properties.add(name, field.schema());
            if (field.required()) required.add(name);
        }
        node.add("properties", properties);
        node.add("required", strings(required.toArray(String[]::new)));
        if (type == BehaviorNodeType.ACTION || type == BehaviorNodeType.CONDITION) {
            Capabilities.Kind kind = type == BehaviorNodeType.ACTION ? Capabilities.Kind.ACTION : Capabilities.Kind.CONDITION;
            JsonArray rules = new JsonArray();
            if (type == BehaviorNodeType.CONDITION) {
                JsonArray either = new JsonArray();
                JsonObject expression = new JsonObject();
                expression.add("required", strings("expression"));
                JsonObject capabilityOnly = new JsonObject();
                capabilityOnly.add("not", expression.deepCopy());
                capabilityOnly.add("required", strings("capability"));
                JsonObject expressionOnly = new JsonObject();
                JsonObject anyCapabilityField = new JsonObject();
                JsonArray capabilityFields = new JsonArray();
                for (String field : List.of("capability", "arguments", "bindings", "expressions", "templates")) {
                    JsonObject present = new JsonObject();
                    present.add("required", strings(field));
                    capabilityFields.add(present);
                }
                anyCapabilityField.add("anyOf", capabilityFields);
                expressionOnly.add("not", anyCapabilityField);
                expressionOnly.add("required", strings("expression"));
                either.add(capabilityOnly);
                either.add(expressionOnly);
                JsonObject rule = new JsonObject();
                rule.add("oneOf", either);
                rules.add(rule);
            }
            for (Capabilities.Availability capability : capabilities)
                if (capability.descriptor().kind() == kind) rules.add(argumentRule(capability.descriptor()));
            if (!rules.isEmpty()) node.add("allOf", rules);
        }
        return node;
    }

    private static Field nodeField(BehaviorNodeType type, String name) {
        return switch (name) {
            case "steps" -> new Field(list(reference("node"), 0, BehaviorCompiler.MAX_STEPS, "Steps run in order."), "nodes", true);
            case "branches" -> new Field(list(reference("node"), 1, BehaviorCompiler.MAX_BRANCHES, "Branches run together and join."), "nodes", true);
            case "capability" -> new Field(capabilityField(type), "capability", type == BehaviorNodeType.ACTION);
            case "arguments" -> type == BehaviorNodeType.CALL
                    ? new Field(callArguments(), "arguments", false)
                    : new Field(described(type("object"), "The capability's arguments."), "arguments", false);
            case "bindings" -> new Field(reference("bindings"), "bindings", false);
            case "expressions" -> new Field(pointerMap("Fills null placeholders in arguments with expression results, keyed by JSON Pointer."), "expressions", false);
            case "templates" -> new Field(pointerMap("Fills null placeholders in arguments with template text, keyed by JSON Pointer."), "templates", false);
            case "expression" -> new Field(described(expressionText(), type == BehaviorNodeType.WHILE ? "Checked before every pass; must be true or false." : "Tested instead of a capability; must be true or false."), "expression", type == BehaviorNodeType.WHILE);
            case "then" -> new Field(described(reference("node"), "Runs when the condition is true."), "node", true);
            case "else" -> new Field(described(reference("node"), "Runs when the condition is false."), "node", false);
            case "body" -> new Field(described(reference("node"), "The repeated step."), "node", true);
            case "ticks" -> new Field(described(range("integer", 1, BehaviorCompiler.MAX_WAIT_TICKS), "Behavior ticks, about 50 ms each."), "integer", true);
            case "count" -> {
                JsonArray options = new JsonArray();
                options.add(range("integer", 0, BehaviorCompiler.MAX_REPEAT));
                options.add(reference("operand"));
                JsonObject count = described(new JsonObject(), "How many times the body runs: 0 to " + BehaviorCompiler.MAX_REPEAT + ", or an operand computed when the loop starts.");
                count.add("anyOf", options);
                yield new Field(count, "integer", true);
            }
            case "maximum" -> new Field(described(range("integer", 1, BehaviorCompiler.MAX_LOOP), "The most passes before the run fails; " + BehaviorCompiler.MAX_REPEAT + " by default."), "integer", false);
            case "index" -> new Field(described(reference("identifier"), "An execution variable that receives the 0-based pass number."), "variable", false);
            case "list" -> new Field(described(reference("operand"), "An operand that evaluates to a list or map."), "value", true);
            case "as" -> new Field(described(reference("identifier"), "An execution variable that receives each item."), "variable", true);
            case "key" -> new Field(described(reference("identifier"), "An execution variable that receives each map key."), "variable", false);
            case "result" -> new Field(described(reference("identifier"), "A declared variable that receives the routine's return value."), "variable", false);
            case "routine" -> new Field(described(reference("identifier"), "A routine of this document."), "routine", true);
            case "variable" -> new Field(described(pattern(NONBLANK), "A declared variable."), "variable", true);
            case "value" -> new Field(described(reference("value"), type == BehaviorNodeType.RETURN
                    ? "The value the routine returns to its call's result."
                    : "A literal of the variable's type, or an operand converted to it."), "value", type == BehaviorNodeType.SET);
            default -> throw new IllegalStateException("No schema for node field " + type.jsonName() + "." + name);
        };
    }
    private static JsonObject expressionText() {
        JsonObject text = pattern(NONBLANK);
        text.addProperty("maxLength", BehaviorExpression.MAX_LENGTH);
        return text;
    }
    private static JsonObject pointerMap(String description) {
        JsonObject map = described(type("object"), description);
        map.addProperty("maxProperties", 32);
        JsonObject name = type("string");
        name.addProperty("maxLength", 4096);
        map.add("propertyNames", name);
        map.add("additionalProperties", expressionText());
        return map;
    }
    private static JsonObject callArguments() {
        JsonObject arguments = described(type("object"), "Declared variables assigned before the routine runs, each from a literal or operand.");
        arguments.addProperty("maxProperties", BehaviorCompiler.MAX_VARIABLES);
        arguments.add("propertyNames", reference("identifier"));
        arguments.add("additionalProperties", reference("value"));
        return arguments;
    }
    /** {"variable": name}, {"expression": text} or {"template": text}. */
    private static JsonObject operand() {
        JsonArray options = new JsonArray();
        options.add(variableReference());
        for (String key : List.of("expression", "template")) {
            JsonObject option = type("object");
            option.addProperty("additionalProperties", false);
            option.add("required", strings(key));
            option.add("properties", property(key, expressionText()).getAsJsonObject("properties"));
            options.add(option);
        }
        JsonObject operand = described(new JsonObject(), "A declared variable, an expression, or a template with {{ expression }} holes.");
        operand.add("oneOf", options);
        return operand;
    }
    private static JsonObject value() {
        JsonArray options = new JsonArray();
        options.add(reference("operand"));
        JsonObject literal = new JsonObject();
        literal.add("not", type("null"));
        options.add(literal);
        JsonObject value = described(new JsonObject(), "A literal JSON value, or an operand. An object whose only key is variable, expression or template is an operand.");
        value.add("anyOf", options);
        return value;
    }
    private static JsonObject capabilityField(BehaviorNodeType type) {
        JsonObject field = described(new JsonObject(), type == BehaviorNodeType.ACTION ? "An action capability, namespace:name." : "A condition capability, namespace:name.");
        JsonArray options = new JsonArray();
        options.add(reference(type == BehaviorNodeType.ACTION ? "actions" : "conditions"));
        options.add(reference("module-capability"));
        field.add("anyOf", options);
        return field;
    }

    /** Arguments are checked against the capability's own schema unless bindings fill them at run time. */
    private static JsonObject argumentRule(Capabilities.Descriptor descriptor) {
        JsonObject arguments = argumentSchema(descriptor);
        JsonObject checked = property("arguments", arguments);
        if (!CapabilitySchema.compile(descriptor.configurationSchemaJson()).validate("{}").isEmpty()) checked.add("required", strings("arguments"));
        JsonArray filled = new JsonArray();
        for (String field : List.of("bindings", "expressions", "templates")) {
            JsonObject slots = new JsonObject();
            slots.add("required", strings(field));
            JsonObject nonEmpty = new JsonObject();
            nonEmpty.addProperty("minProperties", 1);
            slots.add("properties", property(field, nonEmpty).getAsJsonObject("properties"));
            filled.add(slots);
        }
        JsonObject bound = new JsonObject();
        bound.add("anyOf", filled);
        JsonObject literal = new JsonObject();
        literal.add("if", bound);
        literal.add("else", checked);
        return when("capability", descriptor.key().toString(), literal);
    }
    private static JsonObject argumentSchema(Capabilities.Descriptor descriptor) {
        try {
            JsonObject schema = JsonDocuments.object(descriptor.configurationSchemaJson());
            schema.remove("$schema");
            if (!schema.has("description")) schema.addProperty("description", descriptor.description());
            return schema;
        } catch (IOException invalid) { throw new IllegalStateException("Registered schema is not JSON: " + descriptor.key(), invalid); }
    }

    private static JsonObject trigger() {
        JsonObject trigger = described(type("object"), "An automatic start for NPCs that list this behavior, or for a server behavior.");
        trigger.add("required", strings("id", "event"));
        JsonObject properties = new JsonObject();
        for (String name : TriggerKind.COMMON_FIELDS) properties.add(name, triggerField(name).schema());
        trigger.add("properties", properties);
        JsonArray rules = new JsonArray();
        for (TriggerKind kind : TriggerKind.values()) rules.add(when("event", kind.jsonName(), reference("trigger-" + kind.jsonName())));
        trigger.add("allOf", rules);
        return trigger;
    }
    private static JsonObject trigger(TriggerKind kind) {
        JsonObject trigger = described(type("object"), kind.description());
        trigger.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        List<String> required = new ArrayList<>();
        for (String name : kind.keys()) {
            Field field = name.equals("event") ? new Field(constant(kind.jsonName()), "event", true) : triggerField(name);
            properties.add(name, field.schema());
            if (field.required()) required.add(name);
        }
        trigger.add("properties", properties);
        trigger.add("required", strings(required.toArray(String[]::new)));
        return trigger;
    }
    private static Field triggerField(String name) {
        return switch (name) {
            case "id" -> new Field(described(reference("identifier"), "Unique within this document."), "identifier", true);
            case "event" -> new Field(described(choices(TriggerKind.names()), "What starts the run."), "event", true);
            case "routine" -> new Field(described(reference("identifier"), "Starts this routine instead of entry."), "routine", false);
            case "cooldownTicks" -> new Field(described(range("integer", 0, TriggerCompiler.MAX_COOLDOWN_TICKS), "Overrides the configured cooldown, per trigger on each NPC."), "integer", false);
            case "action" -> new Field(described(choices(lower(TriggerBinding.InteractionFilter.values())), "Which hit fires it; interact by default."), "string", false);
            case "phase" -> new Field(described(choices(lower(TriggerBinding.Phase.values())), "Entering or leaving the radius."), "string", true);
            case "radius" -> {
                JsonObject radius = type("number");
                radius.addProperty("exclusiveMinimum", 0);
                radius.addProperty("maximum", TriggerCompiler.MAX_RADIUS);
                yield new Field(described(radius, "Blocks, above 0."), "number", true);
            }
            case "waypoint" -> new Field(described(range("integer", -1, TriggerCompiler.MAX_WAYPOINT), "A route point index; -1 is a manually requested destination. Omit for any arrival."), "integer", false);
            case "intervalTicks" -> new Field(described(range("integer", 1, TriggerCompiler.MAX_INTERVAL_TICKS), "Ticks between firings while the NPC is active. Server behaviors use at least " + TriggerCompiler.MIN_SERVER_INTERVAL_TICKS + "."), "integer", true);
            case "dialogue" -> new Field(described(reference("identifier"), "The id of an animus:choices prompt."), "identifier", true);
            case "choice" -> new Field(described(reference("identifier"), "An option id of that prompt. Omit for any option."), "identifier", false);
            case "key" -> new Field(described(pattern(KEY), "A trigger a module registered, namespace:name."), "capability", true);
            case "animation" -> new Field(described(pattern("^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$"), "An animation id under animations/. Omit for any played animation."), "string", false);
            case "first" -> new Field(described(type("boolean"), "true fires only on first joins, false only for returning players. Omit for every join."), "boolean", false);
            case "pattern" -> new Field(described(bounded(TriggerCompiler.MAX_PATTERN), "Case-insensitive match of the whole message; * matches any text. Omit for every message."), "string", false);
            case "name" -> new Field(described(pattern("^" + TriggerCompiler.COMMAND_NAME + "$"), "The command to register, without a slash."), "string", true);
            case "aliases" -> {
                JsonObject aliases = type("array");
                aliases.addProperty("maxItems", TriggerCompiler.MAX_ALIASES);
                aliases.addProperty("uniqueItems", true);
                aliases.add("items", pattern("^" + TriggerCompiler.COMMAND_NAME + "$"));
                yield new Field(described(aliases, "Other names for the command, at most " + TriggerCompiler.MAX_ALIASES + "."), "array", false);
            }
            case "permission" -> new Field(described(pattern("^" + TriggerCompiler.PERMISSION + "$"), "A permission node the sender must hold. Omit to let everyone run it."), "string", false);
            case "description" -> new Field(described(bounded(TriggerCompiler.MAX_COMMAND_TEXT), "Shown in the server's command help."), "string", false);
            case "usage" -> new Field(described(bounded(TriggerCompiler.MAX_COMMAND_TEXT), "Usage text shown in the server's command help."), "string", false);
            case "from" -> new Field(described(bounded(TriggerCompiler.MAX_WORLD_NAME), "Fires only when leaving this world. Omit for any world."), "string", false);
            case "to" -> new Field(described(bounded(TriggerCompiler.MAX_WORLD_NAME), "Fires only when entering this world. Omit for any world."), "string", false);
            default -> throw new IllegalStateException("No schema for trigger field " + name);
        };
    }

    public static JsonObject packSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("$schema", DIALECT);
        root.addProperty("title", "Animus behavior pack manifest");
        root.addProperty("description", "packs/<pack>/pack.json. The id matches the directory name.");
        root.addProperty("type", "object");
        root.addProperty("additionalProperties", false);
        root.add("required", strings("schemaVersion", "id"));
        JsonObject properties = new JsonObject();
        for (String field : PackManifest.FIELDS) {
            switch (field) {
                case "schemaVersion" -> properties.add(field, constant(1));
                case "id" -> properties.add(field, described(pattern("^[a-z0-9_-]{1,64}$"), "The pack directory's name."));
                case "name" -> properties.add(field, text(PackManifest.MAXIMUM_NAME, "A display name."));
                case "version" -> properties.add(field, text(PackManifest.MAXIMUM_VERSION, "The pack's own version, for people."));
                case "description" -> properties.add(field, text(PackManifest.MAXIMUM_DESCRIPTION, "What the pack does."));
                case "modules" -> {
                    JsonObject modules = described(type("object"), "Java modules this pack uses. A missing required module makes the pack's behaviors unavailable; this configuration is kept.");
                    modules.addProperty("maxProperties", PackManifest.MAXIMUM_MODULES);
                    JsonObject name = pattern(IDENTIFIER);
                    name.add("not", constant("animus"));
                    modules.add("propertyNames", name);
                    JsonObject module = type("object");
                    module.addProperty("additionalProperties", false);
                    JsonObject moduleProperties = new JsonObject();
                    moduleProperties.add("required", described(type("boolean"), "True by default."));
                    moduleProperties.add("configuration", described(type("object"), "The module's own settings, kept as written."));
                    if (!Set.copyOf(PackManifest.MODULE_FIELDS).equals(moduleProperties.keySet())) throw new IllegalStateException("Pack module fields changed");
                    module.add("properties", moduleProperties);
                    modules.add("additionalProperties", module);
                    properties.add(field, modules);
                }
                default -> throw new IllegalStateException("No schema for pack field " + field);
            }
        }
        root.add("properties", properties);
        return root;
    }

    /** Words an editor offers, by position: capability IDs with their arguments, trigger events and language words. */
    public static JsonObject completions(List<Capabilities.Availability> capabilities) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray entries = new JsonArray();
        for (Capabilities.Availability availability : capabilities) {
            Capabilities.Descriptor descriptor = availability.descriptor();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", descriptor.key().toString());
            entry.addProperty("kind", descriptor.kind().name().toLowerCase(Locale.ROOT));
            entry.addProperty("description", descriptor.description());
            entry.addProperty("privileged", descriptor.privileged());
            entry.addProperty("available", availability.available());
            entry.addProperty("reason", availability.reason());
            entry.addProperty("reach", BuiltinCapabilities.npcOnly(descriptor.key()) ? "npc" : "both");
            JsonObject schema = argumentSchema(descriptor);
            Set<String> required = names(schema.get("required"));
            JsonObject arguments = new JsonObject();
            if (schema.has("properties") && schema.get("properties").isJsonObject())
                for (Map.Entry<String, JsonElement> property : schema.getAsJsonObject("properties").entrySet())
                    arguments.add(property.getKey(), completion(property.getValue(), required.contains(property.getKey())));
            entry.add("arguments", arguments);
            JsonArray alternatives = new JsonArray();
            for (String group : List.of("anyOf", "oneOf"))
                if (schema.has(group) && schema.get(group).isJsonArray())
                    for (JsonElement option : schema.getAsJsonArray(group))
                        if (option.isJsonObject() && option.getAsJsonObject().has("required")) alternatives.add(option.getAsJsonObject().get("required").deepCopy());
            if (!alternatives.isEmpty()) entry.add("oneOfRequired", alternatives);
            entries.add(entry);
        }
        root.add("capabilities", entries);
        JsonObject triggers = new JsonObject();
        for (TriggerKind kind : TriggerKind.values()) {
            JsonObject trigger = new JsonObject();
            trigger.addProperty("description", kind.description());
            trigger.addProperty("carriesPlayer", kind.carriesPlayer());
            trigger.addProperty("reach", kind.reach().name().toLowerCase(Locale.ROOT));
            List<String> context = ServerEvent.contextKeys(kind);
            if (!context.isEmpty()) trigger.add("context", strings(context.toArray(String[]::new)));
            JsonObject fields = new JsonObject();
            for (String name : kind.keys()) {
                Field field = name.equals("event") ? new Field(constant(kind.jsonName()), "event", true) : triggerField(name);
                fields.add(name, completion(field));
            }
            trigger.add("fields", fields);
            triggers.add(kind.jsonName(), trigger);
        }
        root.add("triggers", triggers);
        JsonObject language = new JsonObject();
        JsonObject nodes = new JsonObject();
        for (BehaviorNodeType type : BehaviorNodeType.values()) {
            JsonObject node = new JsonObject();
            node.addProperty("description", type.description());
            JsonObject fields = new JsonObject();
            for (String name : type.fields()) fields.add(name, completion(nodeField(type, name)));
            node.add("fields", fields);
            nodes.add(type.jsonName(), node);
        }
        language.add("nodes", nodes);
        List<String> types = new ArrayList<>();
        for (BehaviorProgram.Type type : BehaviorProgram.Type.values()) types.add(type.name().toLowerCase(Locale.ROOT));
        language.add("variableTypes", strings(types.toArray(String[]::new)));
        language.add("variableScopes", strings(scopes().toArray(String[]::new)));
        language.add("variableLifetimes", strings(lifetimes().toArray(String[]::new)));
        language.add("documentFields", strings(documentFields().toArray(String[]::new)));
        language.add("expressions", expressionLanguage());
        root.add("language", language);
        return root;
    }
    /** What behavior expressions can name: roots, facts, flag scopes and functions, with the declared-name rule. */
    private static JsonObject expressionLanguage() {
        JsonObject language = new JsonObject();
        language.addProperty("maxLength", BehaviorExpression.MAX_LENGTH);
        language.addProperty("namePattern", "^" + BehaviorExpression.NAME.pattern() + "$");
        language.add("roots", strings(BehaviorExpression.ROOTS.toArray(String[]::new)));
        language.add("reserved", strings(new java.util.TreeSet<>(BehaviorExpression.RESERVED).toArray(String[]::new)));
        JsonObject facts = new JsonObject();
        facts.add("player", strings(BehaviorExpression.PLAYER_FACTS.toArray(String[]::new)));
        facts.add("npc", strings(BehaviorExpression.NPC_FACTS.toArray(String[]::new)));
        facts.add("server", strings(BehaviorExpression.SERVER_FACTS.toArray(String[]::new)));
        language.add("facts", facts);
        language.add("flagScopes", strings(BehaviorExpression.FLAG_SCOPES.toArray(String[]::new)));
        language.add("functions", strings(BehaviorExpression.FUNCTIONS.toArray(String[]::new)));
        language.add("standardFunctions", strings(GuiExpression.STANDARD_FUNCTIONS.toArray(String[]::new)));
        return language;
    }

    private static JsonObject completion(Field field) {
        JsonObject entry = completion(field.schema(), field.required());
        entry.addProperty("type", field.completion());
        return entry;
    }
    private static JsonObject completion(JsonElement schema, boolean required) {
        JsonObject entry = new JsonObject();
        JsonObject source = schema.isJsonObject() ? schema.getAsJsonObject() : new JsonObject();
        String type = source.has("type") && source.get("type").isJsonPrimitive() ? source.get("type").getAsString()
                : source.has("enum") || source.has("const") ? "string" : "any";
        entry.addProperty("type", type);
        entry.addProperty("required", required);
        for (String keyword : List.of("enum", "const", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern", "description", "default"))
            if (source.has(keyword)) entry.add(keyword, source.get(keyword).deepCopy());
        return entry;
    }

    private static List<String> documentFields() {
        List<String> fields = new ArrayList<>(BehaviorCompiler.DOCUMENT_FIELDS);
        fields.add(1, "revision");
        return fields;
    }
    private static List<String> scopes() {
        List<String> scopes = new ArrayList<>(List.of("execution"));
        for (BehaviorProgram.Scope scope : BehaviorProgram.Scope.values()) scopes.add(scope.name().toLowerCase(Locale.ROOT));
        return scopes;
    }
    private static List<String> lifetimes() {
        List<String> lifetimes = new ArrayList<>();
        for (BehaviorProgram.Lifetime lifetime : BehaviorProgram.Lifetime.values()) lifetimes.add(lifetime.name().toLowerCase(Locale.ROOT));
        return lifetimes;
    }
    private static List<String> lower(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> value : values) names.add(value.name().toLowerCase(Locale.ROOT));
        return names;
    }
    private static Set<String> names(JsonElement values) {
        Set<String> names = new java.util.HashSet<>();
        if (values != null && values.isJsonArray()) for (JsonElement value : values.getAsJsonArray()) if (value.isJsonPrimitive()) names.add(value.getAsString());
        return names;
    }

    private static JsonObject type(String type) { JsonObject schema = new JsonObject(); schema.addProperty("type", type); return schema; }
    private static JsonObject pattern(String pattern) { JsonObject schema = type("string"); schema.addProperty("pattern", pattern); return schema; }
    private static JsonObject bounded(int maximum) { JsonObject schema = pattern(NONBLANK); schema.addProperty("maxLength", maximum); return schema; }
    private static JsonObject text(int maximum, String description) {
        JsonObject schema = described(pattern(NONBLANK), description);
        schema.addProperty("maxLength", maximum);
        return schema;
    }
    private static JsonObject constant(Object value) {
        JsonObject schema = new JsonObject();
        switch (value) {
            case Integer number -> schema.addProperty("const", number);
            case Boolean flag -> schema.addProperty("const", flag);
            default -> schema.addProperty("const", value.toString());
        }
        return schema;
    }
    private static JsonObject choices(List<String> values) { JsonObject schema = new JsonObject(); schema.add("enum", strings(values.toArray(String[]::new))); return schema; }
    private static JsonObject range(String type, Number minimum, Number maximum) {
        JsonObject schema = type(type);
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }
    private static JsonObject list(JsonObject items, int minimum, int maximum, String description) {
        JsonObject schema = described(type("array"), description);
        schema.addProperty("minItems", minimum);
        schema.addProperty("maxItems", maximum);
        schema.add("items", items);
        return schema;
    }
    private static JsonObject reference(String definition) { JsonObject schema = new JsonObject(); schema.addProperty("$ref", "#/$defs/" + definition); return schema; }
    private static JsonObject described(JsonObject schema, String description) { schema.addProperty("description", description); return schema; }
    private static JsonObject property(String name, JsonObject schema) {
        JsonObject wrapper = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.add(name, schema);
        wrapper.add("properties", properties);
        return wrapper;
    }
    /** {@code if name is value then rule}. */
    private static JsonObject when(String name, String value, JsonObject rule) {
        JsonObject condition = property(name, constant(value));
        condition.add("required", strings(name));
        JsonObject result = new JsonObject();
        result.add("if", condition);
        result.add("then", rule);
        return result;
    }
    private static JsonArray strings(String... values) { JsonArray array = new JsonArray(); for (String value : values) array.add(value); return array; }
}
