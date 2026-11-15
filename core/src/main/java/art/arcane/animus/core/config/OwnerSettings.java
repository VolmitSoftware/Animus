package art.arcane.animus.core.config;

/**
 * The {@code [owners]} table of {@code config.toml}: NPCs players make for themselves. Applies live.
 *
 * @param defaultLimit how many NPCs a player may own without an {@code animus.npc.limit.<n>} node; {@code animus.admin}
 *                     has no limit
 */
public record OwnerSettings(int defaultLimit) {
    public static final int MAXIMUM_LIMIT = 1000;
    public static final OwnerSettings DEFAULT = new OwnerSettings(3);

    public OwnerSettings {
        if (defaultLimit < 0 || defaultLimit > MAXIMUM_LIMIT) throw new IllegalArgumentException("defaultLimit must be within 0.." + MAXIMUM_LIMIT + ", not " + defaultLimit);
    }
}
