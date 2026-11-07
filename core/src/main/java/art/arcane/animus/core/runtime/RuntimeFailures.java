package art.arcane.animus.core.runtime;

import art.arcane.animus.core.presentation.BodyTransport;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The runtime's last error for {@code status}, and a WARN line the first time each operation fails with each cause.
 * The context (viewer, NPC) is in the line but not in the key, so one cause across many viewers logs once.
 * Safe from any thread.
 */
public final class RuntimeFailures {
    static final int MAX_CAUSES = 256;
    private final Consumer<String> warn;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private volatile boolean saturated;
    private volatile String last;

    public RuntimeFailures(Consumer<String> warn) { this.warn = Objects.requireNonNull(warn); }

    /** The last recorded error, or null. */
    public String last() { return last; }

    public void record(String operation, String context, Throwable failure) { record(operation, context, PersistedNpcService.concise(failure)); }

    public void record(String operation, String context, String error) {
        last = error;
        once(operation, error, "NPC runtime " + operation + " failed" + (context == null || context.isEmpty() ? "" : " (" + context + ")") + ": " + error);
    }

    /**
     * A viewer the transport could not watch because its connection closed. It is not a runtime error, so status keeps
     * its last error, but the first time each reason happens the line names the player and the reason.
     */
    public void watchEnded(String player, Throwable failure) {
        String reason = PersistedNpcService.concise(failure);
        once("watch", reason, "NPC runtime stopped showing NPCs to " + player + ": " + reason + "; they show again when the player rejoins, respawns or changes world");
    }

    private void once(String operation, String cause, String line) {
        String key = operation + "|" + cause;
        if (reported.contains(key)) return;
        if (reported.size() >= MAX_CAUSES) {
            if (!saturated) { saturated = true; warn.accept("NPC runtime: " + MAX_CAUSES + " different failures logged; further new failures show only in status"); }
            return;
        }
        if (reported.add(key)) warn.accept(line);
    }

    /** True when the failure only says the viewer's transport watch has ended: its work is dropped, not an error. */
    public static boolean viewerGone(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof BodyTransport.ViewerUnwatched) return true;
        return false;
    }
}
