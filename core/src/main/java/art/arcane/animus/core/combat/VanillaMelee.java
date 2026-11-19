package art.arcane.animus.core.combat;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.virtual.Vec3;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Melee damage as vanilla 26.x works it out, for hits the server never sees because one side is a virtual NPC. A
 * player's hit follows {@code Player.attack}: the attack attribute (weapon, Strength and Weakness already in it) scaled by
 * the charge, enchantment damage scaled by the charge, a mace's fall bonus, a critical hit's half again, sprint and
 * Knockback knockback, a sword's sweep, Fire Aspect, Bane of Arthropods' slowness and Looting. An NPC's hit follows
 * {@code Mob.doHurtTarget}: its damage plus the weapon's enchantments, with no charge and no critical hits.
 */
public final class VanillaMelee {
    /** The entity type tags enchantments read: {@code undead}, {@code arthropod} and {@code aquatic}. */
    public enum Group { UNDEAD, ARTHROPOD, AQUATIC, OTHER }

    private static final Set<String> UNDEAD = Set.of("minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:skeleton_horse",
            "minecraft:bogged", "minecraft:parched", "minecraft:zombie_horse", "minecraft:camel_husk", "minecraft:zombie", "minecraft:zombie_villager",
            "minecraft:zombified_piglin", "minecraft:zoglin", "minecraft:drowned", "minecraft:husk", "minecraft:zombie_nautilus", "minecraft:wither",
            "minecraft:phantom");
    private static final Set<String> ARTHROPODS = Set.of("minecraft:bee", "minecraft:endermite", "minecraft:silverfish", "minecraft:spider", "minecraft:cave_spider");
    private static final Set<String> AQUATIC = Set.of("minecraft:turtle", "minecraft:axolotl", "minecraft:guardian", "minecraft:elder_guardian", "minecraft:cod",
            "minecraft:pufferfish", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:dolphin", "minecraft:squid", "minecraft:glow_squid",
            "minecraft:tadpole", "minecraft:nautilus", "minecraft:zombie_nautilus");
    public static final String SHARPNESS = "minecraft:sharpness", SMITE = "minecraft:smite", BANE = "minecraft:bane_of_arthropods",
            IMPALING = "minecraft:impaling", FIRE_ASPECT = "minecraft:fire_aspect", KNOCKBACK = "minecraft:knockback", LOOTING = "minecraft:looting",
            DENSITY = "minecraft:density", POWER = "minecraft:power", PUNCH = "minecraft:punch", FLAME = "minecraft:flame",
            PIERCING = "minecraft:piercing", MULTISHOT = "minecraft:multishot", QUICK_CHARGE = "minecraft:quick_charge", PROTECTION = "minecraft:protection",
            PROJECTILE_PROTECTION = "minecraft:projectile_protection", BLAST_PROTECTION = "minecraft:blast_protection",
            FIRE_PROTECTION = "minecraft:fire_protection", FEATHER_FALLING = "minecraft:feather_falling";
    /** The knockback every hit carries before any extra. */
    public static final double BASE_KNOCKBACK = .4;

    private VanillaMelee() {}

    /** The enchantment groups a body or entity of this type belongs to; a zombie nautilus is both undead and aquatic. */
    public static Set<Group> groups(String type) {
        String id = MinecraftIds.valid(type) ? MinecraftIds.normalize(type) : type;
        java.util.EnumSet<Group> found = java.util.EnumSet.noneOf(Group.class);
        if (UNDEAD.contains(id)) found.add(Group.UNDEAD);
        if (ARTHROPODS.contains(id)) found.add(Group.ARTHROPOD);
        if (AQUATIC.contains(id)) found.add(Group.AQUATIC);
        return found.isEmpty() ? Set.of(Group.OTHER) : Set.copyOf(found);
    }

