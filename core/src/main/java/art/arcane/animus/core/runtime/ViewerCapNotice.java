package art.arcane.animus.core.runtime;

/**
 * Says in the console, at most once every ten minutes, that a player reached {@code [npcs] maximumVisible} while more
 * NPCs were in range, so hidden NPCs do not look like a spawn bug. Whether NPCs were left out is checked at most every
 * 30 seconds. Used on the runtime owner only.
 */
final class ViewerCapNotice {
    static final long CHECK_TICKS = 600, WARN_TICKS = 12_000;
    private long checked = Long.MIN_VALUE, warned = Long.MIN_VALUE;

    /** True when a viewer at the cap should be checked for NPCs it cannot see; at most once per {@link #CHECK_TICKS}. */
    boolean due(long tick) {
        if (warned != Long.MIN_VALUE && tick - warned < WARN_TICKS) return false;
        if (checked != Long.MIN_VALUE && tick - checked < CHECK_TICKS) return false;
        checked = tick;
        return true;
    }

    /** The warning for {@code player} at {@code cap} visible NPCs; the next one waits {@link #WARN_TICKS}. */
    String warn(long tick, String player, int cap) {
        warned = tick;
        return player + " has " + cap + " NPCs in view, the [npcs] maximumVisible cap in config.toml; more NPCs in range stay hidden from them."
                + " Raise maximumVisible (up to 128) or spread the NPCs out. This warning repeats at most every 10 minutes";
    }
}
