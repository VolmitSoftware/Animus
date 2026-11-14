package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Console lines for what the browser is refused: one rate-limit summary per token once its burst quiets down (or after a
 * minute of steady refusals), at most once a minute; other refusals once per key and quiet time. Any thread.
 */
final class EditorWarnings {
    static final Duration MINUTE = Duration.ofMinutes(1), QUIET = Duration.ofSeconds(5);
    private static final int KEYS = 1024;

    /** Refusals of one token since its last line. */
    private static final class Window {
        Instant start, last, logged = Instant.MIN;
        int refused;
    }

    private final Consumer<String> warn;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Instant> said = new LinkedHashMap<>();

    EditorWarnings(Consumer<String> warn, Clock clock) {
        this.warn = Objects.requireNonNull(warn);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Counts one {@code rate-limited} answer to the token, for the request rate or the concurrency bound. */
    synchronized void limited(String tokenId) {
        Instant now = clock.instant();
        Window window = windows.computeIfAbsent(tokenId, (String id) -> new Window());
        if (window.refused == 0) window.start = now;
        window.last = now;
        window.refused++;
    }

    /** Called once a second: logs each token whose refusals quieted down or ran for a minute, at most once a minute. */
    void tick(EditorSettings settings) {
        Instant now = clock.instant();
        Map<String, String> due = new LinkedHashMap<>();
        synchronized (this) {
            windows.entrySet().removeIf((Map.Entry<String, Window> entry) -> {
                Window window = entry.getValue();
                if (window.refused == 0) return window.logged.plus(MINUTE).isBefore(now);
                boolean settled = !window.last.plus(QUIET).isAfter(now) || !window.start.plus(MINUTE).isAfter(now);
                if (!settled || window.logged.plus(MINUTE).isAfter(now)) return false;
                long seconds = Math.max(1, Duration.between(window.start, now).toSeconds());
                due.put(entry.getKey(), window.refused + (window.refused == 1 ? " request" : " requests") + " refused by the rate limit in the last " + seconds + " s");
                window.refused = 0;
                window.logged = now;
                return false;
            });
        }
        String limits = " ([editor] requestsPerSecond = " + settings.requestsPerSecond() + ", concurrentRequestsPerToken = " + settings.concurrentRequestsPerToken() + ")";
        due.forEach((String token, String line) -> warn.accept("Editor " + token + ": " + line + limits));
    }

    /** Logs {@code line} unless the same key was logged within {@code quiet}. */
    void once(String key, Duration quiet, String line) { if (due(key, quiet)) warn.accept(line); }

    /** True, and remembered, when {@code key} was not due within {@code quiet}: for something said once, in the log or in game. */
    synchronized boolean due(String key, Duration quiet) {
        Instant now = clock.instant();
        Instant last = said.get(key);
        if (last != null && last.plus(quiet).isAfter(now)) return false;
        if (said.size() >= KEYS) said.clear();
        said.put(key, now);
        return true;
    }
}
