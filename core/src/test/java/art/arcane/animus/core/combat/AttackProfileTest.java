package art.arcane.animus.core.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the charged melee formula every platform's hit listener shares. */
class AttackProfileTest {
    @Test void chargedDamageFollowsVanillaScaling() {
        assertEquals(7, AttackProfile.charged(7, 1), 1e-12, "a full charge deals the base damage");
        assertEquals(1.4, AttackProfile.charged(7, 0), 1e-12, "an uncharged swing deals a fifth");
        assertEquals(2.8, AttackProfile.charged(7, .5), 1e-12, "half a charge deals .2 + .25 * .8 of the base");
        assertEquals(.2 + .81 * .8, AttackProfile.charged(1, .9), 1e-12);
    }
}
