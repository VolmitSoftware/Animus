package art.arcane.animus.core.npc;

import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named places in a definition's {@code anchors}: {@code {"dock": {"x": 3, "y": 64, "z": 3, "world": "harbor"}}}. Without
 * {@code world} an anchor is in the NPC's own world. Behaviors walk or teleport the NPC to one by name.
 */
public record NpcAnchors(Map<String, Anchor> anchors) {
    public static final NpcAnchors NONE = new NpcAnchors(Map.of());
    public static final int MAXIMUM_ANCHORS = 64;
    public static final String FIELD = "anchors";
    public static final String NAME = NamedPoses.NAME;
    public static final String WORLD = "[^\\s/\\\\]{1,64}";

    /** A position, in the named world or, when empty, the NPC's own. */
    public record Anchor(Vec3 position, Optional<String> world) {}

    public NpcAnchors { anchors = Map.copyOf(anchors); }

    public Optional<Anchor> find(String name) { return Optional.ofNullable(anchors.get(name)); }

    /** @throws IllegalArgumentException naming every malformed field */
    public static NpcAnchors parse(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        NpcAnchors anchors = read(definition, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return anchors;
    }

    /** Reads {@code anchors}, appending each malformed field to {@code errors}. Keeps insertion order. */
    public static NpcAnchors read(JsonObject definition, List<String> errors) {
        JsonElement value = definition.get(FIELD);
        if (value == null) return NONE;
        if (!value.isJsonObject()) { errors.add("$.anchors: expected object"); return NONE; }
        JsonObject anchors = value.getAsJsonObject();
        if (anchors.size() > MAXIMUM_ANCHORS) { errors.add("$.anchors: expected at most " + MAXIMUM_ANCHORS + " anchors"); return NONE; }
        Map<String, Anchor> read = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : anchors.entrySet()) {
            String path = "$.anchors." + entry.getKey();
            if (!entry.getKey().matches(NAME)) { errors.add(path + ": expected a name of 1-32 lowercase letters, digits, _ or -"); continue; }
            if (!entry.getValue().isJsonObject()) { errors.add(path + ": expected object"); continue; }
            JsonObject anchor = entry.getValue().getAsJsonObject();
            boolean valid = true;
            for (String key : anchor.keySet()) if (!Set.of("x", "y", "z", "world").contains(key)) { errors.add(path + "." + key + ": unknown field"); valid = false; }
            double[] axes = new double[3];
            List<String> names = List.of("x", "y", "z");
            for (int i = 0; i < 3; i++) {
                JsonElement axis = anchor.get(names.get(i));
                if (axis == null || !axis.isJsonPrimitive() || !axis.getAsJsonPrimitive().isNumber() || !Double.isFinite(axis.getAsDouble()) || Math.abs(axis.getAsDouble()) > 29_999_984) {
                    errors.add(path + "." + names.get(i) + ": expected a number within the world"); valid = false;
                } else axes[i] = axis.getAsDouble();
            }
            Optional<String> world = Optional.empty();
            if (anchor.has("world")) {
                JsonElement name = anchor.get("world");
                if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || !name.getAsString().matches(WORLD)) { errors.add(path + ".world: expected a world name"); valid = false; }
                else world = Optional.of(name.getAsString());
            }
            if (valid) read.put(entry.getKey(), new Anchor(new Vec3(axes[0], axes[1], axes[2]), world));
        }
        return new NpcAnchors(read);
    }
}
