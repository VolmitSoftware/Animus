package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * A boss bar the NPC shows to players near it, from the definition's {@code bossBar}. {@code progress} is a fixed
 * fill from 0 to 1, or the NPC's health when it is {@code health}; a bar with no progress is full. A {@code permission}
 * limits it to players who hold that node.
 *
 * @param title the bar's text; empty shows the NPC's name
 * @param health the bar follows the NPC's health instead of {@code progress}
 */
public record BossBarSettings(String title, String color, String style, Set<String> flags, double range, boolean health, double progress, Optional<String> permission) {
    public static final String FIELD = "bossBar";
    public static final List<String> COLORS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");
    public static final List<String> STYLES = List.of("solid", "segmented_6", "segmented_10", "segmented_12", "segmented_20");
    public static final List<String> FLAGS = List.of("darken_sky", "play_boss_music", "create_fog");
    public static final double DEFAULT_RANGE = 64, MAX_RANGE = 256;
    private static final Set<String> KEYS = Set.of("title", "color", "style", "flags", "range", "progress", "permission");

    public BossBarSettings { flags = Set.copyOf(flags); java.util.Objects.requireNonNull(permission); }

    /** The bar of a definition, empty when it declares none. */
    public static Optional<BossBarSettings> of(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        Optional<BossBarSettings> bar = read(definition, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return bar;
    }

    static Optional<BossBarSettings> read(JsonObject definition, List<String> errors) {
        JsonElement declared = definition.get(FIELD);
        if (declared == null) return Optional.empty();
        if (!declared.isJsonObject()) { errors.add("$.bossBar: expected object"); return Optional.empty(); }
        JsonObject value = declared.getAsJsonObject();
        int before = errors.size();
        for (String key : value.keySet()) if (!KEYS.contains(key)) errors.add("$.bossBar." + key + ": unknown field");
        String title = "";
        if (value.has("title")) {
            JsonElement text = value.get("title");
            if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString() || !NpcAppearance.displayText(text.getAsString()))
                errors.add("$.bossBar.title: expected at most " + NpcAppearance.MAX_TEXT + " characters without control characters");
            else title = text.getAsString();
        }
        String color = choice(value, "color", COLORS, "purple", errors), style = choice(value, "style", STYLES, "solid", errors);
        Set<String> flags = new LinkedHashSet<>();
        if (value.has("flags")) {
            JsonElement list = value.get("flags");
            if (!list.isJsonArray()) errors.add("$.bossBar.flags: expected an array");
            else for (JsonElement flag : list.getAsJsonArray()) {
                if (!flag.isJsonPrimitive() || !FLAGS.contains(flag.getAsString())) { errors.add("$.bossBar.flags: expected darken_sky, play_boss_music or create_fog"); break; }
                flags.add(flag.getAsString());
            }
        }
        double range = DEFAULT_RANGE;
        if (value.has("range")) {
            JsonElement distance = value.get("range");
            if (!distance.isJsonPrimitive() || !distance.getAsJsonPrimitive().isNumber() || !(distance.getAsDouble() >= 1 && distance.getAsDouble() <= MAX_RANGE))
                errors.add("$.bossBar.range: expected 1-" + (int) MAX_RANGE + " blocks");
            else range = distance.getAsDouble();
        }
        boolean health = false;
        double progress = 1;
        if (value.has("progress")) {
            JsonElement fill = value.get("progress");
            if (fill.isJsonPrimitive() && fill.getAsJsonPrimitive().isString() && fill.getAsString().equals("health")) health = true;
            else if (fill.isJsonPrimitive() && fill.getAsJsonPrimitive().isNumber() && fill.getAsDouble() >= 0 && fill.getAsDouble() <= 1) progress = fill.getAsDouble();
            else errors.add("$.bossBar.progress: expected a number from 0 to 1 or health");
        }
        Optional<String> permission = Optional.empty();
        if (value.has("permission")) {
            JsonElement node = value.get("permission");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isString() || !node.getAsString().matches("[A-Za-z0-9_.*-]{1,128}"))
                errors.add("$.bossBar.permission: expected a permission node");
            else permission = Optional.of(node.getAsString());
        }
        if (errors.size() != before) return Optional.empty();
        return Optional.of(new BossBarSettings(title, color, style, flags, range, health, progress, permission));
    }

    /** The fill to show, from the NPC's health and maximum health when the bar follows health. */
    public double fill(OptionalDouble health, double maximum) {
        if (!this.health) return progress;
        if (health.isEmpty() || maximum <= 0) return 1;
        return Math.max(0, Math.min(1, health.getAsDouble() / maximum));
    }

    private static String choice(JsonObject value, String key, List<String> values, String fallback, List<String> errors) {
        JsonElement declared = value.get(key);
        if (declared == null) return fallback;
        if (!declared.isJsonPrimitive() || !values.contains(declared.getAsString())) {
            errors.add("$.bossBar." + key + ": expected " + String.join(", ", values.subList(0, values.size() - 1)) + " or " + values.getLast());
            return fallback;
        }
        return declared.getAsString();
    }
}
