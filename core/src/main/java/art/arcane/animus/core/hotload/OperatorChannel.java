package art.arcane.animus.core.hotload;

import java.util.List;

/** How a platform reaches its online operators. Called from the hotload thread; never blocks. */
@FunctionalInterface
public interface OperatorChannel {
    /** The permission that receives hotload messages; operators hold it by default. */
    String NOTIFY = "animus.hotload.notify";
    /** The note block ding before each file's messages, as Adapt plays it. */
    float VOLUME = 0.8f, PITCH = 1.6f;

    /**
     * On each online player's own owner: when the player holds {@link #NOTIFY}, play the ding if {@code sound} is true,
     * then send the section-coded {@code lines} without reading colour codes again.
     */
    void notifyOperators(List<String> lines, boolean sound);
}
