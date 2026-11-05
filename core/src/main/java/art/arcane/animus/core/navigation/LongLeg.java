package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One route leg of any length, planned as a chain of captures that each fit the navigation voxel budget. A leg that fits
 * one capture is planned exactly, as it always was. A longer one is seeded along the straight line to its goal: each
 * segment captures a box reaching towards the goal, plans through the real terrain in it and walks to the reachable
 * place closest to the goal, then the next segment starts from there.
 *
 * <p>A segment that gets no closer, or is blocked while walked, is planned again in a box turned to one side, 45, 90 and
 * then 135 degrees either way, aiming along the turn. A turned plan that leads somewhere new is walked as a detour, and
 * later turns keep to that side, so the body follows a cliff or a wall until it can head for the goal again. After
 * {@link #ATTEMPTS} plans in a row that got no closer, the leg is blocked.
 *
 * <p>Each segment is one ordinary plan, so it takes the runtime's planning turns and capture reads like any other. Work
 * stays bounded: a leg walks at most one segment per block it gets closer, plus {@link #ATTEMPTS} plans between any two
 * of them. Owner thread only.
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
    /**
     * One plan to request: its capture, the block it aims at, and whether it must reach the goal exactly or goes as far
     * towards its aim as the capture allows.
     */
    public record Request(CaptureBounds bounds, BlockPos aim, PathPlanner.Limits limits, boolean direct) {}
    /** Plans in a row that may get no closer before the leg is blocked. */
    public static final int ATTEMPTS = 12;
    /** The old single-capture leg: at most this far across and up or down. */
    public static final int DIRECT_HORIZONTAL = 32, DIRECT_VERTICAL = 8;
    public static final PathPlanner.Limits DIRECT = new PathPlanner.Limits(4096, 4096, 256);
    public static final PathPlanner.Limits SEGMENT = new PathPlanner.Limits(4096, 4096, 256, true);
    private static final double[] TURNS = {0, 45, -45, 90, -90, 135, -135};
    private static final double PROGRESS = 1, DETOUR = 3;
    private static final int VOXELS = NavigationLimits.DEFAULT.maximumCaptureVoxels(), MINIMUM_TALL = 18;
    private final Vec3 goal;
    private final double hullWidth, hullHeight, range;
    private double best;
    /** Plans since the body last got closer; the turn of the next plan; the turn of the last detour walked. */
    private int tries, turn, side;
    private Vec3 from;

    public LongLeg(Vec3 origin, Vec3 goal, double hullWidth, double hullHeight) { this(origin, goal, hullWidth, hullHeight, OptionalDouble.empty()); }
    /**
     * @param pathRange the NPC's {@code movement.pathRange}: how far one plan may reach. A goal farther away is still
     *                  walked to, in segments that each reach at most this far.
     */
    public LongLeg(Vec3 origin, Vec3 goal, double hullWidth, double hullHeight, OptionalDouble pathRange) {
        this.goal = Objects.requireNonNull(goal, "goal"); this.hullWidth = hullWidth; this.hullHeight = hullHeight;
        range = Objects.requireNonNull(pathRange, "pathRange").orElse(Double.POSITIVE_INFINITY);
        best = Objects.requireNonNull(origin, "origin").distance(goal);
        from = origin;
    }
    public Vec3 goal() { return goal; }
    /** Plans in a row that got no closer. */
    public int attempts() { return tries; }

    /** The next plan from where the body stands. */
    public Request request(Vec3 at) {
        from = Objects.requireNonNull(at, "at");
        BlockPos target = BlockPos.containing(goal);
        if (turn == 0 && at.distance(goal) <= range && fits(at, goal, hullWidth, hullHeight))
            return new Request(exact(at, goal, hullWidth, hullHeight), target, DIRECT, true);
        CaptureBounds bounds = toward(at, goal, hullWidth, hullHeight, TURNS[turn], range);
        if (turn == 0) return new Request(bounds, target, SEGMENT, false);
        // A turned plan aims along its turn, at the far side of its capture, so it explores instead of hugging the obstacle.
        double angle = Math.atan2(goal.z() - at.z(), goal.x() - at.x()) + Math.toRadians(TURNS[turn]);
        BlockPos feet = BlockPos.containing(at);
        BlockPos aim = new BlockPos(feet.x() + (int) Math.round(Math.cos(angle) * bounds.sizeX()), feet.y(), feet.z() + (int) Math.round(Math.sin(angle) * bounds.sizeZ()));
        return new Request(bounds, aim, SEGMENT, false);
    }
    /** What to do with a finished plan. Unavailable terrain and cancellation are the runtime's to handle first. */
    public Next planned(PathPlanner.Result result) {
        Objects.requireNonNull(result, "result");
        if (result.status() == PathPlanner.Status.FOUND && turn == 0) { tries = 0; return Next.FINAL; }
        if (result.status() == PathPlanner.Status.FOUND || result.status() == PathPlanner.Status.PARTIAL) {
            Vec3 end = result.path().getLast().point();
            double distance = end.distance(goal);
            if (distance <= best - PROGRESS) { best = distance; tries = 0; turn = 0; return Next.SEGMENT; }
            if (turn != 0 && end.distance(from) >= DETOUR) {
                if (++tries >= ATTEMPTS) return Next.BLOCKED;
                side = turn; turn = 0;
                return Next.SEGMENT;
            }
        }
        return stalled();
    }
    /** A step of the walked path was blocked by terrain that changed since it was planned. */
    public Next blocked() { return stalled(); }
    private Next stalled() {
        if (++tries >= ATTEMPTS) return Next.BLOCKED;
        // Straight on failed: keep to the side of the last detour. A turn that failed: try the next one.
        turn = turn == 0 && side != 0 ? side : (turn % (TURNS.length - 1)) + 1;
        return Next.RETRY;
    }

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
     * turn in degrees. Straight up or down only a turn moves it off centre. Climbing legs get most of their height above
     * the feet, descending legs below, level legs both. A path range shorter than the box narrows it to that reach.
     */
    static CaptureBounds toward(Vec3 at, Vec3 goal, double hullWidth, double hullHeight, double turn, double range) {
        BlockPos feet = BlockPos.containing(at);
        int margin = margin(hullWidth), body = (int) Math.ceil(hullHeight);
        int tall = Math.max(MINIMUM_TALL, body + 12), side = (int) Math.floor(Math.sqrt(VOXELS / (double) tall));
        if (range < side) side = Math.max(2 * margin + 3, (int) Math.floor(range) + 1);
        int half = (side - 1) / 2, reach = Math.max(0, half - margin - 1);
        int centreX = feet.x(), centreZ = feet.z();
        double dx = goal.x() - at.x(), dz = goal.z() - at.z();
        if (Math.hypot(dx, dz) > .5 || turn != 0) {
            double angle = Math.atan2(dz, dx) + Math.toRadians(turn);
            centreX += (int) Math.round(Math.cos(angle) * reach); centreZ += (int) Math.round(Math.sin(angle) * reach);
        }
        double dy = goal.y() - at.y();
        int below = dy > 2 ? 4 : dy < -2 ? tall - body - 4 : (tall - body - 4) / 2;
        return new CaptureBounds(centreX - half, feet.y() - below, centreZ - half, side, tall, side);
    }
    private static int margin(double hullWidth) { return Math.max(0, (int) Math.ceil((hullWidth - 1) / 2)) + 2; }
}
