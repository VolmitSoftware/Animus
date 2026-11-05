package art.arcane.animus.core.rig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Field readers for rig and animation documents. Each one adds an exact field problem instead of throwing. */
final class RigFields {
    static final String DOCUMENT_ID = "[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}";
    static final String BONE_ID = "[a-z0-9][a-z0-9_-]{0,63}";
    static final int MAX_BYTES = 256 * 1024;

    private RigFields() {}

    static void keys(JsonObject object, Set<String> allowed, String path, List<String> problems) {
        for (String key : object.keySet()) if (!allowed.contains(key)) problems.add(path + "." + key + ": unknown field");
    }
    static boolean schemaVersion(JsonObject object, List<String> problems) {
        JsonElement version = object.get("schemaVersion");
        if (number(version) && version.getAsBigDecimal().compareTo(BigDecimal.ONE) == 0) return true;
        problems.add("$.schemaVersion: expected 1");
        return false;
    }
    static long revision(JsonObject object, List<String> problems) {
        JsonElement revision = object.get("revision");
        try {
            if (number(revision)) {
                long value = revision.getAsBigDecimal().longValueExact();
                if (value >= 1) return value;
            }
        } catch (ArithmeticException ignored) { /* Reported below. */ }
        problems.add("$.revision: expected integer of at least 1");
        return 0;
    }
    static Optional<String> documentId(JsonObject object, String key, String path, List<String> problems) {
        Optional<String> value = string(object, key, path, problems);
        if (value.isPresent() && (value.get().length() > 256 || !value.get().matches(DOCUMENT_ID))) {
            problems.add(path + "." + key + ": expected namespace:path with lowercase letters, digits, _, - and /");
            return Optional.empty();
        }
        return value;
    }
    static Optional<String> boneId(JsonElement element, String path, List<String> problems) {
        if (element == null || !string(element) || !element.getAsString().matches(BONE_ID)) {
            problems.add(path + ": expected a bone id of 1-64 lowercase letters, digits, _ or -");
            return Optional.empty();
        }
        return Optional.of(element.getAsString());
    }
    static Optional<String> string(JsonObject object, String key, String path, List<String> problems) {
        JsonElement value = object.get(key);
        if (!string(value)) { problems.add(path + "." + key + ": expected string"); return Optional.empty(); }
        return Optional.of(value.getAsString());
    }
    static Optional<String> choice(JsonObject object, String key, String path, Set<String> values, List<String> problems) {
        JsonElement value = object.get(key);
        if (!string(value) || !values.contains(value.getAsString())) {
            problems.add(path + "." + key + ": expected one of " + String.join(", ", values.stream().sorted().toList()));
            return Optional.empty();
        }
        return Optional.of(value.getAsString());
    }
    static double[] vector(JsonElement element, String path, double limit, List<String> problems) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            problems.add(path + ": expected [x, y, z]");
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement value = array.get(i);
            if (!number(value) || !Double.isFinite(value.getAsDouble()) || Math.abs(value.getAsDouble()) > limit) {
                problems.add(path + "[" + i + "]: expected a number within " + (int) -limit + ".." + (int) limit);
                return null;
            }
            result[i] = value.getAsDouble();
        }
        return result;
    }
    static double[] vector(JsonObject object, String key, String path, double limit, double[] fallback, List<String> problems) {
        if (!object.has(key)) return fallback.clone();
        double[] value = vector(object.get(key), path + "." + key, limit, problems);
        return value == null ? fallback.clone() : value;
    }
    static boolean number(JsonElement element) { return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(); }
    static boolean string(JsonElement element) { return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(); }
    static boolean bool(JsonElement element) { return element != null && element.isJsonPrimitive() && ((JsonPrimitive) element).isBoolean(); }
    static int integer(JsonElement element, int minimum, int maximum, String path, List<String> problems, int fallback) {
        try {
            if (number(element)) {
                int value = element.getAsBigDecimal().intValueExact();
                if (value >= minimum && value <= maximum) return value;
            }
        } catch (ArithmeticException ignored) { /* Reported below. */ }
        problems.add(path + ": expected integer " + minimum + ".." + maximum);
        return fallback;
    }
}
