package art.arcane.animus.core.control;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Objects;

/**
 * Resolves one proposed ground step against the shared movement check: level, then a one-block step up,
 * then a descent of at most three blocks. Client coordinates are never an input and nothing is guessed.
 */
public final class RideSteering {
    @FunctionalInterface public interface Probe {
        MotionValidator.Result check(Vec3 from, Vec3 to, boolean requireSupport);
    }
    public enum Outcome { MOVED, BLOCKED, TERRAIN_UNAVAILABLE }
    public record Result(Outcome outcome, Vec3 position, String detail) {}
    public static final int MAX_DROP = 3;
    /** Level, the two step-up segments, the horizontal leg of a drop, then a descent and landing per block. */
    public static final int MAX_CHECKS = 4 + MAX_DROP * 2;
    public static final double DISMOUNT_CLEARANCE = 0.5;
    private RideSteering() {}

    /**
     * Swept clearance comes from the shared {@link MotionValidator}. Support is met when any full block lies under the
     * hull, as with a vanilla entity: demanding every cell would leave a wide body unable to cross a ledge in either direction.
     */
    public static Probe probe(double width, double height, int maximumCells, MotionValidator.Sampler terrain) {
        Objects.requireNonNull(terrain);
        return (from, to, requireSupport) -> {
            MotionValidator.Result clearance = MotionValidator.check(from, to, width, height, MotionValidator.Medium.GROUND, false, maximumCells, terrain);
            if (!clearance.clear() || !requireSupport) return clearance;
            if (Math.abs(from.y() - to.y()) > 0.000001 || Math.abs(to.y() - Math.rint(to.y())) > 0.000001)
                return new MotionValidator.Result(MotionValidator.Status.UNSUPPORTED_GEOMETRY, clearance.sampledCells(), "Ground movement requires integer feet height");
            double half = width / 2;
            int below = (int) Math.rint(to.y()) - 1, sampled = clearance.sampledCells();
            for (int x = cell(to.x() - half); x <= cell(to.x() + half - 0.000002); x++) for (int z = cell(to.z() - half); z <= cell(to.z() + half - 0.000002); z++) {
                VoxelSnapshot.Cell floor = terrain.sample(x, below, z); sampled++;
                if (floor == VoxelSnapshot.Cell.UNAVAILABLE) return new MotionValidator.Result(MotionValidator.Status.UNAVAILABLE_TERRAIN, sampled, "Support unavailable");
                if (floor == VoxelSnapshot.Cell.SOLID) return new MotionValidator.Result(MotionValidator.Status.CLEAR, sampled, "clear");
            }
            return new MotionValidator.Result(MotionValidator.Status.BLOCKED, sampled, "No ground under the body");
        };
    }
    private static int cell(double value) { return (int) Math.floor(value + 0.000001); }

    public static Result step(Vec3 from, Vec3 to, Probe probe) {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(probe);
        MotionValidator.Result level = probe.check(from, to, true);
        if (level.clear()) return new Result(Outcome.MOVED, to, level.detail());
        if (level.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return unavailable(from, level);

        Vec3 above = new Vec3(from.x(), from.y() + 1, from.z()), landing = new Vec3(to.x(), to.y() + 1, to.z());
        MotionValidator.Result rise = probe.check(from, above, false);
        if (rise.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return unavailable(from, rise);
        if (rise.clear()) {
            MotionValidator.Result top = probe.check(above, landing, true);
            if (top.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return unavailable(from, top);
            if (top.clear()) return new Result(Outcome.MOVED, landing, "stepped up");
        }

        MotionValidator.Result across = probe.check(from, to, false);
        if (across.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return unavailable(from, across);
        if (!across.clear()) return new Result(Outcome.BLOCKED, from, level.detail());
        Vec3 at = to;
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            Vec3 below = new Vec3(at.x(), at.y() - 1, at.z());
            MotionValidator.Result fall = probe.check(at, below, false);
            if (fall.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return unavailable(from, fall);
            if (!fall.clear()) break;
            MotionValidator.Result ground = probe.check(below, below, true);
            if (ground.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return unavailable(from, ground);
            if (ground.clear()) return new Result(Outcome.MOVED, below, "stepped down");
            at = below;
        }
        return new Result(Outcome.BLOCKED, from, level.detail());
    }

    /** Places to stand after dismounting, nearest the rider's sides first. Each still needs a live ground check. */
    public static List<Vec3> dismountCandidates(Vec3 feet, float yaw, double halfWidth) {
        double reach = halfWidth + DISMOUNT_CLEARANCE, radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double leftX = forwardZ, leftZ = -forwardX;
        return List.of(
                new Vec3(feet.x() + leftX * reach, feet.y(), feet.z() + leftZ * reach),
                new Vec3(feet.x() - leftX * reach, feet.y(), feet.z() - leftZ * reach),
                new Vec3(feet.x() - forwardX * reach, feet.y(), feet.z() - forwardZ * reach),
                new Vec3(feet.x() + forwardX * reach, feet.y(), feet.z() + forwardZ * reach));
    }
    private static Result unavailable(Vec3 from, MotionValidator.Result cause) {
        return new Result(Outcome.TERRAIN_UNAVAILABLE, from, cause.detail());
    }
}
