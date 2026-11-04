package art.arcane.animus.core.mount;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the mount relation of a persisted definition. A native {@code modules["animus:mount"]} object wins;
 * otherwise the first imported {@code migration.references} record of kind {@code mount} applies. Imported
 * data never fails a definition: anything unusable stays preserved and is reported by {@link MountRelations}.
 */
public final class MountDeclaration {
    public static final String MODULE = "animus:mount";
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "vehicle");
    private MountDeclaration() {}

    /** @throws IllegalArgumentException when the native module is malformed, naming its exact field */
    public static Optional<MountReference> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules != null && modules.isJsonObject() && modules.getAsJsonObject().has(MODULE))
            return parseNative(modules.getAsJsonObject().get(MODULE));
        return parseImported(document);
    }

    private static Optional<MountReference> parseNative(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet())
            if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        if (module.has("enabled")) {
            JsonElement enabled = module.get("enabled");
            if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException(FIELD + ".enabled: expected boolean");
            if (!enabled.getAsBoolean()) return Optional.empty();
        }
        JsonElement vehicle = module.get("vehicle");
        if (vehicle == null || !vehicle.isJsonPrimitive() || !vehicle.getAsJsonPrimitive().isString()
                || !vehicle.getAsString().matches("[a-z0-9][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException(FIELD + ".vehicle: expected an NPC id");
        String id = vehicle.getAsString();
        return Optional.of(new MountReference(FIELD + ".vehicle", id, null, MountReference.Status.DECLARED, id));
    }

    private static Optional<MountReference> parseImported(JsonObject document) {
        JsonElement migration = document.get("migration");
        if (migration == null || !migration.isJsonObject()) return Optional.empty();
        JsonElement references = migration.getAsJsonObject().get("references");
        if (references == null || !references.isJsonArray()) return Optional.empty();
        JsonArray array = references.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject reference = element.getAsJsonObject();
            if (!"mount".equals(text(reference, "kind"))) continue;
            String field = "$.migration.references[" + index + "]";
            String status = text(reference, "status");
            if ("none".equals(status)) return Optional.empty();
            JsonElement rawOriginal = reference.get("original");
            String original = rawOriginal == null ? "" : rawOriginal.isJsonPrimitive() ? rawOriginal.getAsString() : rawOriginal.toString();
            return Optional.of(imported(field, status, reference, original));
        }
        return Optional.empty();
    }

    private static MountReference imported(String field, String status, JsonObject reference, String original) {
        if ("missing".equals(status)) return new MountReference(field, null, null, MountReference.Status.MISSING, original);
        if ("ambiguous".equals(status)) return new MountReference(field, null, null, MountReference.Status.AMBIGUOUS, original);
        if ("resolved".equals(status)) {
            String id = text(reference, "npcId"), uuid = text(reference, "npcUuid");
            if (id != null && uuid != null) {
                try { return new MountReference(field, id, UUID.fromString(uuid), MountReference.Status.DECLARED, original); }
                catch (IllegalArgumentException invalid) { return new MountReference(field, null, null, MountReference.Status.INVALID, original); }
            }
        }
        return new MountReference(field, null, null, MountReference.Status.INVALID, original);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
