package art.arcane.animus.core.editor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * WARN lines for the editor's periodic tasks. A failure warns the first time it is seen; repeats of the same failure are
 * counted and named in one line at most once per interval, so a task failing every minute does not fill the log.
 * Safe from any thread.
 */
final class EditorTaskFailures {
    private static final int MAX_KINDS = 64;
    private final Consumer<String> warn;
    private final Clock clock;
    private final Duration interval;
    private final Map<String, Repeats> seen = new HashMap<>();

    private static final class Repeats {
        Instant logged;
        int count;
        Repeats(Instant logged) { this.logged = logged; }
    }

    EditorTaskFailures(Consumer<String> warn, Clock clock, Duration interval) {
        this.warn = Objects.requireNonNull(warn);
        this.clock = Objects.requireNonNull(clock);
        this.interval = Objects.requireNonNull(interval);
    }

    void failed(RuntimeException failure) {
        String cause = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        String line;
        synchronized (this) {
            Instant now = clock.instant();
            Repeats repeats = seen.get(cause);
            if (repeats == null) {
                if (seen.size() >= MAX_KINDS) seen.clear();
                seen.put(cause, new Repeats(now));
                line = "Editor task failed: " + cause;
            } else {
                repeats.count++;
                if (Duration.between(repeats.logged, now).compareTo(interval) < 0) return;
                line = "Editor task failed " + repeats.count + " more times in the last " + interval.toMinutes() + "m: " + cause;
                repeats.logged = now;
                repeats.count = 0;
            }
        }
        warn.accept(line);
    }
}
