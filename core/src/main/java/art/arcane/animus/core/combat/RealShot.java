package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A real projectile fired by a player or mob, as it strikes an NPC body: who fired it, where from, and the vanilla damage,
 * fire and potion effect it carries. Projectiles NPCs fire are helpers and never become shots.
 *
 * @param from        where the shooter stood, for knockback direction and range checks
 * @param igniteTicks how long a body that takes fire burns after the hit
 * @param potion      the instant effect a thrown potion applies to the body it hits
 */
public record RealShot(Kind kind, TargetRef shooter, WorldPosition from, double damage, int igniteTicks, Optional<CombatSettings.Effect> potion) {
    /** The standard knockback of a vanilla projectile hit. */
    public static final double KNOCKBACK = .4;

    /** What the platform does with the projectile once it struck a body, as vanilla does with a living target. */
    public enum Landing { REMOVE, BOUNCE, DROP, EXPLODE }
    public enum Kind {
        ARROW(Landing.REMOVE), TRIDENT(Landing.BOUNCE), SNOWBALL(Landing.REMOVE), EGG(Landing.REMOVE), SMALL_FIREBALL(Landing.REMOVE),
        LARGE_FIREBALL(Landing.EXPLODE), WITHER_SKULL(Landing.EXPLODE), POTION(Landing.DROP);
        private final Landing landing;
        Kind(Landing landing) { this.landing = landing; }
        public Landing landing() { return landing; }
    }

    public RealShot {
        Objects.requireNonNull(kind); Objects.requireNonNull(shooter); Objects.requireNonNull(from); Objects.requireNonNull(potion);
        if (shooter.kind() == TargetKind.NPC) throw new IllegalArgumentException("NPC projectiles are helpers, not shots");
    }

    /** {@code arrowDamage} is used by arrows only; the rest carry their fixed vanilla damage. */
    public static RealShot of(Kind kind, TargetRef shooter, WorldPosition from, double arrowDamage, Optional<CombatSettings.Effect> potion) {
        return switch (kind) {
            case ARROW -> new RealShot(kind, shooter, from, arrowDamage, 0, Optional.empty());
            case TRIDENT, WITHER_SKULL -> new RealShot(kind, shooter, from, 8, 0, Optional.empty());
            case SNOWBALL, EGG -> new RealShot(kind, shooter, from, 0, 0, Optional.empty());
            case SMALL_FIREBALL -> new RealShot(kind, shooter, from, 5, 100, Optional.empty());
            case LARGE_FIREBALL -> new RealShot(kind, shooter, from, 6, 0, Optional.empty());
            case POTION -> new RealShot(kind, shooter, from, 0, 0, potion);
        };
    }

    /**
     * Vanilla arrow damage: the arrow's speed in blocks per tick times its base damage, with Power adding half a point per
     * level plus half, rounded up; a critical arrow (a fully drawn bow, any crossbow) adds up to half again plus one.
     */
    public static int arrowDamage(double speed, double base, int power, boolean critical, RandomGenerator random) {
        double perSpeed = base + (power > 0 ? .5 * power + .5 : 0);
        int damage = (int) Math.ceil(Math.max(0, Math.min(Integer.MAX_VALUE, speed * perSpeed)));
        if (critical) damage = (int) Math.min((long) damage + random.nextInt(damage / 2 + 2), Integer.MAX_VALUE);
        return damage;
    }
}
