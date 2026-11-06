package art.arcane.animus.core.npc;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.navigation.WaypointSettings;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Immutable persisted definition. Unknown extension data is deliberately retained. */
public final class NpcDefinition {
    /** The most labels one NPC may carry. */
    public static final int MAX_TAGS = 32;
    /** The most trait groups one NPC may list. */
    public static final int MAX_GROUPS = 16;
    private final JsonObject data;
    private final boolean spawned, imported;
    private final List<String> behaviors, tags, groups;
    private NpcDefinition(JsonObject data) {
        this.data = data.deepCopy();
        spawned = data.get("spawned").getAsBoolean();
        imported = data.has("migration");
        List<String> references = new ArrayList<>();
        if (data.has("behaviors")) for (JsonElement reference : data.getAsJsonArray("behaviors")) references.add(reference.getAsString());
        behaviors = List.copyOf(references);
        List<String> labels = new ArrayList<>();
        if (data.has("tags")) for (JsonElement tag : data.getAsJsonArray("tags")) labels.add(tag.getAsString());
        tags = List.copyOf(labels);
        List<String> members = new ArrayList<>();
        if (data.has("groups")) for (JsonElement group : data.getAsJsonArray("groups")) members.add(group.getAsString());
        groups = List.copyOf(members);
    }

    /** A definition from an object that has already passed {@link #validate}. */
    static NpcDefinition validated(JsonObject object) throws IOException {
        List<String> errors = validate(object);
        if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
        return new NpcDefinition(object);
    }

    public static NpcDefinition parse(String json) throws IOException {
        JsonObject object = JsonDocuments.object(json);
        List<String> errors = validate(object);
        if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
        return new NpcDefinition(object);
    }

    /**
     * The definition stored at {@code path}. The file name must be its id; the folder it sits in is its group, so a file
     * the owner moved to another folder keeps working and its {@code group} follows on the next save.
     */
    public static NpcDefinition read(String path, String json) throws IOException {
        if (!NpcPaths.file(path)) throw new IOException("$.id/group: " + path + " is not npcs/<folders>/<id>.json with at most " + NpcPaths.MAX_FOLDERS + " folders");
        NpcDefinition definition = parse(json);
        if (!definition.id().equals(NpcPaths.idOf(path))) throw new IOException("$.id: must match the file name " + NpcPaths.idOf(path) + ".json");
        String folder = NpcPaths.folderOf(path);
        if (definition.group().equals(folder)) return definition;
        JsonObject moved = definition.json();
        moved.addProperty("group", folder);
        return new NpcDefinition(moved);
    }

