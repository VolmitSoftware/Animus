package art.arcane.animus.core.runtime;

/**
 * Longest-waiting-first turns for callers that ask in a fixed order each tick, such as NPC tasks on one Folia region.
 * Each caller counts the ticks in a row it was refused. When a tick refused anyone, the next tick admits only callers
 * that waited at least as long as the longest refused one, so the first callers in the order cannot keep the whole
 * budget. Without refusals everyone is admitted. Each caller still takes the budget itself; one thread at a time.
 */
public final class WaitingTurns {
    private long tick = Long.MIN_VALUE;
    private int threshold, nextThreshold, refused, lastRefused;

    /** Starts a tick; repeated calls in the same tick do nothing. */
    public void begin(long number) {
        if (number == tick) return;
        // A skipped tick means nobody asked, so the old threshold no longer describes anyone waiting.
        boolean consecutive = tick != Long.MIN_VALUE && number == tick + 1;
        tick = number;
        threshold = consecutive ? nextThreshold : 0;
        lastRefused = consecutive ? refused : 0;
        nextThreshold = 0; refused = 0;
    }
    /** True when a caller that has waited this many ticks may try the budget now. */
    public boolean admits(int waited) { return waited >= threshold; }
    /** The caller wanted a turn and did not get one, by this rule or because the budget ran out. */
    public void refused(int waited) {
        refused++;
        nextThreshold = Math.max(nextThreshold, waited + 1);
    }
    /** Callers refused in the previous tick. */
    public int lastRefused() { return lastRefused; }
    /** Callers refused so far in this tick. */
    public int refusedNow() { return refused; }
}
