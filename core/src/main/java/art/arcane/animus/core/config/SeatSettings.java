package art.arcane.animus.core.config;

/**
 * The {@code [seats]} table of {@code config.toml}: players sitting on NPCs. Every key applies live; a lowered
 * {@code maximum} lets players already sitting stay.
 *
 * @param defaultEnabled whether an NPC without an {@code animus:seat} module takes seated players
 * @param maximum players sitting on NPCs at once across the server
 * @param followGSit with GSit running, its player-sit permission, toggle, worlds, regions, empty hand, distance and stack size apply too
 */
public record SeatSettings(boolean defaultEnabled, int maximum, boolean followGSit) {
    public static final SeatSettings DEFAULT = new SeatSettings(false, 32, true);

    public SeatSettings {
        if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("maximum must be within 1..256, not " + maximum);
    }
}
