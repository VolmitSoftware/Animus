package art.arcane.animus.core.combat;

/** Server-authoritative melee parameters; clients never choose these values. */
public record AttackProfile(double damage, double reach, int cooldownTicks) {
    public static final AttackProfile DEFAULT = new AttackProfile(1, 2.5, 20);
    public AttackProfile {
        if (!Double.isFinite(damage) || damage <= 0 || damage > 100
                || !Double.isFinite(reach) || reach <= 0 || reach > 6
                || cooldownTicks < 1 || cooldownTicks > 1200)
            throw new IllegalArgumentException("Damage must be (0,100], reach (0,6], and cooldown 1..1200 ticks");
    }
    /**
     * A player's melee damage at an attack charge of 0..1, scaled as vanilla scales it: a full charge deals the base
     * damage, an uncharged swing a fifth of it. Every platform's hit listener uses this one formula.
     */
    public static double charged(double base, double charge) { return base * (.2 + charge * charge * .8); }
}
