package art.arcane.animus.core.runtime;

/**
 * The order NPC updates give way when a tick's packet budget runs short. Despawns go first and never wait for other
 * work; moves, spawns, equipment and mounts come next; cosmetic updates (look-close turns, rig bone updates, hologram
 * text animation) come last. Once authoritative work had to wait for budget, cosmetic work pauses until the budget has
 * kept up for {@link #RECOVERY_TICKS} ticks. One instance belongs to one owner: the main thread, or one Folia region.
 */
public final class PresentationPressure {
    /** Packet work in the order it is spent and, reversed, the order it yields. */
    public enum Tier { DESPAWN, AUTHORITATIVE, COSMETIC }
    public static final int RECOVERY_TICKS = 20;
    private long shortAt = Long.MIN_VALUE;
    private long deferred;

    /** Authoritative work waited for packet budget on this tick. */
    public void shortOfBudget(long tick) { shortAt = Math.max(shortAt, tick); }

    /** True when work of this tier may run now; a cosmetic refusal is counted for status. */
    public boolean allows(Tier tier, long tick) {
        if (tier != Tier.COSMETIC || !strained(tick)) return true;
        deferred++;
        return false;
    }

    public boolean strained(long tick) { return shortAt != Long.MIN_VALUE && tick - shortAt <= RECOVERY_TICKS; }

    public String describe(long tick) { return (strained(tick) ? "strained" : "normal") + "/cosmetic-deferred=" + deferred; }
}
