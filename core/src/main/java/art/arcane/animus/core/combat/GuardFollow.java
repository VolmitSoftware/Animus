package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Objects;

/**
 * Where a guard walks to keep up with its charge. It aims at a point behind the charge, against the direction the
 * charge last moved, replans when the charge moved more than {@code replanDistance} since the last plan or the plan
 * went stale, never more often than {@link #MINIMUM_REPLAN_TICKS}, and asks for a teleport when the charge is beyond
 * {@code catchUpDistance} or in another world. Pure decision state; the brain applies it.
 */
final class GuardFollow {
    enum Action { HOLD, WALK, CATCH_UP }
    /** {@code hurry} asks for a faster walk because the guard fell well behind. */
    record Step(Action action, WorldPosition goal, boolean hurry) {}

    static final long MINIMUM_REPLAN_TICKS = 5;
    private static final double MOVED = 0.05;
    private static final double HURRY_FACTOR = 2;
    private Vec3 heading;
    private WorldPosition lastSeen;
    private WorldPosition plannedFor;
    private long plannedAt = Long.MIN_VALUE / 2;

    Step next(long now, WorldPosition self, WorldPosition charge, CombatSettings.Guard guard, long staleTicks) {
        Objects.requireNonNull(self); Objects.requireNonNull(charge); Objects.requireNonNull(guard);
        observe(charge);
        WorldPosition goal = behind(self, charge, guard);
        if (!self.world().equals(charge.world())) return new Step(Action.CATCH_UP, goal, true);
        double distance = self.point().distance(charge.point());
        if (guard.catchUpDistance() > 0 && distance > guard.catchUpDistance()) return new Step(Action.CATCH_UP, goal, true);
        if (distance <= guard.followDistance()) return new Step(Action.HOLD, goal, false);
        boolean hurry = distance > guard.followDistance() * HURRY_FACTOR;
        if (now - plannedAt < MINIMUM_REPLAN_TICKS) return new Step(Action.HOLD, goal, hurry);
        boolean drifted = plannedFor == null || !plannedFor.world().equals(charge.world())
                || plannedFor.point().distance(charge.point()) > guard.replanDistance();
        if (drifted || now - plannedAt >= staleTicks) return new Step(Action.WALK, goal, hurry);
        return new Step(Action.HOLD, goal, hurry);
    }

    /** The walk or teleport toward the charge at this position was accepted. */
    void planned(long now, WorldPosition charge) {
        plannedFor = charge;
        plannedAt = now;
    }

    void reset() {
        heading = null;
        lastSeen = null;
        plannedFor = null;
        plannedAt = Long.MIN_VALUE / 2;
    }

    /** Safe-spot candidates for a catch-up teleport: behind the charge first, then around it, then its own feet. */
    static List<Vec3> catchUpSpots(WorldPosition goal, WorldPosition charge) {
        Vec3 center = charge.point(), aim = goal.point();
        double y = Math.floor(center.y());
        return List.of(new Vec3(aim.x(), y, aim.z()), new Vec3(center.x() + 1.5, y, center.z()), new Vec3(center.x() - 1.5, y, center.z()),
                new Vec3(center.x(), y, center.z() + 1.5), new Vec3(center.x(), y, center.z() - 1.5), new Vec3(center.x(), y, center.z()));
    }

    private void observe(WorldPosition charge) {
        if (lastSeen != null && lastSeen.world().equals(charge.world())) {
            Vec3 delta = charge.point().subtract(lastSeen.point());
            double horizontal = Math.hypot(delta.x(), delta.z());
            if (horizontal > MOVED) heading = new Vec3(delta.x() / horizontal, 0, delta.z() / horizontal);
        } else if (lastSeen != null) heading = null;
        lastSeen = charge;
    }

    private WorldPosition behind(WorldPosition self, WorldPosition charge, CombatSettings.Guard guard) {
        Vec3 center = charge.point();
        Vec3 back;
        if (heading != null) back = new Vec3(-heading.x(), 0, -heading.z());
        else {
            Vec3 toward = self.world().equals(charge.world()) ? self.point().subtract(center) : Vec3.ZERO;
            double horizontal = Math.hypot(toward.x(), toward.z());
            back = horizontal > MOVED ? new Vec3(toward.x() / horizontal, 0, toward.z() / horizontal) : Vec3.ZERO;
        }
        double gap = Math.max(0, Math.min(guard.pointRange(), guard.followDistance() - 0.5));
        return new WorldPosition(charge.world(), new Vec3(center.x() + back.x() * gap, Math.floor(center.y()), center.z() + back.z() * gap));
    }
}
