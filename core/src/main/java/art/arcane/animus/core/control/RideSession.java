package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;
import java.util.UUID;

/**
 * One real player's authoritative lease on one virtual NPC. Owner-thread state only: it decides each step,
 * while the caller publishes accepted motion and handles packets. A closed session can never run again.
 */
public final class RideSession {
    public enum State { ATTACHING, RIDING, CLOSED }
    public enum Action { WAIT, TURN, MOVE, BLOCKED, LEASH, STOP }
    /** {@code grounded} is false while a flying or swimming body is off the ground. */
    public record Step(Action action, Vec3 position, float yaw, boolean grounded, String reason) {}
    public static final long ATTACH_TIMEOUT_NANOS = 5_000_000_000L;
    private static final float TURN_THRESHOLD_DEGREES = 1;
    private final UUID generation = UUID.randomUUID();
    private final UUID rider, npc;
    private final Vec3 anchor;
    private final RideSettings settings;
    private final ControlLoop loop;
    private final long started;
    private State state = State.ATTACHING;
    private String reason = "attaching";
    private long steps;

    /** @param flying steer in three dimensions along the rider's look direction */
    public RideSession(UUID rider, UUID npc, Vec3 anchor, RideSettings settings, boolean flying, long now) {
        this.rider = Objects.requireNonNull(rider); this.npc = Objects.requireNonNull(npc);
        this.anchor = Objects.requireNonNull(anchor); this.settings = Objects.requireNonNull(settings);
        loop = new ControlLoop(anchor, now, ControlLoop.Limits.ride(settings, flying));
        started = now;
    }
    public UUID generation() { return generation; }
    public UUID rider() { return rider; }
    public UUID npc() { return npc; }
    public Vec3 anchor() { return anchor; }
    public RideSettings settings() { return settings; }
    public State state() { return state; }
    public String reason() { return reason; }
    public long steps() { return steps; }

    /** The attach packet reached the rider's connection. Only the first completion of a live session counts. */
    /**
     * A ride ended by a lost connection leaves the rider where the server already holds it, at the vehicle's feet:
     * the logout or timeout that follows saves that position, and nobody is there to see a dismount placement.
     */
    public static boolean leavesRiderInPlace(String reason) {
        return ControlInbox.CONNECTION_CLOSED.equals(reason) || ControlLoop.CONNECTION_STALE.equals(reason);
    }
    public boolean attached() {
        if (state != State.ATTACHING) return false;
        state = State.RIDING; reason = "riding";
        return true;
    }
    public boolean close(String cause) {
        Objects.requireNonNull(cause);
        if (state == State.CLOSED) return false;
        state = State.CLOSED; reason = cause; loop.close(cause);
        return true;
    }

    public Step tick(long now, Vec3 position, float npcYaw, float riderYaw, float riderPitch, ControlInbox.Snapshot input, RideSteering.Probe probe) {
        Objects.requireNonNull(position); Objects.requireNonNull(input); Objects.requireNonNull(probe);
        if (state == State.CLOSED) return new Step(Action.STOP, position, npcYaw, true, reason);
        if (state == State.ATTACHING) {
            if (now - started < ATTACH_TIMEOUT_NANOS) return new Step(Action.WAIT, position, npcYaw, true, reason);
            close("attach-timeout");
            return new Step(Action.STOP, position, npcYaw, true, reason);
        }
        ControlLoop.Proposal proposal = loop.propose(now, position, riderYaw, riderPitch, input);
        switch (proposal.state()) {
            case STOPPED -> { close(proposal.reason()); return new Step(Action.STOP, position, npcYaw, true, reason); }
            case LEASH -> { return new Step(Action.LEASH, position, turned(npcYaw, riderYaw), true, proposal.reason()); }
            case PROPOSED -> { return move(position, proposal.position(), npcYaw, riderYaw, probe); }
            default -> {
                float yaw = turned(npcYaw, riderYaw);
                return new Step(yaw == npcYaw ? Action.WAIT : Action.TURN, position, yaw, true, proposal.reason());
            }
        }
    }
    private Step move(Vec3 from, Vec3 to, float npcYaw, float riderYaw, RideSteering.Probe probe) {
        RideSteering.Result result = probe.check(from, to);
        return switch (result.outcome()) {
            case MOVED -> { steps++; yield new Step(Action.MOVE, result.position(), riderYaw, result.grounded(), result.detail()); }
            case BLOCKED -> new Step(Action.BLOCKED, from, turned(npcYaw, riderYaw), result.grounded(), result.detail());
            case TERRAIN_UNAVAILABLE -> { close("terrain-unavailable"); yield new Step(Action.STOP, from, npcYaw, true, reason); }
        };
    }
    private static float turned(float npcYaw, float riderYaw) {
        float difference = ((riderYaw - npcYaw) % 360 + 540) % 360 - 180;
        return Math.abs(difference) >= TURN_THRESHOLD_DEGREES ? riderYaw : npcYaw;
    }
}
