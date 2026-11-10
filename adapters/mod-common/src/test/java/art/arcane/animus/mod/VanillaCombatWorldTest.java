package art.arcane.animus.mod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Who combat sees: a creative or spectator player is never a target, but stays a guard's charge, as on Paper. */
class VanillaCombatWorldTest {
    @Test void aGuardFollowsItsChargeInAnyGameMode() {
        assertTrue(VanillaCombatWorld.seen(false, false));
        assertFalse(VanillaCombatWorld.seen(true, false), "a creative or spectator player is not a target");
        assertTrue(VanillaCombatWorld.seen(true, true), "a guard's charge counts in creative and spectator");
    }
}
