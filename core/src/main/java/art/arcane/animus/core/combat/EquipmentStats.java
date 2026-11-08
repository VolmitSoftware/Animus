package art.arcane.animus.core.combat;

import java.util.List;
import java.util.Map;

/**
 * Damage and armor read from what an NPC's definition says it holds and wears. Enchantments and item components are
 * not read. Worn armor removes at most 80 percent of a hit.
 */
final class EquipmentStats {
    static final double MAXIMUM_ARMOR = .8;
    private static final double BOW_DAMAGE = 6;
    private static final Map<String, Double> WEAPONS = Map.ofEntries(Map.entry("minecraft:netherite_sword", 8.0), Map.entry("minecraft:diamond_sword", 7.0),
            Map.entry("minecraft:iron_sword", 6.0), Map.entry("minecraft:stone_sword", 5.0), Map.entry("minecraft:golden_sword", 4.0),
            Map.entry("minecraft:wooden_sword", 4.0), Map.entry("minecraft:copper_sword", 5.0), Map.entry("minecraft:trident", 8.0),
            Map.entry("minecraft:netherite_axe", 10.0), Map.entry("minecraft:diamond_axe", 9.0), Map.entry("minecraft:iron_axe", 9.0),
            Map.entry("minecraft:stone_axe", 9.0), Map.entry("minecraft:golden_axe", 7.0), Map.entry("minecraft:wooden_axe", 7.0),
            Map.entry("minecraft:copper_axe", 9.0), Map.entry("minecraft:mace", 6.0));
    private static final Map<String, Double> ARMOR = Map.ofEntries(
            Map.entry("minecraft:netherite_helmet", .12), Map.entry("minecraft:netherite_chestplate", .32), Map.entry("minecraft:netherite_leggings", .24),
            Map.entry("minecraft:netherite_boots", .12), Map.entry("minecraft:diamond_helmet", .12), Map.entry("minecraft:diamond_chestplate", .32),
            Map.entry("minecraft:diamond_leggings", .24), Map.entry("minecraft:diamond_boots", .12), Map.entry("minecraft:iron_helmet", .08),
            Map.entry("minecraft:iron_chestplate", .24), Map.entry("minecraft:iron_leggings", .20), Map.entry("minecraft:iron_boots", .08),
            Map.entry("minecraft:copper_helmet", .08), Map.entry("minecraft:copper_chestplate", .16), Map.entry("minecraft:copper_leggings", .12),
            Map.entry("minecraft:copper_boots", .04), Map.entry("minecraft:golden_helmet", .08), Map.entry("minecraft:golden_chestplate", .20),
            Map.entry("minecraft:golden_leggings", .12), Map.entry("minecraft:golden_boots", .04), Map.entry("minecraft:chainmail_helmet", .08),
            Map.entry("minecraft:chainmail_chestplate", .20), Map.entry("minecraft:chainmail_leggings", .16), Map.entry("minecraft:chainmail_boots", .04),
            Map.entry("minecraft:leather_helmet", .04), Map.entry("minecraft:leather_chestplate", .12), Map.entry("minecraft:leather_leggings", .08),
            Map.entry("minecraft:leather_boots", .04), Map.entry("minecraft:turtle_helmet", .08));
    private static final List<String> WORN = List.of("head", "chest", "legs", "feet");

    private EquipmentStats() {}

    static double melee(CombatSettings.Melee melee, CombatLoadout loadout) {
        String held = loadout.item("main_hand");
        Double mapped = melee.weaponDamage().get(held);
        if (mapped != null) return Math.max(.01, mapped);
        if (!melee.fromEquipment()) return melee.damage();
        if (bow(held)) return 1;
        return WEAPONS.getOrDefault(held, 1.0);
    }

    static double ranged(CombatSettings.Ranged ranged, CombatSettings.Melee melee, CombatLoadout loadout) {
        String held = loadout.item("main_hand");
        Double mapped = melee.weaponDamage().get(held);
        if (mapped != null) return Math.max(.01, mapped);
        if (!ranged.fromEquipment()) return ranged.damage();
        if (bow(held)) return BOW_DAMAGE;
        return WEAPONS.getOrDefault(held, 1.0);
    }

    static double armor(HealthSettings.Defense defense, CombatLoadout loadout) {
        if (!defense.armorFromEquipment()) return defense.armor();
        double total = 0;
        for (String slot : WORN) total += ARMOR.getOrDefault(loadout.item(slot), 0.0);
        return Math.min(MAXIMUM_ARMOR, total);
    }

    private static boolean bow(String item) { return item.equals("minecraft:bow") || item.equals("minecraft:crossbow"); }
}
