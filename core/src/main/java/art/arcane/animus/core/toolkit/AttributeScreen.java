package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.VanillaAttributes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The vanilla attributes the NPC's body carries, each with its value or the vanilla default. Numbers step within the
 * attribute's bounds; sneak-left types a value and sneak-right goes back to the vanilla default. A value saved as
 * {@code minecraft:<id>} keeps that spelling.
 */
final class AttributeScreen<P, S> extends ValueScreen<P, S> {
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

    @Override void draw() {
        pageCheck();
        JsonObject document = scope.values().get();
        Set<String> body = VanillaAttributes.BODIES.get(PropertyScreen.type(document));
        List<VanillaAttributes.Attribute> shown = VanillaAttributes.ALL.stream().filter((VanillaAttributes.Attribute attribute) -> body == null || body.contains(attribute.id())).toList();
        JsonElement here = current();
        JsonObject values = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        JsonObject schema = PropertyScreen.SCHEMA.getAsJsonObject("properties").getAsJsonObject(NpcAttributes.FIELD).getAsJsonObject("properties");
        list(shown, "This body has no attributes", (Integer slot, VanillaAttributes.Attribute attribute) -> {
            String spelled = NpcAttributes.NAMESPACE + attribute.id(), key = values.has(spelled) && !values.has(attribute.id()) ? spelled : attribute.id();
            JsonElement value = values.get(key);
            JsonObject node = schema.has(attribute.id()) ? schema.getAsJsonObject(attribute.id()) : new JsonObject();
            double now = value != null && value.isJsonPrimitive() ? value.getAsDouble() : attribute.defaultValue(), step = stepOf(node);
            List<String> lines = new ArrayList<>(describe(node));
            lines.add(value == null ? "&7Now &fvanilla default " + plain(attribute.defaultValue()) : "&7Now &f" + plain(now));
            List<String> target = append(path, key);
            field(slot, icon(attribute.id()), title(attribute.id()), lines, hints("+" + plain(step), "-" + plain(step), "left types a value, right resets"), target, (GuiModel.Click click) -> {
                if (arm(slot, target)) return;
                if (click == GuiModel.Click.SHIFT_RIGHT) apply((JsonObject root) -> with(with(root, append(path, attribute.id()), Optional.empty()), append(path, spelled), Optional.empty()));
                else if (click == GuiModel.Click.SHIFT_LEFT) prompt(attribute.id(), plain(now), (String typed) -> typed(typed, node).ifPresent((JsonPrimitive number) -> set(target, Optional.of(number))));
                else set(target, Optional.of(new JsonPrimitive(step(click, now, step, attribute.minimum(), attribute.maximum()))));
            });
        });
        back();
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
