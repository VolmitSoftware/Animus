package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The definition's {@code attributes}: vanilla entity attribute ids mapped to base values, such as
 * {@code {"scale": 1.5, "gravity": 0.02}}. Ids may carry the {@code minecraft:} namespace; values are checked against
 * the vanilla range in {@link VanillaAttributes}. Every attribute is sent to viewers; {@link #effect} says which ones
 * Animus also simulates for its virtual bodies.
 */
public record NpcAttributes(SortedMap<String, Double> values) {
    public static final String FIELD = "attributes";
    public static final String NAMESPACE = "minecraft:";
    public static final NpcAttributes NONE = new NpcAttributes(new TreeMap<>());
    private static final Map<String, VanillaAttributes.Attribute> TABLE;
    static {
        Map<String, VanillaAttributes.Attribute> table = new LinkedHashMap<>();
        for (VanillaAttributes.Attribute attribute : VanillaAttributes.ALL) table.put(attribute.id(), attribute);
        TABLE = Collections.unmodifiableMap(table);
    }
    /** What Animus's own simulation does with each attribute it reads; every other attribute is only sent to viewers. */
    private static final Map<String, String> SIMULATED = Map.ofEntries(
            Map.entry("scale", "Body size: hitbox, click reach, path clearance, rig bones and text height grow with it."),
            Map.entry("gravity", "Fall acceleration in blocks per tick squared; 0 or less hovers. The gravity field set to false hovers whatever this says."),
            Map.entry("movement_speed", "Walking speed when neither the route nor movement.speed sets one: a player body walks 43.17 times this in blocks per second,"
                    + " other bodies 44.05 times its square, kept within 1..10."),
            Map.entry("step_height", "Blocks climbed without a jump, at most 1.5, unless movement.stepHeight is set."),
            Map.entry("jump_strength", "Jump height on paths, worked out with gravity, at most 3 blocks."),
            Map.entry("safe_fall_distance", "Blocks a path may drop, 0..16, unless movement.maximumFall is set."),
            Map.entry("max_health", "Combat maximum health unless the combat module sets defense.maximumHealth."),
            Map.entry("armor", "Combat armor points with vanilla damage reduction, unless the combat module sets defense.armor or defense.armorFromEquipment."),
            Map.entry("armor_toughness", "Combat armor toughness, used with armor."),
            Map.entry("knockback_resistance", "The share of combat knockback the NPC ignores; below 0 it takes more."),
            Map.entry("attack_damage", "Melee damage unless the combat module sets melee.damage or melee.fromEquipment."),
            Map.entry("attack_knockback", "Adds half its value to the 0.4 base melee knockback unless the combat module sets melee.knockback."),
            Map.entry("attack_speed", "Melee hits per second unless the combat module sets melee.cooldownTicks."),
            Map.entry("follow_range", "Combat scan range, at most 256, unless the combat module sets targeting.scanRange."));

    /** What an attribute changes for a virtual NPC beyond being sent to viewers. */
    public enum Effect {
        /** Animus's own simulation honours it. */
        SIMULATED,
        /** Stored and sent, but Animus does nothing with it: it matters to a real player or a vanilla mob brain. */
        NONE
    }

    public NpcAttributes {
        values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            String problem = problem(entry.getKey(), entry.getValue());
            if (problem != null) throw new IllegalArgumentException(problem);
        }
    }

    /** Every attribute id this build knows, sorted. */
    public static List<VanillaAttributes.Attribute> table() { return VanillaAttributes.ALL; }

    /** The attribute an id names, with or without the {@code minecraft:} namespace. */
    public static Optional<VanillaAttributes.Attribute> attribute(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(TABLE.get(id.startsWith(NAMESPACE) ? id.substring(NAMESPACE.length()) : id));
    }

    /** The bare id of an attribute name, or empty when no supported Minecraft version registers it. */
    public static Optional<String> id(String name) { return attribute(name).map(VanillaAttributes.Attribute::id); }

    public static Effect effect(String id) {
        String bare = id(id).orElseThrow(() -> new IllegalArgumentException("Unknown attribute " + id));
        return SIMULATED.containsKey(bare) ? Effect.SIMULATED : Effect.NONE;
    }

    /** One sentence on what the attribute does to an NPC, for schemas, commands and the editor. */
    public static String note(String id) {
        String bare = id(id).orElseThrow(() -> new IllegalArgumentException("Unknown attribute " + id));
        return SIMULATED.getOrDefault(bare, "Sent to viewers; no effect on an NPC's own simulation.");
    }

    /** Null when the value suits the attribute, otherwise why not. */
    public static String problem(String id, double value) {
        Optional<VanillaAttributes.Attribute> attribute = attribute(id);
        if (attribute.isEmpty()) return "unknown attribute " + id + "; expected a vanilla attribute id such as scale, gravity or movement_speed";
        VanillaAttributes.Attribute known = attribute.get();
        if (!Double.isFinite(value) || value < known.minimum() || value > known.maximum())
            return "expected " + format(known.minimum()) + ".." + format(known.maximum()) + " for " + known.id();
        return null;
    }

    /** The definition's attributes; a malformed entry is reported to {@code errors} and left out. */
    public static NpcAttributes read(JsonObject definition, List<String> errors) {
        JsonElement field = definition.get(FIELD);
        if (field == null) return NONE;
        if (!field.isJsonObject()) { errors.add("$." + FIELD + ": expected an object of attribute ids and numbers"); return NONE; }
        SortedMap<String, Double> values = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : field.getAsJsonObject().entrySet()) {
            String path = "$." + FIELD + "." + entry.getKey();
            Optional<String> id = id(entry.getKey());
            JsonElement value = entry.getValue();
            if (id.isEmpty()) { errors.add(path + ": " + problem(entry.getKey(), 0)); continue; }
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { errors.add(path + ": expected a number"); continue; }
            String problem = problem(id.get(), value.getAsDouble());
            if (problem != null) { errors.add(path + ": " + problem); continue; }
            if (values.put(id.get(), value.getAsDouble()) != null) errors.add(path + ": " + id.get() + " is set twice");
        }
        return new NpcAttributes(values);
    }

    /** The attributes of a definition that already passed validation; a malformed field reads as none. */
    public static NpcAttributes of(JsonObject definition) {
        List<String> ignored = new ArrayList<>();
        return read(definition, ignored);
    }

    public boolean isEmpty() { return values.isEmpty(); }

    /** The value this NPC sets, if any. */
    public OptionalDouble get(String id) {
        Double value = values.get(id(id).orElse(id));
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** The value this NPC sets, otherwise the vanilla default. */
    public double value(String id) {
        VanillaAttributes.Attribute attribute = attribute(id).orElseThrow(() -> new IllegalArgumentException("Unknown attribute " + id));
        return get(attribute.id()).orElse(attribute.defaultValue());
    }

    /** The body size multiplier: {@code scale}, 1 when unset. */
    public double scale() { return value("scale"); }

    /** These attributes with one set; the id may carry the namespace. */
    public NpcAttributes with(String id, double value) {
        String problem = problem(id, value);
        if (problem != null) throw new IllegalArgumentException(problem);
        SortedMap<String, Double> next = new TreeMap<>(values);
        next.put(id(id).orElseThrow(), value);
        return new NpcAttributes(next);
    }

    /** These attributes with one returned to its default. */
    public NpcAttributes without(String id) {
        SortedMap<String, Double> next = new TreeMap<>(values);
        next.remove(id(id).orElse(id));
        return new NpcAttributes(next);
    }

    /** These attributes with {@code overrides} applied on top. */
    public NpcAttributes merged(NpcAttributes overrides) {
        if (overrides.isEmpty()) return this;
        SortedMap<String, Double> next = new TreeMap<>(values);
        next.putAll(overrides.values);
        return new NpcAttributes(next);
    }

    /** The JSON object of these attributes, bare ids in id order. */
    public JsonObject json() {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }

    public static String format(double value) {
        if (value == Double.MAX_VALUE) return "any";
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }
}
