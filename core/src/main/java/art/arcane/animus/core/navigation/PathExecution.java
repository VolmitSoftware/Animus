package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.*;

/** Incremental kinematic path execution. Each proposal requires fresh platform validation before commit. */
public final class PathExecution {
    private record Segment(Vec3 target, MotionValidator.Medium medium, boolean supported, boolean landing, boolean onGround) {}
    public record Proposal(Vec3 from, Vec3 to, MotionValidator.Medium medium, boolean requireSupport,
                           boolean landing, boolean onGround, int segment, boolean segmentEnd) {}
    private final List<Segment> segments;
    private Vec3 position;
    private int cursor;
    private boolean cancelled;
    private Proposal proposed;

    public PathExecution(Vec3 origin, List<PathPlanner.Step> path, Vec3 goal, MovementProfile.Mode mode) {
        Objects.requireNonNull(origin); Objects.requireNonNull(path); Objects.requireNonNull(goal); Objects.requireNonNull(mode);
        if (path.isEmpty() || path.size() > 256) throw new IllegalArgumentException("Execution path requires 1..256 steps");
        position = origin;
        if (origin.equals(goal)) { segments = List.of(); return; }
        List<Segment> built = new ArrayList<>(); Vec3 last = origin;
        MotionValidator.Medium ground = mode == MovementProfile.Mode.SWIM ? MotionValidator.Medium.WATER : MotionValidator.Medium.GROUND;
        for (PathPlanner.Step step : path) {
            Vec3 target = new Vec3(step.feet().x() + .5, step.feet().y(), step.feet().z() + .5);
            switch (step.motion()) {
                case STEP, JUMP -> {
                    // Rise before crossing the obstacle. This bounded kinematic maneuver is not ballistic vanilla physics.
                    double clearanceY = target.y() + .5;
                    add(built, last, new Vec3(last.x(), clearanceY, last.z()), MotionValidator.Medium.AIR, false, false, false);
                    Vec3 raised = new Vec3(target.x(), clearanceY, target.z());
                    add(built, new Vec3(last.x(), clearanceY, last.z()), raised, MotionValidator.Medium.AIR, false, false, false);
                    add(built, raised, target, ground, false, true, true);
                }
                case FALL -> {
                    Vec3 edge = new Vec3(target.x(), last.y(), target.z());
                    add(built, last, edge, MotionValidator.Medium.AIR, false, false, false);
                    add(built, edge, target, mode == MovementProfile.Mode.SWIM ? MotionValidator.Medium.AMPHIBIOUS_AIR : MotionValidator.Medium.AIR, false, true, true);
                }
                case FLY -> add(built, last, target, MotionValidator.Medium.AIR, false, false, false);
                case SWIM -> add(built, last, target, MotionValidator.Medium.WATER, false, false, false);
                default -> add(built, last, target, mode == MovementProfile.Mode.FLY ? MotionValidator.Medium.AIR : ground,
                        mode == MovementProfile.Mode.WALK, false, mode == MovementProfile.Mode.WALK);
            }
            last = target;
        }
        add(built, last, goal, mode == MovementProfile.Mode.FLY ? MotionValidator.Medium.AIR : ground,
                mode == MovementProfile.Mode.WALK, false, mode == MovementProfile.Mode.WALK);
        segments = List.copyOf(built);
    }
    private static void add(List<Segment> list, Vec3 from, Vec3 to, MotionValidator.Medium medium, boolean support, boolean landing, boolean onGround) {
        if (from.distance(to) > 0.000001) list.add(new Segment(to, medium, support, landing, onGround));
    }
    public Vec3 position() { return position; }
    public boolean finished() { return !cancelled && cursor == segments.size(); }
    public boolean cancelled() { return cancelled; }
    public void cancel() { cancelled = true; }
    public Optional<Proposal> next(double maximumDistance) {
        if (!Double.isFinite(maximumDistance) || maximumDistance <= 0 || maximumDistance > 1)
            throw new IllegalArgumentException("Movement increment must be in (0,1]");
        if (cancelled || finished()) return Optional.empty();
        Segment segment = segments.get(cursor); double distance = position.distance(segment.target);
        boolean end = distance <= maximumDistance;
        Vec3 next = end ? segment.target : position.add(segment.target.subtract(position).multiply(maximumDistance / distance));
        proposed = new Proposal(position, next, segment.medium, segment.supported, end && segment.landing,
                segment.onGround && (segment.supported || end), cursor, end);
        return Optional.of(proposed);
    }
    /** A failed validation does not advance the path. Stale proposals cannot be applied after another step. */
    public void commit(Proposal proposal) {
        Objects.requireNonNull(proposal);
        if (cancelled || finished() || proposed != proposal || proposal.segment != cursor || !proposal.from.equals(position))
            throw new IllegalStateException("Path proposal is no longer current");
        Segment segment = segments.get(cursor);
        if (proposal.segmentEnd && !proposal.to.equals(segment.target)) throw new IllegalArgumentException("Invalid segment completion");
        position = proposal.to; proposed = null; if (proposal.segmentEnd) cursor++;
    }
}
