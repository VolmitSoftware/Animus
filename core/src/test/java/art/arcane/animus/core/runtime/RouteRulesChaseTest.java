package art.arcane.animus.core.runtime;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a combat chase goes on when its goal moves, the same on the single owner thread and on Folia's region owners. */
class RouteRulesChaseTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-00000000c4a5", OTHER = "8a0f6f1c-0000-4000-8000-00000000c4a6";
    private static WorldPosition at(String world, double x) { return new WorldPosition(world, new Vec3(x, 64, 0)); }

    @Test void aGoalThatMovedLessThanABlockKeepsThePlan() {
        assertEquals(RouteRules.ChaseStep.KEEP, RouteRules.chaseStep(true, at(WORLD, 10), 4, at(WORLD, 10.9), 4));
    }
    @Test void aGoalThatMovedFartherIsPlannedAgainWhileTheChaseGoesOn() {
        assertEquals(RouteRules.ChaseStep.REPLAN, RouteRules.chaseStep(true, at(WORLD, 10), 4, at(WORLD, 11), 4));
        assertEquals(RouteRules.ChaseStep.REPLAN, RouteRules.chaseStep(true, at(WORLD, 10), 4, at(WORLD, 10.2), 6), "a new speed plans again");
    }
    @Test void noChaseOrAnotherWorldStartsAFreshRoute() {
        assertEquals(RouteRules.ChaseStep.START, RouteRules.chaseStep(false, at(WORLD, 10), 4, at(WORLD, 10), 4));
        assertEquals(RouteRules.ChaseStep.START, RouteRules.chaseStep(true, at(WORLD, 10), 4, at(OTHER, 10), 4));
    }
}
