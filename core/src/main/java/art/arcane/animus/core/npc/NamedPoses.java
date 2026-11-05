package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named look directions in a definition's {@code poses}: {@code {"rest": {"yaw": 90, "pitch": 0}}}. The behavior action
 * {@code animus:pose} turns the NPC to one by name. They only change where the NPC looks, never its body pose.
 */
public record NamedPoses(Map<String, Facing> poses) {
    public static final NamedPoses NONE = new NamedPoses(Map.of());
    public static final int MAXIMUM_POSES = 64;
    public static final String NAME = "[a-z0-9][a-z0-9_-]{0,31}";

    /** Yaw in degrees within -360..360, pitch within -90..90. */
    public record Facing(float yaw, float pitch) {}

    public NamedPoses { poses = Map.copyOf(poses); }

    public Optional<Facing> find(String name) { return Optional.ofNullable(poses.get(name)); }

    /** @throws IllegalArgumentException naming every malformed field */
    public static NamedPoses parse(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        NamedPoses poses = read(definition, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return poses;
    }

    /** Reads {@code poses}, appending each malformed field to {@code errors}. */
    public static NamedPoses read(JsonObject definition, List<String> errors) {
        JsonElement value = definition.get("poses");
        if (value == null) return NONE;
        if (!value.isJsonObject()) { errors.add("$.poses: expected object"); return NONE; }
        JsonObject poses = value.getAsJsonObject();
        if (poses.size() > MAXIMUM_POSES) { errors.add("$.poses: expected at most " + MAXIMUM_POSES + " poses"); return NONE; }
        Map<String, Facing> read = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : poses.entrySet()) {
            String path = "$.poses." + entry.getKey();
            if (!entry.getKey().matches(NAME)) { errors.add(path + ": expected a name of 1-32 lowercase letters, digits, _ or -"); continue; }
            if (!entry.getValue().isJsonObject()) { errors.add(path + ": expected object"); continue; }
            JsonObject pose = entry.getValue().getAsJsonObject();
            for (String key : pose.keySet()) if (!Set.of("yaw", "pitch").contains(key)) errors.add(path + "." + key + ": unknown field");
            Float yaw = angle(pose, "yaw", 360, path, errors), pitch = angle(pose, "pitch", 90, path, errors);
            if (yaw != null && pitch != null) read.put(entry.getKey(), new Facing(yaw, pitch));
        }
        return new NamedPoses(read);
    }

    private static Float angle(JsonObject pose, String key, double limit, String path, List<String> errors) {
        JsonElement value = pose.get(key);
        if (value == null && key.equals("pitch")) return 0f;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())
                || Math.abs(value.getAsDouble()) > limit) {
            errors.add(path + "." + key + ": expected -" + (int) limit + ".." + (int) limit + " degrees");
            return null;
        }
        return value.getAsFloat();
    }
}
