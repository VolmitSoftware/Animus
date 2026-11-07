package art.arcane.animus.core.gui;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.presentation.DisplayEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Display styles, icons and vectors shared by holograms and holographic menus, read with Gloss's field meanings. */
public final class HoloParts {
    private HoloParts() {}

    /** Gloss's {@code iconDisplayStyle}. {@code glow} is -1 or RGB; {@code background} is ARGB. */
    public record Style(DisplayEntity.Billboard billboard, boolean shadow, boolean seeThrough, DisplayEntity.Alignment alignment, int background,
                        int opacity, int lineWidth, int brightness, float viewRange, float shadowRadius, float shadowStrength, int glow,
                        float scaleX, float scaleY, float scaleZ) {
        /** Gloss's menu icon defaults. */
        public static final Style MENU = new Style(DisplayEntity.Billboard.FIXED, false, false, DisplayEntity.Alignment.CENTER, 0, 255, 16384, -1, 1, 0, 0, -1, 1, 1, 1);
        /** Gloss's hologram defaults: facing the viewer and seen through blocks. */
        public static final Style HOLOGRAM = new Style(DisplayEntity.Billboard.CENTER, false, true, DisplayEntity.Alignment.CENTER, 0, 255, 16384, -1, 1, 0, 0, -1, 1, 1, 1);

        /** Display metadata with an extra uniform scale, a translation and the client move interpolation. */
        public DisplayEntity.Style display(float scale, float translateX, float translateY, float translateZ, int moveTicks) {
            return new DisplayEntity.Style(billboard, scaleX * scale, scaleY * scale, scaleZ * scale, translateX, translateY, translateZ,
                    brightness, viewRange, shadowRadius, shadowStrength, glow, moveTicks);
        }
        public DisplayEntity.Text text(String coloured) {
            return new DisplayEntity.Text(coloured, background, lineWidth, opacity, shadow, seeThrough, alignment);
        }
    }

    static final Set<String> STYLE_FIELDS = Set.of("billboard", "shadow", "seeThrough", "textAlignment", "backgroundArgb", "textOpacity", "lineWidth",
            "blockLight", "skyLight", "viewRange", "shadowRadius", "shadowStrength", "cullingWidth", "cullingHeight", "glowColor", "scaleX", "scaleY", "scaleZ");

    /** Reads a style over the defaults; null or absent is the defaults. Culling sizes are checked and left to the client. */
    static Style style(JsonElement value, String path, Style defaults, GuiParser parser) {
        if (value == null || value.isJsonNull()) return defaults;
        if (!value.isJsonObject()) { parser.problem(path, "expected style object"); return defaults; }
        JsonObject object = value.getAsJsonObject();
        parser.unknownFields(object, STYLE_FIELDS, path);
        DisplayEntity.Billboard billboard = object.has("billboard") ? parser.choice(object, "billboard", path, Set.of("fixed", "vertical", "horizontal", "center"))
                .map((String text) -> DisplayEntity.Billboard.valueOf(text.toUpperCase(Locale.ROOT))).orElse(defaults.billboard()) : defaults.billboard();
        boolean shadow = object.has("shadow") ? parser.bool(object, "shadow", path) : defaults.shadow();
        boolean seeThrough = object.has("seeThrough") ? parser.bool(object, "seeThrough", path) : defaults.seeThrough();
        DisplayEntity.Alignment alignment = object.has("textAlignment") ? parser.choice(object, "textAlignment", path, Set.of("center", "left", "right"))
                .map((String text) -> DisplayEntity.Alignment.valueOf(text.toUpperCase(Locale.ROOT))).orElse(defaults.alignment()) : defaults.alignment();
        int background = object.has("backgroundArgb") ? argb(object, "backgroundArgb", path, parser).orElse(defaults.background()) : defaults.background();
        int opacity = object.has("textOpacity") ? parser.integer(object, "textOpacity", path, 0, 255).orElse(255) : defaults.opacity();
        int lineWidth = object.has("lineWidth") ? parser.integer(object, "lineWidth", path, 1, 16384).orElse(16384) : defaults.lineWidth();
        boolean block = object.has("blockLight") && !object.get("blockLight").isJsonNull(), sky = object.has("skyLight") && !object.get("skyLight").isJsonNull();
        int brightness = defaults.brightness();
        if (block != sky) parser.problem(path + (block ? ".skyLight" : ".blockLight"), "blockLight and skyLight are set together");
        else if (block) brightness = parser.integer(object, "blockLight", path, 0, 15).orElse(15) << 4 | parser.integer(object, "skyLight", path, 0, 15).orElse(15) << 20;
        float viewRange = object.has("viewRange") ? parser.decimal(object, "viewRange", path, 0.01, 64).orElse(1.0).floatValue() : defaults.viewRange();
        float shadowRadius = object.has("shadowRadius") ? parser.decimal(object, "shadowRadius", path, 0, 64).orElse(0.0).floatValue() : defaults.shadowRadius();
        float shadowStrength = object.has("shadowStrength") ? parser.decimal(object, "shadowStrength", path, 0, 1).orElse(0.0).floatValue() : defaults.shadowStrength();
        if (object.has("cullingWidth")) parser.decimal(object, "cullingWidth", path, 0, 4096);
        if (object.has("cullingHeight")) parser.decimal(object, "cullingHeight", path, 0, 4096);
        int glow = defaults.glow();
        if (object.has("glowColor") && !object.get("glowColor").isJsonNull()) glow = argb(object, "glowColor", path, parser).map((Integer color) -> color & 0xFFFFFF).orElse(-1);
        float scaleX = object.has("scaleX") ? parser.decimal(object, "scaleX", path, 0.01, 64).orElse(1.0).floatValue() : defaults.scaleX();
        float scaleY = object.has("scaleY") ? parser.decimal(object, "scaleY", path, 0.01, 64).orElse(1.0).floatValue() : defaults.scaleY();
        float scaleZ = object.has("scaleZ") ? parser.decimal(object, "scaleZ", path, 0.01, 64).orElse(1.0).floatValue() : defaults.scaleZ();
        return new Style(billboard, shadow, seeThrough, alignment, background, opacity, lineWidth, brightness, viewRange, shadowRadius, shadowStrength, glow, scaleX, scaleY, scaleZ);
    }

