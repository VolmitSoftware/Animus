package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.combat.VanillaMelee;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
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

    /**
     * The player's state as their hit on a virtual NPC lands, at the charge of their latest swing: the attack attributes
     * with the held weapon, Strength and Weakness already in them, and what decides a critical hit.
     */
    public static VanillaMelee.Swing swing(Player player, Swing swing) {
        return swing(player, value(player.getAttribute(Attribute.ATTACK_DAMAGE), 1), value(player.getAttribute(Attribute.ATTACK_KNOCKBACK), 0),
                value(player.getAttribute(Attribute.SWEEPING_DAMAGE_RATIO), 0), swing);
    }
    /** The swing with these attribute values already read. */
    static VanillaMelee.Swing swing(Player player, double attackDamage, double attackKnockback, double sweepRatio, Swing swing) {
        return new VanillaMelee.Swing(attackDamage, charge(player.getAttackCooldown(), swing, System.nanoTime()), attackKnockback, sweepRatio, player.getFallDistance(),
                player.isOnGround(), player.isClimbing(), player.isInWater(), blind(player), player.isInsideVehicle(), player.isSprinting(), player.isGliding());
    }
    /** Blindness stops critical hits; read from the active effects, so nothing reaches the effect registry when there are none. */
    private static boolean blind(Player player) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) if (effect.getType().getKey().getKey().equals("blindness")) return true;
        return false;
    }
    private static double value(AttributeInstance attribute, double fallback) { return attribute == null ? fallback : attribute.getValue(); }

    /** An item stack as a weapon: its id and enchantment levels. */
    public static VanillaMelee.Weapon weapon(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return VanillaMelee.Weapon.EMPTY;
        Map<String, Integer> levels = new HashMap<>();
        for (Map.Entry<Enchantment, Integer> entry : stack.getEnchantments().entrySet()) levels.put(entry.getKey().getKey().toString(), entry.getValue());
        return new VanillaMelee.Weapon(stack.getType().getKey().toString(), levels);
    }

    /**
     * The sounds vanilla plays for a player's hit, which the server never plays for a body it does not hold: a critical
     * hit, a sweep, a sprint knockback, or a strong or weak hit, at the attacker.
     */
    public static void sounds(Player player, VanillaMelee.Strike strike) {
        Location at = player.getLocation();
        if (strike.sprint()) player.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1, 1);
        if (strike.critical()) player.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1, 1);
        if (strike.sweep() > 0) {
            player.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1, 1);
            double yaw = Math.toRadians(at.getYaw());
            player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.getX() - Math.sin(yaw), at.getY() + player.getHeight() / 2, at.getZ() + Math.cos(yaw), 0, 0, 0, 0, 0);
        }
        if (!strike.critical() && strike.sweep() <= 0)
            player.getWorld().playSound(at, strike.strong() ? Sound.ENTITY_PLAYER_ATTACK_STRONG : Sound.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 1, 1);
        // A mace's smash ends the fall, as it does on a real body.
        if (strike.smash()) player.setFallDistance(0);
    }
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
