package art.arcane.animus.core.combat;

import art.arcane.animus.core.navigation.MovementSettings;
import art.arcane.animus.core.npc.NpcAttributes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Maps the definition's attributes onto combat settings the {@code animus:combat} module leaves out. A field the module
 * sets always wins. {@code attack_damage} gives {@code melee.damage}
 * (unless {@code melee.fromEquipment}), {@code attack_knockback} adds half its value to the 0.4 base
 * {@code melee.knockback}, {@code attack_speed} hits per second give {@code melee.cooldownTicks}, {@code follow_range}
 * gives {@code targeting.scanRange} and {@code movement_speed} the chase {@code pursuit.speed}. Values are kept within
 * the combat ranges. Health and armor attributes belong to {@link HealthSettings}.
 */
public final class CombatAttributes {
    private CombatAttributes() {}

    /** A copy of the module with the attribute values filled in where it sets nothing. */
    static JsonObject fill(JsonObject module, NpcAttributes attributes, boolean player) {
        JsonObject filled = module.deepCopy();
        JsonObject melee = child(filled, "melee");
        boolean fromEquipment = melee != null && melee.has("fromEquipment") && melee.get("fromEquipment").isJsonPrimitive() && melee.get("fromEquipment").getAsBoolean();
        if (!fromEquipment) attributes.get("attack_damage").ifPresent((double value) -> put(filled, "melee", "damage", clamp(value, .01, 100)));
        attributes.get("attack_knockback").ifPresent((double value) -> put(filled, "melee", "knockback", clamp(.4 + value / 2, 0, 4)));
        attributes.get("attack_speed").ifPresent((double value) -> put(filled, "melee", "cooldownTicks",
                value <= 0 ? 1200 : Math.max(1, Math.min(1200, Math.round(20 / value)))));
        attributes.get("follow_range").ifPresent((double value) -> put(filled, "targeting", "scanRange", clamp(value, 1, 256)));
        attributes.get("movement_speed").ifPresent((double value) -> put(filled, "pursuit", "speed", MovementSettings.walkSpeed(value, player)));
        return filled;
    }

    /** Damage left after vanilla armor: {@code points} less the hit over 2 plus a quarter of the toughness, between a fifth of the points and 20, each point a 25th. */
    public static double armored(double damage, double points, double toughness) {
        double blocked = Math.max(points / 5, Math.min(20, points - damage / (2 + toughness / 4)));
        return damage * (1 - blocked / 25);
    }

    private static double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }

    private static JsonObject child(JsonObject module, String key) {
        JsonElement value = module.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** Sets {@code section.key} unless the module already sets it; a malformed section is left for the parser to report. */
    private static void put(JsonObject module, String section, String key, Number value) {
        JsonElement current = module.get(section);
        if (current != null && !current.isJsonObject()) return;
        JsonObject object = current == null ? new JsonObject() : current.getAsJsonObject();
        if (object.has(key)) return;
        object.addProperty(key, value);
        module.add(section, object);
    }
}
