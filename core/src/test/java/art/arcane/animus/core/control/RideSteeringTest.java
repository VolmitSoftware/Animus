package art.arcane.animus.core.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RideSteeringTest {
    private static final MovementProfile PLAYER = BodyMobility.forBody("minecraft:player", 0.6, 1.8);
    private static final MovementProfile HORSE = BodyMobility.forBody("minecraft:horse", 1.4, 2.6);
    private static final MovementProfile DRAGON = BodyMobility.forBody("minecraft:ender_dragon", 4, 8);
    /** Solid below y=64 and air above, unless overridden; the real movement checks decide every step. */
    private static class World {
        final Map<List<Integer>, Cell> cells = new HashMap<>();
        boolean unavailable;
        int floorAt(int x, int z) { return 64; }
        Cell cell(int x, int y, int z) {
            if (unavailable) return Cell.UNAVAILABLE;
            Cell set = cells.get(List.of(x, y, z));
            if (set != null) return set;
            return y < floorAt(x, z) ? Cell.SOLID : Cell.AIR;
        }
        void row(int z, int y, Cell cell) { for (int x = -6; x <= 6; x++) cells.put(List.of(x, y, z), cell); }
        RideSteering.Probe probe(MovementProfile body) { return RideSteering.probe(body, 1024, this::cell); }
    }
    private static World dropAhead(int floor) {
        return new World() { @Override int floorAt(int x, int z) { return z >= 2 ? floor : 64; } };
    }
    private static RideSteering.Result walk(World world, MovementProfile body, double fromZ, double toZ, double y) {
        return world.probe(body).check(new Vec3(0.5, y, fromZ), new Vec3(0.5, y, toZ));
    }

    @Test void flatGroundMovesToTheProposal() {
        RideSteering.Result result = walk(new World(), PLAYER, 0.5, 0.7, 64);
        assertEquals(RideSteering.Outcome.MOVED, result.outcome());
        assertEquals(new Vec3(0.5, 64, 0.7), result.position());
        assertTrue(result.grounded());
    }
    @Test void grassFlowersAndCarpetDoNotStopTheRide() {
        World world = new World();
        world.row(2, 64, Cell.AIR);
        world.row(3, 64, Cell.layer(1 / 16d));
        RideSteering.Result onto = walk(world, HORSE, 2.3, 2.5, 64);
        assertEquals(RideSteering.Outcome.MOVED, onto.outcome(), onto.detail());
        assertEquals(64 + 1 / 16d, onto.position().y(), 1e-9, "the body stands on the carpet");
    }
    @Test void slabsAndStairsAreClimbedAtTheirRealHeights() {
        World world = new World();
        world.row(2, 64, Cell.layer(0.5));
        RideSteering.Result slab = walk(world, PLAYER, 1.6, 1.8, 64);
        assertEquals(RideSteering.Outcome.MOVED, slab.outcome(), slab.detail());
        assertEquals(64.5, slab.position().y(), 1e-9);
        world.row(3, 64, Cell.SOLID);
        RideSteering.Result next = walk(world, PLAYER, 2.6, 2.8, 64.5);
        assertEquals(65, next.position().y(), 1e-9);
    }
    @Test void aFullBlockIsClimbedButAFenceOrWallIsNot() {
        World block = new World();
        block.row(2, 64, Cell.SOLID);
        assertEquals(65, walk(block, PLAYER, 1.65, 1.85, 64).position().y(), 1e-9);
        World fence = new World();
        fence.row(2, 64, Cell.layer(1.5));
        Vec3 from = new Vec3(0.5, 64, 1.65);
        RideSteering.Result result = fence.probe(PLAYER).check(from, new Vec3(0.5, 64, 1.85));
        assertEquals(RideSteering.Outcome.BLOCKED, result.outcome());
        assertEquals(from, result.position());
        assertEquals(RideSteering.Outcome.BLOCKED, fence.probe(HORSE).check(from, new Vec3(0.5, 64, 1.85)).outcome());
    }
    @Test void walledColumnStaysBlockedAtTheAuthoritativePosition() {
        World world = new World();
        for (int y = 64; y <= 69; y++) world.row(2, y, Cell.SOLID);
        Vec3 from = new Vec3(0.5, 64, 1.65);
        RideSteering.Result result = world.probe(PLAYER).check(from, new Vec3(0.5, 64, 1.85));
        assertEquals(RideSteering.Outcome.BLOCKED, result.outcome());
        assertEquals(from, result.position());
    }
    @Test void bodyHangingOverAnEdgeStaysSupportedUntilNothingIsUnderIt() {
        RideSteering.Result overhanging = walk(dropAhead(61), PLAYER, 2.05, 2.25, 64);
        assertEquals(RideSteering.Outcome.MOVED, overhanging.outcome());
        assertEquals(64, overhanging.position().y(), 1e-9, "one supporting block is enough, as with a vanilla entity");
    }
    @Test void missingSupportDescendsAtMostThreeBlocks() {
        RideSteering.Result result = walk(dropAhead(61), PLAYER, 2.15, 2.35, 64);
        assertEquals(RideSteering.Outcome.MOVED, result.outcome(), result.detail());
        assertEquals(61, result.position().y(), 1e-9);
        assertEquals(RideSteering.Outcome.BLOCKED, walk(dropAhead(40), PLAYER, 2.15, 2.35, 64).outcome());
    }
    @Test void hazardsAreRefused() {
        World world = new World();
        world.row(2, 64, Cell.of(List.of(), Cell.Fluid.NONE, true));
        assertEquals(RideSteering.Outcome.BLOCKED, walk(world, PLAYER, 1.6, 1.8, 64).outcome());
        World lava = dropAhead(62);
        lava.row(2, 62, Cell.LAVA);
        lava.row(2, 63, Cell.LAVA);
        assertEquals(RideSteering.Outcome.BLOCKED, walk(lava, PLAYER, 2.15, 2.35, 64).outcome());
    }
    @Test void aSwimmingBodyCrossesWaterAtTheSurfaceAndClimbsOut() {
        World pond = dropAhead(60);
        for (int z = 2; z <= 8; z++) for (int y = 60; y <= 63; y++) pond.row(z, y, Cell.WATER);
        RideSteering.Result in = walk(pond, HORSE, 2.6, 2.8, 64);
        assertEquals(RideSteering.Outcome.MOVED, in.outcome(), in.detail());
        assertEquals(63, in.position().y(), 1e-9);
        assertFalse(in.grounded());
        RideSteering.Result across = walk(pond, HORSE, 4.5, 4.7, 63);
        assertEquals(63, across.position().y(), 1e-9);
        World bank = new World() { @Override int floorAt(int x, int z) { return z <= 5 ? 60 : 64; } };
        for (int z = -6; z <= 5; z++) for (int y = 60; y <= 63; y++) bank.row(z, y, Cell.WATER);
        RideSteering.Result out = walk(bank, HORSE, 5.1, 5.4, 63);
        assertEquals(RideSteering.Outcome.MOVED, out.outcome(), out.detail());
        assertEquals(64, out.position().y(), 1e-9);
        assertEquals(RideSteering.Outcome.BLOCKED, walk(pond, BodyMobility.forBody("minecraft:armor_stand", 0.5, 2), 2.6, 2.8, 64).outcome());
    }
    @Test void aFlyingBodyMovesThroughClearAirAndSettlesOnGround() {
        World world = new World();
        RideSteering.Probe flight = world.probe(DRAGON);
        RideSteering.Result up = flight.check(new Vec3(0.5, 70, 0.5), new Vec3(0.5, 70.2, 0.5));
        assertEquals(RideSteering.Outcome.MOVED, up.outcome());
        assertFalse(up.grounded());
        RideSteering.Result landing = flight.check(new Vec3(0.5, 64.1, 0.5), new Vec3(0.5, 63.9, 0.5));
        assertEquals(RideSteering.Outcome.MOVED, landing.outcome(), landing.detail());
        assertEquals(64, landing.position().y(), 1e-9);
        assertTrue(landing.grounded());
    }
    @Test void hoveringIsOnlyAPlaceToStandForAFlyer() {
        World world = new World();
        Vec3 air = new Vec3(0.5, 70, 0.5), low = new Vec3(0.5, 65.5, 0.5);
        assertEquals(air, world.probe(DRAGON).check(air, air).position());
        assertEquals(RideSteering.Outcome.BLOCKED, world.probe(PLAYER).check(air, air).outcome());
        assertEquals(64, world.probe(PLAYER).check(low, low).position().y(), 1e-9, "a walker settles instead of hovering");
    }
    @Test void unavailableTerrainIsReportedNeverGuessed() {
        World world = new World(); world.unavailable = true;
        Vec3 from = new Vec3(0.5, 64, 0.5);
        RideSteering.Result result = world.probe(PLAYER).check(from, new Vec3(0.5, 64, 0.7));
        assertEquals(RideSteering.Outcome.TERRAIN_UNAVAILABLE, result.outcome());
        assertEquals(from, result.position());
    }
    @Test void dismountCandidatesStandBesideTheBodyInAStableOrder() {
        List<Vec3> candidates = RideSteering.dismountCandidates(new Vec3(10, 64, 10), 0, 0.7);
        assertEquals(4, candidates.size());
        double reach = 0.7 + RideSteering.DISMOUNT_CLEARANCE;
        assertEquals(new Vec3(10 + reach, 64, 10), candidates.get(0));
        assertEquals(new Vec3(10 - reach, 64, 10), candidates.get(1));
        assertEquals(10 - reach, candidates.get(2).z(), 1e-9);
        assertEquals(10 + reach, candidates.get(3).z(), 1e-9);
    }
}