    /** An item and its enchantment levels. */
    public record Weapon(String item, Map<String, Integer> enchantments) {
        public static final Weapon EMPTY = new Weapon(CombatLoadout.EMPTY, Map.of());
        public Weapon {
            Objects.requireNonNull(item, "item");
            enchantments = Map.copyOf(enchantments);
        }
        public int level(String enchantment) { return enchantments.getOrDefault(enchantment, 0); }
        /** Items in the {@code swords} tag, the only ones that sweep. */
        public boolean sword() { return item.endsWith("_sword"); }
        public boolean mace() { return item.equals("minecraft:mace"); }
    }

    /**
     * A player's state as the swing lands. {@code attackDamage}, {@code attackKnockback} and {@code sweepRatio} are the
     * attribute values, so the held weapon, Strength, Weakness and Sweeping Edge are already in them; {@code charge}
     * is the attack strength from 0 to 1.
     */
    public record Swing(double attackDamage, double charge, double attackKnockback, double sweepRatio, double fallDistance,
                        boolean onGround, boolean climbing, boolean inWater, boolean blind, boolean riding, boolean sprinting, boolean fallFlying) {
        /** A plain standing hit at full charge, as a command or a platform without player state delivers one. */
        public static Swing standing(double attackDamage) { return new Swing(attackDamage, 1, 0, 0, 0, true, false, false, false, false, false, false); }
    }

    /**
     * What one hit does to the struck body.
     *
     * @param knockback     total knockback strength, the 0.4 every hit has included
     * @param magic         enchantments added damage, shown as the enchanted hit sparkle
     * @param sweep         the damage base every other body in the sweep takes before its own enchantment bonus; 0 for no sweep
     * @param charge        the attack strength the sweep is scaled by
     * @param fireTicks     how long the struck body burns
     * @param slownessTicks how long Bane of Arthropods slows it, at Slowness IV
     * @param smash         a mace's falling smash, which ends the attacker's fall
     * @param sprint        a full-charge sprinting hit, which knocks back further
     */
    public record Strike(double damage, double knockback, boolean critical, boolean magic, double sweep, double charge, int fireTicks, int slownessTicks,
                         int looting, boolean smash, boolean sprint) {
        /** A hit that only sets the struck body burning, as an arrow from a Flame bow does after its damage. */
        public static Strike burning(int fireTicks) { return new Strike(0, 0, false, false, 0, 1, fireTicks, 0, 0, false, false); }
        /** A full-charge hit, which sounds strong. */
        public boolean strong() { return charge > .9; }
    }

    /** A player's hit on a body of this group; empty when the swing deals no damage at all. */
    public static Optional<Strike> player(Swing swing, Weapon weapon, Set<Group> target, RandomGenerator random) {
        double charge = Math.max(0, Math.min(1, swing.charge()));
        double magic = charge * bonus(weapon, target);
        double base = swing.attackDamage() * (.2 + charge * charge * .8);
        if (!(base > 0) && !(magic > 0)) return Optional.empty();
        boolean full = charge > .9;
        boolean sprintKnockback = swing.sprinting() && full;
        boolean smash = weapon.mace() && swing.fallDistance() > 1.5 && !swing.fallFlying();
        if (smash) base += smashBonus(swing.fallDistance(), weapon.level(DENSITY));
        boolean critical = full && swing.fallDistance() > 0 && !swing.onGround() && !swing.climbing() && !swing.inWater() && !swing.blind()
                && !swing.riding() && !swing.sprinting();
        if (critical) base *= 1.5;
        double knockback = BASE_KNOCKBACK + (swing.attackKnockback() + weapon.level(KNOCKBACK)) / 2 + (sprintKnockback ? .5 : 0);
        boolean sweeps = full && !critical && !sprintKnockback && swing.onGround() && weapon.sword();
        double sweep = sweeps ? 1 + swing.sweepRatio() * base : 0;
        return Optional.of(new Strike(base + magic, knockback, critical, magic > 0, sweep, charge, fireTicks(weapon), slownessTicks(weapon, target, random),
                weapon.level(LOOTING), smash, sprintKnockback));
    }

