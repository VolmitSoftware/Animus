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
 * A native module names either another NPC ({@code vehicle}) or a real server entity ({@code entity}).
 */
public final class MountDeclaration {
    public static final String MODULE = "animus:mount";
    public static final double DEFAULT_RANGE = 8, MAXIMUM_RANGE = 32;
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "vehicle", "entity", "range");
    private static final java.util.regex.Pattern UUID_FORM = java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final java.util.regex.Pattern TYPE_FORM = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * A real server entity an NPC rides: the one with {@code uuid}, or the nearest {@code type} within {@code range} blocks
     * of the NPC's own position. Exactly one of {@code uuid} and {@code type} is set.
     */
    public record EntityMount(String field, UUID uuid, String type, double range) {
        public EntityMount {
            java.util.Objects.requireNonNull(field, "field");
            if ((uuid == null) == (type == null)) throw new IllegalArgumentException("An entity mount names a UUID or a type");
        }
        /** How {@code /animus inspect} and the menu name the vehicle. */
        public String describe() { return uuid != null ? "entity " + uuid : "nearest " + type + " within " + (range == Math.rint(range) ? String.valueOf((int) range) : String.valueOf(range)); }
    }
    private MountDeclaration() {}

    /** @throws IllegalArgumentException when the native module is malformed, naming its exact field */
    public static Optional<MountReference> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules != null && modules.isJsonObject() && modules.getAsJsonObject().has(MODULE))
            return parseNative(modules.getAsJsonObject().get(MODULE));
        return parseImported(document);
    }

    /** The real entity a native module names; empty for an NPC vehicle, a disabled module or none. */
    public static Optional<EntityMount> entity(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonObject module = checked(modules.getAsJsonObject().get(MODULE));
        if (module == null || !module.has("entity")) return Optional.empty();
        String entity = module.get("entity").getAsString();
        double range = module.has("range") ? module.get("range").getAsDouble() : DEFAULT_RANGE;
        return Optional.of(UUID_FORM.matcher(entity).matches()
                ? new EntityMount(FIELD + ".entity", UUID.fromString(entity), null, range)
                : new EntityMount(FIELD + ".entity", null, entity, range));
    }

    private static Optional<MountReference> parseNative(JsonElement value) {
        JsonObject module = checked(value);
        if (module == null || module.has("entity")) return Optional.empty();
        JsonElement vehicle = module.get("vehicle");
        if (vehicle == null || !vehicle.isJsonPrimitive() || !vehicle.getAsJsonPrimitive().isString()
                || !vehicle.getAsString().matches("[a-z0-9][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException(FIELD + ".vehicle: expected an NPC id");
        String id = vehicle.getAsString();
        return Optional.of(new MountReference(FIELD + ".vehicle", id, null, MountReference.Status.DECLARED, id));
    }

    /** The module once its fields are valid; null when it is turned off. */
    private static JsonObject checked(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet())
            if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        if (module.has("enabled")) {
            JsonElement enabled = module.get("enabled");
            if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException(FIELD + ".enabled: expected boolean");
        }
        if (module.has("vehicle") && module.has("entity")) throw new IllegalArgumentException(FIELD + ".entity: name a vehicle NPC or a real entity, not both");
        if (module.has("entity")) {
            JsonElement entity = module.get("entity");
            if (!entity.isJsonPrimitive() || !entity.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException(FIELD + ".entity: expected an entity UUID or an entity type such as minecraft:horse");
            String text = entity.getAsString();
            boolean uuid = UUID_FORM.matcher(text).matches();
            if (!uuid && !TYPE_FORM.matcher(text).matches())
                throw new IllegalArgumentException(FIELD + ".entity: expected an entity UUID or an entity type such as minecraft:horse");
            if (text.equals("minecraft:player")) throw new IllegalArgumentException(FIELD + ".entity: NPCs do not ride players");
            if (module.has("range")) {
                if (uuid) throw new IllegalArgumentException(FIELD + ".range: only an entity type is searched within a range");
                JsonElement range = module.get("range");
                if (!range.isJsonPrimitive() || !range.getAsJsonPrimitive().isNumber() || !Double.isFinite(range.getAsDouble())
                        || range.getAsDouble() < 1 || range.getAsDouble() > MAXIMUM_RANGE)
                    throw new IllegalArgumentException(FIELD + ".range: expected 1-" + (int) MAXIMUM_RANGE + " blocks");
            }
        } else if (module.has("range")) throw new IllegalArgumentException(FIELD + ".range: only an entity type is searched within a range");
        if (module.has("enabled") && !module.get("enabled").getAsBoolean()) return null;
        return module;
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
