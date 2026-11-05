package art.arcane.animus.core.virtual;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DragonMotionTest {
    private static final Vec3 HERE = new Vec3(0, 61, 0), THERE = new Vec3(1, 61, 0);

    @Test void shouldSitOnGroundAndHoverInAirWhileResting() {
        DragonMotion motion = new DragonMotion();
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, motion.observe(1, HERE, true));
        assertEquals(DragonBody.PHASE_HOVERING, motion.observe(2, HERE, false));
    }

    @Test void shouldFlyWhileMovingAndSettleAfterTheRestTicks() {
        DragonMotion motion = new DragonMotion();
        motion.observe(1, HERE, true);
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, motion.observe(2, THERE, true));
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, motion.observe(2 + DragonMotion.REST_TICKS - 1, THERE, true));
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, motion.observe(2 + DragonMotion.REST_TICKS, THERE, true));
    }
}
