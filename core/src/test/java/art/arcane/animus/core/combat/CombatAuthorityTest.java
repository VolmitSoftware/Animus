package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatAuthorityTest {
    @Test void aReservedAttackCannotBeReenteredEvenAfterItsCooldown() {
        StrikeWindow window = new StrikeWindow();
        assertTrue(window.reserve(10, 20));
        assertFalse(window.reserve(40, 20));
        window.release();
        assertTrue(window.reserve(40, 20));
        window.release();
        assertFalse(window.reserve(59, 20));
        assertTrue(window.reserve(60, 20));
    }
    @Test void deniedAttemptsStillConsumeTheirCooldownAndCloseRevokesAuthority() {
        StrikeWindow window = new StrikeWindow();
        assertTrue(window.reserve(0, 10));
        window.release(); // cancelled event or protection denial
        assertFalse(window.reserve(9, 10));
        window.close();
        assertFalse(window.reserve(10, 10));
        assertTrue(window.closed());
    }
    @Test void aClockCannotRewindOrOverflowIntoUnlimitedAttacks() {
        StrikeWindow window = new StrikeWindow();
        assertTrue(window.reserve(20, 5)); window.release();
        assertThrows(IllegalArgumentException.class, () -> window.reserve(19, 5));
        assertTrue(window.reserve(Long.MAX_VALUE - 1, 20)); window.release();
        assertFalse(window.reserve(Long.MAX_VALUE, 20));
        assertFalse(window.reserve(Long.MAX_VALUE, 20));
    }
    @Test void reachMeasuresTheActualAuthoritativeBounds() {
        Hitbox box = Hitbox.atFeet(new Vec3(3, 0, 0), 1, 2);
        assertEquals(2.5, CombatGeometry.reach(new Vec3(0, 1, 0), box));
        assertEquals(0, CombatGeometry.reach(new Vec3(3, 1, 0), box));
        assertEquals(new Vec3(3, 1, 0), CombatGeometry.center(box));
        assertEquals(new Vec3(2.5, 2, .5), CombatGeometry.nearest(box, new Vec3(0, 10, 10)));
    }
    @Test void anAttacksParametersCannotDisableSafetyBounds() {
        assertThrows(IllegalArgumentException.class, () -> new AttackProfile(Double.NaN, 2, 20));
        assertThrows(IllegalArgumentException.class, () -> new AttackProfile(1, Double.POSITIVE_INFINITY, 20));
        assertThrows(IllegalArgumentException.class, () -> new AttackProfile(1, 6.1, 20));
        assertThrows(IllegalArgumentException.class, () -> new AttackProfile(1, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new AttackProfile(101, 2, 1));
    }
}
