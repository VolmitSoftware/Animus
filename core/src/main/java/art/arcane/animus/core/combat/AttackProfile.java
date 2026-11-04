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
}
