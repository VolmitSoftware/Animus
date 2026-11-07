package art.arcane.animus.core.virtual;

import art.arcane.animus.core.npc.BodyVariant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DragonMotionTest {
    private static final Vec3 HERE = new Vec3(0, 61, 0), THERE = new Vec3(1, 61, 0);

    @Test void shouldSitOnGroundAndHoverInAirWhileResting() {
        DragonMotion motion = new DragonMotion();
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, motion.observe(1, HERE, true, DragonBody.AUTOMATIC));
        assertEquals(DragonBody.PHASE_HOVERING, motion.observe(2, HERE, false, DragonBody.AUTOMATIC));
    }

    @Test void shouldFlyWhileMovingAndSettleAfterTheRestTicks() {
        DragonMotion motion = new DragonMotion();
        motion.observe(1, HERE, true, DragonBody.AUTOMATIC);
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, motion.observe(2, THERE, true, DragonBody.AUTOMATIC));
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, motion.observe(2 + DragonMotion.REST_TICKS - 1, THERE, true, DragonBody.AUTOMATIC));
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, motion.observe(2 + DragonMotion.REST_TICKS, THERE, true, DragonBody.AUTOMATIC));
    }

    @Test void shouldRestInTheDefinitionsPhaseWhereverItStands() {
        DragonMotion motion = new DragonMotion();
        assertEquals(DragonBody.PHASE_HOVERING, motion.observe(1, HERE, true, DragonBody.PHASE_HOVERING));
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, motion.observe(2, HERE, false, DragonBody.PHASE_SITTING_SCANNING));
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, motion.observe(3, THERE, false, DragonBody.PHASE_SITTING_SCANNING));
    }

    @Test void restingPhaseComesFromTheDragonSetting() {
        assertEquals(DragonBody.AUTOMATIC, DragonBody.restingPhase(BodyVariant.NONE));
        assertEquals(DragonBody.PHASE_HOVERING, DragonBody.restingPhase(new BodyVariant("dragon", java.util.Map.of("phase", "hovering"), java.util.Set.of())));
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, DragonBody.restingPhase(new BodyVariant("dragon", java.util.Map.of("phase", "sitting"), java.util.Set.of())));
    }
}
