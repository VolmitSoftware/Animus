package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.virtual.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An NPC's hit on a real body: the damage's own source position already gives vanilla's push, so only the rest is added. */
class RealKnockbackTest {
    @Test void theDefaultHitLeavesThePushToVanillaHurt() {
        List<Vector> set = new ArrayList<>();
        BukkitCombatWorld.push(zombie(set), new Vec3(0, 61, 2), .4, false, 0);
        assertTrue(set.isEmpty(), "vanilla's hurt already pushed the zombie by 0.4");
    }

    @Test void aHarderHitAddsWhatIsAboveVanillasPush() {
        List<Vector> set = new ArrayList<>();
        BukkitCombatWorld.push(zombie(set), new Vec3(0, 61, 2), .9, false, 0);
        assertEquals(1, set.size());
        assertEquals(0, set.getFirst().getX(), 1e-9);
        assertEquals(-.5, set.getFirst().getZ(), 1e-9, "away from the NPC, by the half block Knockback I adds");
    }

    @Test void aPunchArrowPushesAlongItsFlightLessByResistance() {
        List<Vector> set = new ArrayList<>();
        BukkitCombatWorld.push(zombie(set), new Vec3(0, 61, 2), .4 + .6, true, .5);
        assertEquals(1, set.size());
        assertEquals(-.3, set.getFirst().getZ(), 1e-9, "Punch I is 0.6, halved by half knockback resistance");
        assertEquals(.1, set.getFirst().getY(), 1e-9, "and lifts as vanilla's arrow does");
    }

    private static Zombie zombie(List<Vector> set) {
        return (Zombie) Proxy.newProxyInstance(Zombie.class.getClassLoader(), new Class<?>[] {Zombie.class}, (Object self, Method method, Object[] args) -> switch (method.getName()) {
            case "getLocation" -> new Location(null, 0, 61, 0);
            case "getVelocity" -> new Vector(0, 0, 0);
            case "setVelocity" -> { set.add((Vector) args[0]); yield null; }
            case "isValid" -> true;
            case "isDead" -> false;
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            default -> null;
        });
    }
}
