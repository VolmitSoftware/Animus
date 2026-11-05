package art.arcane.animus.core.behavior.schema;

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
 * An independent evaluator for the JSON Schema 2020-12 keywords the generator emits, so the agreement tests do not
 * trust the engine they are checking. An unrecognized assertion keyword fails rather than silently passing.
 */
final class JsonSchemaCheck {
    private static final Set<String> ANNOTATIONS = Set.of("$schema", "$comment", "title", "description", "examples", "default");
    private final JsonObject root;
    JsonSchemaCheck(JsonObject root) { this.root = root; }

    List<String> validate(JsonElement instance) {
        List<String> errors = new ArrayList<>();
        check(root, instance, "$", errors);
        return errors;
    }

    private void check(JsonElement schema, JsonElement value, String path, List<String> errors) {
        if (schema.isJsonPrimitive()) {
            if (!schema.getAsBoolean()) errors.add(path + ": prohibited");
            return;
        }
        JsonObject object = schema.getAsJsonObject();
        for (Map.Entry<String, JsonElement> keyword : object.entrySet()) {
            String name = keyword.getKey();
            JsonElement argument = keyword.getValue();
            if (ANNOTATIONS.contains(name)) continue;
            switch (name) {
                case "$defs", "properties", "if", "then", "else" -> { }
                case "$ref" -> check(resolve(argument.getAsString()), value, path, errors);
                case "type" -> {
                    boolean matched = false;
                    if (argument.isJsonArray()) { for (JsonElement type : argument.getAsJsonArray()) matched |= hasType(value, type.getAsString()); }
                    else matched = hasType(value, argument.getAsString());
                    if (!matched) errors.add(path + ": expected type " + argument);
                }
                case "const" -> { if (!same(argument, value)) errors.add(path + ": expected " + argument); }
                case "enum" -> {
                    boolean found = false;
                    for (JsonElement option : argument.getAsJsonArray()) found |= same(option, value);
                    if (!found) errors.add(path + ": not one of " + argument);
                }
                case "pattern" -> { if (text(value) && !Pattern.compile(argument.getAsString()).matcher(value.getAsString()).find()) errors.add(path + ": does not match " + argument); }
                case "minLength" -> { if (text(value) && length(value) < argument.getAsInt()) errors.add(path + ": too short"); }
                case "maxLength" -> { if (text(value) && length(value) > argument.getAsInt()) errors.add(path + ": too long"); }
                case "minimum" -> { if (number(value) && decimal(value).compareTo(argument.getAsBigDecimal()) < 0) errors.add(path + ": below minimum"); }
                case "maximum" -> { if (number(value) && decimal(value).compareTo(argument.getAsBigDecimal()) > 0) errors.add(path + ": above maximum"); }
                case "exclusiveMinimum" -> { if (number(value) && decimal(value).compareTo(argument.getAsBigDecimal()) <= 0) errors.add(path + ": not above minimum"); }
                case "exclusiveMaximum" -> { if (number(value) && decimal(value).compareTo(argument.getAsBigDecimal()) >= 0) errors.add(path + ": not below maximum"); }
                case "minItems" -> { if (value.isJsonArray() && value.getAsJsonArray().size() < argument.getAsInt()) errors.add(path + ": too few items"); }
                case "maxItems" -> { if (value.isJsonArray() && value.getAsJsonArray().size() > argument.getAsInt()) errors.add(path + ": too many items"); }
                case "uniqueItems" -> { if (argument.getAsBoolean() && value.isJsonArray() && new java.util.HashSet<>(value.getAsJsonArray().asList()).size() < value.getAsJsonArray().size()) errors.add(path + ": duplicate items"); }
                case "minProperties" -> { if (value.isJsonObject() && value.getAsJsonObject().size() < argument.getAsInt()) errors.add(path + ": too few properties"); }
                case "maxProperties" -> { if (value.isJsonObject() && value.getAsJsonObject().size() > argument.getAsInt()) errors.add(path + ": too many properties"); }
                case "required" -> {
                    if (value.isJsonObject()) for (JsonElement key : argument.getAsJsonArray())
                        if (!value.getAsJsonObject().has(key.getAsString())) errors.add(path + "." + key.getAsString() + ": required");
                }
                case "propertyNames" -> {
                    if (value.isJsonObject()) for (String key : value.getAsJsonObject().keySet()) check(argument, new JsonPrimitive(key), path + "." + key + "(name)", errors);
                }
                case "additionalProperties" -> {
                    if (value.isJsonObject()) {
                        JsonObject declared = object.has("properties") ? object.getAsJsonObject("properties") : new JsonObject();
                        for (Map.Entry<String, JsonElement> property : value.getAsJsonObject().entrySet())
                            if (!declared.has(property.getKey())) check(argument, property.getValue(), path + "." + property.getKey(), errors);
                    }
                }
                case "items" -> {
                    if (value.isJsonArray()) {
                        JsonArray array = value.getAsJsonArray();
                        for (int index = 0; index < array.size(); index++) check(argument, array.get(index), path + "[" + index + "]", errors);
                    }
                }
                case "allOf" -> { for (JsonElement option : argument.getAsJsonArray()) check(option, value, path, errors); }
                case "anyOf" -> { if (matches(argument.getAsJsonArray(), value) < 1) errors.add(path + ": no alternative matched"); }
                case "oneOf" -> { if (matches(argument.getAsJsonArray(), value) != 1) errors.add(path + ": expected exactly one alternative"); }
                case "not" -> { if (valid(argument, value)) errors.add(path + ": matches a prohibited schema"); }
                default -> throw new IllegalArgumentException("Generated schema uses an unchecked keyword: " + name);
            }
        }
        if (object.has("properties") && value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> property : object.getAsJsonObject("properties").entrySet()) {
                JsonElement child = value.getAsJsonObject().get(property.getKey());
                if (child != null) check(property.getValue(), child, path + "." + property.getKey(), errors);
            }
        }
        if (object.has("if")) {
            String branch = valid(object.get("if"), value) ? "then" : "else";
            if (object.has(branch)) check(object.get(branch), value, path, errors);
        }
    }

    private JsonElement resolve(String reference) {
        if (!reference.startsWith("#/")) throw new IllegalArgumentException("Generated schema must only use local references: " + reference);
        JsonElement current = root;
        for (String part : reference.substring(2).split("/")) current = current.getAsJsonObject().get(part.replace("~1", "/").replace("~0", "~"));
        if (current == null) throw new IllegalArgumentException("Dangling reference: " + reference);
        return current;
    }
    private boolean valid(JsonElement schema, JsonElement value) {
        List<String> errors = new ArrayList<>();
        check(schema, value, "$", errors);
        return errors.isEmpty();
    }
    private int matches(JsonArray options, JsonElement value) {
        int count = 0;
        for (JsonElement option : options) if (valid(option, value)) count++;
        return count;
    }
    private static boolean text(JsonElement value) { return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(); }
    private static boolean number(JsonElement value) { return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(); }
    private static BigDecimal decimal(JsonElement value) { return value.getAsBigDecimal(); }
    private static int length(JsonElement value) { String text = value.getAsString(); return text.codePointCount(0, text.length()); }
    private static boolean hasType(JsonElement value, String type) {
        return switch (type) {
            case "null" -> value.isJsonNull();
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> text(value);
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> number(value);
            case "integer" -> number(value) && decimal(value).stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }
    private static boolean same(JsonElement left, JsonElement right) {
        if (number(left) && number(right)) return decimal(left).compareTo(decimal(right)) == 0;
        return left.equals(right);
    }
}
