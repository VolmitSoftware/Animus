package art.arcane.animus.bukkit.integration.libsdisguises;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.NpcDisguise;
import art.arcane.animus.core.presentation.BodySkin;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The definition fields one disguise sets, collected option by option from what LibsDisguises read. Names and values
 * come in as LibsDisguises and Bukkit spell them; this class turns them into Animus field values and keeps every option
 * it cannot express, in LibsDisguises' own command words.
 */
final class DisguiseFields {
    /** Ender dragon phases by their vanilla order: hovering is 10, and the three perched phases sit. */
    private static final Map<Integer, String> DRAGON_PHASES = Map.of(10, "hovering", 5, "sitting", 6, "sitting", 7, "sitting");
    private static final Map<String, String> POSES = Map.of("CROUCHING", "sneaking", "SLEEPING", "sleeping", "SWIMMING", "swimming", "SITTING", "sitting", "STANDING", "standing");

    private final String type;
    private final JsonObject fields = new JsonObject();
    private final Set<String> unsupported = new LinkedHashSet<>();
    private Optional<BodySkin> skin = Optional.empty();

    DisguiseFields(String type) {
        this.type = type;
        fields.addProperty("type", type);
    }

    String type() { return type; }

    void name(String name) { if (!name.isBlank()) fields.addProperty("name", name); }

    void nameplate(boolean visible) {
        JsonObject nameplate = new JsonObject();
        nameplate.addProperty("visible", visible);
        fields.add("nameplate", nameplate);
    }

    /** {@code color} is a Bukkit {@code ChatColor} name such as {@code DARK_AQUA}. */
    void glowing(Optional<String> color) {
        fields.addProperty("glowing", true);
        color.map((String name) -> name.toLowerCase(Locale.ROOT)).ifPresent((String name) -> {
            if (BodyTraits.TEAM_COLORS.contains(name)) fields.addProperty("teamColor", name);
            else unsupported.add("setGlowColor " + name);
        });
    }

    void floating() { fields.addProperty("gravity", false); }
    void baby() { fields.addProperty("baby", true); }
    void usingItem() { fields.addProperty("usingItem", true); }
    void listed() { fields.addProperty("listed", true); }

    /** {@code pose} is a vanilla {@code Pose} name such as {@code CROUCHING}. */
    void pose(String pose) {
        String key = POSES.get(pose.toUpperCase(Locale.ROOT));
        if (key == null) { unsupported.add("setEntityPose " + pose.toLowerCase(Locale.ROOT)); return; }
        if (!key.equals("standing")) fields.addProperty("pose", key);
    }

    /** An item in {@code slot}, an Animus equipment slot key, as its namespaced id, count and component text. */
    void equipment(String slot, String item, int count, Optional<String> components) {
        JsonObject equipment = fields.has("equipment") ? fields.getAsJsonObject("equipment") : new JsonObject();
        JsonObject stack = new JsonObject();
        stack.addProperty("id", item);
        stack.addProperty("count", Math.max(1, Math.min(99, count)));
        components.filter((String text) -> text.length() > 2).ifPresent((String text) -> stack.addProperty("components", text));
        equipment.add(slot, stack);
        fields.add("equipment", equipment);
    }

    /** Bukkit {@code Horse.Color} and {@code Horse.Style} names. */
    void horse(String color, String style) {
        JsonObject horse = new JsonObject();
        horse.addProperty("color", color.toLowerCase(Locale.ROOT));
        horse.addProperty("markings", style.equalsIgnoreCase("WHITEFIELD") ? "white_field" : style.toLowerCase(Locale.ROOT));
        fields.add("horse", horse);
    }

    /** A Bukkit {@code Llama.Color} name. */
    void llama(String color, boolean chest) {
        JsonObject llama = new JsonObject();
        llama.addProperty("variant", color.toLowerCase(Locale.ROOT));
        if (chest) llama.addProperty("chest", true);
        fields.add("llama", llama);
    }

    /** A frog variant key such as {@code minecraft:warm}. */
    void frog(String variant) { variant("frog", "variant", variant.substring(variant.indexOf(':') + 1)); }

    /** A PacketEvents {@code ArmadilloState} name. */
    void armadillo(String state) { variant("armadillo", "state", state.toLowerCase(Locale.ROOT)); }

    void dragon(int phase) {
        String rest = DRAGON_PHASES.get(phase);
        if (rest == null) unsupported.add("setPhase " + phase);
        else variant("dragon", "phase", rest);
    }

    /** Armor stand parts, with each declared angle in radians as Bukkit's {@code EulerAngle} holds it. */
    void armorStand(boolean small, boolean arms, boolean basePlate, boolean invisible, Map<ArmorStandLook.Part, double[]> angles) {
        JsonObject stand = new JsonObject();
        if (small) stand.addProperty("small", true);
        if (arms) stand.addProperty("arms", true);
        if (!basePlate) stand.addProperty("basePlate", false);
        if (invisible) stand.addProperty("invisible", true);
        for (Map.Entry<ArmorStandLook.Part, double[]> angle : angles.entrySet()) {
            com.google.gson.JsonArray degrees = new com.google.gson.JsonArray();
            for (double radians : angle.getValue()) degrees.add(Math.round(Math.IEEEremainder(Math.toDegrees(radians), 360) * 100) / 100.0);
            stand.add(angle.getKey().key(), degrees);
        }
        if (!stand.isEmpty()) fields.add("armorStand", stand);
    }

    void scale(double scale) {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("scale", scale);
        fields.add("attributes", attributes);
    }

    /** A block state, such as {@code minecraft:sand}, as {@code block} of a block body's field. */
    void block(String field, String block) {
        JsonObject settings = settings(field);
        settings.addProperty("block", block);
        fields.add(field, settings);
    }

    /** An item as {@code item} of an item body's field. */
    void item(String field, String item, int count, Optional<String> components) {
        JsonObject stack = new JsonObject();
        stack.addProperty("id", item);
        stack.addProperty("count", Math.max(1, Math.min(99, count)));
        components.filter((String text) -> text.length() > 2).ifPresent((String text) -> stack.addProperty("components", text));
        JsonObject settings = settings(field);
        settings.add("item", stack);
        fields.add(field, settings);
    }

    /** The text of a text display body. */
    void text(String text) {
        JsonObject settings = settings("textDisplay");
        settings.addProperty("text", text);
        fields.add("textDisplay", settings);
    }

    void skin(BodySkin textures) { skin = Optional.of(textures); }

    /** An option the body cannot show, as the disguise command spells it. */
    void unsupported(String option) { unsupported.add(option); }

    NpcDisguise.Body body() { return new NpcDisguise.Body(fields, skin, new ArrayList<>(unsupported)); }

    private JsonObject settings(String field) { return fields.has(field) ? fields.getAsJsonObject(field) : new JsonObject(); }

    private void variant(String field, String setting, String value) {
        JsonObject settings = settings(field);
        settings.addProperty(setting, value);
        fields.add(field, settings);
    }
}
