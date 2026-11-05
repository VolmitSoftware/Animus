package art.arcane.animus.core.npc;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.navigation.WaypointSettings;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Immutable persisted definition. Unknown extension data is deliberately retained. */
public final class NpcDefinition {
    private final JsonObject data;
    private final boolean spawned, imported;
    private final List<String> behaviors;
    private NpcDefinition(JsonObject data) {
        this.data = data.deepCopy();
        spawned = data.get("spawned").getAsBoolean();
        imported = data.has("migration");
        List<String> references = new ArrayList<>();
        if (data.has("behaviors")) for (JsonElement reference : data.getAsJsonArray("behaviors")) references.add(reference.getAsString());
        behaviors = List.copyOf(references);
    }

    public static NpcDefinition parse(String json) throws IOException {
        JsonObject object = JsonDocuments.object(json);
        List<String> errors = validate(object);
        if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
        return new NpcDefinition(object);
    }

    public static List<String> validate(JsonObject object) {
        List<String> errors = new ArrayList<>();
        integer(object, "schemaVersion", 1, 1, errors);
        integer(object, "revision", 1, Long.MAX_VALUE, errors);
        String uuid = string(object, "uuid", errors);
        try { if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { errors.add("$.uuid: expected canonical UUID"); }
        for (String key : List.of("id", "group")) {
            if (!string(object, key, errors).matches("[a-z0-9][a-z0-9_-]{0,63}"))
                errors.add("$." + key + ": expected 1–64 lowercase letters, digits, _ or -");
        }
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
        if (object.has("owner")) {
            JsonElement owner = object.get("owner");
            String text = owner.isJsonPrimitive() && owner.getAsJsonPrimitive().isString() ? owner.getAsString() : "";
            try { if (!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { errors.add("$.owner: expected canonical player UUID"); }
        }
        errors.addAll(NpcAppearance.validate(object));
        errors.addAll(NpcInventory.validate(object));
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
    public String path() { return "npcs/" + group() + "/" + id() + ".json"; }
    public boolean spawned() { return spawned; }
    /** True for a definition installed by an import, which keeps its {@code migration} record. */
    public boolean imported() { return imported; }
    /** Behavior references attached to this NPC. Their trigger bindings run once the NPC is active. */
    public List<String> behaviors() { return behaviors; }
    public JsonObject json() { return data.deepCopy(); }
    public String serialize() { return JsonDocuments.write(data); }
}