    public static List<String> validate(JsonObject object) {
        List<String> errors = new ArrayList<>();
        integer(object, "schemaVersion", 1, 1, errors);
        integer(object, "revision", 1, Long.MAX_VALUE, errors);
        String uuid = string(object, "uuid", errors);
        try { if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { errors.add("$.uuid: expected canonical UUID"); }
        if (!string(object, "id", errors).matches(NpcPaths.SEGMENT))
            errors.add("$.id: expected 1–64 lowercase letters, digits, _ or -");
        if (!string(object, "group", errors).matches(NpcPaths.FOLDER))
            errors.add("$.group: expected the folder under npcs/: empty, or up to " + NpcPaths.MAX_FOLDERS + " names of 1–64 lowercase letters, digits, _ or - joined by /");
        if (!NpcAppearance.displayText(string(object, "name", errors)))
            errors.add("$.name: expected at most " + NpcAppearance.MAX_TEXT + " characters without control characters");
        if (!string(object, "type", errors).matches("[a-z0-9_]+:[a-z0-9_/]+"))
            errors.add("$.type: expected namespace:type");
        if (!object.has("spawned") || !object.get("spawned").isJsonPrimitive() || !object.getAsJsonPrimitive("spawned").isBoolean())
            errors.add("$.spawned: expected boolean");
        if (!object.has("position") || !object.get("position").isJsonObject()) errors.add("$.position: expected object");
        else {
            JsonObject position = object.getAsJsonObject("position");
            List<String> positionErrors = new ArrayList<>();
            string(position, "world", positionErrors);
            for (String key : List.of("x", "y", "z", "yaw", "pitch")) {
                JsonElement value = position.get(key);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                        || !Double.isFinite(value.getAsDouble()) || Math.abs(value.getAsDouble()) > 30_000_000)
                    positionErrors.add("$." + key + ": expected finite coordinate within ±30000000");
            }
            positionErrors.forEach(e -> errors.add("$.position" + e.substring(1)));
        }
        if (object.has("skin") && (!object.get("skin").isJsonPrimitive() || !object.getAsJsonPrimitive("skin").isString()
                || !object.get("skin").getAsString().matches("[a-f0-9]{64}"))) errors.add("$.skin: expected SHA-256 reference");
        if (object.has("behaviors")) {
            if (!object.get("behaviors").isJsonArray()) errors.add("$.behaviors: expected array");
            else for (JsonElement ref : object.getAsJsonArray("behaviors")) {
                if (!ref.isJsonPrimitive() || !ref.getAsJsonPrimitive().isString() || !ref.getAsString().matches("[a-z0-9_-]+:[a-z0-9_/-]+"))
                    errors.add("$.behaviors: expected namespace:id references");
            }
        }
        if (object.has("tags")) {
            if (!object.get("tags").isJsonArray()) errors.add("$.tags: expected array");
            else if (object.getAsJsonArray("tags").size() > MAX_TAGS) errors.add("$.tags: expected at most " + MAX_TAGS + " tags");
            else {
                Set<String> seen = new HashSet<>();
                JsonArray tags = object.getAsJsonArray("tags");
                for (int i = 0; i < tags.size(); i++) {
                    JsonElement tag = tags.get(i);
                    if (!tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString() || !tag.getAsString().matches("[a-z0-9][a-z0-9_-]{0,31}"))
                        errors.add("$.tags[" + i + "]: expected 1–32 lowercase letters, digits, _ or -");
                    else if (!seen.add(tag.getAsString())) errors.add("$.tags[" + i + "]: duplicate tag");
                }
            }
        }
        if (object.has("groups")) {
            JsonElement value = object.get("groups");
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAX_GROUPS)
                errors.add("$.groups: expected an array of 1-" + MAX_GROUPS + " group ids");
            else {
                Set<String> seen = new HashSet<>();
                JsonArray groups = value.getAsJsonArray();
                for (int i = 0; i < groups.size(); i++) {
                    JsonElement group = groups.get(i);
                    if (!group.isJsonPrimitive() || !group.getAsJsonPrimitive().isString() || !group.getAsString().matches(GroupDocument.ID))
                        errors.add("$.groups[" + i + "]: expected 1-64 lowercase letters, digits, _ or -");
                    else if (!seen.add(group.getAsString())) errors.add("$.groups[" + i + "]: duplicate group");
                }
            }
        }
        if (object.has("owner")) {
            JsonElement owner = object.get("owner");
            String text = owner.isJsonPrimitive() && owner.getAsJsonPrimitive().isString() ? owner.getAsString() : "";
            try { if (!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { errors.add("$.owner: expected canonical player UUID"); }
        }
        errors.addAll(NpcAppearance.validate(object));
        errors.addAll(NpcInventory.validate(object));
        art.arcane.animus.core.navigation.MovementSettings.read(object, errors);
        NamedPoses.read(object, errors);
        if (object.has("modules") && !object.get("modules").isJsonObject()) errors.add("$.modules: expected object");
        else {
            try { WaypointSettings.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { CombatSettings.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { MountDeclaration.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { RideSettings.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { art.arcane.animus.core.control.LookClose.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { art.arcane.animus.core.shop.ShopBinding.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { art.arcane.animus.core.rig.RigReference.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { art.arcane.animus.core.gui.GuiBinding.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
            try { art.arcane.animus.core.hire.HireTerms.parse(object); }
            catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
        }
        return List.copyOf(errors);
    }

    private static String string(JsonObject data, String key, List<String> errors) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            errors.add("$." + key + ": expected nonblank string"); return "";
        }
        return value.getAsString();
    }

    private static void integer(JsonObject object, String key, long min, long max, List<String> errors) {
        try {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long n = value.getAsBigDecimal().longValueExact();
            if (n < min || n > max) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException e) { errors.add("$." + key + ": expected integer " + min + ".." + max); }
    }

    public UUID uuid() { return UUID.fromString(data.get("uuid").getAsString()); }
    public String id() { return data.get("id").getAsString(); }
    public String group() { return data.get("group").getAsString(); }
    public String name() { return data.get("name").getAsString(); }
    /** The owning player, when one is recorded. Plugins and modules may use it for their own permission rules. */
    public Optional<UUID> owner() { return data.has("owner") ? Optional.of(UUID.fromString(data.get("owner").getAsString())) : Optional.empty(); }
    public long revision() { return data.get("revision").getAsLong(); }
    public String path() { return NpcPaths.path(group(), id()); }
    /** The id with its folder, {@code world/town/blacksmith}, or the bare id for a file directly in {@code npcs/}. */
    public String qualifiedId() { return group().isEmpty() ? id() : group() + "/" + id(); }
    public boolean spawned() { return spawned; }
    /** True for a definition installed by an import, which keeps its {@code migration} record. */
    public boolean imported() { return imported; }
    /** Behavior references attached to this NPC. Their trigger bindings run once the NPC is active. */
    public List<String> behaviors() { return behaviors; }
    /** Labels the editor uses to find and group NPCs; empty when none are declared. */
    public List<String> tags() { return tags; }
    /** Trait groups whose values this NPC takes, in merge order; empty when none are listed. */
    public List<String> groups() { return groups; }
    public JsonObject json() { return data.deepCopy(); }
    public String serialize() { return JsonDocuments.write(data); }
}
