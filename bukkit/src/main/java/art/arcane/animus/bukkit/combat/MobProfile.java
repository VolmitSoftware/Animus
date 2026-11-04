package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.AttackProfile;
import java.util.Objects;

/** Explicit control of one existing hostile mob; initially only ordinary zombies are supported. */
public record MobProfile(AttackProfile attack, double speed, double pursuitRange, int replanTicks, int lifetimeTicks) {
    public static final MobProfile DEFAULT = new MobProfile(AttackProfile.DEFAULT, 1.0, 12, 20, 600);
    public MobProfile {
        Objects.requireNonNull(attack);
        if (!Double.isFinite(speed) || speed <= 0 || speed > 1.5 || !Double.isFinite(pursuitRange) || pursuitRange < 1 || pursuitRange > 16
                || replanTicks < 10 || replanTicks > 200 || lifetimeTicks < 1 || lifetimeTicks > 1200)
            throw new IllegalArgumentException("Mob control exceeds speed, range, repath or lifetime limits");
    }
}
