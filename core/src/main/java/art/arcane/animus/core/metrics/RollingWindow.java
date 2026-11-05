package art.arcane.animus.core.metrics;

/**
 * The last {@link #SECONDS} one-second buckets of a fixed set of channels, plus the server ticks each bucket spanned.
 * Sums over 1, 10 or 60 seconds cover only the buckets filled so far. Thread-safe.
 */
public final class RollingWindow {
    public static final int SECONDS = 60;
    private final int channels;
    private final long[][] buckets;
    private final long[] ticks = new long[SECONDS];
    private int head = -1, filled;

    public RollingWindow(int channels) {
        if (channels < 1) throw new IllegalArgumentException("A window needs at least one channel");
        this.channels = channels;
        buckets = new long[SECONDS][channels];
    }

    public int channels() { return channels; }

    /** Adds one second: each channel's change over that second and the ticks it spanned. */
    public synchronized void push(long[] deltas, long tickCount) {
        if (deltas.length != channels) throw new IllegalArgumentException("Expected " + channels + " channels");
        head = (head + 1) % SECONDS;
        System.arraycopy(deltas, 0, buckets[head], 0, channels);
        ticks[head] = Math.max(0, tickCount);
        filled = Math.min(SECONDS, filled + 1);
    }

    /** Seconds actually covered by a request for the latest {@code seconds}. */
    public synchronized int covered(int seconds) { return Math.min(Math.max(0, seconds), filled); }

    public synchronized long total(int channel, int seconds) {
        long sum = 0;
        int count = covered(seconds);
        for (int i = 0; i < count; i++) sum += buckets[(head - i + SECONDS) % SECONDS][channel];
        return sum;
    }

    public synchronized long ticks(int seconds) {
        long sum = 0;
        int count = covered(seconds);
        for (int i = 0; i < count; i++) sum += ticks[(head - i + SECONDS) % SECONDS];
        return sum;
    }

    /** Average per second over the covered seconds; 0 before the first bucket. */
    public double perSecond(int channel, int seconds) {
        int count = covered(seconds);
        return count == 0 ? 0 : (double) total(channel, seconds) / count;
    }

    /** Average per server tick over the covered seconds; 0 when no tick ran. */
    public double perTick(int channel, int seconds) {
        long spanned = ticks(seconds);
        return spanned == 0 ? 0 : (double) total(channel, seconds) / spanned;
    }
}
