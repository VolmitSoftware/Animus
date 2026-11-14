package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.VanillaAttributes;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The vanilla attributes the NPC's body carries, the common ones first, each with its value or the vanilla default. A
 * click adds a step, a right-click takes one off, a sneak-click asks for a value and a blank one goes back to the vanilla
 * default. A value saved as {@code minecraft:<id>} keeps that spelling.
 */
final class AttributeScreen<P, S> extends ValueScreen<P, S> {
    /** Shown first, in this order; the rest follow by name. */
    static final List<String> COMMON = List.of("max_health", "movement_speed", "scale", "attack_damage", "armor", "attack_speed", "knockback_resistance",
            "jump_strength", "step_height", "gravity", "follow_range");
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("air_drag_modifier", "feather"), Map.entry("armor", "iron_chestplate"), Map.entry("armor_toughness", "diamond_chestplate"),
            Map.entry("attack_damage", "iron_sword"), Map.entry("attack_knockback", "piston"), Map.entry("attack_speed", "golden_sword"),
            Map.entry("below_name_distance", "oak_sign"), Map.entry("block_break_speed", "iron_pickaxe"), Map.entry("block_interaction_range", "grass_block"),
            Map.entry("bounciness", "slime_ball"), Map.entry("burning_time", "blaze_powder"), Map.entry("camera_distance", "spyglass"),
            Map.entry("entity_interaction_range", "lead"), Map.entry("explosion_knockback_resistance", "tnt"), Map.entry("fall_damage_multiplier", "pointed_dripstone"),
            Map.entry("flying_speed", "elytra"), Map.entry("follow_range", "compass"), Map.entry("friction_modifier", "ice"), Map.entry("gravity", "anvil"),
            Map.entry("jump_strength", "rabbit_foot"), Map.entry("knockback_resistance", "shield"), Map.entry("luck", "emerald"),
            Map.entry("max_absorption", "golden_apple"), Map.entry("max_health", "glistering_melon_slice"), Map.entry("mining_efficiency", "golden_pickaxe"),
            Map.entry("movement_efficiency", "soul_sand"), Map.entry("movement_speed", "sugar"), Map.entry("name_tag_distance", "name_tag"),
            Map.entry("oxygen_bonus", "turtle_helmet"), Map.entry("safe_fall_distance", "hay_block"), Map.entry("scale", "bone"),
            Map.entry("sneaking_speed", "leather_boots"), Map.entry("spawn_reinforcements", "zombie_head"), Map.entry("step_height", "oak_stairs"),
            Map.entry("submerged_mining_speed", "prismarine_shard"), Map.entry("sweeping_damage_ratio", "diamond_sword"), Map.entry("tempt_range", "wheat"),
            Map.entry("water_movement_efficiency", "heart_of_the_sea"), Map.entry("waypoint_receive_range", "recovery_compass"),
            Map.entry("waypoint_transmit_range", "lodestone"));
    private final String label;

    AttributeScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, Scope scope) {
        super(toolkit, viewer, commands, scope, List.of(NpcAttributes.FIELD));
        this.label = label;
    }

    @Override String title() { return clip(label, 32); }
    @Override boolean searchable() { return true; }

    /** The attributes a body carries, the common ones first, then the rest by name. */
    static List<VanillaAttributes.Attribute> shown(String type) {
        Set<String> body = VanillaAttributes.BODIES.get(type);
        List<VanillaAttributes.Attribute> shown = new ArrayList<>(VanillaAttributes.ALL.stream().filter((VanillaAttributes.Attribute attribute) -> body == null || body.contains(attribute.id())).toList());
        shown.sort(Comparator.comparingInt((VanillaAttributes.Attribute attribute) -> COMMON.contains(attribute.id()) ? COMMON.indexOf(attribute.id()) : COMMON.size())
                .thenComparing((VanillaAttributes.Attribute attribute) -> Words.readable(attribute.id())));
        return shown;
    }

    @Override void draw() {
        JsonObject document = scope.values().get();
        JsonElement here = current();
        JsonObject values = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        JsonObject schema = PropertyScreen.SCHEMA.getAsJsonObject("properties").getAsJsonObject(NpcAttributes.FIELD).getAsJsonObject("properties");
        list(shown(PropertyScreen.type(document)), (VanillaAttributes.Attribute attribute) -> attribute.id() + " " + Words.readable(attribute.id()), "No attributes for this body",
                (Integer slot, VanillaAttributes.Attribute attribute) -> {
                    String spelled = NpcAttributes.NAMESPACE + attribute.id(), key = values.has(spelled) && !values.has(attribute.id()) ? spelled : attribute.id(), name = Words.readable(attribute.id());
                    JsonElement value = values.get(key);
                    JsonObject node = schema.has(attribute.id()) ? schema.getAsJsonObject(attribute.id()) : new JsonObject();
                    double now = value != null && value.isJsonPrimitive() ? value.getAsDouble() : attribute.defaultValue(), step = stepOf(node);
                    List<String> lines = new ArrayList<>(describe(node));
                    lines.add(value == null ? FieldScreen.now(plain(attribute.defaultValue()) + " (vanilla default)", false) : FieldScreen.now(plain(now), false));
                    List<String> target = append(path, key);
                    field(slot, icon(attribute.id()), name, lines, hints("+" + plain(step), "-" + plain(step), "type a value"), key, (GuiModel.Click click) -> {
                        if (sneak(click)) change(slot, target, "Sets " + name, () -> prompt(asking(name), plain(now), (String typed) -> {
                            if (typed.isBlank()) apply((JsonObject root) -> with(with(root, append(path, attribute.id()), Optional.empty()), append(path, spelled), Optional.empty()));
                            else typed(typed, node, false).ifPresent((JsonPrimitive number) -> set(target, Optional.of(number)));
                        }));
                        else change(slot, target, "Sets " + name, () -> set(target, Optional.of(new JsonPrimitive(step(click, now, step, attribute.minimum(), attribute.maximum())))));
                    });
                });
        back();
        help("Vanilla values the body has, such as health and speed. Sneak-click types a value; type nothing to go back to the vanilla default.", "21-in-game-toolkit", "attributes");
    }

    /** An item that suggests what the attribute does; one the list does not know yet shows as a plain number field. */
    static String icon(String attribute) { return ICONS.containsKey(attribute) ? "minecraft:" + ICONS.get(attribute) : FieldScreen.NUMBER; }

    /** Every attribute field of the schema that names a vanilla attribute, bare or with its namespace. */
    static Set<String> covered(JsonObject schema) {
        Set<String> out = new HashSet<>();
        JsonObject attributes = schema.getAsJsonObject("properties").getAsJsonObject(NpcAttributes.FIELD).getAsJsonObject("properties");
        for (String key : attributes.keySet()) if (NpcAttributes.attribute(key).isPresent()) out.add(NpcAttributes.FIELD + "." + key);
        return out;
    }
}
