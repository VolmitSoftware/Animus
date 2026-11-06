package art.arcane.animus.bukkit.combat;

import java.util.List;
import java.util.Optional;
import org.bukkit.EntityEffect;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mobs whose model never renders an arm swing show their attack through their own entity event. */
class MobAttackAnimationTest {
    @Test void golemsAndBeastsPlayTheirOwnAttackAndHumanoidsSwing() {
        assertEquals(Optional.of(EntityEffect.IRON_GOLEN_ATTACK), MobAttackAnimation.effect("minecraft:iron_golem"));
        assertEquals(Optional.of(EntityEffect.RAVAGER_ATTACK), MobAttackAnimation.effect("minecraft:ravager"));
        assertEquals(Optional.of(EntityEffect.HOGLIN_ATTACK), MobAttackAnimation.effect("minecraft:hoglin"));
        assertEquals(Optional.of(EntityEffect.ZOGLIN_ATTACK), MobAttackAnimation.effect("minecraft:zoglin"));
        assertEquals(Optional.of(EntityEffect.WARDEN_ATTACK), MobAttackAnimation.effect("minecraft:warden"));
        for (String swings : List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:piglin", "minecraft:vindicator", "minecraft:player"))
            assertEquals(Optional.empty(), MobAttackAnimation.effect(swings), swings);
    }
}
