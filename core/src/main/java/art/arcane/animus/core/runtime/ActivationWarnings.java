package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusLog;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Reports a definition that cannot activate once through {@link AnimusLog}, naming the field; the same failure stays quiet until it changes or clears. */
public final class ActivationWarnings {
    /** The routine diagnostic kind of these lines, counted per NPC while debug mode is off. */
    public static final String KIND = "spawned NPCs could not become active";
    private final Consumer<String> warn;
    private final Map<String, String> reported = new ConcurrentHashMap<>();

    public ActivationWarnings(Consumer<String> warn) { this.warn = Objects.requireNonNull(warn); }

    public void failed(String id, String error) {
        String line = "NPC " + id + " cannot become active: " + error;
        // Counted every time while debug mode is off, so turning it on later still shows each NPC once.
        if (!AnimusLog.shared().debug()) { AnimusLog.shared().routine(KIND, id, line, warn); return; }
        String previous = reported.put(id, error);
        if (!error.equals(previous)) warn.accept(line);
    }

    /** The definition activated, despawned or was removed: a later failure logs again. */
    public void cleared(String id) { reported.remove(id); }
}
