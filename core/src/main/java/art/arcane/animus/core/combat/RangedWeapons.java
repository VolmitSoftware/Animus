package art.arcane.animus.core.combat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Which held items fire what, as Sentinel reads a held item: a bow or crossbow fires arrows, a blaze rod a fireball. */
public final class RangedWeapons {
    private static final Map<String, RangedWeapon> FIRED = Map.ofEntries(Map.entry("minecraft:bow", RangedWeapon.ARROW), Map.entry("minecraft:crossbow", RangedWeapon.ARROW),
            Map.entry("minecraft:trident", RangedWeapon.TRIDENT), Map.entry("minecraft:snowball", RangedWeapon.SNOWBALL), Map.entry("minecraft:egg", RangedWeapon.EGG),
            Map.entry("minecraft:blaze_rod", RangedWeapon.FIREBALL), Map.entry("minecraft:spectral_arrow", RangedWeapon.SPECTRAL_ARROW),
            Map.entry("minecraft:wither_skeleton_skull", RangedWeapon.WITHER_SKULL), Map.entry("minecraft:white_dye", RangedWeapon.LLAMA_SPIT),
            Map.entry("minecraft:splash_potion", RangedWeapon.POTION), Map.entry("minecraft:lingering_potion", RangedWeapon.POTION),
            Map.entry("minecraft:potion", RangedWeapon.POTION));
    private static final Set<String> MELEE = Set.of("minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword", "minecraft:stone_sword",
            "minecraft:golden_sword", "minecraft:wooden_sword", "minecraft:copper_sword", "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe",
            "minecraft:stone_axe", "minecraft:golden_axe", "minecraft:wooden_axe", "minecraft:copper_axe", "minecraft:mace");

    private RangedWeapons() {}

    /** What a held item fires, or empty when it is not a ranged weapon. */
    public static Optional<RangedWeapon> fired(String item) { return Optional.ofNullable(FIRED.get(item)); }
    /** A bow or crossbow, which is drawn and fires arrows. */
    public static boolean drawn(String item) { return item.equals("minecraft:bow") || item.equals("minecraft:crossbow"); }
    /** What a held bow, crossbow or trident fires, which an NPC holding one shoots with; empty for any other item. */
    public static Optional<RangedWeapon> held(String item) {
        if (drawn(item)) return Optional.of(RangedWeapon.ARROW);
        return item.equals("minecraft:trident") ? Optional.of(RangedWeapon.TRIDENT) : Optional.empty();
    }
    /** A sword, axe or mace: what a melee switch picks. */
    public static boolean melee(String item) { return MELEE.contains(item); }
    /** A potion that leaves a lingering cloud where it breaks. */
    public static boolean lingering(String item) { return item.equals("minecraft:lingering_potion"); }
}
