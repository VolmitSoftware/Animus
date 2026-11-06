package art.arcane.animus.core.runtime;

/**
 * The order NPC updates give way when a tick's budgets run short. Despawns go first and never wait for other work;
 * moves, spawns, equipment and mounts come next; cosmetic updates (look-close turns, rig sampling and bones, playback
 * poses, arm swings, dragon wing phases, hologram text animation) come last.
 * <p>
 * Two levels of pressure, each lasting {@link #RECOVERY_TICKS} after the last tick that caused it:
 * <ul>
 *   <li>{@link Level#DEGRADED}: the simulation, navigation or behavior budget ran short. Cosmetic work runs on one turn
 *   in {@code interval}, and moves of bodies far from a viewer are spread the same way, so authoritative work keeps its
 *   budget.</li>
 *   <li>{@link Level#STRAINED}: authoritative packet work waited for budget. Cosmetic work pauses and distant moves stay
 *   spread.</li>
 * </ul>
 * One instance belongs to one owner: the main thread, or one Folia region.
 */
public final class PresentationPressure {
    /** Packet work in the order it is spent and, reversed, the order it yields. */
    public enum Tier { DESPAWN, AUTHORITATIVE, COSMETIC }
    public enum Level { NORMAL, DEGRADED, STRAINED }
    public static final int RECOVERY_TICKS = 20;
    private long shortAt = Long.MIN_VALUE, busyAt = Long.MIN_VALUE;
    private int interval = 1;
    private long deferred;

    /** Authoritative work waited for packet budget on this tick. */
    public void shortOfBudget(long tick) { shortAt = Math.max(shortAt, tick); }

    /** Simulation, navigation or behavior work waited for its budget on this tick. */
    public void busy(long tick) { busyAt = Math.max(busyAt, tick); }

    /** Turns between two cosmetic turns while degraded: {@code [npcs] degradedIntervalTicks}. */
    public void interval(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Interval must be at least one tick");
        interval = ticks;
    }

    public Level level(long tick) {
        if (recent(shortAt, tick)) return Level.STRAINED;
        return recent(busyAt, tick) ? Level.DEGRADED : Level.NORMAL;
    }

    /** True when work of this tier may run on this tick; a cosmetic refusal is counted for status. */
    public boolean allows(Tier tier, long tick) { return allows(tier, tick, tick); }

    /**
     * Like {@link #allows(Tier, long)}, spaced by the caller's own turn counter: a viewer that updates every few ticks
     * counts its own updates, so a turn schedule that lines up with the interval cannot starve it.
     */
    public boolean allows(Tier tier, long tick, long turn) {
        if (tier != Tier.COSMETIC || cosmetic(tick, turn)) return true;
        deferred++;
        return false;
    }

    /** Whether cosmetic work may run now, without counting a refusal. */
    public boolean cosmetic(long tick, long turn) {
        return switch (level(tick)) {
            case NORMAL -> true;
            case DEGRADED -> Math.floorMod(turn, interval) == 0;
            case STRAINED -> false;
        };
    }

    /**
     * Whether a body far from its viewer gets its move on this viewer turn. Under pressure each body moves on one turn in
     * {@code interval}, offset by its own {@code phase}, and always with its latest position.
     */
    public boolean distantMove(long tick, long turn, long phase) {
        return level(tick) == Level.NORMAL || Math.floorMod(turn + phase, interval) == 0;
    }

    public boolean strained(long tick) { return level(tick) == Level.STRAINED; }

    /** Cosmetic updates refused under pressure since start. */
    public long deferred() { return deferred; }

    public String describe(long tick) { return level(tick).name().toLowerCase(java.util.Locale.ROOT) + "/cosmetic-deferred=" + deferred; }

    private static boolean recent(long at, long tick) { return at != Long.MIN_VALUE && tick - at <= RECOVERY_TICKS; }
}
