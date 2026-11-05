package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorNodeType;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.behavior.trigger.TriggerBinding;
import art.arcane.animus.core.behavior.trigger.TriggerCompiler;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.npc.NpcDefinition;
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
    public static final String BEHAVIOR = "schemas/behavior.schema.json", NPC = "schemas/npc.schema.json", PACK = "schemas/pack.schema.json", COMPLETIONS = "schemas/completions.json";
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
        documents.put(NPC, JsonDocuments.write(npcSchema()));
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
        JsonObject definitions = new JsonObject();
        definitions.add("identifier", described(pattern(IDENTIFIER), "1-64 lowercase letters, digits, _ or -, starting with a letter or digit."));
        definitions.add("module-capability", moduleCapability());
        definitions.add("actions", capabilityList(capabilities, Capabilities.Kind.ACTION));
        definitions.add("conditions", capabilityList(capabilities, Capabilities.Kind.CONDITION));
        definitions.add("variable", variable());
        definitions.add("bindings", bindings());
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
        properties.add("type", described(choices(types), "The value type. There is no coercion."));
        properties.add("initial", described(new JsonObject(), "The value until something writes it, of the declared type."));
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
            case "capability" -> new Field(capabilityField(type), "capability", true);
            case "arguments" -> new Field(described(type("object"), "The capability's arguments."), "arguments", false);
            case "bindings" -> new Field(reference("bindings"), "bindings", false);
            case "then" -> new Field(described(reference("node"), "Runs when the condition is true."), "node", true);
            case "else" -> new Field(described(reference("node"), "Runs when the condition is false."), "node", false);
            case "body" -> new Field(described(reference("node"), "The repeated step."), "node", true);
            case "ticks" -> new Field(described(range("integer", 1, BehaviorCompiler.MAX_WAIT_TICKS), "Behavior ticks, about 50 ms each."), "integer", true);
            case "count" -> new Field(described(range("integer", 0, BehaviorCompiler.MAX_REPEAT), "How many times the body runs."), "integer", true);
            case "routine" -> new Field(described(reference("identifier"), "A routine of this document."), "routine", true);
            case "variable" -> new Field(described(pattern(NONBLANK), "A declared variable."), "variable", true);
            case "value" -> {
                JsonObject literal = new JsonObject();
                literal.add("type", strings("boolean", "number", "string"));
                JsonArray options = new JsonArray();
                options.add(literal);
                options.add(variableReference());
                JsonObject value = described(new JsonObject(), "A literal of the variable's type, or {\"variable\": \"name\"} of the same type.");
                value.add("anyOf", options);
                yield new Field(value, "value", true);
            }
            default -> throw new IllegalStateException("No schema for node field " + type.jsonName() + "." + name);
        };
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
        JsonObject bound = new JsonObject();
        bound.add("required", strings("bindings"));
        JsonObject nonEmpty = new JsonObject();
        nonEmpty.addProperty("minProperties", 1);
        bound.add("properties", property("bindings", nonEmpty).getAsJsonObject("properties"));
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
        JsonObject trigger = described(type("object"), "An automatic start for NPCs that list this behavior.");
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
            case "intervalTicks" -> new Field(described(range("integer", 1, TriggerCompiler.MAX_INTERVAL_TICKS), "Ticks between firings while the NPC is active."), "integer", true);
            case "dialogue" -> new Field(described(reference("identifier"), "The id of an animus:choices prompt."), "identifier", true);
            case "choice" -> new Field(described(reference("identifier"), "An option id of that prompt. Omit for any option."), "identifier", false);
            case "key" -> new Field(described(pattern(KEY), "A trigger a module registered, namespace:name."), "capability", true);
            case "animation" -> new Field(described(pattern("^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$"), "An animation id under animations/. Omit for any played animation."), "string", false);
            default -> throw new IllegalStateException("No schema for trigger field " + name);
        };
    }

    /** Validation of NPC definitions as files and commands see it; unknown fields and modules are preserved. */
    public static JsonObject npcSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("$schema", DIALECT);
        root.addProperty("title", "Animus NPC definition");
        root.addProperty("description", "npcs/<group>/<id>.json. Unknown fields and modules are kept as written.");
        root.addProperty("type", "object");
        root.add("required", strings("schemaVersion", "revision", "uuid", "id", "group", "name", "type", "spawned", "position"));
        JsonObject properties = new JsonObject();
        properties.add("schemaVersion", constant(1));
        properties.add("revision", described(range("integer", 1, Long.MAX_VALUE), "Raise this with every edit."));
        properties.add("uuid", described(pattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"), "A canonical lowercase UUID that never changes."));
        properties.add("id", described(pattern(IDENTIFIER), "Matches the file name."));
        properties.add("group", described(pattern(IDENTIFIER), "Matches the directory name."));
        properties.add("name", described(pattern(NONBLANK), "The displayed name."));
        properties.add("type", described(pattern("^[a-z0-9_]+:[a-z0-9_/]+$"), "An entity type such as minecraft:player."));
        properties.add("spawned", described(type("boolean"), "Whether the NPC is presented."));
        JsonObject position = type("object");
        position.add("required", strings("world", "x", "y", "z", "yaw", "pitch"));
        JsonObject coordinates = new JsonObject();
        coordinates.add("world", pattern(NONBLANK));
        for (String axis : List.of("x", "y", "z", "yaw", "pitch")) coordinates.add(axis, range("number", -30_000_000, 30_000_000));
        position.add("properties", coordinates);
        properties.add("position", position);
        properties.add("skin", described(pattern("^[a-f0-9]{64}$"), "SHA-256 of a skin document under skins/."));
        JsonObject behaviors = described(type("array"), "Behaviors whose triggers this NPC runs.");
        behaviors.add("items", pattern("^[a-z0-9_-]+:[a-z0-9_/-]+$"));
        properties.add("behaviors", behaviors);
        JsonObject tags = described(type("array"), "Labels for finding and grouping NPCs in the editor; at most 32.");
        tags.add("items", pattern("^[a-z0-9][a-z0-9_-]{0,31}$"));
        tags.addProperty("maxItems", NpcDefinition.MAX_TAGS);
        tags.addProperty("uniqueItems", true);
        properties.add("tags", tags);
        JsonObject rig = described(type("object"), "The rig under rigs/ this NPC's body shows.");
        rig.addProperty("additionalProperties", false);
        rig.add("required", strings("id"));
        rig.add("properties", property("id", pattern("^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$")).getAsJsonObject("properties"));
        properties.add("rig", rig);
        JsonObject modules = described(type("object"), "Per-NPC module settings, keyed by namespace:name. Unknown modules are kept.");
        JsonObject known = new JsonObject();
        known.add("animus:navigation", navigation());
        known.add(MountDeclaration.MODULE, mount());
        known.add(RideSettings.MODULE, riding());
        known.add(LookClose.MODULE, lookClose());
        modules.add("properties", known);
        properties.add("modules", modules);
        root.add("properties", properties);
        JsonArray rules = new JsonArray();
        JsonObject imported = new JsonObject();
        JsonObject migration = new JsonObject();
        migration.add("required", strings("migration"));
        imported.add("if", migration);
        JsonObject reviewed = new JsonObject();
        reviewed.add("animus:navigation", reviewRequired());
        reviewed.add(RideSettings.MODULE, reviewRequired());
        JsonObject reviewedModules = new JsonObject();
        reviewedModules.add("properties", reviewed);
        imported.add("then", property("modules", reviewedModules));
        rules.add(imported);
        root.add("allOf", rules);
        return root;
    }
    private static JsonObject reviewRequired() {
        JsonObject then = new JsonObject();
        then.add("required", strings("reviewAccepted"));
        then.add("properties", property("reviewAccepted", constant(true)).getAsJsonObject("properties"));
        return whenEnabled(then);
    }
    private static JsonObject whenEnabled(JsonObject then) {
        JsonObject rule = new JsonObject();
        JsonObject enabled = new JsonObject();
        enabled.add("properties", property("enabled", constant(true)).getAsJsonObject("properties"));
        enabled.add("required", strings("enabled"));
        rule.add("if", enabled);
        rule.add("then", then);
        return rule;
    }
    private static JsonObject navigation() {
        JsonObject navigation = described(type("object"), "Native waypoints, or a reusable route under routes/.");
        JsonObject properties = new JsonObject();
        properties.add("enabled", type("boolean"));
        properties.add("reviewAccepted", type("boolean"));
        properties.add("route", pattern(IDENTIFIER));
        properties.add("mode", choices(List.of("walk", "swim", "fly")));
        properties.add("speed", range("number", 1, 5));
        properties.add("order", described(choices(List.of("sequence", "pingpong", "random", "wander")), "How the next point is chosen. sequence by default."));
        properties.add("loop", described(type("boolean"), "Order sequence only: start again from the first point."));
        properties.add("waitTicks", described(range("integer", 0, WaypointSettings.MAXIMUM_WAIT), "Wait before the first leg and after each arrival."));
        JsonObject point = coordinate();
        point.getAsJsonObject("properties").add("waitTicks", described(range("integer", 0, WaypointSettings.MAXIMUM_WAIT), "Wait after arriving here, instead of the route's."));
        properties.add("points", list(point, 0, WaypointSettings.MAXIMUM_POINTS, "Route points in the NPC's world; wander centres for order wander."));
        properties.add("guides", list(coordinate(), 0, WaypointSettings.MAXIMUM_GUIDES, "Order random only: helper nodes a path may pass through."));
        properties.add("linkDistance", described(range("number", WaypointSettings.MINIMUM_LINK_DISTANCE, WaypointSettings.MAXIMUM_LINK_DISTANCE), "Order random only: the longest link between two nodes."));
        JsonObject wander = described(type("object"), "Order wander only.");
        wander.addProperty("additionalProperties", false);
        JsonObject area = new JsonObject();
        area.add("radius", range("integer", 1, WaypointSettings.Wander.MAXIMUM_RADIUS));
        area.add("height", range("integer", 0, WaypointSettings.Wander.MAXIMUM_HEIGHT));
        wander.add("properties", area);
        properties.add("wander", wander);
        navigation.add("properties", properties);
        JsonObject routed = new JsonObject();
        JsonObject hasRoute = new JsonObject();
        hasRoute.add("required", strings("route"));
        routed.add("if", hasRoute);
        JsonObject exclusive = new JsonObject();
        exclusive.add("not", anyRequired(List.of("points", "mode", "speed", "order", "loop", "waitTicks", "guides", "linkDistance", "wander")));
        routed.add("then", exclusive);
        JsonObject inline = new JsonObject();
        JsonObject wandering = new JsonObject();
        wandering.add("if", orderIs("wander"));
        JsonObject centres = new JsonObject();
        JsonObject fewer = new JsonObject();
        fewer.addProperty("maxItems", WaypointSettings.MAXIMUM_WANDER_CENTRES);
        centres.add("properties", property("points", fewer).getAsJsonObject("properties"));
        wandering.add("then", centres);
        JsonObject listed = new JsonObject();
        listed.add("required", strings("points"));
        JsonObject some = new JsonObject();
        some.addProperty("minItems", 1);
        listed.add("properties", property("points", some).getAsJsonObject("properties"));
        wandering.add("else", listed);
        JsonArray inlineRules = new JsonArray();
        inlineRules.add(wandering);
        inline.add("allOf", inlineRules);
        routed.add("else", inline);
        JsonArray rules = new JsonArray();
        rules.add(whenEnabled(routed));
        rules.add(onlyWithOrder("loop", "sequence", true));
        rules.add(onlyWithOrder("guides", "random", false));
        rules.add(onlyWithOrder("linkDistance", "random", false));
        rules.add(onlyWithOrder("wander", "wander", false));
        navigation.add("allOf", rules);
        return navigation;
    }
    private static JsonObject coordinate() {
        JsonObject point = type("object");
        point.add("required", strings("x", "y", "z"));
        JsonObject axes = new JsonObject();
        for (String axis : List.of("x", "y", "z")) axes.add(axis, range("number", -29_999_984, 29_999_984));
        point.add("properties", axes);
        return point;
    }
    private static JsonObject anyRequired(List<String> keys) {
        JsonObject conflicting = new JsonObject();
        JsonArray any = new JsonArray();
        for (String key : keys) { JsonObject present = new JsonObject(); present.add("required", strings(key)); any.add(present); }
        conflicting.add("anyOf", any);
        return conflicting;
    }
    private static JsonObject orderIs(String order) {
        JsonObject condition = new JsonObject();
        condition.add("properties", property("order", constant(order)).getAsJsonObject("properties"));
        condition.add("required", strings("order"));
        return condition;
    }
    /** The field may appear only with the given order; the default order counts when it is the default. */
    private static JsonObject onlyWithOrder(String field, String order, boolean isDefault) {
        JsonObject rule = new JsonObject();
        JsonObject present = new JsonObject();
        present.add("required", strings(field));
        rule.add("if", present);
        JsonObject then = new JsonObject();
        then.add("properties", property("order", constant(order)).getAsJsonObject("properties"));
        if (!isDefault) then.add("required", strings("order"));
        rule.add("then", then);
        return rule;
    }
    private static JsonObject lookClose() {
        JsonObject look = described(type("object"), "Turns the NPC toward the nearest player in range while it is spawned.");
        look.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        properties.add("enabled", described(type("boolean"), "True when the object is present."));
        properties.add("range", described(range("number", LookClose.MINIMUM_RANGE, LookClose.MAXIMUM_RANGE), "Blocks. 10 by default."));
        properties.add("lineOfSight", described(type("boolean"), "Only look at players it can see."));
        properties.add("whileNavigating", described(type("boolean"), "Also look while following a route."));
        properties.add("npcs", described(type("boolean"), "Also look at the nearest other NPC when no player is in range."));
        JsonObject random = described(type("object"), "With nobody to look at, turn to a random facing every delayTicks.");
        random.addProperty("additionalProperties", false);
        JsonObject randomProperties = new JsonObject();
        randomProperties.add("enabled", described(type("boolean"), "True by default."));
        randomProperties.add("delayTicks", described(range("integer", 1, LookClose.MAXIMUM_RANDOM_DELAY), "Ticks between random turns. 60 by default."));
        randomProperties.add("yaw", described(angles(-360, 360), "[minimum, maximum] yaw in degrees. [0, 360] by default."));
        randomProperties.add("pitch", described(angles(-90, 90), "[minimum, maximum] pitch in degrees. [0, 0] by default."));
        random.add("properties", randomProperties);
        properties.add("randomLook", random);
        look.add("properties", properties);
        return look;
    }
    private static JsonObject angles(int minimum, int maximum) {
        JsonObject array = type("array");
        array.add("items", range("number", minimum, maximum));
        array.addProperty("minItems", 2);
        array.addProperty("maxItems", 2);
        return array;
    }
    private static JsonObject mount() {
        JsonObject mount = described(type("object"), "Rides another NPC.");
        mount.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        properties.add("enabled", type("boolean"));
        properties.add("vehicle", described(pattern(IDENTIFIER), "The vehicle NPC's id."));
        mount.add("properties", properties);
        JsonObject disabled = new JsonObject();
        disabled.add("properties", property("enabled", constant(false)).getAsJsonObject("properties"));
        disabled.add("required", strings("enabled"));
        JsonObject rule = new JsonObject();
        rule.add("if", disabled);
        JsonObject vehicle = new JsonObject();
        vehicle.add("required", strings("vehicle"));
        rule.add("else", vehicle);
        JsonArray rules = new JsonArray();
        rules.add(rule);
        mount.add("allOf", rules);
        return mount;
    }
    private static JsonObject riding() {
        JsonObject riding = described(type("object"), "Lets a real player ride this NPC.");
        riding.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        properties.add("enabled", type("boolean"));
        properties.add("reviewAccepted", type("boolean"));
        properties.add("permission", type("string"));
        properties.add("speed", type("number"));
        properties.add("leash", type("number"));
        properties.add("lifetimeSeconds", type("number"));
        riding.add("properties", properties);
        JsonObject limits = new JsonObject();
        JsonObject bounded = new JsonObject();
        JsonObject permission = pattern("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$");
        permission.addProperty("maxLength", 128);
        bounded.add("permission", permission);
        bounded.add("speed", range("number", RideSettings.MINIMUM_SPEED, RideSettings.MAXIMUM_SPEED));
        bounded.add("leash", range("number", RideSettings.MINIMUM_LEASH, RideSettings.MAXIMUM_LEASH));
        bounded.add("lifetimeSeconds", range("integer", RideSettings.MINIMUM_LIFETIME_SECONDS, RideSettings.MAXIMUM_LIFETIME_SECONDS));
        limits.add("properties", bounded);
        JsonArray rules = new JsonArray();
        rules.add(whenEnabled(limits));
        riding.add("allOf", rules);
        return riding;
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
        root.add("language", language);
        return root;
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
