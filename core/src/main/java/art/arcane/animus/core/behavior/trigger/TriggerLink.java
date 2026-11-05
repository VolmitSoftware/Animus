package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.runtime.NpcRuntime;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Keeps behavior triggers connected to the NPC runtime while it is available. Run it on the console owner every tick:
 * a runtime turned on or off by a hotload of {@code npcs.enabled} connects or blocks the triggers on the next tick.
 */
public final class TriggerLink implements Runnable {
    private final TriggerDispatcher triggers;
    private final NpcRuntime<?, ?> living;
    private final Consumer<String> log;
    private Boolean connected;

    /** Binds the runtime to the dispatcher and applies the current availability at once. Owner thread. */
    public TriggerLink(TriggerDispatcher triggers, NpcRuntime<?, ?> living, Consumer<String> log) {
        this.triggers = Objects.requireNonNull(triggers);
        this.living = Objects.requireNonNull(living);
        this.log = Objects.requireNonNull(log);
        living.bindTriggers(triggers);
        run();
    }

    @Override public void run() {
        boolean available = living.available();
        if (connected != null && connected == available) return;
        connected = available;
        if (available) triggers.connect();
        else {
            String reason = living.status();
            triggers.unavailable(reason);
            log.accept("Behavior triggers unavailable: " + reason);
        }
    }
}
