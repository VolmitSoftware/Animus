package art.arcane.animus.core.config;

/** Plugin-wide build limits, read from the {@code [builds]} table of {@code config.toml}. A hotload applies them live: a lower {@code maximumBuilds} or {@code maximumTeam} lets running builds finish and only refuses new ones. */
public record BuildSettings(boolean enabled, int maximumBuilds, int maximumTeam, int placementsPerTick, int checksPerTick, int undoPerTick,
                            int maximumVolume, int maximumPalette, int maximumSide, int maximumFileBytes, int maximumUndoEntries, int saveSeconds) {
    public static final BuildSettings DEFAULT = new BuildSettings(true, 16, 8, 32, 512, 32, 1_000_000, 4096, 1024, 32 * 1024 * 1024, 250_000, 5);

    public BuildSettings {
        require("maximumBuilds", maximumBuilds, 1, 256);
        require("maximumTeam", maximumTeam, 1, 32);
        require("placementsPerTick", placementsPerTick, 1, 1024);
        require("checksPerTick", checksPerTick, 1, 16384);
        require("undoPerTick", undoPerTick, 1, 1024);
        require("maximumVolume", maximumVolume, 1, 16_000_000);
        require("maximumPalette", maximumPalette, 1, 16384);
        require("maximumSide", maximumSide, 1, 4096);
        require("maximumFileBytes", maximumFileBytes, 1024, 256 * 1024 * 1024);
        require("maximumUndoEntries", maximumUndoEntries, 0, 5_000_000);
        require("saveSeconds", saveSeconds, 1, 300);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
