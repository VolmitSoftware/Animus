package art.arcane.animus.core.hotload;

import art.arcane.animus.core.text.ChatLine;

/** How a platform reaches its online operators. Called from the hotload thread; never blocks. */
@FunctionalInterface
public interface OperatorChannel {
    /** The permission that receives hotload lines; operators hold it by default. */
    String NOTIFY = "animus.hotload.notify";
    /** The note block ding before each file's line, as Adapt plays it. */
    float VOLUME = 0.8f, PITCH = 1.6f;

    /**
     * On each online player's own owner: when the player holds {@link #NOTIFY}, play the ding if {@code sound} is true,
     * then send the line with its chips, or its section-coded text when it has none.
     */
    void notifyOperators(ChatLine line, boolean sound);
}
