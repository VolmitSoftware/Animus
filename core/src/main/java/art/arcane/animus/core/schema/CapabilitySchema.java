package art.arcane.animus.core.schema;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Compiled, bounded subset of JSON Schema 2020-12 for module argument objects.
 * Unsupported keywords are registration errors, never silently ignored assertions.
 * This private engine class performs no I/O, reference loading, regex or user callbacks.
 */
public final class CapabilitySchema {
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_JSON_NODES = 4_096;
    public static final int MAX_SCHEMA_NODES = 512;
    public static final int MAX_SCHEMA_DEPTH = 16;
    public static final int MAX_WORK = 32_768;
    public static final int MAX_DIAGNOSTICS = 32;
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> TYPES = Set.of("null", "boolean", "object", "array", "number", "integer", "string");
    private static final Set<String> KEYWORDS = Set.of("$schema", "$comment", "title", "description", "default", "examples",
            "readOnly", "writeOnly", "deprecated", "type", "enum", "const", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "minItems", "maxItems", "minProperties",
            "maxProperties", "required", "properties", "additionalProperties", "items", "prefixItems", "allOf", "anyOf",
            "oneOf", "not", "if", "then", "else");
    private final Node root;
    private CapabilitySchema(Node root) { this.root = root; }

    /** Root schemas are objects to match the module descriptor contract; subschemas may be booleans. */
    public static CapabilitySchema compile(String schemaJson) {
        JsonObject object;
        try { object = boundedObject(schemaJson); }
        catch (InvalidInput invalid) { throw new SchemaException(invalid.code, "$", invalid.getMessage()); }
        return new CapabilitySchema(new Compiler().node(object, "$", 0));
    }

    /** Validates the final argument object without changing it or supplying defaults. Thread safe. */
    public List<Diagnostic> validate(String argumentsJson) {
        JsonObject arguments;
        try { arguments = boundedObject(argumentsJson); }
        catch (InvalidInput invalid) { return List.of(diagnostic(invalid.code, "$", invalid.getMessage())); }
        Evaluation evaluation = new Evaluation();
        try { root.validate(arguments, "$", evaluation, true); }
        catch (WorkLimit ignored) { evaluation.add("schema-work-limit", "$", "Argument validation exceeded its work limit"); }
        return List.copyOf(evaluation.errors);
    }

