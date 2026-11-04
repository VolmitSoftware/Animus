package art.arcane.animus.core.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Armor stand parts and pose angles in degrees. Undeclared angles keep the client's defaults. */
public record ArmorStandLook(boolean small, boolean arms, boolean basePlate, boolean invisible, Map<Part, Angles> angles) {
    public enum Part {
        HEAD("head"), BODY("body"), LEFT_ARM("leftArm"), RIGHT_ARM("rightArm"), LEFT_LEG("leftLeg"), RIGHT_LEG("rightLeg");
        private final String key;
        Part(String key) { this.key = key; }
        public String key() { return key; }
    }
    public record Angles(float x, float y, float z) {}
    private static final Set<String> FLAGS = Set.of("small", "arms", "basePlate", "invisible");

    public ArmorStandLook { angles = angles.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(angles)); }

    static Optional<ArmorStandLook> parse(JsonElement value, List<String> errors) {
        if (!value.isJsonObject()) { errors.add("$.armorStand: expected object"); return Optional.empty(); }
        JsonObject look = value.getAsJsonObject();
        int before = errors.size();
        for (String key : look.keySet()) {
            boolean part = false;
            for (Part candidate : Part.values()) part |= candidate.key.equals(key);
            if (!part && !FLAGS.contains(key)) errors.add("$.armorStand." + key + ": unknown field");
        }
        boolean small = flag(look, "small", false, errors), arms = flag(look, "arms", false, errors);
        boolean basePlate = flag(look, "basePlate", true, errors), invisible = flag(look, "invisible", false, errors);
        Map<Part, Angles> angles = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            if (!look.has(part.key)) continue;
            Optional<Angles> parsed = angles(look.get(part.key));
            if (parsed.isEmpty()) errors.add("$.armorStand." + part.key + ": expected [x, y, z] degrees within -360..360");
            else angles.put(part, parsed.get());
        }
        if (errors.size() != before) return Optional.empty();
        return Optional.of(new ArmorStandLook(small, arms, basePlate, invisible, angles));
    }

    private static boolean flag(JsonObject look, String key, boolean fallback, List<String> errors) {
        JsonElement value = look.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { errors.add("$.armorStand." + key + ": expected boolean"); return fallback; }
        return value.getAsBoolean();
    }

    private static Optional<Angles> angles(JsonElement value) {
        if (!value.isJsonArray()) return Optional.empty();
        JsonArray values = value.getAsJsonArray();
        if (values.size() != 3) return Optional.empty();
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            JsonElement angle = values.get(i);
            if (!angle.isJsonPrimitive() || !angle.getAsJsonPrimitive().isNumber()) return Optional.empty();
            double degrees = angle.getAsDouble();
            if (!Double.isFinite(degrees) || Math.abs(degrees) > 360) return Optional.empty();
            result[i] = (float) degrees;
        }
        return Optional.of(new Angles(result[0], result[1], result[2]));
    }
}
