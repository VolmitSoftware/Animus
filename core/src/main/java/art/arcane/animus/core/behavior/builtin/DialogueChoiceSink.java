package art.arcane.animus.core.behavior.builtin;

/** Narrow publication point for the triggers lane. Called on the platform owner; must return quickly. */
@FunctionalInterface
public interface DialogueChoiceSink {
    void publish(DialogueChoice choice);
}
