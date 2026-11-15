package art.arcane.animus.core.api;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.ItemSpec;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Turns a public {@link NpcSpec} into definition JSON. Pure: no files, no runtime. */
final class NpcSpecs {
    /** Fields only Animus writes. */
    static final Set<String> OWNED = Set.of("uuid", "revision", "schemaVersion");

    /** The JSON a spec produced, and every problem found while producing it. */
    record Built(JsonObject json, List<Diagnostic> problems) {
        Built { problems = List.copyOf(problems); }
        boolean valid() { return problems.isEmpty(); }
    }

    private NpcSpecs() {}

    /**
     * A new definition: Animus's defaults, then the raw fields, then the typed parts. {@code skin} is the skin file hash
     * the spec's skin resolved to. {@code worldName} turns a world UUID into its name, as definition files store it.
     */
    static Built create(NpcSpec spec, String id, UUID uuid, boolean spawnedByDefault, Optional<String> skin, Function<String, String> worldName) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", NpcDefinition.SCHEMA_VERSION);
        json.addProperty("revision", 1);
        json.addProperty("uuid", uuid.toString());
        json.addProperty("id", id);
        json.addProperty("group", "");
        json.addProperty("name", id);
        json.addProperty("type", "minecraft:player");
        json.addProperty("spawned", spawnedByDefault);
        List<Diagnostic> problems = new ArrayList<>();
        if (spec.location().isEmpty()) problems.add(error("location-missing", "$.position", "A new NPC needs a location"));
        lay(json, spec, skin, worldName, problems);
        if (spec.id().isPresent() && !spec.id().get().equals(id)) problems.add(error("identity-change", "$.id", "The id is " + id));
        json.addProperty("id", id);
        json.addProperty("uuid", uuid.toString());
        return checked(json, problems);
    }

    /** {@code current} with the parts the spec sets. The id and folder stay; the revision stays for the writer to raise. */
    static Built patch(JsonObject current, NpcSpec spec, Optional<String> skin, Function<String, String> worldName) {
        JsonObject json = current.deepCopy();
        List<Diagnostic> problems = new ArrayList<>();
        String id = current.get("id").getAsString(), group = current.get("group").getAsString();
        lay(json, spec, skin, worldName, problems);
        if (spec.id().isPresent() && !spec.id().get().equals(id) || !json.get("id").getAsString().equals(id) || !json.get("group").getAsString().equals(group))
            problems.add(error("identity-change", "$.id", "An edit keeps the id and folder; create a new NPC instead"));
        for (String owned : OWNED) json.add(owned, current.get(owned));
        return checked(json, problems);
    }

    /** {@code current} with one module's data replaced, or removed for an empty value. */
    static Built module(JsonObject current, String key, Optional<String> data) {
        JsonObject json = current.deepCopy();
        List<Diagnostic> problems = new ArrayList<>();
        setModule(json, key, data, problems);
        return checked(json, problems);
    }

    /** Field diagnostics from the definition parser's {@code $.field: message} lines. */
    static List<Diagnostic> diagnostics(List<String> errors) {
        return errors.stream().limit(64).map((String line) -> {
            int separator = line.indexOf(": ");
            String field = separator < 0 ? "$" : line.substring(0, separator);
            return error("definition-invalid", bounded(field, 240), bounded(separator < 0 ? line : line.substring(separator + 2), 1000));
        }).toList();
    }

    static Diagnostic error(String code, String field, String message) { return new Diagnostic(Diagnostic.Severity.ERROR, code, field, message); }

    private static void lay(JsonObject json, NpcSpec spec, Optional<String> skin, Function<String, String> worldName, List<Diagnostic> problems) {
        spec.json().ifPresent((String raw) -> raw(json, raw, problems));
        spec.group().ifPresent((String group) -> json.addProperty("group", group));
        spec.type().ifPresent((String type) -> json.addProperty("type", MinecraftIds.valid(type) ? MinecraftIds.normalize(type) : type));
        spec.name().ifPresent((String name) -> json.addProperty("name", name));
        spec.spawned().ifPresent((Boolean spawned) -> json.addProperty("spawned", spawned));
        spec.location().ifPresent((NpcLocation location) -> json.add("position", position(location, worldName)));
        skin.ifPresent((String hash) -> json.addProperty("skin", hash));
        spec.pose().ifPresent((NpcSpec.Pose pose) -> {
            if (pose == NpcSpec.Pose.STANDING) json.remove("pose"); else json.addProperty("pose", pose.name().toLowerCase(Locale.ROOT));
        });
        if (!spec.equipment().isEmpty()) {
            JsonObject equipment = json.has("equipment") && json.get("equipment").isJsonObject() ? json.getAsJsonObject("equipment") : new JsonObject();
            for (Map.Entry<NpcSpec.Slot, Optional<ItemSpec>> slot : spec.equipment().entrySet()) {
                String key = slot.getKey().name().toLowerCase(Locale.ROOT);
                if (slot.getValue().isEmpty()) equipment.remove(key); else equipment.add(key, item(slot.getValue().get()));
            }
            if (equipment.isEmpty()) json.remove("equipment"); else json.add("equipment", equipment);
        }
        for (Map.Entry<String, Optional<String>> module : spec.modules().entrySet()) setModule(json, module.getKey(), module.getValue(), problems);
    }

    /** One item as a definition stores it. */
    static JsonObject item(ItemSpec item) {
        JsonObject json = new JsonObject();
        json.addProperty("id", MinecraftIds.valid(item.id()) ? MinecraftIds.normalize(item.id()) : item.id());
        if (item.count() != 1) json.addProperty("count", item.count());
        item.components().ifPresent((String components) -> json.addProperty("components", components));
        return json;
    }

    static JsonObject position(NpcLocation location, Function<String, String> worldName) {
        JsonObject position = new JsonObject();
        position.addProperty("world", uuid(location.world()) ? worldName.apply(location.world()) : location.world());
        position.addProperty("x", location.x()); position.addProperty("y", location.y()); position.addProperty("z", location.z());
        position.addProperty("yaw", location.yaw()); position.addProperty("pitch", location.pitch());
        return position;
    }

    private static boolean uuid(String text) {
        try { return UUID.fromString(text).toString().equalsIgnoreCase(text); }
        catch (IllegalArgumentException name) { return false; }
    }

    private static void raw(JsonObject json, String raw, List<Diagnostic> problems) {
        JsonObject fields;
        try { fields = JsonDocuments.object(raw); }
        catch (IOException invalid) { problems.add(error("json-invalid", "$", "Raw fields must be one JSON object")); return; }
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            if (OWNED.contains(field.getKey())) { problems.add(error("field-owned", "$." + field.getKey(), "Animus sets " + field.getKey() + " itself")); continue; }
            if (field.getValue() instanceof JsonNull) json.remove(field.getKey()); else json.add(field.getKey(), field.getValue().deepCopy());
        }
    }

    private static void setModule(JsonObject json, String key, Optional<String> data, List<Diagnostic> problems) {
        if (!key.matches("[a-z0-9][a-z0-9_-]{0,63}:[a-z0-9][a-z0-9_/-]{0,63}")) {
            problems.add(error("module-key", "$.modules", "Module keys are namespace:name in lowercase letters, digits, _ or -, such as demo:clicks"));
            return;
        }
        JsonObject modules = json.has("modules") && json.get("modules").isJsonObject() ? json.getAsJsonObject("modules") : new JsonObject();
        if (data.isEmpty()) modules.remove(key);
        else {
            try { modules.add(key, JsonDocuments.object(data.get())); }
            catch (IOException invalid) { problems.add(error("module-json", "$.modules." + key, "Module data must be one JSON object")); return; }
        }
        if (modules.isEmpty()) json.remove("modules"); else json.add("modules", modules);
    }

    private static Built checked(JsonObject json, List<Diagnostic> problems) {
        List<Diagnostic> all = new ArrayList<>(problems);
        if (all.isEmpty()) all.addAll(diagnostics(NpcDefinition.validate(json)));
        return new Built(json, all);
    }

    private static String bounded(String text, int codePoints) {
        return new String(text.codePoints().limit(codePoints).toArray(), 0, (int) Math.min(text.codePointCount(0, text.length()), codePoints));
    }
}
