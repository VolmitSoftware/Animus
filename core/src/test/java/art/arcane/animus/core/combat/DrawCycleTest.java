package art.arcane.animus.core.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The draw, charge and release timing of a drawn weapon from shot to shot. */
class DrawCycleTest {
    private final Random random = new Random(3);

    /** Aims every tick from {@code from} until it is ready, and returns the tick it became ready. */
    private long aimUntilReady(DrawCycle cycle, long from, long limit) {
        for (long tick = from; tick < limit; tick++) if (cycle.aim(tick, random).ready()) return tick;
        return -1;
    }

    @Test void aBowIsDrawnForAFullDrawBeforeItsShot() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.BOW, 0);
        assertFalse(cycle.aim(0, random).ready());
        assertTrue(cycle.drawing(), "the bow is drawn from the first turn");
        assertEquals(20, aimUntilReady(cycle, 1, 100), "a full draw takes a second");
        assertTrue(cycle.drawing(), "still drawn until it is let go");
    }

    @Test void aReleasedBowIsLoweredRestsAndIsDrawnAgainSoTheNextShotComesOnCooldown() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.BOW, 0);
        long first = aimUntilReady(cycle, 0, 100);
        DrawCycle.Sound shot = cycle.release(first, 40, random).orElseThrow();
        assertEquals("minecraft:entity.arrow.shoot", shot.id());
        assertFalse(cycle.drawing(), "let go with the shot");
        for (long tick = first + 1; tick < first + 20; tick++) {
            assertFalse(cycle.aim(tick, random).ready());
            assertFalse(cycle.drawing(), "lowered while it rests, tick " + tick);
        }
        cycle.aim(first + 20, random);
        assertTrue(cycle.drawing(), "drawn again for the next shot");
        assertEquals(first + 40, aimUntilReady(cycle, first + 21, 200), "the next draw ends as the cooldown does");
    }

    @Test void aCooldownShorterThanTheDrawStillShowsTheReleaseAndAFullDraw() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.BOW, 0);
        long first = aimUntilReady(cycle, 0, 100);
        cycle.release(first, 1, random);
        for (long tick = first + 1; tick < first + DrawCycle.RELEASE_TICKS; tick++) {
            cycle.aim(tick, random);
            assertFalse(cycle.drawing(), "the release shows before the next draw");
        }
        assertEquals(first + DrawCycle.RELEASE_TICKS + 20, aimUntilReady(cycle, first + 1, 200));
    }

    @Test void aSkeletonBodyShootsWithTheSkeletonSound() {
        DrawCycle cycle = new DrawCycle("minecraft:skeleton");
        cycle.use(DrawCycle.Style.BOW, 0);
        long ready = aimUntilReady(cycle, 0, 100);
        assertEquals("minecraft:entity.skeleton.shoot", cycle.release(ready, 40, random).orElseThrow().id());
    }

    @Test void aCrossbowChargesWithItsLoadingSoundsThenHoldsLoadedAndAims() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.CROSSBOW, 0);
        List<String> heard = new ArrayList<>();
        for (long tick = 0; tick < 25; tick++) {
            DrawCycle.Turn turn = cycle.aim(tick, random);
            for (DrawCycle.Sound sound : turn.sounds()) heard.add(tick + " " + sound.id());
            assertTrue(cycle.drawing(), "charging, tick " + tick);
            assertFalse(turn.loaded());
            assertFalse(turn.ready());
        }
        DrawCycle.Turn loaded = cycle.aim(25, random);
        assertTrue(loaded.loaded(), "loaded after 25 ticks of charge");
        assertFalse(cycle.drawing(), "a loaded crossbow is held, not charged");
        assertTrue(cycle.loaded());
        loaded.sounds().forEach((DrawCycle.Sound sound) -> heard.add("25 " + sound.id()));
        assertEquals(List.of("5 minecraft:item.crossbow.loading_start", "13 minecraft:item.crossbow.loading_middle", "25 minecraft:item.crossbow.loading_end"), heard);
        assertFalse(loaded.ready(), "aimed for a moment before it fires");
        for (long tick = 26; tick < 25 + DrawCycle.AIM_TICKS; tick++) assertFalse(cycle.aim(tick, random).ready());
        assertTrue(cycle.aim(25 + DrawCycle.AIM_TICKS, random).ready());
    }

    @Test void quickChargeShortensTheChargeAndChangesTheStartSound() {
        DrawCycle cycle = new DrawCycle("minecraft:pillager");
        cycle.use(DrawCycle.Style.CROSSBOW, 3);
        List<String> heard = new ArrayList<>();
        long loadedAt = -1;
        for (long tick = 0; tick < 40 && loadedAt < 0; tick++) {
            DrawCycle.Turn turn = cycle.aim(tick, random);
            turn.sounds().forEach((DrawCycle.Sound sound) -> heard.add(sound.id()));
            if (turn.loaded()) loadedAt = tick;
        }
        assertEquals(10, loadedAt, "Quick Charge III charges in half a second");
        assertEquals("minecraft:item.crossbow.quick_charge_3", heard.getFirst());
    }

    @Test void aFiredCrossbowIsEmptiedAndChargedAgainAfterTheRelease() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.CROSSBOW, 0);
        long ready = aimUntilReady(cycle, 0, 100);
        assertEquals("minecraft:item.crossbow.shoot", cycle.release(ready, 40, random).orElseThrow().id());
        assertFalse(cycle.loaded(), "emptied by the shot");
        assertFalse(cycle.drawing());
        for (long tick = ready + 1; tick < ready + DrawCycle.RELEASE_TICKS; tick++) {
            cycle.aim(tick, random);
            assertFalse(cycle.drawing(), "the shot shows before the next charge");
        }
        cycle.aim(ready + DrawCycle.RELEASE_TICKS, random);
        assertTrue(cycle.drawing(), "charged again right after the release");
    }

    @Test void aTridentIsRaisedHalfASecondThenThrown() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.TRIDENT, 0);
        assertEquals(10, aimUntilReady(cycle, 0, 100));
        assertTrue(cycle.drawing(), "raised until it is thrown");
        assertEquals("minecraft:item.trident.throw", cycle.release(10, 40, random).orElseThrow().id());
        assertFalse(cycle.drawing());
        DrawCycle drowned = new DrawCycle("minecraft:drowned");
        drowned.use(DrawCycle.Style.TRIDENT, 0);
        long thrown = aimUntilReady(drowned, 0, 100);
        assertEquals("minecraft:entity.drowned.shoot", drowned.release(thrown, 40, random).orElseThrow().id());
    }

    @Test void aThrownWeaponIsNeverDrawnAndMakesNoDrawSound() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.THROWN, 0);
        assertTrue(cycle.aim(0, random).ready());
        assertFalse(cycle.drawing());
        assertTrue(cycle.release(0, 40, random).isEmpty());
        assertTrue(cycle.aim(1, random).ready(), "a throw waits only for the cooldown");
    }

    @Test void aDrawThatCannotShootIsLoweredButALoadedCrossbowStaysLoaded() {
        DrawCycle bow = new DrawCycle("minecraft:player");
        bow.use(DrawCycle.Style.BOW, 0);
        bow.aim(0, random);
        bow.aim(1, random);
        bow.lower(2);
        assertFalse(bow.drawing(), "lowered when the target is out of reach");
        assertEquals(2 + DrawCycle.RELEASE_TICKS + 20, aimUntilReady(bow, 2, 100), "a lowered bow is drawn in full again");

        DrawCycle crossbow = new DrawCycle("minecraft:player");
        crossbow.use(DrawCycle.Style.CROSSBOW, 0);
        aimUntilReady(crossbow, 0, 100);
        crossbow.lower(40);
        assertTrue(crossbow.loaded(), "a loaded crossbow stays loaded between targets");
        assertTrue(crossbow.aim(41, random).ready());
    }

    @Test void endingTheFightOrChangingWeaponsLowersAndEmptiesIt() {
        DrawCycle cycle = new DrawCycle("minecraft:player");
        cycle.use(DrawCycle.Style.CROSSBOW, 0);
        aimUntilReady(cycle, 0, 100);
        assertTrue(cycle.end(), "the fight's end empties a loaded crossbow");
        assertFalse(cycle.loaded());
        assertFalse(cycle.end(), "nothing left to empty");
        cycle.use(DrawCycle.Style.CROSSBOW, 0);
        aimUntilReady(cycle, 0, 100);
        assertTrue(cycle.use(DrawCycle.Style.BOW, 0), "switching to a bow empties the crossbow");
        assertFalse(cycle.drawing());
        assertFalse(cycle.use(DrawCycle.Style.BOW, 0), "the same weapon changes nothing");
    }
}