    static Optional<Integer> argb(JsonObject object, String field, String path, GuiParser parser) {
        Optional<String> text = parser.string(object, field, path);
        if (text.isEmpty()) return Optional.empty();
        if (!text.get().matches("#[0-9A-Fa-f]{8}")) { parser.problem(path + "." + field, "expected #AARRGGBB"); return Optional.empty(); }
        return Optional.of((int) Long.parseLong(text.get().substring(1), 16));
    }

    /** Gloss's {@code box}: an enabled panel is not drawn by Animus and is refused; a disabled one is accepted. */
    static void box(JsonElement value, String path, GuiParser parser) {
        if (value == null || value.isJsonNull()) return;
        if (!value.isJsonObject()) { parser.problem(path, "expected box object"); return; }
        JsonObject object = value.getAsJsonObject();
        parser.unknownFields(object, Set.of("enabled", "padding", "borderWidth", "backgroundArgb", "borderArgb"), path);
        if (object.has("enabled") && parser.bool(object, "enabled", path))
            parser.problem(path + ".enabled", "box panels are not available in Animus; use style.backgroundArgb");
    }

    /** Non-empty particle layers are refused; Animus draws no particle layers. */
    static void particleLayers(JsonElement value, String path, GuiParser parser) {
        if (value == null || value.isJsonNull()) return;
        if (!value.isJsonArray()) { parser.problem(path, "expected array"); return; }
        if (!value.getAsJsonArray().isEmpty()) parser.problem(path, "particle layers are not available in Animus");
    }

    static Optional<double[]> vector(JsonElement value, String path, GuiParser parser, double limit) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) { parser.problem(path, "expected [x, y, z]"); return Optional.empty(); }
        JsonArray array = value.getAsJsonArray();
        double[] out = new double[3];
        for (int index = 0; index < 3; index++) {
            JsonElement number = array.get(index);
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !Double.isFinite(number.getAsDouble()) || Math.abs(number.getAsDouble()) > limit) {
                parser.problem(path + "[" + index + "]", "expected a number within " + limit);
                return Optional.empty();
            }
            out[index] = number.getAsDouble();
        }
        return Optional.of(out);
    }

    static boolean entityType(String type) { return type.matches(MinecraftIds.PATTERN); }
    static String key(String id) { return MinecraftIds.normalize(id); }

    /** Plain characters of the widest line, for the automatic width of a text click area. */
    static int widest(List<String> lines) {
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, RichText.plain(line).length());
        return widest;
    }
}
