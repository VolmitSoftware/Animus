package art.arcane.animus.core.migration;

import art.arcane.animus.core.building.BuildOptions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts the saves.yml trait of the Citizens add-on Builder ({@code traits.builder}) into an {@code animus:builder}
 * definition section, and reports the trait of its premium successor Constructor ({@code traits.constructor}), whose saved
 * keys are not published, so it is only kept for review. Nothing converted here starts on its own: the section needs
 * {@code "reviewAccepted": true} and an administrator's {@code /animus build start <npc>}.
 */
public final class BuilderMapping {
    public static final String BUILDER_TRAIT = "builder", CONSTRUCTOR_TRAIT = "constructor";
    static final Set<String> CONVERTED = Set.of("Schematic", "PatternXY", "IgnoreAir", "IgnoreLiquid", "Excavate", "HoldItems", "RequireMaterials",
            "MoveTimeoutSeconds", "YOffset", "YLayers", "Origin", "Silent");
    static final Set<String> KEPT = Set.of("State", "NeededMaterials", "ContinueLoc", "onstart", "oncomplete", "oncancel", "toggled");

    public record Result(JsonObject module, List<String> warnings) {}

    private BuilderMapping() {}

    public static Result convert(JsonElement trait) {
        List<String> warnings = new ArrayList<>();
        JsonObject module = new JsonObject();
        JsonObject source = trait != null && trait.isJsonObject() ? trait.getAsJsonObject() : new JsonObject();
        if (trait == null || !trait.isJsonObject()) warnings.add("builder trait is not a mapping; kept for review only");
        String name = text(source, "Schematic");
        if (name == null || name.isBlank()) {
            warnings.add("builder trait names no schematic; set animus:builder.schematic by hand");
            module.addProperty("schematic", "missing.schem");
        } else {
            String base = name.replaceAll("\\.schematic$", "").replaceAll("[^A-Za-z0-9_.-]", "_");
            module.addProperty("schematic", base + ".schem");
            warnings.add("Builder used " + name + " in the old .schematic format, which Animus cannot read; save it again as " + base
                    + ".schem in the Animus schematics folder");
        }
        JsonObject options = new JsonObject();
        options.addProperty("anchor", "center");
        String pattern = text(source, "PatternXY");
        if (pattern != null) {
            try { options.addProperty("pattern", BuildOptions.Pattern.named(pattern).word()); }
            catch (IllegalArgumentException unknown) { warnings.add("builder pattern " + pattern + " is unknown; the default spiral is used"); }
        }
        flag(source, "IgnoreAir", options, "ignoreAir");
        flag(source, "IgnoreLiquid", options, "ignoreLiquids");
        flag(source, "Excavate", options, "excavate");
        flag(source, "HoldItems", options, "holdBlock");
        flag(source, "Silent", options, "silent");
        if (bool(source, "RequireMaterials")) {
            options.addProperty("materials", "donate");
            if (source.has("NeededMaterials")) warnings.add("Builder's outstanding materials use old numeric item IDs; they are kept under imported and the build counts its needs again");
        }
        Double timeout = number(source, "MoveTimeoutSeconds");
        if (timeout != null) options.addProperty("moveTimeoutSeconds", (int) Math.max(1, Math.min(300, Math.round(timeout))));
        Double offset = number(source, "YOffset");
        if (offset != null) {
            JsonObject shift = new JsonObject();
            shift.addProperty("x", 0); shift.addProperty("y", (int) Math.max(-BuildOptions.MAXIMUM_OFFSET, Math.min(BuildOptions.MAXIMUM_OFFSET, Math.round(offset)))); shift.addProperty("z", 0);
            options.add("offset", shift);
        }
        Double layers = number(source, "YLayers");
        if (layers != null) options.addProperty("layers", (int) Math.max(1, Math.min(256, Math.round(layers))));
        module.add("options", options);
        JsonElement origin = source.get("Origin");
        if (origin != null && origin.isJsonObject()) {
            Double x = number(origin.getAsJsonObject(), "x"), y = number(origin.getAsJsonObject(), "y"), z = number(origin.getAsJsonObject(), "z");
            if (x != null && y != null && z != null) {
                JsonObject point = new JsonObject();
                point.addProperty("x", (int) Math.floor(x)); point.addProperty("y", (int) Math.floor(y)); point.addProperty("z", (int) Math.floor(z));
                module.add("origin", point);
                warnings.add("builder origin is used in the NPC's own world; its saved world was " + text(origin.getAsJsonObject(), "world"));
            } else warnings.add("builder origin is incomplete; the build will start where the NPC stands");
        }
        String state = text(source, "State");
        if (state != null && List.of("building", "collecting").contains(state.toLowerCase(Locale.ROOT)))
            warnings.add("Builder was " + state.toLowerCase(Locale.ROOT) + " when saved; start the build again with /animus build start after review. Blocks already in place are skipped");
        for (String task : List.of("onstart", "oncomplete", "oncancel"))
            if (source.has(task)) warnings.add("builder " + task + " names a Denizen task; recreate it with a build_finished or build_paused trigger");
        List<String> unknown = new ArrayList<>();
        for (String key : source.keySet()) if (!CONVERTED.contains(key) && !KEPT.contains(key)) unknown.add(key);
        if (!unknown.isEmpty()) warnings.add("builder fields kept for review without conversion: " + String.join(", ", unknown));
        module.addProperty("reviewAccepted", false);
        JsonObject imported = new JsonObject();
        imported.add("builder", source.deepCopy());
        JsonArray notes = new JsonArray();
        warnings.forEach(notes::add);
        imported.add("notes", notes);
        module.add("imported", imported);
        return new Result(module, List.copyOf(warnings));
    }

    /** What the importer reports for a Constructor trait. The trait itself stays in {@code citizens:review}. */
    public static List<String> constructor(JsonElement trait) {
        List<String> warnings = new ArrayList<>();
        String keys = trait != null && trait.isJsonObject() ? String.join(", ", trait.getAsJsonObject().keySet()) : "(not a mapping)";
        warnings.add("constructor trait kept for review only: Constructor's saved keys are not published, so nothing was converted; fields: " + keys);
        warnings.add("copy Constructor schematics (.schem or .nbt) into the Animus schematics folder and start the build with /animus build start");
        return warnings;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
    private static Double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return null;
        try { double number = Double.parseDouble(value.getAsString()); return Double.isFinite(number) ? number : null; }
        catch (NumberFormatException invalid) { return null; }
    }
    private static boolean bool(JsonObject object, String key) {
        String value = text(object, key);
        return value != null && value.equalsIgnoreCase("true");
    }
    private static void flag(JsonObject source, String key, JsonObject options, String target) {
        if (source.has(key)) options.addProperty(target, bool(source, key));
    }
}
