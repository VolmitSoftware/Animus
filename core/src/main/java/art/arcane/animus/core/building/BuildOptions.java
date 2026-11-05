package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How one build is laid out and run. Command words ({@code rotation=90}) and the JSON form in saved build state and in
 * the {@code animus:builder} definition module carry the same fields. {@code scaffolding} lets the NPC put up and climb
 * scaffolding to reach blocks too high to reach from the ground.
 */
public record BuildOptions(Anchor anchor, BlockTransform transform, int offsetX, int offsetY, int offsetZ, Pattern pattern, int layers,
                           boolean groupAll, boolean ignoreAir, boolean ignoreLiquids, boolean excavate, double speed, int placeIntervalTicks,
                           double reach, int moveTimeoutSeconds, boolean holdBlock, boolean silent, Materials materials, Optional<BlockPos> container,
                           boolean scaffolding) {
    public static final BuildOptions DEFAULT = new BuildOptions(Anchor.CENTER, BlockTransform.IDENTITY, 0, 0, 0, Pattern.SPIRAL, 1, false,
            false, false, false, 2, 4, 4.5, 10, true, false, Materials.CREATIVE, Optional.empty(), true);
    public static final int MAXIMUM_OFFSET = 1024;
    private static final Set<String> FIELDS = Set.of("anchor", "rotation", "mirror", "offset", "pattern", "layers", "groupAll", "ignoreAir",
            "ignoreLiquids", "excavate", "speed", "placeIntervalTicks", "reach", "moveTimeoutSeconds", "holdBlock", "silent", "materials", "container",
            "scaffolding");

    /**
     * Where the schematic sits against the build origin: {@code center} puts the middle of its footprint on the origin
     * and its lowest filled layer at the origin's height, {@code corner} puts its minimum corner there, and
     * {@code schematic} uses the offset saved in the file.
     */
    public enum Anchor { CENTER, CORNER, SCHEMATIC }
    /** Order of columns within a layer: from the outside in, from the inside out, row by row, or rows backwards. */
    public enum Pattern {
        SPIRAL("spiral"), REVERSE_SPIRAL("reversespiral"), LINEAR("linear"), REVERSE_LINEAR("reverselinear");
        private final String word;
        Pattern(String word) { this.word = word; }
        public String word() { return word; }
        public static Pattern named(String text) {
            String lowered = text.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            for (Pattern pattern : values()) if (pattern.word.equals(lowered)) return pattern;
            throw new IllegalArgumentException("Pattern must be spiral, reversespiral, linear or reverselinear");
        }
    }
    /** Free blocks, items players hand over, or items taken from one linked container. */
    public enum Materials { CREATIVE, DONATE, CONTAINER }

    public BuildOptions {
        Objects.requireNonNull(anchor); Objects.requireNonNull(transform); Objects.requireNonNull(pattern);
        Objects.requireNonNull(materials); Objects.requireNonNull(container);
        offset("offset.x", offsetX); offset("offset.y", offsetY); offset("offset.z", offsetZ);
        range("layers", layers, 1, 256);
        if (!Double.isFinite(speed) || speed < 1 || speed > 5) throw new IllegalArgumentException("speed must be within 1..5 blocks per second");
        range("placeIntervalTicks", placeIntervalTicks, 1, 200);
        if (!Double.isFinite(reach) || reach < 2 || reach > 6) throw new IllegalArgumentException("reach must be within 2..6 blocks");
        range("moveTimeoutSeconds", moveTimeoutSeconds, 1, 300);
        if (materials == Materials.CONTAINER && container.isEmpty()) throw new IllegalArgumentException("materials=container needs container=<x>,<y>,<z>");
        if (materials != Materials.CONTAINER && container.isPresent()) throw new IllegalArgumentException("container is used only with materials=container");
    }

    /** Applies {@code key=value} words over these options. Unknown keys and bad values are refused with the reason. */
    public BuildOptions with(Map<String, String> words) {
        Map<String, String> remaining = new LinkedHashMap<>(words);
        Anchor nextAnchor = remaining.containsKey("anchor") ? named(Anchor.class, remaining.remove("anchor"), "anchor", "center, corner or schematic") : anchor;
        BlockTransform.Rotation rotation = remaining.containsKey("rotation") ? BlockTransform.Rotation.degrees(integer("rotation", remaining.remove("rotation"))) : transform.rotation();
        BlockTransform.Mirror mirror = remaining.containsKey("mirror") ? BlockTransform.Mirror.named(remaining.remove("mirror")) : transform.mirror();
        int[] offset = remaining.containsKey("offset") ? triple("offset", remaining.remove("offset")) : new int[]{offsetX, offsetY, offsetZ};
        if (remaining.containsKey("yoffset")) offset[1] = integer("yoffset", remaining.remove("yoffset"));
        Pattern nextPattern = remaining.containsKey("pattern") ? Pattern.named(remaining.remove("pattern")) : pattern;
        int nextLayers = remaining.containsKey("layers") ? integer("layers", remaining.remove("layers")) : layers;
        boolean nextGroupAll = remaining.containsKey("groupall") ? bool("groupall", remaining.remove("groupall")) : groupAll;
        boolean nextIgnoreAir = remaining.containsKey("ignoreair") ? bool("ignoreair", remaining.remove("ignoreair")) : ignoreAir;
        boolean nextIgnoreLiquids = remaining.containsKey("ignoreliquids") ? bool("ignoreliquids", remaining.remove("ignoreliquids")) : ignoreLiquids;
        boolean nextExcavate = remaining.containsKey("excavate") ? bool("excavate", remaining.remove("excavate")) : excavate;
        double nextSpeed = remaining.containsKey("speed") ? decimal("speed", remaining.remove("speed")) : speed;
        int nextInterval = remaining.containsKey("interval") ? integer("interval", remaining.remove("interval")) : placeIntervalTicks;
        double nextReach = remaining.containsKey("reach") ? decimal("reach", remaining.remove("reach")) : reach;
        int nextTimeout = remaining.containsKey("timeout") ? integer("timeout", remaining.remove("timeout")) : moveTimeoutSeconds;
        boolean nextHold = remaining.containsKey("hold") ? bool("hold", remaining.remove("hold")) : holdBlock;
        boolean nextSilent = remaining.containsKey("silent") ? bool("silent", remaining.remove("silent")) : silent;
        Materials nextMaterials = remaining.containsKey("materials") ? named(Materials.class, remaining.remove("materials"), "materials", "creative, donate or container") : materials;
        Optional<BlockPos> nextContainer = remaining.containsKey("container") ? Optional.of(position("container", remaining.remove("container"))) : container;
        if (nextMaterials != Materials.CONTAINER) nextContainer = Optional.empty();
        boolean nextScaffolding = remaining.containsKey("scaffolding") ? bool("scaffolding", remaining.remove("scaffolding")) : scaffolding;
        if (!remaining.isEmpty())
            throw new IllegalArgumentException("Unknown build option " + remaining.keySet().iterator().next() + "; use " + String.join(", ", words()));
        return new BuildOptions(nextAnchor, new BlockTransform(rotation, mirror), offset[0], offset[1], offset[2], nextPattern, nextLayers, nextGroupAll,
                nextIgnoreAir, nextIgnoreLiquids, nextExcavate, nextSpeed, nextInterval, nextReach, nextTimeout, nextHold, nextSilent, nextMaterials, nextContainer,
                nextScaffolding);
    }

    /** Command option words, for usage and completion. */
    public static List<String> words() {
        return List.of("anchor", "rotation", "mirror", "offset", "yoffset", "pattern", "layers", "groupall", "ignoreair", "ignoreliquids", "excavate",
                "speed", "interval", "reach", "timeout", "hold", "silent", "materials", "container", "scaffolding");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("anchor", anchor.name().toLowerCase(Locale.ROOT));
        json.addProperty("rotation", transform.rotation().degrees());
        json.addProperty("mirror", transform.mirror().name().toLowerCase(Locale.ROOT));
        JsonObject offset = new JsonObject();
        offset.addProperty("x", offsetX); offset.addProperty("y", offsetY); offset.addProperty("z", offsetZ);
        json.add("offset", offset);
        json.addProperty("pattern", pattern.word());
        json.addProperty("layers", layers);
        json.addProperty("groupAll", groupAll);
        json.addProperty("ignoreAir", ignoreAir);
        json.addProperty("ignoreLiquids", ignoreLiquids);
        json.addProperty("excavate", excavate);
        json.addProperty("speed", speed);
        json.addProperty("placeIntervalTicks", placeIntervalTicks);
        json.addProperty("reach", reach);
        json.addProperty("moveTimeoutSeconds", moveTimeoutSeconds);
        json.addProperty("holdBlock", holdBlock);
        json.addProperty("silent", silent);
        json.addProperty("materials", materials.name().toLowerCase(Locale.ROOT));
        container.ifPresent((BlockPos at) -> json.add("container", point(at)));
        json.addProperty("scaffolding", scaffolding);
        return json;
    }

    /** Reads the JSON form over the defaults; missing fields keep their default. */
    public static BuildOptions fromJson(JsonObject json, String path) {
        for (String key : json.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown build option");
        try {
            Anchor anchor = json.has("anchor") ? named(Anchor.class, text(json, "anchor"), "anchor", "center, corner or schematic") : DEFAULT.anchor;
            BlockTransform.Rotation rotation = json.has("rotation") ? BlockTransform.Rotation.degrees(whole(json, "rotation")) : BlockTransform.Rotation.NONE;
            BlockTransform.Mirror mirror = json.has("mirror") ? BlockTransform.Mirror.named(text(json, "mirror")) : BlockTransform.Mirror.NONE;
            int[] offset = {0, 0, 0};
            if (json.has("offset")) {
                BlockPos at = point(object(json, "offset"), "offset");
                offset = new int[]{at.x(), at.y(), at.z()};
            }
            Materials materials = json.has("materials") ? named(Materials.class, text(json, "materials"), "materials", "creative, donate or container") : DEFAULT.materials;
            Optional<BlockPos> container = json.has("container") ? Optional.of(point(object(json, "container"), "container")) : Optional.empty();
            return new BuildOptions(anchor, new BlockTransform(rotation, mirror), offset[0], offset[1], offset[2],
                    json.has("pattern") ? Pattern.named(text(json, "pattern")) : DEFAULT.pattern,
                    json.has("layers") ? whole(json, "layers") : DEFAULT.layers,
                    flag(json, "groupAll", DEFAULT.groupAll), flag(json, "ignoreAir", DEFAULT.ignoreAir), flag(json, "ignoreLiquids", DEFAULT.ignoreLiquids),
                    flag(json, "excavate", DEFAULT.excavate), json.has("speed") ? number(json, "speed") : DEFAULT.speed,
                    json.has("placeIntervalTicks") ? whole(json, "placeIntervalTicks") : DEFAULT.placeIntervalTicks,
                    json.has("reach") ? number(json, "reach") : DEFAULT.reach,
                    json.has("moveTimeoutSeconds") ? whole(json, "moveTimeoutSeconds") : DEFAULT.moveTimeoutSeconds,
                    flag(json, "holdBlock", DEFAULT.holdBlock), flag(json, "silent", DEFAULT.silent), materials, container,
                    flag(json, "scaffolding", DEFAULT.scaffolding));
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException invalid) {
            throw new IllegalArgumentException(path + ": " + invalid.getMessage(), invalid);
        }
    }

    public static JsonObject point(BlockPos at) {
        JsonObject json = new JsonObject();
        json.addProperty("x", at.x()); json.addProperty("y", at.y()); json.addProperty("z", at.z());
        return json;
    }
    public static BlockPos point(JsonObject json, String path) {
        for (String key : json.keySet()) if (!Set.of("x", "y", "z").contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        return new BlockPos(whole(json, "x"), whole(json, "y"), whole(json, "z"));
    }

    private static void offset(String field, int value) {
        if (Math.abs(value) > MAXIMUM_OFFSET) throw new IllegalArgumentException(field + " must be within -" + MAXIMUM_OFFSET + ".." + MAXIMUM_OFFSET);
    }
    private static void range(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
    private static <E extends Enum<E>> E named(Class<E> type, String text, String field, String expected) {
        try { return Enum.valueOf(type, text.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field + " must be " + expected); }
    }
    private static int integer(String field, String text) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(field + " must be a whole number"); }
    }
    private static double decimal(String field, String text) {
        try { return Double.parseDouble(text); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(field + " must be a number"); }
    }
    private static boolean bool(String field, String text) {
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(field + " must be true or false");
    }
    private static int[] triple(String field, String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException(field + " must be <x>,<y>,<z>");
        return new int[]{integer(field, parts[0]), integer(field, parts[1]), integer(field, parts[2])};
    }
    private static BlockPos position(String field, String text) {
        int[] parts = triple(field, text);
        return new BlockPos(parts[0], parts[1], parts[2]);
    }
    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }
    private static int whole(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a whole number");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(key + " must be a whole number"); }
    }
    private static double number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a number");
        return value.getAsDouble();
    }
    private static boolean flag(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be true or false");
        return value.getAsBoolean();
    }
    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (!value.isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return value.getAsJsonObject();
    }
}
