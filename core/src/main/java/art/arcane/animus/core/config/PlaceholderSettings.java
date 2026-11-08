package art.arcane.animus.core.config;

/**
 * {@code [placeholders]} in {@code config.toml}: how often nameplates and hologram lines with PlaceholderAPI
 * placeholders are filled again for each viewer.
 *
 * @param refreshTicks 0 fills them when a body or hologram is shown and when it changes; otherwise every this many ticks
 */
public record PlaceholderSettings(int refreshTicks) {
    public static final int MINIMUM_REFRESH = 20, MAXIMUM_REFRESH = 72_000;
    public static final PlaceholderSettings DEFAULT = new PlaceholderSettings(0);

    public PlaceholderSettings {
        if (refreshTicks != 0 && (refreshTicks < MINIMUM_REFRESH || refreshTicks > MAXIMUM_REFRESH))
            throw new IllegalArgumentException("refreshTicks must be 0 or within " + MINIMUM_REFRESH + ".." + MAXIMUM_REFRESH);
    }

    public boolean refreshes() { return refreshTicks > 0; }
}
