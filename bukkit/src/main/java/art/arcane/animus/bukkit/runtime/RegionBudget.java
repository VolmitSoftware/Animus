package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.runtime.PresentationPressure;
import art.arcane.animus.core.runtime.WaitingTurns;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Work allowances for one region tick. A region tick runs on a single thread, so a thread-local window keyed by the
 * region and its tick number bounds the total work every task of that region does in that tick. Route turns also
 * remember, per region, who waited longest, because Folia runs the NPC tasks of a region in the same order every tick.
 * New limits from a hotload are adopted by each region at its next region tick, never in the middle of one.
 */
final class RegionBudget {
    enum Kind { OWNER_WORK, ROUTES, MOTION_CELLS, CAPTURE, VIEWERS, PACKETS, WAKES, RIG_PLANS, LOOKS, ROUTE_PLANS, SIMULATION, COMBAT_BRAINS, COMBAT_SCANS, COMBAT_PATHS, COMBAT_ALERTS, COMBAT_HELPERS }
    private static final long IDLE_TURNS_NANOS = 60_000_000_000L;
    private static final class Window { Object region; long tick = Long.MIN_VALUE; int[] limits; final int[] used = new int[Kind.values().length]; }
    private static final class Turns { final WaitingTurns turns = new WaitingTurns(); final PresentationPressure pressure = new PresentationPressure(); volatile long seen; }
    private final RegionScheduler regions;
    private volatile int[] limits;
    private final ThreadLocal<Window> windows = ThreadLocal.withInitial(Window::new);
    private final Map<Object, Turns> turns = new ConcurrentHashMap<>(), combatTurns = new ConcurrentHashMap<>();
    private final AtomicInteger refusedRoutes = new AtomicInteger();
    private volatile long shortAtNanos = Long.MIN_VALUE;
    /** {@code [npcs] degradedIntervalTicks}; each region's pressure takes it at its next use. */
    private volatile int interval = 1;
    /** Behavior runs waiting for their start budget, which slows cosmetic work on every region. */
    private volatile BooleanSupplier backlog = () -> false;

    RegionBudget(RegionScheduler regions, Map<Kind, Integer> limits) {
        this.regions = Objects.requireNonNull(regions);
        limits(limits);
    }

    /** Any thread: replaces every limit; each region uses the new ones from its next region tick. */
    synchronized void limits(Map<Kind, Integer> limits) {
        EnumMap<Kind, Integer> copy = new EnumMap<>(Kind.class);
        copy.putAll(limits);
        int[] next = new int[Kind.values().length];
        for (Kind kind : Kind.values()) {
            Integer limit = copy.get(kind);
            if (limit == null || limit < 1) throw new IllegalArgumentException("Missing or invalid region budget: " + kind);
            next[kind.ordinal()] = limit;
        }
        this.limits = next;
    }

    /** A new limit, from any thread; regions use it from their next region tick on. */
    synchronized void limit(Kind kind, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid region budget: " + kind);
        int[] next = limits.clone();
        next[kind.ordinal()] = limit;
        limits = next;
    }