    /** What one body in a player's sweep takes. */
    public static double swept(Strike strike, Weapon weapon, Set<Group> target) { return (strike.sweep() + bonus(weapon, target)) * strike.charge(); }

    /**
     * An NPC's hit on a body of this group. {@code fromWeapon} adds the weapon's enchantment damage, as a mob's held item
     * does; a fixed damage the NPC's settings name is dealt as it is.
     */
    public static Strike npc(double damage, boolean fromWeapon, double knockback, Weapon weapon, Set<Group> target, RandomGenerator random) {
        double total = damage + (fromWeapon ? bonus(weapon, target) : 0);
        return new Strike(total, knockback + weapon.level(KNOCKBACK) / 2.0, false, false, 0, 1, fireTicks(weapon), slownessTicks(weapon, target, random), 0, false, false);
    }

    /**
     * The push an NPC's hit adds to a real body. Vanilla's hurt already pushes a body struck from a known position by
     * {@link #BASE_KNOCKBACK}, so only what the hit has beyond that is added.
     */
    public static double extraKnockback(double knockback) { return Math.max(0, knockback - BASE_KNOCKBACK); }

    /**
     * The velocity a real body ends with once an NPC's hit adds its push to what vanilla's hurt gave it: the
     * {@link #extraKnockback} away from the NPC, less by the body's knockback resistance. A projectile's push is Punch, which
     * lifts the body 0.1 as vanilla's arrow does; a melee push lifts it to at least 0.25.
     */
    public static Vec3 pushed(Vec3 velocity, double awayX, double awayZ, double knockback, boolean projectile, double resistance) {
        double extra = extraKnockback(knockback) * Math.max(0, 1 - resistance), length = Math.hypot(awayX, awayZ);
        if (!(extra > 0) || length < .01) return velocity;
        double x = velocity.x() + awayX / length * extra, z = velocity.z() + awayZ / length * extra;
        return new Vec3(x, projectile ? velocity.y() + .1 : Math.max(velocity.y(), .25), z);
    }

    /** Sharpness, Smite, Bane of Arthropods and Impaling against this group. */
    static double bonus(Weapon weapon, Set<Group> target) {
        int sharpness = weapon.level(SHARPNESS);
        double bonus = sharpness > 0 ? 1 + .5 * (sharpness - 1) : 0;
        if (target.contains(Group.UNDEAD)) bonus += 2.5 * weapon.level(SMITE);
        if (target.contains(Group.ARTHROPOD)) bonus += 2.5 * weapon.level(BANE);
        if (target.contains(Group.AQUATIC)) bonus += 2.5 * weapon.level(IMPALING);
        return bonus;
    }

    /** A mace's extra damage for this fall: 4 a block up to 3, 2 a block up to 8, then 1, plus half a block per level of Density. */
    static double smashBonus(double fall, int density) {
        double damage = fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + fall - 8;
        return damage + .5 * density * fall;
    }

    private static int fireTicks(Weapon weapon) { return 80 * weapon.level(FIRE_ASPECT); }

    /** Bane of Arthropods slows an arthropod for 1.5 seconds up to half a second more per level after the first. */
    private static int slownessTicks(Weapon weapon, Set<Group> target, RandomGenerator random) {
        int level = weapon.level(BANE);
        if (level <= 0 || !target.contains(Group.ARTHROPOD)) return 0;
        double seconds = 1.5 + random.nextDouble() * .5 * (level - 1);
        return (int) Math.round(seconds * 20);
    }

    /**
     * Damage left after protection enchantments on worn armor: Protection 1 a level, Projectile, Blast and Fire Protection 2
     * against their own kind, Feather Falling 3 against falls; the sum is capped at 20 and each point removes a 25th.
     */
    public static double protectedDamage(double damage, int protection, int specific, int multiplier) {
        int points = Math.min(20, protection + specific * multiplier);
        return damage * (1 - points / 25.0);
    }
}
