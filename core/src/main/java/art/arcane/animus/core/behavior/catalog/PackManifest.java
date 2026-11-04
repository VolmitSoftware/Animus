package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.core.behavior.schema.Suggestions;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code packs/<pack>/pack.json}. The manifest is read, never rewritten, so configuration for a module that is not
 * installed stays in place. A required module that is missing makes the pack's behaviors unavailable, not invalid.
 */
public record PackManifest(String id, Optional<String> name, Optional<String> version, Optional<String> description, Map<String, Module> modules) {
    public static final List<String> FIELDS = List.of("schemaVersion", "id", "name", "version", "description", "modules");
    public static final List<String> MODULE_FIELDS = List.of("required", "configuration");
    public static final int MAXIMUM_MODULES = 64, MAXIMUM_NAME = 128, MAXIMUM_VERSION = 64, MAXIMUM_DESCRIPTION = 1024;
    /** {@code configuration} is the module's own JSON object, kept as written. */
    public record Module(boolean required, Optional<String> configuration) {
        public Module { Objects.requireNonNull(configuration); }
    }
    public PackManifest {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(version); Objects.requireNonNull(description);
        modules = Collections.unmodifiableMap(new TreeMap<>(modules));
    }

    /** @throws IllegalArgumentException naming the exact field, such as {@code $.modules.mod.required} */
    public static PackManifest parse(String text, String directory) {
        JsonObject object;
        try { object = JsonDocuments.object(text); }
        catch (IOException | RuntimeException invalid) { throw new IllegalArgumentException("$: expected one strict JSON object with unique keys"); }
        for (String key : object.keySet())
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("$." + key + ": unsupported field; allowed: " + Suggestions.list(FIELDS) + Suggestions.didYouMean(key, FIELDS));
        JsonElement schema = object.get("schemaVersion");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber() || schema.getAsBigDecimal().compareTo(BigDecimal.ONE) != 0)
            throw new IllegalArgumentException("$.schemaVersion: expected 1");
        String id = text(object, "id", 64).orElseThrow(() -> new IllegalArgumentException("$.id: expected the pack directory name"));
        if (!id.equals(directory)) throw new IllegalArgumentException("$.id: expected " + directory + ", the name of this pack's directory");
        try { BehaviorPath.packId(id); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("$.id: " + invalid.getMessage()); }
        Map<String, Module> modules = new TreeMap<>();
        if (object.has("modules")) {
            JsonElement raw = object.get("modules");
            if (!raw.isJsonObject()) throw new IllegalArgumentException("$.modules: expected object");
            if (raw.getAsJsonObject().size() > MAXIMUM_MODULES) throw new IllegalArgumentException("$.modules: at most " + MAXIMUM_MODULES + " modules");
            for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
                String field = "$.modules." + entry.getKey();
                if (!entry.getKey().matches("[a-z0-9][a-z0-9_-]{0,63}") || entry.getKey().equals("animus"))
                    throw new IllegalArgumentException(field + ": expected a module identifier other than animus");
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException(field + ": expected object");
                JsonObject module = entry.getValue().getAsJsonObject();
                for (String key : module.keySet())
                    if (!MODULE_FIELDS.contains(key)) throw new IllegalArgumentException(field + "." + key + ": unsupported field; allowed: " + Suggestions.list(MODULE_FIELDS) + Suggestions.didYouMean(key, MODULE_FIELDS));
                boolean required = true;
                if (module.has("required")) {
                    JsonElement value = module.get("required");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(field + ".required: expected boolean");
                    required = value.getAsBoolean();
                }
                Optional<String> configuration = Optional.empty();
                if (module.has("configuration")) {
                    if (!module.get("configuration").isJsonObject()) throw new IllegalArgumentException(field + ".configuration: expected object");
                    configuration = Optional.of(module.get("configuration").toString());
                }
                modules.put(entry.getKey(), new Module(required, configuration));
            }
        }
        return new PackManifest(id, text(object, "name", MAXIMUM_NAME), text(object, "version", MAXIMUM_VERSION), text(object, "description", MAXIMUM_DESCRIPTION), modules);
    }

    private static Optional<String> text(JsonObject object, String key, int maximum) {
        if (!object.has(key)) return Optional.empty();
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank() || value.getAsString().length() > maximum)
            throw new IllegalArgumentException("$." + key + ": expected nonblank text within " + maximum + " characters");
        return Optional.of(value.getAsString());
    }
}
