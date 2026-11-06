package art.arcane.animus.core.config;

import art.arcane.animus.core.npc.BodyTraits;
import java.util.Objects;

/**
 * The {@code [recordings]} table of {@code config.toml}: performance capture, the recorder's ghost and previews.
 * Every key applies live; lowering a cap lets sessions and previews already running finish.
 *
 * @param enabled off refuses recording, previews and {@code play-recording}, and stops ghosts
 * @param sampleTicks ticks between two captured frames
 * @param maximumTicks the longest recording a session captures
 * @param maximumFileBytes the largest recording file read or saved
 * @param maximumSessions recording players at once
 * @param ghostIntervalTicks ticks between two ghost replays while recording
 * @param ghostStretchTicks how far behind the recorder a ghost replay starts
 * @param ghostColor glow colour of the ghost, one of the sixteen chat colours
 * @param maximumPreviews {@code record preview} ghosts at once
 */
public record RecordingSettings(boolean enabled, int sampleTicks, int maximumTicks, int maximumFileBytes, int maximumSessions, boolean ghostEnabled,
                                int ghostIntervalTicks, int ghostStretchTicks, boolean ghostGlow, String ghostColor, int maximumPreviews) {
    public static final RecordingSettings DEFAULT = new RecordingSettings(true, 2, 12_000, 1_048_576, 4, true, 100, 100, true, "aqua", 8);
    /** The longest recording any setting allows; documents are bounded by this, not by the current {@code maximumTicks}. */
    public static final int MAXIMUM_TICKS = 72_000;

    public RecordingSettings {
        require("sampleTicks", sampleTicks, 1, 20);
        require("maximumTicks", maximumTicks, 200, MAXIMUM_TICKS);
        require("maximumFileBytes", maximumFileBytes, 65_536, 4_194_304);
        require("maximumSessions", maximumSessions, 1, 16);
        require("ghostIntervalTicks", ghostIntervalTicks, 40, 1200);
        require("ghostStretchTicks", ghostStretchTicks, 20, 600);
        require("maximumPreviews", maximumPreviews, 1, 32);
        Objects.requireNonNull(ghostColor);
        if (!BodyTraits.TEAM_COLORS.contains(ghostColor)) throw new IllegalArgumentException("ghostColor must be one of the sixteen chat colours, such as aqua or gold");
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
}
