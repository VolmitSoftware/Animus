package art.arcane.animus.core.extension;

import java.util.ArrayDeque;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * When integration hook overruns suspend a module and when it may come back. {@value #LIMIT} overruns within
 * {@link #WINDOW} suspend it; the cool-off starts at {@link #FIRST_COOL_OFF} and doubles with each suspension up to
 * {@link #MAXIMUM_COOL_OFF}, and an hour without a suspension starts over. Times are {@link System#nanoTime()} values.
 * Not thread-safe; the module host guards it.
 */
final class OverrunPolicy {
    static final int LIMIT = 3;
    static final long WINDOW = TimeUnit.SECONDS.toNanos(60);
    static final long FIRST_COOL_OFF = TimeUnit.SECONDS.toNanos(30);
    static final long MAXIMUM_COOL_OFF = TimeUnit.MINUTES.toNanos(15);
    static final long FORGET = TimeUnit.HOURS.toNanos(1);
    private static final int MAXIMUM_DOUBLINGS = 16;

    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private int suspensions;
    private long lastSuspended;
    private boolean waiting;
    private long resumeAt;

    /** Records one overrun; the cool-off when it suspends the module, otherwise empty. */
    OptionalLong overran(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW) recent.removeFirst();
        recent.addLast(now);
        if (recent.size() < LIMIT) return OptionalLong.empty();
        recent.clear();
        if (suspensions > 0 && now - lastSuspended > FORGET) suspensions = 0;
        long coolOff = Math.min(MAXIMUM_COOL_OFF, FIRST_COOL_OFF << Math.min(suspensions, MAXIMUM_DOUBLINGS));
        suspensions++;
        lastSuspended = now;
        waiting = true;
        resumeAt = now + coolOff;
        return OptionalLong.of(coolOff);
    }

    /** True once the cool-off of the current suspension has passed. */
    boolean due(long now) { return waiting && now - resumeAt >= 0; }

    /** The module came back after its cool-off; the backoff stays for the next suspension. */
    void resumed() { waiting = false; recent.clear(); }

    /** An administrator resumed the module; the next suspension starts from the first cool-off. */
    void cleared() { resumed(); suspensions = 0; }
}
