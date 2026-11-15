package art.arcane.animus.core.config;

/**
 * {@code [skins]} in {@code config.toml}: the MineSkin API key that signs uploaded skins and skin addresses, and how
 * often skins taken from a player name are checked for a change.
 *
 * @param mineskinKey a key from MineSkin's account page; blank signs at MineSkin's anonymous limit
 * @param refreshMinutes how often NPCs that follow a player's skin look it up again
 */
public record SkinSettings(String mineskinKey, int refreshMinutes) {
    public static final int MINIMUM_REFRESH = 5, MAXIMUM_REFRESH = 1440;
    public static final SkinSettings DEFAULT = new SkinSettings("", 30);

    public SkinSettings {
        if (mineskinKey == null) mineskinKey = "";
        mineskinKey = mineskinKey.strip();
        if (mineskinKey.length() > 256 || mineskinKey.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("mineskinKey must be one line of at most 256 characters");
        if (refreshMinutes < MINIMUM_REFRESH || refreshMinutes > MAXIMUM_REFRESH)
            throw new IllegalArgumentException("refreshMinutes must be within " + MINIMUM_REFRESH + ".." + MAXIMUM_REFRESH);
    }

    /** Whether a key is set; without one MineSkin still signs, more slowly. */
    public boolean keyed() { return !mineskinKey.isEmpty(); }

    /** Never prints the key. */
    @Override public String toString() { return "SkinSettings[mineskinKey=" + (keyed() ? "set" : "blank") + ", refreshMinutes=" + refreshMinutes + "]"; }
}