    public static final class SchemaException extends IllegalArgumentException {
        private final List<Diagnostic> diagnostics;
        private SchemaException(String code, String path, String message) {
            super(message); diagnostics = List.of(diagnostic(code, path, message));
        }
        public List<Diagnostic> diagnostics() { return diagnostics; }
    }
    private static Diagnostic diagnostic(String code, String path, String message) {
        StringBuilder bounded = new StringBuilder(); int bytes = 0;
        for (int i = 0; i < path.length();) {
            int point = path.codePointAt(i), width = point < 128 ? 1 : point < 2_048 ? 2 : point < 65_536 ? 3 : 4;
            if (bytes + width > 1_021) { bounded.append('…'); break; }
            bounded.appendCodePoint(point); bytes += width; i += Character.charCount(point);
        }
        return new Diagnostic(Diagnostic.Severity.ERROR, code, bounded.toString(), message);
    }
    private static String child(String path, String key) { return path + "/" + key.replace("~", "~0").replace("/", "~1"); }
    private static final class InvalidInput extends RuntimeException {
        final String code;
        InvalidInput(String code, String message) { super(message); this.code = code; }
    }
    private static JsonObject boundedObject(String text) {
        if (text == null || text.length() > MAX_BYTES || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new InvalidInput("json-byte-limit", "Expected valid UTF-8 JSON within 64 KiB");
        try {
            JsonObject object = JsonDocuments.object(text);
            inspect(object, new int[]{0}, 0);
            return object;
        } catch (IOException | NumberFormatException invalid) {
            throw new InvalidInput("invalid-json", "Expected one strict JSON object without duplicate keys");
        }
    }
    private static void inspect(JsonElement value, int[] count, int depth) {
        if (++count[0] > MAX_JSON_NODES || depth > 48)
            throw new InvalidInput("json-structure-limit", "JSON exceeds the node or depth limit");
        if (value.isJsonObject()) for (var property : value.getAsJsonObject().entrySet()) {
            validText(property.getKey()); inspect(property.getValue(), count, depth + 1);
        }
        else if (value.isJsonArray()) for (JsonElement element : value.getAsJsonArray()) inspect(element, count, depth + 1);
        else if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) validText(primitive.getAsString());
            else if (primitive.isNumber()) {
                BigDecimal number = primitive.getAsBigDecimal();
                if (number.precision() > 1_024 || Math.abs((long) number.scale()) > 4_096)
                    throw new InvalidInput("json-number-limit", "JSON number exceeds precision or exponent limits");
            }
        }
    }
    private static void validText(String value) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new InvalidInput("invalid-json-unicode", "JSON contains an unpaired Unicode surrogate");
    }
    private static final class WorkLimit extends RuntimeException {
        private WorkLimit() { super(null, null, false, false); }
    }
    private static final class Evaluation {
        int work;
        final ArrayList<Diagnostic> errors = new ArrayList<>();
        void step() { if (++work > MAX_WORK) throw new WorkLimit(); }
        void add(String code, String path, String message) {
            if (errors.size() < MAX_DIAGNOSTICS) errors.add(diagnostic(code, path, message));
        }
        boolean fail(boolean report, String code, String path, String message) {
            if (report) add(code, path, message);
            return false;
        }
    }
    private static final class Compiler {
        int count;
        Node node(JsonElement raw, String path, int depth) {
            if (++count > MAX_SCHEMA_NODES || depth > MAX_SCHEMA_DEPTH)
                throw bad(path, "schema-structure-limit", "Schema exceeds its node or depth limit");
            if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()) return new Node(raw.getAsBoolean());
            if (!raw.isJsonObject()) throw bad(path, "schema-type", "Expected an object or boolean schema");
            JsonObject object = raw.getAsJsonObject();
            for (String key : object.keySet()) if (!KEYWORDS.contains(key))
                throw bad(child(path, key), "schema-keyword-unavailable", "This schema keyword is not supported by the current profile");
            for (String key : List.of("title", "description", "$comment")) if (object.has(key)) string(object.get(key), child(path, key));
            if (object.has("$schema") && (!path.equals("$") || !string(object.get("$schema"), child(path, "$schema")).equals(DIALECT)))
                throw bad(child(path, "$schema"), "schema-dialect", "Only a root JSON Schema 2020-12 dialect declaration is supported");
            if (object.has("examples") && !object.get("examples").isJsonArray()) throw bad(child(path, "examples"), "schema-type", "Expected an array");
            for (String key : List.of("readOnly", "writeOnly", "deprecated")) if (object.has(key)) bool(object.get(key), child(path, key));
            Node node = new Node(true);
            if (object.has("type")) {
                JsonElement type = object.get("type"); LinkedHashSet<String> types = new LinkedHashSet<>();
                if (type.isJsonArray()) {
                    if (type.getAsJsonArray().isEmpty()) throw bad(child(path, "type"), "schema-type", "Type array must not be empty");
                    for (JsonElement item : type.getAsJsonArray()) if (!types.add(type(item, child(path, "type"))))
                        throw bad(child(path, "type"), "schema-duplicate", "Type array must contain distinct entries");
                } else types.add(type(type, child(path, "type")));
                node.types = Set.copyOf(types);
            }
            if (object.has("enum")) {
                JsonElement rawEnum = object.get("enum");
                if (!rawEnum.isJsonArray() || rawEnum.getAsJsonArray().size() > 128)
                    throw bad(child(path, "enum"), "schema-enum", "Expected at most 128 enum entries");
                node.allowed = List.copyOf(rawEnum.getAsJsonArray().asList());
                // Duplicate enum values have the same validation meaning; do not perform a quadratic compile scan.
            }
            if (object.has("const")) node.constant = object.get("const");
            for (String key : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")) if (object.has(key)) {
                BigDecimal number = number(object.get(key), child(path, key));
                if (key.equals("multipleOf") && number.signum() <= 0) throw bad(child(path, key), "schema-number", "multipleOf must be positive");
                node.numbers.put(key, number);
            }
            for (String key : List.of("minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties")) if (object.has(key)) {
                BigDecimal number = number(object.get(key), child(path, key));
                if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0)
                    throw bad(child(path, key), "schema-count", "Expected a nonnegative integer");
                // Finite payloads cannot approach this sentinel. Clamp huge legal counts without allocating by them.
                node.counts.put(key, number.min(BigDecimal.valueOf(MAX_BYTES + 1L)).intValueExact());
            }
            if (object.has("required")) {
                JsonElement required = object.get("required");
                if (!required.isJsonArray()) throw bad(child(path, "required"), "schema-type", "Expected an array");
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                for (JsonElement key : required.getAsJsonArray()) if (!keys.add(string(key, child(path, "required"))))
                    throw bad(child(path, "required"), "schema-duplicate", "Required names must be distinct");
                node.required = Set.copyOf(keys);
            }
            if (object.has("properties")) {
                JsonElement properties = object.get("properties");
                if (!properties.isJsonObject()) throw bad(child(path, "properties"), "schema-type", "Expected an object");
                for (var property : properties.getAsJsonObject().entrySet())
                    node.properties.put(property.getKey(), node(property.getValue(), child(child(path, "properties"), property.getKey()), depth + 1));
            }
            for (String key : List.of("additionalProperties", "items", "not", "if", "then", "else")) if (object.has(key))
                node.children.put(key, node(object.get(key), child(path, key), depth + 1));
            for (String key : List.of("prefixItems", "allOf", "anyOf", "oneOf")) if (object.has(key)) {
                JsonElement list = object.get(key);
                if (!list.isJsonArray() || list.getAsJsonArray().isEmpty()) throw bad(child(path, key), "schema-type", "Expected a nonempty schema array");
                ArrayList<Node> nodes = new ArrayList<>();
                for (JsonElement item : list.getAsJsonArray()) nodes.add(node(item, child(child(path, key), Integer.toString(nodes.size())), depth + 1));
                node.groups.put(key, List.copyOf(nodes));
            }
            return node;
        }
        private static SchemaException bad(String path, String code, String message) { return new SchemaException(code, path, message); }
        private static String string(JsonElement value, String path) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(path, "schema-type", "Expected a string");
            return value.getAsString();
        }
        private static boolean bool(JsonElement value, String path) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw bad(path, "schema-type", "Expected a boolean");
            return value.getAsBoolean();
        }
        private static String type(JsonElement value, String path) {
            String type = string(value, path);
            if (!TYPES.contains(type)) throw bad(path, "schema-type", "Unknown JSON instance type");
            return type;
        }
        private static BigDecimal number(JsonElement value, String path) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(path, "schema-type", "Expected a number");
            return value.getAsBigDecimal();
        }
    }
    /** Nodes are private, fully constructed before publication, and never mutated during validation. */
    private static final class Node {
        final boolean accepted;
        Set<String> types = Set.of(), required = Set.of();
        List<JsonElement> allowed;
        JsonElement constant;
        final Map<String, BigDecimal> numbers = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, Node> properties = new LinkedHashMap<>(), children = new LinkedHashMap<>();
        final Map<String, List<Node>> groups = new LinkedHashMap<>();
        Node(boolean accepted) { this.accepted = accepted; }
        boolean validate(JsonElement value, String path, Evaluation evaluation, boolean report) {
            evaluation.step();
            if (!accepted) return evaluation.fail(report, "schema-false", path, "Value is prohibited by the schema");
            if (!types.isEmpty() && types.stream().noneMatch(type -> hasType(value, type)))
                return evaluation.fail(report, "schema-type", path, "Value has an unexpected JSON type");
            boolean valid = true;
            if (constant != null && !equal(value, constant, evaluation)) valid = evaluation.fail(report, "schema-const", path, "Value does not match the declared constant");
            if (allowed != null) {
                boolean found = false;
                for (JsonElement option : allowed) if (equal(value, option, evaluation)) { found = true; break; }
                if (!found) valid = evaluation.fail(report, "schema-enum", path, "Value is not one of the permitted choices");
            }
            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                valid &= size(object.size(), "Properties", path, evaluation, report);
                for (String name : required) {
                    evaluation.step();
                    if (!object.has(name)) valid = evaluation.fail(report, "schema-required", child(path, name), "Required property is missing");
                }
                for (var property : object.entrySet()) {
                    evaluation.step();
                    Node propertySchema = properties.get(property.getKey());
                    if (propertySchema == null) propertySchema = children.get("additionalProperties");
                    if (propertySchema != null) valid &= propertySchema.validate(property.getValue(), child(path, property.getKey()), evaluation, report);
                }
            } else if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                valid &= size(array.size(), "Items", path, evaluation, report);
                List<Node> prefix = groups.getOrDefault("prefixItems", List.of());
                for (int i = 0; i < array.size(); i++) {
                    evaluation.step();
                    Node item = i < prefix.size() ? prefix.get(i) : children.get("items");
                    if (item != null) valid &= item.validate(array.get(i), child(path, Integer.toString(i)), evaluation, report);
                }
            } else if (value.isJsonPrimitive()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isString()) {
                    String text = primitive.getAsString();
                    valid &= size(text.codePointCount(0, text.length()), "Length", path, evaluation, report);
                } else if (primitive.isNumber()) {
                    BigDecimal number = primitive.getAsBigDecimal();
                    for (var limit : numbers.entrySet()) {
                        evaluation.step();
                        int compared = number.compareTo(limit.getValue());
                        boolean good = switch (limit.getKey()) {
                            case "minimum" -> compared >= 0;
                            case "maximum" -> compared <= 0;
                            case "exclusiveMinimum" -> compared > 0;
                            case "exclusiveMaximum" -> compared < 0;
                            case "multipleOf" -> number.remainder(limit.getValue()).signum() == 0;
                            default -> throw new AssertionError();
                        };
                        if (!good) valid = evaluation.fail(report, "schema-" + limit.getKey(), path, "Number violates its declared constraint");
                    }
                }
            }
            for (Node all : groups.getOrDefault("allOf", List.of())) valid &= all.validate(value, path, evaluation, report);
            for (String kind : List.of("anyOf", "oneOf")) if (groups.containsKey(kind)) {
                int successes = 0;
                for (Node option : groups.get(kind)) {
                    if (option.validate(value, path, evaluation, false)) successes++;
                    if (kind.equals("anyOf") && successes > 0 || kind.equals("oneOf") && successes > 1) break;
                }
                if (successes != 1) valid = evaluation.fail(report, "schema-" + kind, path, "Value does not satisfy the required schema alternatives");
            }
            Node not = children.get("not");
            if (not != null && not.validate(value, path, evaluation, false)) valid = evaluation.fail(report, "schema-not", path, "Value matches a prohibited schema");
            Node condition = children.get("if");
            if (condition != null) {
                Node branch = children.get(condition.validate(value, path, evaluation, false) ? "then" : "else");
                if (branch != null) valid &= branch.validate(value, path, evaluation, report);
            }
            return valid;
        }
        boolean size(int size, String kind, String path, Evaluation evaluation, boolean report) {
            Integer min = counts.get("min" + kind), max = counts.get("max" + kind);
            if (min != null && size < min || max != null && size > max)
                return evaluation.fail(report, "schema-size", path, "Value violates its declared size constraint");
            return true;
        }
    }
    private static boolean hasType(JsonElement value, String type) {
        return switch (type) {
            case "null" -> value.isJsonNull();
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }
    private static boolean equal(JsonElement left, JsonElement right, Evaluation evaluation) {
        evaluation.step();
        if (left.isJsonNull() || right.isJsonNull()) return left.isJsonNull() && right.isJsonNull();
        if (left.isJsonPrimitive() && right.isJsonPrimitive()) {
            JsonPrimitive a = left.getAsJsonPrimitive(), b = right.getAsJsonPrimitive();
            if (a.isNumber() && b.isNumber()) return a.getAsBigDecimal().compareTo(b.getAsBigDecimal()) == 0;
            if (a.isBoolean() && b.isBoolean()) return a.getAsBoolean() == b.getAsBoolean();
            return a.isString() && b.isString() && a.getAsString().equals(b.getAsString());
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            JsonArray a = left.getAsJsonArray(), b = right.getAsJsonArray();
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i), evaluation)) return false;
            return true;
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            JsonObject a = left.getAsJsonObject(), b = right.getAsJsonObject();
            if (a.size() != b.size()) return false;
            for (var property : a.entrySet()) {
                evaluation.step();
                if (!b.has(property.getKey()) || !equal(property.getValue(), b.get(property.getKey()), evaluation)) return false;
            }
            return true;
        }
        return false;
    }
}
