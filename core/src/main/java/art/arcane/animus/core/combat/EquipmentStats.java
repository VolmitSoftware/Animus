package art.arcane.animus.core.combat;

import java.util.List;
import java.util.Map;

/**
 * Damage and armor read from what an NPC's definition says it holds and wears. A held weapon gives the attack damage a
 * player holding it would have; its enchantments are added by {@link VanillaMelee}. Worn armor gives vanilla armor points
 * and toughness, and its protection enchantments reduce what is left as vanilla's do.
 */
final class EquipmentStats {
    /** The base damage of a vanilla arrow, and the speed of a fully drawn bow in blocks per tick. */
    static final double ARROW_DAMAGE = 2, FULL_DRAW = 3;
    private static final Map<String, Double> WEAPONS = Map.ofEntries(Map.entry("minecraft:netherite_sword", 8.0), Map.entry("minecraft:diamond_sword", 7.0),
            Map.entry("minecraft:iron_sword", 6.0), Map.entry("minecraft:stone_sword", 5.0), Map.entry("minecraft:golden_sword", 4.0),
            Map.entry("minecraft:wooden_sword", 4.0), Map.entry("minecraft:copper_sword", 5.0), Map.entry("minecraft:trident", 9.0),
            Map.entry("minecraft:netherite_axe", 10.0), Map.entry("minecraft:diamond_axe", 9.0), Map.entry("minecraft:iron_axe", 9.0),
            Map.entry("minecraft:stone_axe", 9.0), Map.entry("minecraft:golden_axe", 7.0), Map.entry("minecraft:wooden_axe", 7.0),
            Map.entry("minecraft:copper_axe", 9.0), Map.entry("minecraft:mace", 6.0));
    /** Armor points and toughness of each wearable piece. */
    private record Piece(double points, double toughness) {}
    private static final Map<String, Piece> ARMOR = Map.ofEntries(
            Map.entry("minecraft:netherite_helmet", new Piece(3, 3)), Map.entry("minecraft:netherite_chestplate", new Piece(8, 3)),
            Map.entry("minecraft:netherite_leggings", new Piece(6, 3)), Map.entry("minecraft:netherite_boots", new Piece(3, 3)),
            Map.entry("minecraft:diamond_helmet", new Piece(3, 2)), Map.entry("minecraft:diamond_chestplate", new Piece(8, 2)),
            Map.entry("minecraft:diamond_leggings", new Piece(6, 2)), Map.entry("minecraft:diamond_boots", new Piece(3, 2)),
            Map.entry("minecraft:iron_helmet", new Piece(2, 0)), Map.entry("minecraft:iron_chestplate", new Piece(6, 0)),
            Map.entry("minecraft:iron_leggings", new Piece(5, 0)), Map.entry("minecraft:iron_boots", new Piece(2, 0)),
            Map.entry("minecraft:copper_helmet", new Piece(2, 0)), Map.entry("minecraft:copper_chestplate", new Piece(4, 0)),
            Map.entry("minecraft:copper_leggings", new Piece(3, 0)), Map.entry("minecraft:copper_boots", new Piece(1, 0)),
            Map.entry("minecraft:golden_helmet", new Piece(2, 0)), Map.entry("minecraft:golden_chestplate", new Piece(5, 0)),
            Map.entry("minecraft:golden_leggings", new Piece(3, 0)), Map.entry("minecraft:golden_boots", new Piece(1, 0)),
            Map.entry("minecraft:chainmail_helmet", new Piece(2, 0)), Map.entry("minecraft:chainmail_chestplate", new Piece(5, 0)),
            Map.entry("minecraft:chainmail_leggings", new Piece(4, 0)), Map.entry("minecraft:chainmail_boots", new Piece(1, 0)),
            Map.entry("minecraft:leather_helmet", new Piece(1, 0)), Map.entry("minecraft:leather_chestplate", new Piece(3, 0)),
            Map.entry("minecraft:leather_leggings", new Piece(2, 0)), Map.entry("minecraft:leather_boots", new Piece(1, 0)),
            Map.entry("minecraft:turtle_helmet", new Piece(2, 0)));
    private static final List<String> WORN = List.of("head", "chest", "legs", "feet");

    /**
     * What worn armor gives: vanilla points and toughness, and the summed protection enchantment levels.
     *
     * @param protection levels of Protection, which counts against every attack
     */
    record Armor(double points, double toughness, int protection, int projectile, int blast, int fire, int falling) {
        static final Armor NONE = new Armor(0, 0, 0, 0, 0, 0, 0);
    }

    private EquipmentStats() {}

    /** Whether a melee hit with this item adds the item's enchantment damage, as a hit with the item's own damage does. */
    static boolean meleeFromWeapon(CombatSettings.Melee melee, String held) { return melee.fromEquipment() && !melee.weaponDamage().containsKey(held); }

    static double melee(CombatSettings.Melee melee, String held) {
        Double mapped = melee.weaponDamage().get(held);
        if (mapped != null) return Math.max(.01, mapped);
        if (!melee.fromEquipment()) return melee.damage();
        if (RangedWeapons.drawn(held)) return 1;
        return WEAPONS.getOrDefault(held, 1.0);
    }

    /**
     * A shot's damage. A bow or crossbow fires as a full draw: two points times its speed, with Power adding one point at
     * level 1 and half a point for each level after, rounded up; the critical bonus is added per shot.
     */
    static double ranged(CombatSettings.Ranged ranged, CombatSettings.Melee melee, VanillaMelee.Weapon held) {
        Double mapped = melee.weaponDamage().get(held.item());
        if (mapped != null) return Math.max(.01, mapped);
        if (!ranged.fromEquipment()) return ranged.damage();
        if (RangedWeapons.drawn(held.item())) {
            int power = held.level(VanillaMelee.POWER);
            return Math.ceil(FULL_DRAW * (ARROW_DAMAGE + (power > 0 ? 1 + .5 * (power - 1) : 0)));
        }
        return WEAPONS.getOrDefault(held.item(), 1.0);
    }

    /** The armor points, toughness and protection the worn items give. */
    static Armor armor(CombatLoadout loadout) {
        double points = 0, toughness = 0;
        int protection = 0, projectile = 0, blast = 0, fire = 0, falling = 0;
        for (String slot : WORN) {
            Piece piece = ARMOR.get(loadout.item(slot));
            if (piece != null) { points += piece.points(); toughness += piece.toughness(); }
            VanillaMelee.Weapon worn = loadout.weapon(slot);
            protection += worn.level(VanillaMelee.PROTECTION);
            projectile += worn.level(VanillaMelee.PROJECTILE_PROTECTION);
            blast += worn.level(VanillaMelee.BLAST_PROTECTION);
            fire += worn.level(VanillaMelee.FIRE_PROTECTION);
            falling += worn.level(VanillaMelee.FEATHER_FALLING);
        }
        return new Armor(points, toughness, protection, projectile, blast, fire, falling);
    }
}
