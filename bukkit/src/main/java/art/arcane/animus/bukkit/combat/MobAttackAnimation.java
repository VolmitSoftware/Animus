package art.arcane.animus.bukkit.combat;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.EntityEffect;
import org.bukkit.entity.LivingEntity;

/**
 * Shows a controlled mob's attack the way vanilla does. Most mobs swing the main arm; a few never render a swing and play
 * their own attack animation from an entity event instead.
 */
public final class MobAttackAnimation {
    private MobAttackAnimation() {}

    /** The entity effect a mob of this type plays for an attack, or empty when it swings its arm. */
    static Optional<EntityEffect> effect(String typeKey) {
        return Optional.ofNullable(switch (typeKey.toLowerCase(Locale.ROOT)) {
            case "minecraft:iron_golem" -> EntityEffect.IRON_GOLEN_ATTACK;
            case "minecraft:ravager" -> EntityEffect.RAVAGER_ATTACK;
            case "minecraft:hoglin" -> EntityEffect.HOGLIN_ATTACK;
            case "minecraft:zoglin" -> EntityEffect.ZOGLIN_ATTACK;
            case "minecraft:warden" -> EntityEffect.WARDEN_ATTACK;
            default -> null;
        });
    }

    /** One attack animation for everyone tracking the mob. */
    public static void show(LivingEntity mob) {
        Optional<EntityEffect> effect = effect(mob.getType().getKey().toString());
        if (effect.isPresent()) mob.playEffect(effect.get());
        else mob.swingMainHand();
    }
}
