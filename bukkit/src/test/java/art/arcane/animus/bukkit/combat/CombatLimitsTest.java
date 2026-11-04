package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.AttackProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatLimitsTest {
    @Test void mobPlansAndOwnershipCannotGrowWithoutBounds() {
        assertThrows(IllegalArgumentException.class, () -> new CombatLimits(1, 33, 4, 1, 256, 2_000_000));
        assertThrows(IllegalArgumentException.class, () -> new CombatLimits(1, 32, 4, 2, 256, 2_000_000));
        assertThrows(IllegalArgumentException.class, () -> new CombatLimits(1, 32, 4, 1, 513, 2_000_000));
        assertThrows(IllegalArgumentException.class, () -> new MobProfile(AttackProfile.DEFAULT, 1, 17, 20, 100));
        assertThrows(IllegalArgumentException.class, () -> new MobProfile(AttackProfile.DEFAULT, 1, 12, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> new MobProfile(AttackProfile.DEFAULT, 1, 12, 20, Integer.MAX_VALUE));
    }
}
