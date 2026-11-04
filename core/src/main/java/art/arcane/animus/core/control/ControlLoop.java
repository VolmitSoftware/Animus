package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;
import java.util.UUID;

/** Bounded remote-control proposal clock. The caller must approve live terrain and publish accepted state. */
public final class ControlLoop {
    public enum State { THROTTLED, WAITING_INPUT, IDLE, PROPOSED, LEASH, STOPPED }
    public record Proposal(State state, Vec3 position, String reason) {}
    public static final long STEP_NANOS = 50_000_000L, LIFETIME_NANOS = 30_000_000_000L, LIVENESS_NANOS = 2_000_000_000L;
    public static final double STEP_BLOCKS = 0.1, LEASH_BLOCKS = 8;
    /** A flat lease refuses any height change; otherwise the leash measures horizontal travel only. */
    public record Limits(double stepBlocks, double leashBlocks, long lifetimeNanos, boolean flat) {
        public static final Limits PREVIEW = new Limits(STEP_BLOCKS, LEASH_BLOCKS, LIFETIME_NANOS, true);
        public static Limits ride(RideSettings settings) {
            return new Limits(settings.speed() * STEP_NANOS / 1_000_000_000d, settings.leash(), settings.lifetimeSeconds() * 1_000_000_000L, false);
        }
    }
    private final UUID generation = UUID.randomUUID();
    private final Vec3 anchor;
    private final Limits limits;
    private final long started;
    private long lastStep, ticks;
    private boolean closed;
    private String reason = "stopped";

    public ControlLoop(Vec3 anchor, long now, Limits limits) {
        this.anchor = Objects.requireNonNull(anchor); this.limits = Objects.requireNonNull(limits); started = lastStep = now;
    }
    public UUID generation() { return generation; }
    public long ticks() { return ticks; }
    public Vec3 anchor() { return anchor; }
    public void close(String cause) { closed = true; reason = Objects.requireNonNull(cause); }
    public Proposal propose(long now, Vec3 authoritativePosition, double authoritativeYaw, ControlInbox.Snapshot input) {
        Objects.requireNonNull(authoritativePosition); Objects.requireNonNull(input);
        if (closed) return new Proposal(State.STOPPED, authoritativePosition, reason);
        if (!input.active()) return stop(authoritativePosition, input.reason());
        if (now - started >= limits.lifetimeNanos()) return stop(authoritativePosition, "expired");
        if (now - input.lastActivityNanos() >= LIVENESS_NANOS) return stop(authoritativePosition, "connection-stale");
        if (input.latest() != null && input.latest().input().shift()) return stop(authoritativePosition, "shift");
        if (now - lastStep < STEP_NANOS) return new Proposal(State.THROTTLED, authoritativePosition, "fixed-step");
        lastStep = now; ticks++;
        if (input.latest() == null) return new Proposal(State.WAITING_INPUT, authoritativePosition, "fresh-input-required");
        Vec3 direction = input.latest().input().horizontal(authoritativeYaw);
        if (direction.equals(Vec3.ZERO)) return new Proposal(State.IDLE, authoritativePosition, "released");
        Vec3 target = authoritativePosition.add(direction.multiply(limits.stepBlocks()));
        boolean leashed = limits.flat()
                ? Math.abs(target.y() - anchor.y()) > 0.000001 || target.distance(anchor) > limits.leashBlocks() + 0.00000001
                : Math.hypot(target.x() - anchor.x(), target.z() - anchor.z()) > limits.leashBlocks() + 0.00000001;
        if (leashed) return new Proposal(State.LEASH, authoritativePosition, "leash");
        return new Proposal(State.PROPOSED, target, "terrain-validation-required");
    }
    private Proposal stop(Vec3 at, String cause) { close(cause); return new Proposal(State.STOPPED, at, cause); }
}
