package art.arcane.animus.core.control;

import java.util.Objects;

/** Single bounded latest-value mailbox. Input is change-driven; liveness is maintained separately. */
public final class ControlInbox {
    public record Frame(ControlInput input, long sequence, long receivedAtNanos) {}
    public record Snapshot(Frame latest, long lastActivityNanos, boolean active, String reason) {}
    public static final int MAX_INPUTS_PER_SECOND = 80;
    /** The rider's network connection is gone; the server may not have processed the logout yet. */
    public static final String CONNECTION_CLOSED = "connection-closed";
    private Frame latest;
    private long lastActivity, windowStart, sequence;
    private int receivedInWindow;
    private boolean active = true;
    private String reason = "active";

    public ControlInbox(long startedAtNanos) { lastActivity = windowStart = startedAtNanos; }
    public synchronized boolean offer(ControlInput input, long now) {
        Objects.requireNonNull(input);
        if (!active) return false;
        if (now - windowStart >= 1_000_000_000L) { windowStart = now; receivedInWindow = 0; }
        if (++receivedInWindow > MAX_INPUTS_PER_SECOND || sequence == Long.MAX_VALUE) {
            close("input-rate"); return false;
        }
        latest = new Frame(input, ++sequence, now);
        if (input.shift()) close("shift"); // Release must not disappear when later input is coalesced in the same owner tick.
        return true;
    }
    /** Only a decoded client-tick-end packet renews connection liveness; changing keys cannot substitute for it. */
    public synchronized void heartbeat(long now) { if (active) lastActivity = now; }
    public synchronized boolean active() { return active; }
    public synchronized Snapshot snapshot() { return new Snapshot(latest, lastActivity, active, reason); }
    public synchronized void close(String cause) {
        Objects.requireNonNull(cause);
        if (!active) return;
        active = false; latest = null; reason = cause;
    }
}
