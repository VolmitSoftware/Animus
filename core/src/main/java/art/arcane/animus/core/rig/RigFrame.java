package art.arcane.animus.core.rig;

/**
 * One planned segment of a rig, shared by every viewer of the NPC. Arrays follow {@link RigDocument#displayBones()}.
 * {@code targets} is each bone's display transform at the end of the segment; {@code durations} is the interpolation
 * each bone uses (0 snaps, as when a bone appears or hides); {@code revisions} is the sequence in which each bone's
 * target last changed, so a viewer sends only bones whose revision is newer than what it already sent.
 * Immutable after construction: arrays are never handed out for writing.
 */
public final class RigFrame {
    private static final long TICK_NANOS = 50_000_000L;
    private final long sequence, startTick, issuedNanos;
    private final int durationTicks;
    private final DisplayTransform[] targets;
    private final int[] durations;
    private final long[] revisions;

    RigFrame(long sequence, long startTick, int durationTicks, long issuedNanos, DisplayTransform[] targets, int[] durations, long[] revisions) {
        this.sequence = sequence; this.startTick = startTick; this.durationTicks = durationTicks; this.issuedNanos = issuedNanos;
        this.targets = targets.clone(); this.durations = durations.clone(); this.revisions = revisions.clone();
    }

    public long sequence() { return sequence; }
    public long startTick() { return startTick; }
    public int durationTicks() { return durationTicks; }
    public long issuedNanos() { return issuedNanos; }
    public int bones() { return targets.length; }
    public DisplayTransform target(int bone) { return targets[bone]; }
    public long revision(int bone) { return revisions[bone]; }
    public DisplayTransform[] targets() { return targets.clone(); }
    public int[] durations() { return durations.clone(); }
    public long[] revisions() { return revisions.clone(); }

    /** Interpolation ticks left for a bone sent at {@code nanos}: a late viewer catches up instead of replaying the whole segment. */
    public int remaining(long nanos, int bone) {
        if (durations[bone] == 0) return 0;
        long elapsed = Math.max(0, (nanos - issuedNanos) / TICK_NANOS);
        return (int) Math.max(0, durationTicks - Math.min(durationTicks, elapsed));
    }
}
