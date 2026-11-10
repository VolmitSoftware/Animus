package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * How a definition's hologram lines are drawn. Without a line height or margins every line shares one text display;
 * with either, each line gets its own display, stacked from the bottom as Citizens stacks them. {@code margins} has
 * one entry per hologram line, top line first.
 */
public record HologramStyle(String billboard, boolean shadowed, boolean seeThrough, OptionalDouble viewRange, OptionalDouble lineHeight,
                            int interpolationDelay, int interpolationDuration, List<Margin> margins) {
    public static final Set<String> BILLBOARDS = Set.of("center", "fixed", "vertical", "horizontal");
    public static final double MAX_LINE_HEIGHT = 4, MAX_MARGIN = 4, MIN_VIEW_RANGE = 1, MAX_VIEW_RANGE = 512;
    public static final int MAX_INTERPOLATION = 1200;
    /** The spacing of rows that have their own displays when no line height is given, the height of one text line. */
    public static final double DEFAULT_LINE_HEIGHT = 0.25;
    public static final HologramStyle DEFAULT = new HologramStyle("center", false, false, OptionalDouble.empty(), OptionalDouble.empty(), 0, 0, List.of());
    static final Set<String> FIELDS = Set.of("lines", "lineHeight", "viewRange", "billboard", "shadowed", "seeThrough", "interpolationDelay", "interpolationDuration");
    private static final Set<String> LINE_FIELDS = Set.of("text", "marginTop", "marginBottom");

    /** Extra space in blocks above and below one line. */
    public record Margin(double top, double bottom) {
        public static final Margin NONE = new Margin(0, 0);
    }

    public HologramStyle {
        Objects.requireNonNull(billboard); Objects.requireNonNull(viewRange); Objects.requireNonNull(lineHeight);
        margins = List.copyOf(margins);
    }

    /** This style with other line margins, one per line. */
    public HologramStyle withMargins(List<Margin> next) {
        return new HologramStyle(billboard, shadowed, seeThrough, viewRange, lineHeight, interpolationDelay, interpolationDuration, next);
    }

    /** True when the lines need one display each: a line height or any margin is set. */
    public boolean separateRows() { return lineHeight.isPresent() || margins.stream().anyMatch((Margin margin) -> !margin.equals(Margin.NONE)); }

    /** The text of one {@code lines} entry: a string, or an object with {@code text} and margins. Appends exact errors. */
    static Optional<String> line(JsonElement value, String path, List<Margin> margins, List<String> errors) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) { margins.add(Margin.NONE); return Optional.of(value.getAsString()); }
        if (!value.isJsonObject()) { errors.add(path + ": expected string or object with text"); return Optional.empty(); }
        JsonObject line = value.getAsJsonObject();
        for (String key : line.keySet()) if (!LINE_FIELDS.contains(key)) errors.add(path + "." + key + ": unknown field");
        JsonElement text = line.get("text");
        if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) { errors.add(path + ".text: expected string"); return Optional.empty(); }
        double top = decimal(line, "marginTop", path, -MAX_MARGIN, MAX_MARGIN, "blocks", errors).orElse(0);
        double bottom = decimal(line, "marginBottom", path, -MAX_MARGIN, MAX_MARGIN, "blocks", errors).orElse(0);
        margins.add(new Margin(top, bottom));
        return Optional.of(text.getAsString());
    }

    /** The style fields of a {@code hologram} object; {@code margins} come from its lines. */
    static HologramStyle read(JsonObject hologram, List<Margin> margins, List<String> errors) {
        OptionalDouble lineHeight = decimal(hologram, "lineHeight", "$.hologram", 0, MAX_LINE_HEIGHT, "blocks", errors);
        OptionalDouble viewRange = decimal(hologram, "viewRange", "$.hologram", MIN_VIEW_RANGE, MAX_VIEW_RANGE, "blocks", errors);
        String billboard = "center";
        if (hologram.has("billboard")) {
            JsonElement value = hologram.get("billboard");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !BILLBOARDS.contains(value.getAsString()))
                errors.add("$.hologram.billboard: expected center, fixed, vertical or horizontal");
            else billboard = value.getAsString();
        }
        boolean shadowed = flag(hologram, "shadowed", errors), seeThrough = flag(hologram, "seeThrough", errors);
        int delay = ticks(hologram, "interpolationDelay", errors), duration = ticks(hologram, "interpolationDuration", errors);
        return new HologramStyle(billboard, shadowed, seeThrough, viewRange, lineHeight, delay, duration, margins);
    }

    private static boolean flag(JsonObject object, String key, List<String> errors) {
        JsonElement value = object.get(key);
        if (value == null) return false;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { errors.add("$.hologram." + key + ": expected boolean"); return false; }
        return value.getAsBoolean();
    }

    private static int ticks(JsonObject object, String key, List<String> errors) {
        JsonElement value = object.get(key);
        if (value == null) return 0;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsDouble() != Math.rint(value.getAsDouble())
                || value.getAsDouble() < 0 || value.getAsDouble() > MAX_INTERPOLATION) {
            errors.add("$.hologram." + key + ": expected 0.." + MAX_INTERPOLATION + " ticks");
            return 0;
        }
        return value.getAsInt();
    }

    private static OptionalDouble decimal(JsonObject object, String key, String path, double minimum, double maximum, String unit, List<String> errors) {
        JsonElement value = object.get(key);
        if (value == null) return OptionalDouble.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < minimum || value.getAsDouble() > maximum) {
            errors.add(path + "." + key + ": expected " + format(minimum) + ".." + format(maximum) + " " + unit);
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value.getAsDouble());
    }

    private static String format(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }
}
