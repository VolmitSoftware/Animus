package art.arcane.animus.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.bukkit.combat.MobProfile;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class TargetableMobsTest {
    @Test void aDrawnMobHitsWithItsOwnDamageForALimitedTime() {
        MobProfile zombie = RuntimeCombatService.drawnProfile(OptionalDouble.of(3));
        assertEquals(3, zombie.attack().damage());
        assertEquals(20, zombie.attack().cooldownTicks());
        assertEquals(RuntimeCombatService.TARGETABLE_LEASE_TICKS, zombie.lifetimeTicks());
        assertEquals(RuntimeCombatService.TARGETABLE_RANGE, zombie.pursuitRange());
        assertEquals(0.5, RuntimeCombatService.drawnProfile(OptionalDouble.of(0)).attack().damage(), "a harmless mob still swings");
        assertEquals(100, RuntimeCombatService.drawnProfile(OptionalDouble.of(500)).attack().damage());
        assertTrue(RuntimeCombatService.drawnProfile(OptionalDouble.empty()).attack().damage() > 0);
    }
}
