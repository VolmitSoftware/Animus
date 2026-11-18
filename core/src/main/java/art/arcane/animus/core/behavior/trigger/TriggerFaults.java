package art.arcane.animus.core.behavior.trigger;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A trigger event that fails to be admitted or to start costs only that event: the dispatcher drops it and keeps going.
 * The first failure of each behavior and event is logged. Pump thread only, apart from {@link #sink}.
 */
final class TriggerFaults {
    /** Behavior and event pairs remembered as reported; past this nothing more is logged. */
    private static final int REMEMBERED = 256;
    private volatile Consumer<String> sink = (String line) -> {};
    private final Set<String> reported = new HashSet<>();

    void sink(Consumer<String> sink) { this.sink = Objects.requireNonNull(sink); }

    /** Logs {@code line} the first time {@code key} comes up. */
    void once(String key, String line) {
        if (reported.size() >= REMEMBERED || !reported.add("once:" + key)) return;
        sink.accept(line);
    }

    void report(String behavior, String event, String where, RuntimeException fault) {
        if (reported.size() >= REMEMBERED || !reported.add(behavior + "#" + event)) return;
        sink.accept("Behavior trigger: FAILED | " + behavior + " on its " + event + " trigger" + where + ": " + fault.getClass().getSimpleName()
                + (fault.getMessage() == null ? "" : " " + fault.getMessage()) + ". That event was dropped; other triggers keep running.");
    }
}
