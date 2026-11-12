package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Hit rules both combat listeners share, so a balance change reaches Paper's main thread and Folia's region owners
 * alike. Pure reads of the event's subjects; each listener still dispatches on its own platform's thread.
 */
public final class CombatHits {
    private CombatHits() {}

    /** A player's attack charge read as they swung, before the server reset it. */
    public record Swing(double charge, long nanos) {
        public static Swing of(Player player) { return new Swing(player.getAttackCooldown(), System.nanoTime()); }
    }
    /** How long a swing's charge stands for the virtual NPC hit it came with: two ticks. */
    public static final long SWING_NANOS = 100_000_000L;

    /**
     * The charge a virtual NPC hit lands with. Paper resets the charge when it handles the punch packet that follows the
     * attack, which is before Animus reads the hit on the main thread, so the charge read at that swing counts.
     */
    public static double charge(double live, Swing swing, long now) {
        return swing != null && now - swing.nanos() <= SWING_NANOS ? Math.max(live, swing.charge()) : live;
    }

    /** The player's melee damage at the charge of their latest swing, or their current one. */
    public static double attackDamage(Player player, Swing swing) {
        return attackDamage(player.getAttribute(Attribute.ATTACK_DAMAGE), charge(player.getAttackCooldown(), swing, System.nanoTime()));
    }
    /** Melee damage by {@link AttackProfile#charged}; a player without the attack attribute hits for 1 at full charge. */
    static double attackDamage(AttributeInstance attack, double charge) { return AttackProfile.charged(attack == null ? 1 : attack.getValue(), charge); }
    /** A player or mob as a combat target; null for any other entity. */
    public static TargetRef reference(Entity entity) {
        if (entity instanceof Player) return new TargetRef(TargetKind.PLAYER, entity.getUniqueId());
        return entity instanceof Mob ? new TargetRef(TargetKind.MOB, entity.getUniqueId()) : null;
    }
    /** Who dealt damage: the damager itself, or a projectile's shooter. Null when that is not a player or mob. */
    public static TargetRef attacker(Entity damager) {
        Entity source = damager;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            source = shooter instanceof Entity entity ? entity : null;
        }
        return source == null ? null : reference(source);
    }
}
