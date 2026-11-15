package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Vanilla 26.x melee numbers for hits on and by virtual NPCs. */
class VanillaMeleeTest {
    private static final Random RANDOM = new Random(3);
    private static final Set<VanillaMelee.Group> OTHER = Set.of(VanillaMelee.Group.OTHER), UNDEAD = Set.of(VanillaMelee.Group.UNDEAD),
            ARTHROPOD = Set.of(VanillaMelee.Group.ARTHROPOD);
    private static final VanillaMelee.Weapon SHARP_NETHERITE = new VanillaMelee.Weapon("minecraft:netherite_sword", Map.of(VanillaMelee.SHARPNESS, 5));
    private static final VanillaMelee.Weapon KNOCKBACK_STICK = new VanillaMelee.Weapon("minecraft:stick", Map.of(VanillaMelee.KNOCKBACK, 1));

    private static VanillaMelee.Swing standing(double damage) { return VanillaMelee.Swing.standing(damage); }
    private static VanillaMelee.Swing falling(double damage, double fall) {
        return new VanillaMelee.Swing(damage, 1, 0, 0, fall, false, false, false, false, false, false, false);
    }

    @Test void sharpnessFiveOnANetheriteSwordDealsElevenAtFullCharge() {
        VanillaMelee.Strike strike = VanillaMelee.player(standing(8), SHARP_NETHERITE, OTHER, RANDOM).orElseThrow();
        assertEquals(11, strike.damage(), 1e-9);
        assertTrue(strike.magic());
        assertFalse(strike.critical());
    }
    @Test void aFallingHitIsCriticalAndAddsHalfTheBaseOnly() {
        VanillaMelee.Strike strike = VanillaMelee.player(falling(8, .4), SHARP_NETHERITE, OTHER, RANDOM).orElseThrow();
        assertTrue(strike.critical());
        assertEquals(8 * 1.5 + 3, strike.damage(), 1e-9);
        assertEquals(0, strike.sweep(), "A critical hit never sweeps");
    }
    @Test void criticalHitsFollowTheVanillaConditions() {
        for (VanillaMelee.Swing swing : new VanillaMelee.Swing[] {
                new VanillaMelee.Swing(8, 1, 0, 0, .4, false, true, false, false, false, false, false),
                new VanillaMelee.Swing(8, 1, 0, 0, .4, false, false, true, false, false, false, false),
                new VanillaMelee.Swing(8, 1, 0, 0, .4, false, false, false, true, false, false, false),
                new VanillaMelee.Swing(8, 1, 0, 0, .4, false, false, false, false, true, false, false),
                new VanillaMelee.Swing(8, 1, 0, 0, .4, false, false, false, false, false, true, false),
                new VanillaMelee.Swing(8, .8, 0, 0, .4, false, false, false, false, false, false, false),
                new VanillaMelee.Swing(8, 1, 0, 0, .4, true, false, false, false, false, false, false)})
            assertFalse(VanillaMelee.player(swing, VanillaMelee.Weapon.EMPTY, OTHER, RANDOM).orElseThrow().critical(), swing.toString());
    }
    @Test void anUnchargedSwingScalesBaseByTheSquareAndEnchantmentsLinearly() {
        VanillaMelee.Swing half = new VanillaMelee.Swing(8, .5, 0, 0, 0, true, false, false, false, false, false, false);
        VanillaMelee.Strike strike = VanillaMelee.player(half, SHARP_NETHERITE, OTHER, RANDOM).orElseThrow();
        assertEquals(8 * (.2 + .25 * .8) + 3 * .5, strike.damage(), 1e-9);
    }
    @Test void smiteAndBaneOnlyCountAgainstTheirGroups() {
        VanillaMelee.Weapon smite = new VanillaMelee.Weapon("minecraft:iron_sword", Map.of(VanillaMelee.SMITE, 5));
        assertEquals(6 + 12.5, VanillaMelee.player(standing(6), smite, UNDEAD, RANDOM).orElseThrow().damage(), 1e-9);
        assertEquals(6, VanillaMelee.player(standing(6), smite, OTHER, RANDOM).orElseThrow().damage(), 1e-9);
        VanillaMelee.Weapon bane = new VanillaMelee.Weapon("minecraft:iron_sword", Map.of(VanillaMelee.BANE, 3));
        VanillaMelee.Strike spider = VanillaMelee.player(standing(6), bane, ARTHROPOD, RANDOM).orElseThrow();
        assertEquals(6 + 7.5, spider.damage(), 1e-9);
        assertTrue(spider.slownessTicks() >= 30 && spider.slownessTicks() <= 50, "Bane III slows for 1.5 to 2.5 seconds");
        assertEquals(0, VanillaMelee.player(standing(6), bane, UNDEAD, RANDOM).orElseThrow().slownessTicks());
    }
    @Test void bodyTypesFallIntoTheVanillaTagGroups() {
        assertEquals(UNDEAD, VanillaMelee.groups("minecraft:zombie"));
        assertEquals(UNDEAD, VanillaMelee.groups("skeleton"));
        assertEquals(Set.of(VanillaMelee.Group.ARTHROPOD), VanillaMelee.groups("minecraft:cave_spider"));
        assertEquals(UNDEAD, VanillaMelee.groups("minecraft:drowned"), "A drowned is undead, not aquatic");
        assertEquals(Set.of(VanillaMelee.Group.UNDEAD, VanillaMelee.Group.AQUATIC), VanillaMelee.groups("minecraft:zombie_nautilus"));
        assertEquals(OTHER, VanillaMelee.groups("minecraft:player"));
    }
    @Test void knockbackFireAndLootingComeFromTheWeapon() {
        VanillaMelee.Weapon weapon = new VanillaMelee.Weapon("minecraft:diamond_sword", Map.of(VanillaMelee.KNOCKBACK, 2, VanillaMelee.FIRE_ASPECT, 2, VanillaMelee.LOOTING, 3));
        VanillaMelee.Strike strike = VanillaMelee.player(standing(7), weapon, OTHER, RANDOM).orElseThrow();
        assertEquals(.4 + 1, strike.knockback(), 1e-9);
        assertEquals(160, strike.fireTicks());
        assertEquals(3, strike.looting());
        assertFalse(strike.magic(), "Knockback, Fire Aspect and Looting add no damage");
    }
    @Test void aFullChargeSprintHitKnocksBackFurtherAndDoesNotSweep() {
        VanillaMelee.Swing sprint = new VanillaMelee.Swing(7, 1, 0, 0, 0, true, false, false, false, false, true, false);
        VanillaMelee.Strike strike = VanillaMelee.player(sprint, new VanillaMelee.Weapon("minecraft:diamond_sword", Map.of()), OTHER, RANDOM).orElseThrow();
        assertEquals(.9, strike.knockback(), 1e-9);
        assertEquals(0, strike.sweep());
    }
    @Test void aStandingSwordHitSweepsWithTheSweepingRatio() {
        VanillaMelee.Swing swing = new VanillaMelee.Swing(7, 1, 0, .75, 0, true, false, false, false, false, false, false);
        VanillaMelee.Weapon sword = new VanillaMelee.Weapon("minecraft:diamond_sword", Map.of(VanillaMelee.SHARPNESS, 1));
        VanillaMelee.Strike strike = VanillaMelee.player(swing, sword, OTHER, RANDOM).orElseThrow();
        assertEquals(1 + .75 * 7, strike.sweep(), 1e-9);
        assertEquals(1 + .75 * 7 + 1, VanillaMelee.swept(strike, sword, OTHER), 1e-9);
        VanillaMelee.Strike axe = VanillaMelee.player(swing, new VanillaMelee.Weapon("minecraft:diamond_axe", Map.of()), OTHER, RANDOM).orElseThrow();
        assertEquals(0, axe.sweep(), "Only swords sweep");
    }
    @Test void aFallingMaceSmashAddsItsFallBonus() {
        VanillaMelee.Weapon mace = new VanillaMelee.Weapon("minecraft:mace", Map.of(VanillaMelee.DENSITY, 2));
        VanillaMelee.Strike strike = VanillaMelee.player(falling(6, 5), mace, OTHER, RANDOM).orElseThrow();
        assertTrue(strike.smash());
        assertEquals((6 + 12 + 2 * 2 + .5 * 2 * 5) * 1.5, strike.damage(), 1e-9);
        assertFalse(VanillaMelee.player(falling(6, 1), mace, OTHER, RANDOM).orElseThrow().smash());
    }
    @Test void theChargeScalesTheBaseByVanillasCurve() {
        for (double charge : new double[] {0, .5, .9, 1}) {
            VanillaMelee.Swing swing = new VanillaMelee.Swing(7, charge, 0, 0, 0, true, false, false, false, false, false, false);
            assertEquals(7 * (.2 + charge * charge * .8), VanillaMelee.player(swing, VanillaMelee.Weapon.EMPTY, OTHER, RANDOM).orElseThrow().damage(), 1e-12);
        }
    }
    @Test void nothingInHandAndNoChargeStillDealsAFifth() {
        VanillaMelee.Swing empty = new VanillaMelee.Swing(1, 0, 0, 0, 0, true, false, false, false, false, false, false);
        assertEquals(.2, VanillaMelee.player(empty, VanillaMelee.Weapon.EMPTY, OTHER, RANDOM).orElseThrow().damage(), 1e-9);
        assertEquals(Optional.empty(), VanillaMelee.player(new VanillaMelee.Swing(0, 1, 0, 0, 0, true, false, false, false, false, false, false),
                VanillaMelee.Weapon.EMPTY, OTHER, RANDOM));
    }
    @Test void anNpcAddsItsWeaponEnchantmentsOnlyWhenDamageComesFromTheWeapon() {
        VanillaMelee.Strike derived = VanillaMelee.npc(8, true, .4, SHARP_NETHERITE, OTHER, RANDOM);
        assertEquals(11, derived.damage(), 1e-9);
        assertFalse(derived.critical());
        assertEquals(8, VanillaMelee.npc(8, false, .4, SHARP_NETHERITE, OTHER, RANDOM).damage(), 1e-9);
    }
    /** Vanilla's hurt already pushes a body struck from a known spot by 0.4; the platform adds only the rest. */
    @Test void aRealBodyGetsOnlyThePushVanillaHurtLeavesOut() {
        assertEquals(0, VanillaMelee.extraKnockback(.4), 1e-9, "the default hit pushes exactly as a zombie's does");
        assertEquals(0, VanillaMelee.extraKnockback(.2), 1e-9);
        assertEquals(.5, VanillaMelee.extraKnockback(VanillaMelee.npc(4, true, .4, KNOCKBACK_STICK, OTHER, RANDOM).knockback()), 1e-9,
                "Knockback I adds half a block of push, as a mob's held item does");
    }
    /** Punch pushes along the arrow 0.6 a level on top of hurt's 0.4, lifts by 0.1 and is less by knockback resistance. */
    @Test void punchPushesARealBodyAsVanillaDoes() {
        Vec3 punched = VanillaMelee.pushed(Vec3.ZERO, 0, -2, .4 + 2 * .6, true, 0);
        assertEquals(0, punched.x(), 1e-9);
        assertEquals(-1.2, punched.z(), 1e-9);
        assertEquals(.1, punched.y(), 1e-9);
        assertEquals(-.6, VanillaMelee.pushed(Vec3.ZERO, 0, -2, .4 + 2 * .6, true, .5).z(), 1e-9, "half knockback resistance halves Punch");
        assertEquals(Vec3.ZERO, VanillaMelee.pushed(Vec3.ZERO, 0, -2, .4, true, 0), "a plain arrow pushes by hurt's 0.4 alone");
        assertEquals(-.25, VanillaMelee.pushed(Vec3.ZERO, 0, -2, .9, false, .5).z(), 1e-9, "a melee push is less by knockback resistance too");
    }
    @Test void protectionCapsAtEightyPercent() {
        assertEquals(10 * (1 - 8 / 25.0), VanillaMelee.protectedDamage(10, 4, 2, 2), 1e-9);
        assertEquals(10 * (1 - 20 / 25.0), VanillaMelee.protectedDamage(10, 16, 4, 2), 1e-9);
    }
    @Test void enchantmentsAreReadFromEveryComponentSpelling() {
        assertEquals(Map.of("minecraft:sharpness", 5), EnchantmentLevels.of(Optional.of("[minecraft:enchantments={\"minecraft:sharpness\":5}]")));
        assertEquals(Map.of("minecraft:sharpness", 5, "minecraft:fire_aspect", 2), EnchantmentLevels.of(Optional.of("[enchantments={sharpness:5,fire_aspect:2b}]")));
        assertEquals(Map.of("minecraft:smite", 3), EnchantmentLevels.of(Optional.of("[enchantments={levels:{'minecraft:smite':3},show_in_tooltip:false}]")));
        assertEquals(Map.of("minecraft:looting", 3), EnchantmentLevels.of(Optional.of("[custom_name='Blade',minecraft:enchantments={minecraft:looting:3}]")));
        assertEquals(Map.of(), EnchantmentLevels.of(Optional.of("[stored_enchantments={sharpness:5}]")), "A book's stored enchantments do not count");
        assertEquals(Map.of(), EnchantmentLevels.of(Optional.of("[enchantment_glint_override=true]")));
        assertEquals(Map.of(), EnchantmentLevels.of(Optional.empty()));
    }
}
