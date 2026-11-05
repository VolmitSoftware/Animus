package art.arcane.animus.core.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Logs a definition that cannot activate once at WARN, naming the field; the same failure stays quiet until it changes or clears. */
public final class ActivationWarnings {
    private final Consumer<String> warn;
    private final Map<String, String> reported = new ConcurrentHashMap<>();

    public ActivationWarnings(Consumer<String> warn) { this.warn = Objects.requireNonNull(warn); }

    public void failed(String id, String error) {
        String previous = reported.put(id, error);
        if (!error.equals(previous)) warn.accept("NPC " + id + " cannot activate: " + error);
    }

    /** The definition activated, despawned or was removed: a later failure logs again. */
    public void cleared(String id) { reported.remove(id); }
}
