package art.arcane.animus.core.navigation;

import static art.arcane.animus.core.navigation.MotionValidator.Medium;
import static art.arcane.animus.core.navigation.MotionValidator.Sampler;
import static art.arcane.animus.core.navigation.MotionValidator.Status;
import static art.arcane.animus.core.navigation.MotionValidator.check;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MotionValidatorTest {
    private static final Vec3 START = new Vec3(.5, 1, .5);
    private static final Cell AIR = Cell.AIR, SOLID = Cell.SOLID, WATER = Cell.WATER, LAVA = Cell.LAVA, UNAVAILABLE = Cell.UNAVAILABLE;

    @Test void revalidatesNewObstructionInsteadOfTrustingPreviouslyClearPath() {
        Sampler clear = (x, y, z) -> y == 0 ? SOLID : AIR;
        Vec3 next = new Vec3(.75, 1, .5);
        assertTrue(check(START, next, .6, 1.8, Medium.GROUND, true, 128, clear).clear());
        assertEquals(Status.BLOCKED, check(START, next, .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> x == 1 && y == 1 ? SOLID : clear.sample(x, y, z)).status());
    }
    /**
     * An NPC placed at a whole height on a closed trapdoor stands inside it. It may rise out onto its top, but not move
     * on through it while still inside.
     */
    @Test void aBodyInsideALowSurfaceRisesOutOfItButCannotMoveThroughIt() {
        Sampler trapdoor = (x, y, z) -> y == 0 ? SOLID : y == 1 ? Cell.layer(0.1875) : AIR;
        assertTrue(check(START, new Vec3(.5, 1.1875, .5), .6, 1.8, Medium.GROUND, true, 128, trapdoor).clear());
        assertTrue(check(START, new Vec3(.5, 1.0625, .5), .6, 1.8, Medium.GROUND, false, 128, trapdoor).clear(), "a step part of the way out");
        assertEquals(Status.BLOCKED, check(START, new Vec3(.75, 1, .5), .6, 1.8, Medium.GROUND, true, 128, trapdoor).status());
    }
    @Test void unavailableTerrainIsNotAirOrARequestToSampleFurtherCells() {
        AtomicInteger calls = new AtomicInteger();
        MotionValidator.Result result = check(START, new Vec3(.75, 1, .5), .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> { calls.incrementAndGet(); return UNAVAILABLE; });
        assertEquals(Status.UNAVAILABLE_TERRAIN, result.status());
        assertEquals(1, calls.get());
    }
    @Test void volumeBudgetRejectsBeforeWorldReadsAndCountsActualReads() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(Status.BUDGET_EXHAUSTED, check(START, START, 4, 8, Medium.GROUND, true, 8, (x, y, z) -> { calls.incrementAndGet(); return AIR; }).status());
        assertEquals(0, calls.get());
        MotionValidator.Result result = check(START, START, .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> { calls.incrementAndGet(); return y == 0 ? SOLID : AIR; });
        assertTrue(result.clear());
        assertEquals(calls.get(), result.sampledCells());
        assertEquals(3, result.sampledCells(), "the support layer and the two voxels the body occupies");
    }
    @Test void swimmingNeedsFluidOrSupportWhileFlyingNeedsNeither() {
        Vec3 next = new Vec3(.5, 1.25, .5);
        assertTrue(check(START, next, .6, 1.8, Medium.WATER, false, 128, (x, y, z) -> WATER).clear());
        assertEquals(Status.BLOCKED, check(START, next, .6, 1.8, Medium.WATER, false, 128, (x, y, z) -> AIR).status());
        assertTrue(check(START, next, .6, 1.8, Medium.AIR, false, 128, (x, y, z) -> AIR).clear());
        assertEquals(Status.BLOCKED, check(START, next, .6, 1.8, Medium.AIR, false, 128, (x, y, z) -> WATER).status());
    }
    @Test void anyBlockUnderTheHullSupportsItButSweptBodiesStillCollide() {
        Vec3 next = new Vec3(.7, 1, .7);
        assertTrue(check(new Vec3(.8, 1, .8), next, .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> y == 0 ? (x == 1 && z == 0 ? AIR : SOLID) : AIR).clear());
        assertEquals(Status.BLOCKED, check(new Vec3(.8, 1, .8), next, .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> AIR).status());
        assertEquals(Status.BLOCKED, check(START, START, 1.4, 1.8, Medium.AIR, false, 128, (x, y, z) -> x != 0 ? SOLID : AIR).status());
        assertEquals(Status.UNSUPPORTED_GEOMETRY, check(START, START, Double.NaN, 1.8, Medium.AIR, false, 128, (x, y, z) -> AIR).status());
        assertEquals(Status.UNSUPPORTED_GEOMETRY, check(START, new Vec3(3, 1, .5), .6, 1.8, Medium.AIR, false, 128, (x, y, z) -> AIR).status());
    }
    @Test void partialSurfacesCarryTheBodyAtTheirExactHeight() {
        Sampler slab = (x, y, z) -> y == 0 ? SOLID : y == 1 ? Cell.layer(.5) : AIR;
        Vec3 on = new Vec3(.5, 1.5, .5);
        assertTrue(check(on, new Vec3(.7, 1.5, .5), .6, 1.8, Medium.GROUND, true, 128, slab).clear());
        assertEquals(Status.BLOCKED, check(new Vec3(.5, 1.4, .5), new Vec3(.5, 1.4, .5), .6, 1.8, Medium.GROUND, true, 128, slab).status(), "inside the slab");
        assertEquals(Status.BLOCKED, check(new Vec3(.5, 1.6, .5), new Vec3(.5, 1.6, .5), .6, 1.8, Medium.GROUND, true, 128, slab).status(), "floating above the slab");
    }
    @Test void aFenceTopOverhangsTheVoxelAboveItButNotItsNeighbours() {
        Sampler fence = (x, y, z) -> y == 0 && x == 0 ? Cell.layer(1.5) : y == 0 ? SOLID : AIR;
        assertEquals(Status.BLOCKED, check(new Vec3(.5, 1.25, .5), new Vec3(.5, 1.25, .5), .6, 1.8, Medium.AIR, false, 128, fence).status());
        assertTrue(check(new Vec3(.5, 1.5, .5), new Vec3(.5, 1.5, .5), .6, 1.8, Medium.GROUND, true, 128, fence).clear());
        assertTrue(check(new Vec3(1.5, 1, .5), new Vec3(1.5, 1, .5), .6, 1.8, Medium.GROUND, true, 128, fence).clear(), "standing beside a fence is fine");
    }
    @Test void plantsAndOtherShapelessBlocksCollideWithNothing() {
        Cell grass = Cell.of(List.of(), Cell.Fluid.NONE, false);
        assertEquals(AIR, grass);
        assertTrue(check(START, new Vec3(1.2, 1, .5), .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> y == 0 ? SOLID : y == 1 ? grass : AIR).clear());
    }
    @Test void hazardsStopEntryButLetABodyWalkOut() {
        Cell fire = Cell.of(List.of(), Cell.Fluid.NONE, true);
        Sampler ahead = (x, y, z) -> y == 0 ? SOLID : y == 1 && x == 1 ? fire : AIR;
        assertEquals(Status.HAZARD, check(new Vec3(.6, 1, .5), new Vec3(.8, 1, .5), .6, 1.8, Medium.GROUND, true, 128, ahead).status());
        assertTrue(check(new Vec3(.8, 1, .5), new Vec3(.6, 1, .5), .6, 1.8, Medium.GROUND, true, 128, ahead).clear());
        assertEquals(Status.HAZARD, check(new Vec3(1.5, 1, .5), new Vec3(1.5, 1, .5), .6, 1.8, Medium.GROUND, true, 128, ahead).status(), "nothing stands in fire");
        assertEquals(Status.HAZARD, check(START, START, .6, 1.8, Medium.AMPHIBIOUS_AIR, false, 128, (x, y, z) -> LAVA).status());
        Cell magma = Cell.of(List.of(new Cell.Box(0, 0, 0, 1, 1, 1)), Cell.Fluid.NONE, true);
        assertEquals(Status.HAZARD, check(START, START, .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> y == 0 ? magma : AIR).status(), "magma is not ground");
    }
    @Test void walkingWadesOneBlockOfWaterButAirMediumDoesNot() {
        Sampler stream = (x, y, z) -> y == 0 ? SOLID : y == 1 ? WATER : AIR;
        assertTrue(check(START, new Vec3(.7, 1, .5), .6, 1.8, Medium.GROUND, true, 128, stream).clear());
        assertEquals(Status.BLOCKED, check(START, START, .6, 1.8, Medium.AIR, false, 128, stream).status());
        assertEquals(Status.BLOCKED, check(START, START, .6, 1.8, Medium.GROUND, true, 128, (x, y, z) -> y == 0 ? SOLID : y < 3 ? WATER : AIR).status(),
                "over the head is not wading");
    }
    @Test void waterloggedShapesAreBothWaterAndCollision() {
        Cell logged = Cell.of(List.of(new Cell.Box(0, 0, 0, 1, .5, 1)), Cell.Fluid.WATER, false);
        Sampler pond = (x, y, z) -> y == 0 ? SOLID : y == 1 ? logged : AIR;
        assertEquals(Status.BLOCKED, check(START, START, .6, 1.8, Medium.WATER, false, 128, pond).status());
        Vec3 on = new Vec3(.5, 1.5, .5);
        assertTrue(check(on, on, .6, 1.8, Medium.WATER, false, 128, pond).clear());
    }
    @Test void dragonSizedBodyMovesWithinItsHullBudgetAtEveryAlignment() {
        Sampler floor = (x, y, z) -> y == 0 ? SOLID : AIR;
        double width = MovementProfile.MAX_WIDTH, height = MovementProfile.MAX_HEIGHT, step = .25;
        int budget = MotionValidator.cellBudget(width, height, step);
        assertTrue(budget > 256 && budget <= 4096);
        Vec3 hover = new Vec3(.9, 1.5, .9), ahead = new Vec3(1.15, 1.5, .9);
        assertEquals(Status.BUDGET_EXHAUSTED, check(hover, ahead, width, height, Medium.AIR, false, 256, floor).status());
        for (double ox = 0; ox < 1; ox += .05) for (double dy : new double[]{0, step, -step}) {
            Vec3 from = new Vec3(ox, 1 + Math.max(0, -dy), ox), to = new Vec3(ox + step * .6, 1 + Math.max(0, dy), ox + step * .6);
            to = from.add(to.subtract(from).multiply(Math.min(1, step / from.distance(to))));
            MotionValidator.Result result = check(from, to, width, height, Medium.AIR, false, budget, floor);
            assertTrue(result.status() != Status.BUDGET_EXHAUSTED, "budget too small at " + from + " -> " + to);
        }
        assertTrue(check(hover, ahead, width, height, Medium.AIR, false, budget, floor).clear());
        assertTrue(check(new Vec3(.9, 1, .9), new Vec3(1.15, 1, .9), width, height, Medium.GROUND, true, budget, floor).clear());
        assertTrue(MotionValidator.cellBudget(width, height, MotionValidator.MAX_SEGMENT) <= 4096);
    }
    @Test void layerUnderTheFeetBelowTheWorldReadsAsEmpty() {
        Sampler world = (x, y, z) -> y < 0 ? UNAVAILABLE : y == 0 ? SOLID : AIR;
        Vec3 inFloor = new Vec3(.5, 0, .5);
        assertEquals(Status.BLOCKED, check(inFloor, inFloor, .6, 1, Medium.AIR, false, 128, world).status());
        Vec3 deeper = new Vec3(.5, -1, .5);
        assertEquals(Status.UNAVAILABLE_TERRAIN, check(deeper, deeper, .6, 1, Medium.AIR, false, 128, world).status());
    }
}
