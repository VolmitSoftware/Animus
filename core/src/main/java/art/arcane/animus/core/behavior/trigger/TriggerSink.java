package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Outcome;

/** Quick, nonblocking, thread-safe event admission. A true value means the event was queued for a bound NPC. */
public interface TriggerSink {
    boolean available();
    /** The reason triggers cannot run, or "available". */
    String availability();
    Outcome<Boolean> publish(TriggerEvent event);
}
