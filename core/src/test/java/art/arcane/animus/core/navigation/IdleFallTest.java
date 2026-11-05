package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.MotionValidator.Sampler;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdleFallTest {
    private static final Sampler GROUND_AT_ZERO = (int x, int y, int z) -> y == 0 ? Cell.SOLID : Cell.AIR;

    @Test void aBodyStandingOnABlockStaysWhereItIs() {
        IdleFall.Step step = IdleFall.step(new Vec3(.5, 1, .5), 0, .6, 1.8, true, GROUND_AT_ZERO);
        assertEquals(IdleFall.Outcome.SUPPORTED, step.outcome());
        assertEquals(new Vec3(.5, 1, .5), step.to());
    }
    @Test void aBodyLeftInTheAirFallsUnderGravityAndLandsOnTheGroundBelow() {
        Vec3 feet = new Vec3(.5, 9, .5);
        double speed = 0;
        int ticks = 0;
        IdleFall.Step step;
        do {
            step = IdleFall.step(feet, speed, .6, 1.8, true, GROUND_AT_ZERO);
            assertTrue(step.to().y() <= feet.y(), "a fall never rises");
            assertTrue(step.sampled() > 0);
            feet = step.to(); speed = step.speed(); ticks++;
        } while (step.outcome() == IdleFall.Outcome.FALLING && ticks < 200);
        assertEquals(IdleFall.Outcome.LANDED, step.outcome());
        assertEquals(new Vec3(.5, 1, .5), feet);
        assertEquals(0, step.speed(), 1e-9);
        assertTrue(ticks > 3, "the fall takes several ticks instead of a teleport");
    }
    @Test void theFirstTickOfAFallMovesOnlyAsFarAsGravityAllows() {
        IdleFall.Step step = IdleFall.step(new Vec3(.5, 9, .5), 0, .6, 1.8, true, GROUND_AT_ZERO);
        assertEquals(IdleFall.Outcome.FALLING, step.outcome());
        assertEquals(9 - step.speed(), step.to().y(), 1e-9);
        assertTrue(step.speed() > 0 && step.speed() < .1);
    }
    @Test void aFallDeeperThanOneSearchKeepsFallingAtTerminalSpeed() {
        IdleFall.Step step = IdleFall.step(new Vec3(.5, 100, .5), IdleFall.TERMINAL, .6, 1.8, true, GROUND_AT_ZERO);
        assertEquals(IdleFall.Outcome.FALLING, step.outcome());
        assertEquals(100 - IdleFall.TERMINAL, step.to().y(), 1e-9);
        assertEquals(IdleFall.TERMINAL, step.speed(), 1e-9);
    }
    @Test void aSwimmingBodyStopsAtTheWaterSurface() {
        Sampler pool = (int x, int y, int z) -> y == 0 ? Cell.SOLID : y >= 1 && y <= 3 ? Cell.WATER : Cell.AIR;
        IdleFall.Step step = IdleFall.step(new Vec3(.5, 4.05, .5), 1, .6, 1.8, true, pool);
        assertEquals(IdleFall.Outcome.LANDED, step.outcome());
        assertEquals(3, step.to().y(), 1e-9);
    }
    @Test void unknownTerrainHoldsTheBodyInsteadOfDroppingIt() {
        Sampler unloaded = (int x, int y, int z) -> y < 5 ? Cell.UNAVAILABLE : Cell.AIR;
        IdleFall.Step step = IdleFall.step(new Vec3(.5, 9, .5), 0, .6, 1.8, true, unloaded);
        assertEquals(IdleFall.Outcome.HELD, step.outcome());
        assertEquals(new Vec3(.5, 9, .5), step.to());
        assertEquals(0, step.speed(), 1e-9);
    }
    @Test void largeHullsStayWithinTheCellBudget() {
        IdleFall.Step step = IdleFall.step(new Vec3(.5, 30, .5), IdleFall.TERMINAL, 4, 8, true, GROUND_AT_ZERO);
        assertEquals(IdleFall.Outcome.FALLING, step.outcome());
        assertTrue(step.sampled() > 0 && step.sampled() <= IdleFall.cells(4, 8));
        assertTrue(IdleFall.cells(.6, 1.8) < 100, "an ordinary body reads a small column");
    }
    @Test void onlyABlockUnderOrBesideTheFeetAffectsTheBody() {
        Vec3 feet = new Vec3(.5, 65, .5);
        assertTrue(IdleFall.affects(feet, .6, new BlockPos(0, 64, 0)), "the block it stands on");
        assertTrue(IdleFall.affects(feet, .6, new BlockPos(0, 65, 0)), "a block at its feet, such as a slab or carpet");
        assertTrue(IdleFall.affects(new Vec3(.95, 65, .5), .6, new BlockPos(1, 64, 0)), "a block under an overhanging edge");
        assertFalse(IdleFall.affects(feet, .6, new BlockPos(0, 63, 0)), "a block below the one it stands on");
        assertFalse(IdleFall.affects(feet, .6, new BlockPos(0, 67, 0)), "a block above its feet");
        assertFalse(IdleFall.affects(feet, .6, new BlockPos(2, 64, 0)), "a neighbouring column");
        assertTrue(IdleFall.affects(new Vec3(.5, 65, .5), 4, new BlockPos(2, 64, 2)), "a wide hull covers several columns");
    }
    @Test void aBlockChangeFindsOnlyTheNpcsStandingOnIt() {
        SpatialNpcIndex index = new SpatialNpcIndex(16);
        UUID roof = UUID.randomUUID(), beside = UUID.randomUUID(), below = UUID.randomUUID(), elsewhere = UUID.randomUUID();
        index.upsert(spawned(roof, new WorldPosition("world", new Vec3(15.5, 70, 15.5))));
        index.upsert(spawned(beside, new WorldPosition("world", new Vec3(17.5, 70, 15.5))));
        index.upsert(spawned(below, new WorldPosition("world", new Vec3(15.5, 64, 15.5))));
        index.upsert(spawned(elsewhere, new WorldPosition("nether", new Vec3(15.5, 70, 15.5))));
        assertEquals(List.of(roof), IdleFall.standingOn(index, "world", new BlockPos(15, 69, 15)));
        assertEquals(List.of(), IdleFall.standingOn(index, "world", new BlockPos(15, 60, 15)));
    }
    private static VirtualNpcState.Snapshot spawned(UUID id, WorldPosition at) {
        VirtualNpcState state = new VirtualNpcState(id, at, .6, 1.8, 20);
        return state.spawn();
    }
    @Test void flyingAndAquaticBodiesDoNotFall() {
        assertTrue(BodyMobility.falls("minecraft:player"));
        assertTrue(BodyMobility.falls("minecraft:armor_stand"));
        assertFalse(BodyMobility.falls("minecraft:phantom"));
        assertFalse(BodyMobility.falls("minecraft:dolphin"));
    }
}
