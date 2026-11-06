package art.arcane.animus.core.recording;

import java.util.Locale;
import java.util.Optional;

/** How a recording repeats: {@code once} stops on the last frame, {@code loop} starts over, {@code pingpong} plays back and forth. */
public enum Playback {
    ONCE, LOOP, PINGPONG;

    /** The mode a navigation module or action gets when it names none. */
    public static final Playback DEFAULT = LOOP;

    /** The lowercase key documents use. */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** The mode for an exact lowercase key, or empty. */
    public static Optional<Playback> parse(String key) {
        for (Playback mode : values()) if (mode.key().equals(key)) return Optional.of(mode);
        return Optional.empty();
    }
}
