package art.arcane.animus.core.command;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The sounds a player hears from {@code /animus}, the same ones Iris and Adapt play: a soft chime on every completion
 * request while typing, a ding when a command or help page answers, and a low tone when one is refused. Every platform
 * plays them at the player in the master category.
 */
public enum CommandSound {
    /** A completion request while typing; the pitch varies so typing sounds like a run of chimes. */
    TYPING,
    /** A command answered, or a help or list page shown. */
    SUCCESS,
    /** A command refused, failed or unknown. */
    FAILURE;

    /** One vanilla sound by its registry key. */
    public record Tone(String sound, float volume, float pitch) {}

    static final float TYPING_LOW = 0.125f, TYPING_HIGH = 1.95f;

    /** What this event plays, in order. */
    public List<Tone> tones(RandomGenerator random) {
        return switch (this) {
            case TYPING -> List.of(new Tone("minecraft:block.amethyst_block.chime", 0.25f, random.nextFloat(TYPING_LOW, TYPING_HIGH)));
            case SUCCESS -> List.of(new Tone("minecraft:block.amethyst_cluster.break", 0.77f, 1.65f),
                    new Tone("minecraft:block.respawn_anchor.charge", 0.125f, 2.99f));
            case FAILURE -> List.of(new Tone("minecraft:block.amethyst_cluster.break", 0.77f, 0.25f),
                    new Tone("minecraft:block.beacon.deactivate", 0.2f, 0.45f));
        };
    }
}
