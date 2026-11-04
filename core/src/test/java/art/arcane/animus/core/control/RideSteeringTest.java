package art.arcane.animus.core.control;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.virtual.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RideSteeringTest {
    private static final double WIDTH = 0.6, HEIGHT = 1.8;
    /** Solid below y=64 and air above, unless overridden; the real MotionValidator decides every step. */
    private static class World {
        final Set<List<Integer>> solid = new HashSet<>(), obstacles = new HashSet<>();
        boolean unavailable;
        int checks;
        int floorAt(int x, int z) { return 64; }
        VoxelSnapshot.Cell cell(int x, int y, int z) {
            if (unavailable) return VoxelSnapshot.Cell.UNAVAILABLE;
            if (obstacles.contains(List.of(x, y, z))) return VoxelSnapshot.Cell.OBSTACLE;
            if (solid.contains(List.of(x, y, z))) return VoxelSnapshot.Cell.SOLID;
            return y < floorAt(x, z) ? VoxelSnapshot.Cell.SOLID : VoxelSnapshot.Cell.AIR;
        }
        RideSteering.Probe probe() {
            RideSteering.Probe real = RideSteering.probe(WIDTH, HEIGHT, 1024, this::cell);
            return (from, to, support) -> { checks++; return real.check(from, to, support); };
        }
    }
    private static World dropAhead(int floor) {
        return new World() { @Override int floorAt(int x, int z) { return z >= 2 ? floor : 64; } };
    }

    @Test void flatGroundMovesToTheProposal() {
        World world = new World();
        RideSteering.Result result = RideSteering.step(new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 0.7), world.probe());
        assertEquals(RideSteering.Outcome.MOVED, result.outcome());
        assertEquals(new Vec3(0.5, 64, 0.7), result.position());
    }
    @Test void oneBlockLedgeStepsUpWhenTheHeadroomIsClear() {
        World world = new World();
        for (int x = -3; x <= 4; x++) for (int z = 2; z <= 6; z++) world.solid.add(List.of(x, 64, z));
        RideSteering.Result result = RideSteering.step(new Vec3(0.5, 64, 1.65), new Vec3(0.5, 64, 1.85), world.probe());
        assertEquals(RideSteering.Outcome.MOVED, result.outcome(), result.detail());
        assertEquals(65, result.position().y(), 1e-9);
        assertEquals(1.85, result.position().z(), 1e-9);
    }
    @Test void walledColumnStaysBlockedAtTheAuthoritativePosition() {
        World world = new World();
        for (int y = 64; y <= 69; y++) for (int x = -3; x <= 4; x++) world.solid.add(List.of(x, y, 2));
        Vec3 from = new Vec3(0.5, 64, 1.65);
        RideSteering.Result result = RideSteering.step(from, new Vec3(0.5, 64, 1.85), world.probe());
        assertEquals(RideSteering.Outcome.BLOCKED, result.outcome());
        assertEquals(from, result.position());
    }
    @Test void bodyHangingOverAnEdgeStaysSupportedUntilNothingIsUnderIt() {
        World world = dropAhead(61);
        RideSteering.Result overhanging = RideSteering.step(new Vec3(0.5, 64, 2.05), new Vec3(0.5, 64, 2.25), world.probe());
        assertEquals(RideSteering.Outcome.MOVED, overhanging.outcome());
        assertEquals(64, overhanging.position().y(), 1e-9, "one supporting block is enough, as with a vanilla entity");
    }
    @Test void missingSupportDescendsAtMostThreeBlocks() {
        World world = dropAhead(61);
        RideSteering.Result result = RideSteering.step(new Vec3(0.5, 64, 2.15), new Vec3(0.5, 64, 2.35), world.probe());
        assertEquals(RideSteering.Outcome.MOVED, result.outcome(), result.detail());
        assertEquals(61, result.position().y(), 1e-9);
    }
    @Test void deeperDropIsRefusedWithoutMoving() {
        World world = dropAhead(40);
        Vec3 from = new Vec3(0.5, 64, 2.15);
        RideSteering.Result result = RideSteering.step(from, new Vec3(0.5, 64, 2.35), world.probe());
        assertEquals(RideSteering.Outcome.BLOCKED, result.outcome());
        assertEquals(from, result.position());
    }
    @Test void hoveringOrFractionalHeightIsNeverTreatedAsGround() {
        World world = new World();
        RideSteering.Probe probe = world.probe();
        assertEquals(MotionValidator.Status.BLOCKED, probe.check(new Vec3(0.5, 70, 0.5), new Vec3(0.5, 70, 0.5), true).status());
        assertEquals(MotionValidator.Status.UNSUPPORTED_GEOMETRY, probe.check(new Vec3(0.5, 64.5, 0.5), new Vec3(0.5, 64.5, 0.5), true).status());
    }
    @Test void unavailableTerrainIsReportedNeverGuessed() {
        World world = new World(); world.unavailable = true;
        Vec3 from = new Vec3(0.5, 64, 0.5);
        RideSteering.Result result = RideSteering.step(from, new Vec3(0.5, 64, 0.7), world.probe());
        assertEquals(RideSteering.Outcome.TERRAIN_UNAVAILABLE, result.outcome());
        assertEquals(from, result.position());
    }
    @Test void unsupportedPartialShapesBlockAndNeverBecomeImaginaryGround() {
        World world = new World();
        world.obstacles.add(List.of(0, 64, 2));
        assertEquals(RideSteering.Outcome.BLOCKED, RideSteering.step(new Vec3(0.5, 64, 1.65), new Vec3(0.5, 64, 1.85), world.probe()).outcome());
    }
    @Test void oneStepUsesABoundedNumberOfChecks() {
        World world = dropAhead(40);
        RideSteering.step(new Vec3(0.5, 64, 2.15), new Vec3(0.5, 64, 2.35), world.probe());
        assertTrue(world.checks > 4 && world.checks <= RideSteering.MAX_CHECKS, "checks=" + world.checks);
    }
    @Test void dismountCandidatesStandBesideTheBodyInAStableOrder() {
        List<Vec3> candidates = RideSteering.dismountCandidates(new Vec3(10, 64, 10), 0, 0.7);
        assertEquals(4, candidates.size());
        double reach = 0.7 + RideSteering.DISMOUNT_CLEARANCE;
        // Yaw 0 faces +Z: left of the rider first, then right, then behind, then ahead.
        assertEquals(new Vec3(10 + reach, 64, 10), candidates.get(0));
        assertEquals(new Vec3(10 - reach, 64, 10), candidates.get(1));
        assertEquals(10 - reach, candidates.get(2).z(), 1e-9);
        assertEquals(10 + reach, candidates.get(3).z(), 1e-9);
    }
}