    /** Grants at most {@code wanted} units for this region tick; zero away from a region thread. */
    int take(Kind kind, int wanted) {
        if (wanted <= 0) return 0;
        Window window = window();
        if (window == null) return 0;
        int granted = Math.min(wanted, window.limits[kind.ordinal()] - window.used[kind.ordinal()]);
        if (granted <= 0) return 0;
        window.used[kind.ordinal()] += granted;
        return granted;
    }
    /** Returns unused units to this region tick. */
    void refund(Kind kind, int unused) {
        Window window = unused <= 0 ? null : window();
        if (window != null) window.used[kind.ordinal()] = Math.max(0, window.used[kind.ordinal()] - unused);
    }
    /**
     * One route turn for an NPC that has been refused {@code waited} ticks in a row. Under pressure the NPCs that waited
     * longest go first; a refusal is counted for status and logging.
     */
    boolean takeRoute(int waited) {
        Turns entry = turns();
        if (entry == null) return false;
        if (entry.turns.admits(waited) && take(Kind.ROUTES, 1) == 1) return true;
        entry.turns.refused(waited);
        refusedRoutes.incrementAndGet();
        busy();
        return false;
    }
    /** One combat brain turn, longest waiting first, like {@link #takeRoute}: combat NPCs ask in the same order every tick. */
    boolean takeCombatTurn(int waited) {
        Turns entry = turns(combatTurns);
        if (entry == null) return false;
        if (entry.turns.admits(waited) && take(Kind.COMBAT_BRAINS, 1) == 1) return true;
        entry.turns.refused(waited);
        return false;
    }
    /** A route that took a turn but could not use it, for lack of another budget: it counts as waiting. */
    void routeRefused(int waited) {
        refund(Kind.ROUTES, 1);
        Turns entry = turns();
        if (entry != null) entry.turns.refused(waited);
        refusedRoutes.incrementAndGet();
        busy();
    }
    /** One simulation turn (an idle fall step or rig playback) for this region tick; a refusal slows cosmetic work here. */
    boolean takeSimulation() {
        if (take(Kind.SIMULATION, 1) == 1) return true;
        busy();
        return false;
    }
    /** Simulation, navigation or behavior work on this region waited for its budget this tick. */
    private void busy() {
        RegionScheduler.Tick tick = regions.tick();
        Turns entry = tick == null ? null : turns();
        if (entry != null) entry.pressure.busy(tick.number());
    }
    void interval(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Invalid degraded interval");
        interval = ticks;
    }
    void backlog(BooleanSupplier backlogged) { backlog = java.util.Objects.requireNonNull(backlogged); }
    /** Authoritative packet work on this region waited for budget this tick: cosmetic work pauses here for a while. */
    void shortOfPackets() {
        RegionScheduler.Tick tick = regions.tick();
        Turns entry = tick == null ? null : turns();
        if (entry != null) entry.pressure.shortOfBudget(tick.number());
        shortAtNanos = System.nanoTime();
    }
    /** Any thread: some region's authoritative packets waited for budget within the last second, for diagnostics. */
    boolean recentlyShortOfPackets() {
        long at = shortAtNanos;
        return at != Long.MIN_VALUE && System.nanoTime() - at < 1_000_000_000L;
    }
    /** Cosmetic work (look-close, rig sampling, hologram text refresh) may run on this region now; true away from a region thread. */
    boolean cosmeticAllowed() {
        RegionScheduler.Tick tick = regions.tick();
        return tick == null || cosmeticAllowed(tick.number());
    }
    /** Like {@link #cosmeticAllowed()}, spaced by a viewer's own update count so its turn schedule cannot starve it. */
    boolean cosmeticAllowed(long turn) {
        RegionScheduler.Tick tick = regions.tick();
        Turns entry = tick == null ? null : turns();
        return entry == null || entry.pressure.allows(PresentationPressure.Tier.COSMETIC, tick.number(), turn);
    }
    /** A body far from its viewer gets its move on this viewer turn; under pressure, one turn in the interval. */
    boolean distantMove(long turn, long phase) {
        RegionScheduler.Tick tick = regions.tick();
        Turns entry = tick == null ? null : turns();
        return entry == null || entry.pressure.distantMove(tick.number(), turn, phase);
    }
    /** Route refusals on every region since the last call. */
    int drainRefusedRoutes() { return refusedRoutes.getAndSet(0); }
    /** Forgets turns of regions that stopped ticking, after a merge or split. */
    void forgetIdleRegions(long nowNanos) {
        turns.values().removeIf((Turns entry) -> nowNanos - entry.seen > IDLE_TURNS_NANOS);
        combatTurns.values().removeIf((Turns entry) -> nowNanos - entry.seen > IDLE_TURNS_NANOS);
    }
    private Turns turns() { return turns(turns); }
    private Turns turns(Map<Object, Turns> table) {
        RegionScheduler.Tick tick = regions.tick();
        if (tick == null) return null;
        Turns entry = table.computeIfAbsent(tick.region(), (Object region) -> new Turns());
        entry.seen = System.nanoTime();
        entry.turns.begin(tick.number());
        entry.pressure.interval(interval);
        if (table == turns && backlog.getAsBoolean()) entry.pressure.busy(tick.number());
        return entry;
    }
    private Window window() {
        RegionScheduler.Tick tick = regions.tick();
        if (tick == null) return null;
        Window window = windows.get();
        if (window.region != tick.region() || window.tick != tick.number()) {
            window.region = tick.region(); window.tick = tick.number(); window.limits = limits; Arrays.fill(window.used, 0);
        }
        return window;
    }
}
