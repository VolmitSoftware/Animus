package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.projectiles.BlockProjectileSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hit rules Paper's and Folia's combat listeners share, fed the same synthetic hit. */
class CombatHitsTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000c0a1");
    private static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-00000000c0a2");
    /** Both platforms' listeners, and the mod loaders' one, which may not keep a copy of the formula. */
    private static final List<Path> LISTENERS = List.of(Path.of("src/main/java/art/arcane/animus/bukkit/RuntimeCombatService.java"),
            Path.of("src/main/java/art/arcane/animus/bukkit/runtime/RegionCombat.java"),
            Path.of("../adapters/mod-common/src/main/java/art/arcane/animus/mod/ModCombat.java"));

    @Test void playerHitUsesTheSharedChargedFormula() {
        assertEquals(AttackProfile.charged(6, .75), CombatHits.attackDamage(attack(6), .75f), 1e-9);
        assertEquals(4.5, CombatHits.attackDamage(attack(4.5), 1f), 1e-9);
        assertEquals(.2, CombatHits.attackDamage(null, 0f), 1e-9, "no attack attribute hits for 1 at full charge");
    }

    @Test void damagerResolvesToThePlayerOrMobBehindIt() {
        Player player = entity(Player.class, PLAYER, null);
        Entity zombie = entity(Zombie.class, ZOMBIE, null);
        assertEquals(new TargetRef(TargetKind.PLAYER, PLAYER), CombatHits.attacker(player));
        assertEquals(new TargetRef(TargetKind.MOB, ZOMBIE), CombatHits.attacker(zombie));
        assertEquals(new TargetRef(TargetKind.PLAYER, PLAYER), CombatHits.attacker(entity(Arrow.class, UUID.randomUUID(), player)), "a projectile's shooter dealt the hit");
        assertEquals(new TargetRef(TargetKind.MOB, ZOMBIE), CombatHits.attacker(entity(Arrow.class, UUID.randomUUID(), zombie)));
        assertNull(CombatHits.attacker(entity(Arrow.class, UUID.randomUUID(), proxy(BlockProjectileSource.class, (String name) -> null))), "a dispenser arrow has no attacker");
        assertNull(CombatHits.attacker(entity(ArmorStand.class, UUID.randomUUID(), null)), "an armor stand is neither player nor mob");
        assertNull(CombatHits.reference(entity(Arrow.class, UUID.randomUUID(), player)));
    }

    @Test void listenersKeepNoCopyOfTheHitRules() throws IOException {
        for (Path listener : LISTENERS) {
            String source = Files.readString(listener);
            assertFalse(source.contains("charge * charge"), listener + " computes the charged damage itself");
            assertTrue(source.contains("AttackProfile.charged(") || source.contains("CombatHits.attackDamage("), listener + " must use the shared formula");
        }
        for (Path listener : LISTENERS.subList(0, 2)) {
            String source = Files.readString(listener);
            assertFalse(source.contains("getShooter();"), listener + " resolves projectile shooters itself");
            assertTrue(source.contains("CombatHits.attacker("), listener + " must resolve damagers through CombatHits");
        }
    }

    private static AttributeInstance attack(double value) { return proxy(AttributeInstance.class, (String name) -> "getValue".equals(name) ? value : null); }
    private static <T extends Entity> T entity(Class<T> type, UUID id, Object shooter) {
        return proxy(type, (String name) -> switch (name) {
            case "getUniqueId" -> id;
            case "getShooter" -> shooter;
            default -> null;
        });
    }
    private interface Answer { Object of(String method); }
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object self, Method method, Object[] args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName();
            return answer.of(method.getName());
        }));
    }
}
