package art.arcane.animus.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A death an NPC caused names the NPC, as vanilla names a mob. */
class NpcHitTest {
    /** Damage types need vanilla's bootstrap; NeoForge's patched classes bootstrap only inside FML, so this runs on Fabric and Forge. */
    @BeforeAll
    static void bootstrap() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (LinkageError outsideLoader) {
            org.junit.jupiter.api.Assumptions.abort("vanilla cannot bootstrap outside this loader: " + outsideLoader.getCause());
        }
    }

    @Test void aPlayerAnNpcKillsIsSlainOrShotByThatNpc() {
        VanillaCombatWorld.NpcHit melee = new VanillaCombatWorld.NpcHit(Holder.direct(new DamageType("mob", 0.1f)), Vec3.ZERO, ModText.component("&cGuard"));
        assertEquals("Steve was slain by Guard", melee.deathMessage(Component.literal("Steve")).getString());
        VanillaCombatWorld.NpcHit shot = new VanillaCombatWorld.NpcHit(Holder.direct(new DamageType("arrow", 0.1f)), Vec3.ZERO, Component.literal("Archer"));
        assertEquals("Steve was shot by Archer", shot.deathMessage(Component.literal("Steve")).getString());
        assertNull(melee.getEntity(), "an NPC body is no entity, so the hit has no causing entity");
    }
}
