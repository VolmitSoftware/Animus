package art.arcane.animus.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The gate for routine diagnostics about one NPC, player or packet, such as a blocked route leg. With debug mode on
 * ({@code /animus debug mode}) each line is logged as it happens. With it off, the default, the lines are counted and at
 * most one summary line per {@link #WINDOW} names how many subjects each kind touched. Debug mode lives in memory and
 * resets when the runtime starts. Real problems (startup, settings, invalid documents, import failures, refusals) never
 * come through here.
 */
public final class AnimusLog {
    public static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAXIMUM_TRACED = 256;
    private static final AnimusLog SHARED = new AnimusLog(System::nanoTime, WINDOW);
    private final LongSupplier nanos;
    private final Duration span;
    private final long window;
    private final Map<String, Set<String>> pending = new LinkedHashMap<>();
    private final Set<String> traced = new LinkedHashSet<>();
    private volatile boolean debug;
    private long windowStart;

    public AnimusLog(LongSupplier nanos, Duration window) {
        this.nanos = Objects.requireNonNull(nanos);
        span = Objects.requireNonNull(window);
        this.window = window.toNanos();
        windowStart = nanos.getAsLong();
    }

    /** The server's one gate; every platform reports through it. */
    public static AnimusLog shared() { return SHARED; }

    public boolean debug() { return debug; }

    /** Turns debug mode on or off; pending counts are dropped either way. */
    public synchronized void debug(boolean on) {
        debug = on;
        pending.clear();
        windowStart = nanos.getAsLong();
    }

    /**
     * One routine diagnostic. {@code kind} reads after a count, such as {@code "NPCs hit route legs blocked by terrain"}, and
     * {@code subject} is what it is about, counted once per window.
     */
    public void routine(String kind, String subject, String message, Consumer<String> sink) {
        if (debug) { sink.accept(message); return; }
        String summary;
        synchronized (this) {
            pending.computeIfAbsent(kind, (String ignored) -> new LinkedHashSet<>()).add(subject);
            summary = due();
        }
        if (summary != null) sink.accept(summary);
    }

    /**
     * A failure with its stack trace the first time its root cause (class and message) is seen, then as one line
     * {@code message: Class: detail} every later time, so a repeating fault prints one trace.
     */
    public void failure(String message, Throwable failure, Consumer<String> line, BiConsumer<String, Throwable> trace) {
        String cause = cause(failure);
        boolean first;
        synchronized (this) {
            first = traced.add(cause);
            if (traced.size() > MAXIMUM_TRACED) traced.remove(traced.iterator().next());
        }
        if (first) trace.accept(message, failure);
        else line.accept(message + ": " + cause);
    }

    /** The root cause as {@code SimpleName: message}. */
    public static String cause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    /** The summary line once the window has passed, clearing the counts; otherwise null. */
    private String due() {
        long now = nanos.getAsLong();
        if (now - windowStart < window || pending.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : pending.entrySet()) parts.add(entry.getValue().size() + " " + entry.getKey());
        pending.clear();
        windowStart = now;
        return String.join(", ", parts) + " in the last " + span.toMinutes() + "m; /animus debug mode for details";
    }
}
