package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;

/**
 * One route leg of any length, planned as a chain of captures that each fit the navigation voxel budget. A leg that fits
 * one capture is planned exactly, as it always was. A longer one is seeded along the straight line to its goal: each
 * segment captures a box reaching towards the goal, plans through the real terrain in it and walks to the reachable
 * place closest to the goal, then the next segment starts from there. A segment that gets no closer, or is blocked while
 * walked, is planned again in a box turned to one side; after {@link #ATTEMPTS} such tries in a row the leg is blocked.
 *
 * <p>Each segment is one ordinary plan, so it takes the runtime's planning turns and capture reads like any other. Work
 * stays bounded: every walked segment brings the body at least a block closer than any segment before it. Owner thread
 * only.
 */
public final class LongLeg {
    public enum Next {
        /** Walk the planned path to the leg's goal and arrive. */
        FINAL,
        /** Walk the planned path to its end, then plan the next segment. */
        SEGMENT,
        /** Plan again from where the body stands. */
        RETRY,
        /** The terrain allows no way on. */
        BLOCKED
    }
    /** One plan to request: its capture, and whether it aims at the goal exactly or as far towards it as it gets. */
    public record Request(CaptureBounds bounds, PathPlanner.Limits limits, boolean direct) {}
    /** Plans in a row that may fail before the leg is blocked: straight on, then turned 45 and 90 degrees either way. */
    public static final int ATTEMPTS = 5;
    /** The old single-capture leg: at most this far across and up or down. */
    public static final int DIRECT_HORIZONTAL = 32, DIRECT_VERTICAL = 8;
    public static final PathPlanner.Limits DIRECT = new PathPlanner.Limits(4096, 4096, 256);
    public static final PathPlanner.Limits SEGMENT = new PathPlanner.Limits(4096, 4096, 256, true);
    private static final double[] TURNS = {0, 45, -45, 90, -90};
    private static final double PROGRESS = 1;
    private static final int VOXELS = NavigationLimits.DEFAULT.maximumCaptureVoxels(), MINIMUM_TALL = 18;
    private final Vec3 goal;
    private final double hullWidth, hullHeight;
    private double best;
    private int attempt;

    public LongLeg(Vec3 origin, Vec3 goal, double hullWidth, double hullHeight) {
        this.goal = Objects.requireNonNull(goal, "goal"); this.hullWidth = hullWidth; this.hullHeight = hullHeight;
        best = Objects.requireNonNull(origin, "origin").distance(goal);
    }
    public Vec3 goal() { return goal; }
    /** Failed plans in a row since the body last got closer. */
    public int attempts() { return attempt; }

    /** The next plan from where the body stands. */
    public Request request(Vec3 at) {
        if (attempt == 0 && fits(at, goal, hullWidth, hullHeight)) return new Request(exact(at, goal, hullWidth, hullHeight), DIRECT, true);
        return new Request(toward(at, goal, hullWidth, hullHeight, attempt), SEGMENT, false);
    }
    /** What to do with a finished plan. Unavailable terrain and cancellation are the runtime's to handle first. */
    public Next planned(PathPlanner.Result result) {
        Objects.requireNonNull(result, "result");
        if (result.status() == PathPlanner.Status.FOUND) { attempt = 0; return Next.FINAL; }
        if (result.status() == PathPlanner.Status.PARTIAL) {
            double distance = result.path().getLast().point().distance(goal);
            if (distance <= best - PROGRESS) { best = distance; attempt = 0; return Next.SEGMENT; }
        }
        return stalled();
    }
    /** A step of the walked path was blocked by terrain that changed since it was planned. */
    public Next blocked() { return stalled(); }
    private Next stalled() { return ++attempt >= ATTEMPTS ? Next.BLOCKED : Next.RETRY; }

    /** True when one exact capture holds the whole leg. */
    public static boolean fits(Vec3 from, Vec3 to, double hullWidth, double hullHeight) {
        Vec3 delta = to.subtract(from);
        if (Math.abs(delta.x()) > DIRECT_HORIZONTAL || Math.abs(delta.z()) > DIRECT_HORIZONTAL || Math.abs(delta.y()) > DIRECT_VERTICAL) return false;
        BlockPos a = BlockPos.containing(from), b = BlockPos.containing(to);
        int margin = margin(hullWidth);
        long across = (long) Math.abs(a.x() - b.x()) + 2L * margin + 1, deep = (long) Math.abs(a.z() - b.z()) + 2L * margin + 1;
        long tall = (long) Math.abs(a.y() - b.y()) + 4 + (int) Math.ceil(hullHeight) + 3 + 1;
        return across * deep * tall <= VOXELS;
    }
    /** The capture of a leg that fits: both ends with room for the body around them. */
    public static CaptureBounds exact(Vec3 origin, Vec3 destination, double hullWidth, double hullHeight) {
        BlockPos from = BlockPos.containing(origin), to = BlockPos.containing(destination);
        int margin = margin(hullWidth);
        int minX = Math.min(from.x(), to.x()) - margin, minZ = Math.min(from.z(), to.z()) - margin;
        int minY = Math.min(from.y(), to.y()) - 4, maxY = Math.max(from.y(), to.y()) + (int) Math.ceil(hullHeight) + 3;
        return new CaptureBounds(minX, minY, minZ, Math.max(from.x(), to.x()) + margin - minX + 1,
                maxY - minY + 1, Math.max(from.z(), to.z()) + margin - minZ + 1);
    }
    /**
     * A square box of the full voxel budget that holds the feet near one side and reaches towards the goal, turned by the
     * attempt's angle. Climbing legs get most of their height above the feet, descending legs below, level legs both.
     */
    static CaptureBounds toward(Vec3 at, Vec3 goal, double hullWidth, double hullHeight, int attempt) {
        BlockPos feet = BlockPos.containing(at);
        int margin = margin(hullWidth), body = (int) Math.ceil(hullHeight);
        int tall = Math.max(MINIMUM_TALL, body + 12), side = (int) Math.floor(Math.sqrt(VOXELS / (double) tall));
        int half = (side - 1) / 2, reach = Math.max(0, half - margin - 1);
        int centreX = feet.x(), centreZ = feet.z();
        double dx = goal.x() - at.x(), dz = goal.z() - at.z();
        if (Math.hypot(dx, dz) > .5) {
            double angle = Math.atan2(dz, dx) + Math.toRadians(TURNS[Math.min(attempt, TURNS.length - 1)]);
            centreX += (int) Math.round(Math.cos(angle) * reach); centreZ += (int) Math.round(Math.sin(angle) * reach);
        } else if (attempt > 0) {
            // Straight up or down: a retry shifts the box sideways so the climb can find another way.
            double angle = Math.toRadians(TURNS[Math.min(attempt, TURNS.length - 1)] * 2);
            centreX += (int) Math.round(Math.cos(angle) * reach); centreZ += (int) Math.round(Math.sin(angle) * reach);
        }
        double dy = goal.y() - at.y();
        int below = dy > 2 ? 4 : dy < -2 ? tall - body - 4 : (tall - body - 4) / 2;
        return new CaptureBounds(centreX - half, feet.y() - below, centreZ - half, side, tall, side);
    }
    private static int margin(double hullWidth) { return Math.max(0, (int) Math.ceil((hullWidth - 1) / 2)) + 2; }
}
