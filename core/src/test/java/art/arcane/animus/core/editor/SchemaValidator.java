package art.arcane.animus.core.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The JSON Schema 2020-12 keywords editor-v1.schema.json uses, enough to check every request, response and event the Java
 * API produces. Refuses any keyword it does not implement, as the Dart validator does, so a schema change cannot pass unchecked.
 */
final class SchemaValidator {
    private static final Set<String> KNOWN = Set.of("$schema", "$id", "title", "description", "$defs", "$ref", "type", "enum", "const", "properties",
            "required", "additionalProperties", "items", "minItems", "maxItems", "minLength", "maxLength", "pattern", "minimum", "maximum",
            "anyOf", "oneOf", "not", "if", "then", "propertyNames");
    private final JsonObject root;

    SchemaValidator(JsonObject root) { this.root = root; }

    /** Problems of {@code value} against {@code #/$defs/<definition>}; empty when valid. */
    List<String> check(String definition, JsonElement value) {
        JsonObject schema = root.getAsJsonObject("$defs").getAsJsonObject(definition);
        if (schema == null) throw new IllegalArgumentException("No definition " + definition);
        List<String> problems = new ArrayList<>();
        validate(schema, value, "$", problems);
        return problems;
    }

    private void validate(JsonElement schemaElement, JsonElement value, String at, List<String> problems) {
        if (schemaElement.isJsonPrimitive()) {
            if (!schemaElement.getAsBoolean()) problems.add(at + ": false schema");
            return;
        }
        JsonObject schema = schemaElement.getAsJsonObject();
        for (String keyword : schema.keySet()) if (!KNOWN.contains(keyword)) throw new IllegalStateException("Unsupported schema keyword " + keyword);
        if (schema.has("$ref")) {
            String reference = schema.get("$ref").getAsString();
            if (!reference.startsWith("#/$defs/")) throw new IllegalStateException("Unsupported $ref " + reference);
            validate(root.getAsJsonObject("$defs").get(reference.substring(8)), value, at, problems);
        }
        if (schema.has("type") && !typed(schema.get("type"), value)) { problems.add(at + ": expected type " + schema.get("type")); return; }
        if (schema.has("const") && !equal(schema.get("const"), value)) problems.add(at + ": expected " + schema.get("const"));
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonElement option : schema.getAsJsonArray("enum")) found |= equal(option, value);
            if (!found) problems.add(at + ": not one of " + schema.get("enum"));
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            int length = text.codePointCount(0, text.length());
            if (schema.has("minLength") && length < schema.get("minLength").getAsInt()) problems.add(at + ": shorter than " + schema.get("minLength"));
            if (schema.has("maxLength") && length > schema.get("maxLength").getAsInt()) problems.add(at + ": longer than " + schema.get("maxLength"));
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").getAsString()).matcher(text).find()) problems.add(at + ": does not match " + schema.get("pattern"));
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            BigDecimal number = value.getAsBigDecimal();
            if (schema.has("minimum") && number.compareTo(schema.get("minimum").getAsBigDecimal()) < 0) problems.add(at + ": below " + schema.get("minimum"));
            if (schema.has("maximum") && number.compareTo(schema.get("maximum").getAsBigDecimal()) > 0) problems.add(at + ": above " + schema.get("maximum"));
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (schema.has("minItems") && array.size() < schema.get("minItems").getAsInt()) problems.add(at + ": fewer than " + schema.get("minItems") + " items");
            if (schema.has("maxItems") && array.size() > schema.get("maxItems").getAsInt()) problems.add(at + ": more than " + schema.get("maxItems") + " items");
            if (schema.has("items")) for (int index = 0; index < array.size(); index++) validate(schema.get("items"), array.get(index), at + "[" + index + "]", problems);
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (schema.has("required")) for (JsonElement name : schema.getAsJsonArray("required"))
                if (!object.has(name.getAsString())) problems.add(at + ": missing " + name.getAsString());
            JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (schema.has("propertyNames")) validate(schema.get("propertyNames"), new JsonPrimitive(entry.getKey()), at + "." + entry.getKey() + "(name)", problems);
                if (properties.has(entry.getKey())) validate(properties.get(entry.getKey()), entry.getValue(), at + "." + entry.getKey(), problems);
                else if (schema.has("additionalProperties")) validate(schema.get("additionalProperties"), entry.getValue(), at + "." + entry.getKey(), problems);
            }
        }
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonElement option : schema.getAsJsonArray("anyOf")) any |= passes(option, value, at);
            if (!any) problems.add(at + ": matches none of anyOf");
        }
        if (schema.has("oneOf")) {
            int matched = 0;
            for (JsonElement option : schema.getAsJsonArray("oneOf")) if (passes(option, value, at)) matched++;
            if (matched != 1) problems.add(at + ": matches " + matched + " of oneOf");
        }
        if (schema.has("not") && passes(schema.get("not"), value, at)) problems.add(at + ": matches not");
        if (schema.has("if") && passes(schema.get("if"), value, at) && schema.has("then")) validate(schema.get("then"), value, at, problems);
    }

    private boolean passes(JsonElement schema, JsonElement value, String at) {
        List<String> nested = new ArrayList<>();
        validate(schema, value, at, nested);
        return nested.isEmpty();
    }

    private static boolean typed(JsonElement type, JsonElement value) {
        if (type.isJsonArray()) {
            for (JsonElement option : type.getAsJsonArray()) if (typed(option, value)) return true;
            return false;
        }
        return switch (type.getAsString()) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "null" -> value.isJsonNull();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && isInteger(value.getAsBigDecimal());
            default -> throw new IllegalStateException("Unknown type " + type);
        };
    }

    private static boolean isInteger(BigDecimal number) { return number.stripTrailingZeros().scale() <= 0; }

    private static boolean equal(JsonElement a, JsonElement b) {
        if (a.isJsonPrimitive() && b.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber())
            return a.getAsBigDecimal().compareTo(b.getAsBigDecimal()) == 0;
        return a.equals(b);
    }
}
