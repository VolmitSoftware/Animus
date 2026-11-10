package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A trait group, {@code groups/<id>.json}: definition values that NPCs listing the group take before their own. A group
 * cannot reference another group. Values pass the same field validators an NPC uses, so a bad value fails the group file.
 */
public final class GroupDocument {
    public static final String ID = "[a-z0-9][a-z0-9_-]{0,63}";
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SKINS = 64;
    /** The group format this build writes and reads; older files are upgraded by the storage migrations at startup. */
    public static final int SCHEMA_VERSION = 2;
    /** Identity and state fields a group may not set. */
    public static final Set<String> REFUSED = Set.of("schemaVersion", "revision", "uuid", "id", "group", "name", "type", "spawned", "position", "owner", "migration", "groups");

    public record Result(Optional<GroupDocument> group, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    private final String id;
    private final long revision;
    private final String description;
    private final JsonObject values;

    private GroupDocument(String id, long revision, String description, JsonObject values) {
        this.id = id;
        this.revision = revision;
        this.description = description;
        this.values = values.deepCopy();
    }

    public static Result parse(String id, String json) {
        JsonObject object;
        try { object = JsonDocuments.object(json); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
        List<String> problems = new ArrayList<>();
        JsonElement version = object.get("schemaVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsDouble() != SCHEMA_VERSION) problems.add("$.schemaVersion: expected " + SCHEMA_VERSION);
        long revision = 0;
        JsonElement revisionValue = object.get("revision");
        try {
            if (revisionValue == null || !revisionValue.isJsonPrimitive() || !revisionValue.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            revision = revisionValue.getAsBigDecimal().longValueExact();
            if (revision < 1) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException invalid) { problems.add("$.revision: expected integer of at least 1"); }
        JsonElement idValue = object.get("id");
        if (idValue == null || !idValue.isJsonPrimitive() || !idValue.getAsJsonPrimitive().isString() || !idValue.getAsString().matches(ID))
            problems.add("$.id: expected 1-64 lowercase letters, digits, _ or -");
        else if (!idValue.getAsString().equals(id)) problems.add("$.id: the group id is " + idValue.getAsString() + " but the file is " + id);
        String description = "";
        if (object.has("description")) {
            JsonElement value = object.get("description");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !NpcAppearance.displayText(value.getAsString()))
                problems.add("$.description: expected at most " + NpcAppearance.MAX_TEXT + " characters without control characters");
            else description = value.getAsString();
        }
        for (String key : object.keySet())
            if (!Set.of("schemaVersion", "revision", "id", "description", "values").contains(key)) problems.add("$." + key + ": unknown field");
        JsonElement valuesElement = object.get("values");
        JsonObject values = new JsonObject();
        if (valuesElement == null || !valuesElement.isJsonObject()) problems.add("$.values: expected object");
        else {
            values = valuesElement.getAsJsonObject();
            problems.addAll(validateValues(values));
        }
        if (!problems.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new GroupDocument(id, revision, description, values)), List.of());
    }

    /** Field errors of a group's values, named under {@code $.values}. */
    static List<String> validateValues(JsonObject values) {
        List<String> problems = new ArrayList<>();
        JsonObject probe = new JsonObject();
        probe.addProperty("schemaVersion", NpcDefinition.SCHEMA_VERSION);
        probe.addProperty("revision", 1);
        probe.addProperty("uuid", "00000000-0000-4000-8000-000000000000");
        probe.addProperty("id", "group-probe");
        probe.addProperty("group", "groups");
        probe.addProperty("name", "Group");
        // Settings of one body type are checked against that body; every other field accepts the player body.
        probe.addProperty("type", values.has("armorStand") ? "minecraft:armor_stand" : BodyVariant.FIELDS.values().stream()
                .filter((BodyVariant.Field field) -> values.has(field.field())).map(BodyVariant.Field::type).findFirst().orElse("minecraft:player"));
        probe.addProperty("spawned", false);
        JsonObject position = new JsonObject();
        position.addProperty("world", "world");
        for (String axis : List.of("x", "y", "z", "yaw", "pitch")) position.addProperty(axis, 0);
        probe.add("position", position);
        for (String key : values.keySet()) {
            if (REFUSED.contains(key)) { problems.add("$.values." + key + ": not allowed in a group"); continue; }
            if (key.equals("skins")) { skins(values.get(key), problems); continue; }
            probe.add(key, values.get(key).deepCopy());
        }
        for (String error : NpcDefinition.validate(probe))
            problems.add(error.startsWith("$.") ? "$.values." + error.substring(2) : "$.values: " + error);
        return problems;
    }

    private static void skins(JsonElement value, List<String> problems) {
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAX_SKINS) {
            problems.add("$.values.skins: expected an array of 1-" + MAX_SKINS + " skin hashes");
            return;
        }
        Set<String> seen = new HashSet<>();
        JsonArray skins = value.getAsJsonArray();
        for (int i = 0; i < skins.size(); i++) {
            JsonElement skin = skins.get(i);
            if (!skin.isJsonPrimitive() || !skin.getAsJsonPrimitive().isString() || !skin.getAsString().matches("[a-f0-9]{64}"))
                problems.add("$.values.skins[" + i + "]: expected SHA-256 reference");
            else if (!seen.add(skin.getAsString())) problems.add("$.values.skins[" + i + "]: duplicate skin");
        }
    }

    public String id() { return id; }
    public long revision() { return revision; }
    public String description() { return description; }
    public String path() { return "groups/" + id + ".json"; }
    public JsonObject values() { return values.deepCopy(); }
    JsonObject valuesView() { return values; }
}
