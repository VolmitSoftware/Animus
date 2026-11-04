package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Incremental kinematic path execution. Each proposal requires fresh platform validation before commit. */
public final class PathExecution {
    public record Proposal(Vec3 from, Vec3 to, MotionValidator.Medium medium, boolean requireSupport,
                           boolean landing, MotionValidator.Medium landingMedium, boolean onGround, int segment, boolean segmentEnd) {
        /** A landing is checked again at rest; ground landings need support there. */
        public boolean landingSupport() { return landingMedium == MotionValidator.Medium.GROUND; }
    }
    private final List<Maneuver.Leg> segments;
    private Vec3 position;
    private int cursor;
    private boolean cancelled;
    private Proposal proposed;

    /** Walking and surface-swimming routes end at the goal's horizontal position on the surface the planner found. */
    public PathExecution(Vec3 origin, List<PathPlanner.Step> path, Vec3 goal, MovementProfile profile) {
        Objects.requireNonNull(origin); Objects.requireNonNull(path); Objects.requireNonNull(goal); Objects.requireNonNull(profile);
        if (path.isEmpty() || path.size() > 256) throw new IllegalArgumentException("Execution path requires 1..256 steps");
        position = origin;
        if (origin.equals(goal)) { segments = List.of(); return; }
        List<Maneuver.Leg> built = new ArrayList<>();
        Vec3 last = origin;
        boolean swimming = path.getFirst().swimming();
        for (PathPlanner.Step step : path) {
            Vec3 target = step.point();
            double peak = step.motion() == PathPlanner.Motion.START ? Math.max(last.y(), target.y()) : step.peak();
            built.addAll(Maneuver.legs(last, target, peak, swimming, step.swimming(), profile));
            last = target; swimming = step.swimming();
        }
        boolean free = profile.supports(MovementProfile.Mode.FLY) || !profile.supports(MovementProfile.Mode.WALK);
        Vec3 end = free ? goal : new Vec3(goal.x(), last.y(), goal.z());
        built.addAll(Maneuver.legs(last, end, Math.max(last.y(), end.y()), swimming, swimming, profile));
        segments = List.copyOf(built);
    }
    public Vec3 position() { return position; }
    public boolean finished() { return !cancelled && cursor == segments.size(); }
    public boolean cancelled() { return cancelled; }
    public void cancel() { cancelled = true; }
    public Optional<Proposal> next(double maximumDistance) {
        if (!Double.isFinite(maximumDistance) || maximumDistance <= 0 || maximumDistance > 1)
            throw new IllegalArgumentException("Movement increment must be in (0,1]");
        if (cancelled || finished()) return Optional.empty();
        Maneuver.Leg segment = segments.get(cursor); double distance = position.distance(segment.target());
        boolean end = distance <= maximumDistance;
        Vec3 next = end ? segment.target() : position.add(segment.target().subtract(position).multiply(maximumDistance / distance));
        proposed = new Proposal(position, next, segment.medium(), segment.support(), end && segment.landing(), segment.landingMedium(),
                segment.onGround() && (segment.support() || end), cursor, end);
        return Optional.of(proposed);
    }
    /** A failed validation does not advance the path. Stale proposals cannot be applied after another step. */
    public void commit(Proposal proposal) {
        Objects.requireNonNull(proposal);
        if (cancelled || finished() || proposed != proposal || proposal.segment() != cursor || !proposal.from().equals(position))
            throw new IllegalStateException("Path proposal is no longer current");
        Maneuver.Leg segment = segments.get(cursor);
        if (proposal.segmentEnd() && !proposal.to().equals(segment.target())) throw new IllegalArgumentException("Invalid segment completion");
        position = proposal.to(); proposed = null; if (proposal.segmentEnd()) cursor++;
    }
}
