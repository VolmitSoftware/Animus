package art.arcane.animus.core.recording;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One NPC playing one recording, as both runtimes drive it from their route slot on the NPC's owner. Pure state: the
 * runtime plans the walks, checks the steps and moves the body, and tells the run what happened.
 *
 * <p>A run starts {@link Phase#WALKING_IN}: the NPC walks a normal planned leg to the pose at the cursor, then plays. A
 * refused step switches to {@link Phase#CATCHING_UP}: a leg to the pose {@link #CATCH_UP_TICKS} ahead, then the cursor
 * jumps there. A goal that cannot be reached moves the goal another {@value #CATCH_UP_TICKS} ticks on, at most
 * {@value #MAXIMUM_SKIPS} times; after that the NPC is stuck and the caller applies its {@code movement.stuck} rules.
 */
public final class RecordingRun {
    public static final int CATCH_UP_TICKS = 40, MAXIMUM_SKIPS = 5, BLOCKED_RETRY_TICKS = 100;

    public enum Phase {
        WALKING_IN, PLAYING, CATCHING_UP, BLOCKED, UNAVAILABLE, SLEEPING;

        /** The {@code state=} word inspect shows. */
        public String key() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    /** What follows a walk that could not reach its goal. */
    public enum Failure { RETRY_FURTHER, STUCK }

    private final RecordingSource source;
    private final RecordingDocument document;
    private RecordingPlayback playback;
    private RecordingPlayback target;
    private Phase phase = Phase.WALKING_IN;
    private Phase walking = Phase.WALKING_IN;
    private int skips;

    /** Starts from the first frame. */
    public RecordingRun(RecordingSource source, RecordingDocument document) {
        this(source, document, new RecordingPlayback(document, source.playback(), 0));
    }

    private RecordingRun(RecordingSource source, RecordingDocument document, RecordingPlayback playback) {
        this.source = Objects.requireNonNull(source); this.document = Objects.requireNonNull(document); this.playback = playback;
    }

    /**
     * Continues a playback stopped earlier or handed over by another owner: the NPC walks to the pose at the cursor and
     * plays on from there. A state of another mode, or one outside the document, starts from the first frame instead.
     */
    public static RecordingRun resume(RecordingSource source, RecordingDocument document, Optional<RecordingPlayback.State> state) {
        if (state.isEmpty() || state.get().mode() != source.playback() || state.get().cursor() > document.durationTicks())
            return new RecordingRun(source, document);
        try { return new RecordingRun(source, document, RecordingPlayback.resume(document, state.get())); }
        catch (IllegalArgumentException mismatched) { return new RecordingRun(source, document); }
    }

    public RecordingSource source() { return source; }
    public RecordingDocument document() { return document; }
    public Phase phase() { return phase; }
    public int skips() { return skips; }
    /** The playback the cursor belongs to; its {@link RecordingPlayback#state()} is what a stop or handoff keeps. */
    public RecordingPlayback playback() { return playback; }
    public RecordingPlayback.State state() { return playback.state(); }
    /** True while the NPC walks a planned leg instead of playing frames. */
    public boolean walking() { return phase == Phase.WALKING_IN || phase == Phase.CATCHING_UP; }

    /** Where the current walk goes: the cursor's pose walking in, the catch-up pose catching up. */
    public RecordingPose goal() { return phase == Phase.CATCHING_UP && target != null ? target.pose() : playback.pose(); }

    /** The walk reached its goal: a catch-up jumps the cursor there, and the frames play from the next tick. */
    public void arrived() {
        if (phase == Phase.CATCHING_UP && target != null) playback = target;
        target = null; skips = 0;
        phase = Phase.PLAYING;
    }

    /** A playing step was refused: catch up to the pose {@value #CATCH_UP_TICKS} ticks ahead. */
    public void refused() {
        skips = 0;
        catchUp();
    }

    /** The walk could not reach its goal: aim {@value #CATCH_UP_TICKS} ticks further, or report the NPC stuck. */
    public Failure walkFailed() {
        if (phase != Phase.CATCHING_UP) {
            skips = 0;
            catchUp();
            return Failure.RETRY_FURTHER;
        }
        if (skips >= MAXIMUM_SKIPS) return Failure.STUCK;
        target.skip(CATCH_UP_TICKS);
        skips++;
        return Failure.RETRY_FURTHER;
    }

    /** {@code movement.stuck} teleported the NPC to the goal: play on from it. */
    public void teleported() { arrived(); }

    /** {@code movement.stuck} does not teleport: wait {@value #BLOCKED_RETRY_TICKS} ticks, then catch up again. */
    public void blocked() { phase = Phase.BLOCKED; target = null; skips = 0; }

    /** A blocked or paused run tries again: blocked catches up from the cursor, paused goes back to what it did. */
    public void retry() {
        if (phase == Phase.BLOCKED) { refused(); return; }
        if (phase == Phase.UNAVAILABLE || phase == Phase.SLEEPING) phase = walking == null ? Phase.PLAYING : walking;
    }

    /** The next frame lies in terrain that is not loaded, or the NPC fell asleep: the cursor waits. */
    public void paused(Phase reason) {
        if (reason != Phase.UNAVAILABLE && reason != Phase.SLEEPING) throw new IllegalArgumentException("A run pauses only as unavailable or sleeping");
        if (phase == Phase.UNAVAILABLE || phase == Phase.SLEEPING) { phase = reason; return; }
        walking = walking() ? phase : null;
        phase = reason;
    }

    /** Puts the cursor back where it was before an advance whose step could not be taken this tick. */
    public void rewind(RecordingPlayback.State before) { playback = RecordingPlayback.resume(document, before); }

    /** A {@code once} playback reached its last frame and ran every event. */
    public boolean finished() { return phase == Phase.PLAYING && playback.finished(); }

    /** {@code recording=<id> playback=<mode> cursor=<tick>/<duration> state=<phase>}, for inspect and navigation status. */
    public String describe() { return describe(phase.key()); }

    /** The same line with another state word, such as {@code stopped} for a run kept after its NPC stopped. */
    public String describe(String state) {
        return "recording=" + source.recording() + " playback=" + source.playback().key() + " cursor=" + playback.cursor() + "/" + document.durationTicks()
                + " state=" + state;
    }

    private void catchUp() {
        target = RecordingPlayback.resume(document, playback.state());
        target.skip(CATCH_UP_TICKS);
        phase = Phase.CATCHING_UP;
    }
}
