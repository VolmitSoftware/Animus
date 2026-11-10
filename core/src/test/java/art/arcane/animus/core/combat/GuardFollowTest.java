package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardFollowTest {
    static final CombatSettings.Guard GUARD = new CombatSettings.Guard(Optional.empty(), Optional.empty(), 4, 2, false, 24, 1.5, true);
    static WorldPosition at(double x, double z) { return new WorldPosition("world", new Vec3(x, 1, z)); }

    @Test void holdsWithinFollowDistanceAndWalksToAPointBehindTheMovingCharge() {
        GuardFollow follow = new GuardFollow();
        assertEquals(GuardFollow.Action.HOLD, follow.next(0, at(0, 0), at(3, 0), GUARD, 20).action());
        GuardFollow.Step step = follow.next(5, at(0, 0), at(8, 0), GUARD, 20);
        assertEquals(GuardFollow.Action.WALK, step.action());
        assertEquals(new Vec3(6, 1, 0), step.goal().point(), "two blocks behind a charge walking along +x");
        assertFalse(step.hurry());
        assertTrue(follow.next(10, at(0, 0), at(12, 0), GUARD, 20).hurry(), "far behind walks faster");
    }

    @Test void replansOnlyWhenTheChargeMovedOrThePlanWentStaleAndNeverFasterThanTheMinimum() {
        GuardFollow follow = new GuardFollow();
        assertEquals(GuardFollow.Action.WALK, follow.next(0, at(0, 0), at(8, 0), GUARD, 20).action());
        follow.planned(0, at(8, 0));
        assertEquals(GuardFollow.Action.HOLD, follow.next(GuardFollow.MINIMUM_REPLAN_TICKS - 1, at(0, 0), at(12, 0), GUARD, 20).action(), "bounded");
        assertEquals(GuardFollow.Action.HOLD, follow.next(6, at(1, 0), at(9, 0), GUARD, 20).action(), "moved only a block");
        assertEquals(GuardFollow.Action.WALK, follow.next(7, at(1, 0), at(10, 0), GUARD, 20).action(), "moved past replanDistance");
        follow.planned(7, at(10, 0));
        assertEquals(GuardFollow.Action.WALK, follow.next(27, at(2, 0), at(10, 0), GUARD, 20).action(), "stale plan");
    }

    @Test void teleportsToCatchUpWhenTooFarOrInAnotherWorld() {
        GuardFollow follow = new GuardFollow();
        assertEquals(GuardFollow.Action.CATCH_UP, follow.next(0, at(0, 0), at(30, 0), GUARD, 20).action());
        GuardFollow.Step crossed = follow.next(5, at(0, 0), new WorldPosition("nether", new Vec3(5, 70.4, 5)), GUARD, 20);
        assertEquals(GuardFollow.Action.CATCH_UP, crossed.action());
        assertEquals("nether", crossed.goal().world());
        assertEquals(70, crossed.goal().point().y());
        CombatSettings.Guard noTeleport = new CombatSettings.Guard(Optional.empty(), Optional.empty(), 4, 2, false, 0, 1.5, true);
        assertEquals(GuardFollow.Action.WALK, new GuardFollow().next(0, at(0, 0), at(30, 0), noTeleport, 20).action());
        assertEquals(new Vec3(30, 1, 0), GuardFollow.catchUpSpots(crossed.goal(), at(30, 0)).getLast());
    }

    @Test void aStandingChargeIsApproachedFromTheGuardsSide() {
        GuardFollow.Step step = new GuardFollow().next(0, at(0, 0), at(0, 10), GUARD, 20);
        assertEquals(new Vec3(0, 1, 8), step.goal().point());
    }
}
