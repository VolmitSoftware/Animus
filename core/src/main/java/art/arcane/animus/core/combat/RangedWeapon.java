package art.arcane.animus.core.combat;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a ranged NPC launches. Every weapon is a real helper projectile tracked by the module, so it hits once and is
 * cleaned up like an arrow. Explosive weapons never break blocks.
 */
public enum RangedWeapon {
    ARROW(Set.of("minecraft:arrow", "minecraft:tipped_arrow", "minecraft:spectral_arrow"), true),
    SPECTRAL_ARROW(Set.of("minecraft:spectral_arrow"), true),
    TRIDENT(Set.of("minecraft:trident"), true),
    SNOWBALL(Set.of("minecraft:snowball"), true),
    EGG(Set.of("minecraft:egg"), true),
    FIREBALL(Set.of("minecraft:fire_charge"), false),
    SMALL_FIREBALL(Set.of("minecraft:fire_charge"), false),
    WITHER_SKULL(Set.of("minecraft:wither_skeleton_skull"), false),
    LLAMA_SPIT(Set.of(), false),
    POTION(Set.of("minecraft:splash_potion", "minecraft:lingering_potion"), true);

    private final Set<String> ammunition;
    private final boolean gravity;

    RangedWeapon(Set<String> ammunition, boolean gravity) {
        this.ammunition = ammunition;
        this.gravity = gravity;
    }

    /** Inventory items one shot uses when {@code useAmmo} is set; empty when the weapon needs none. */
    public Set<String> ammunition() { return ammunition; }
    /** Whether the projectile falls, so the shot is aimed higher with distance. */
    public boolean gravity() { return gravity; }
    public String jsonName() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<RangedWeapon> parse(String value) {
        for (RangedWeapon weapon : values()) if (weapon.jsonName().equals(value)) return Optional.of(weapon);
        return Optional.empty();
    }
}
