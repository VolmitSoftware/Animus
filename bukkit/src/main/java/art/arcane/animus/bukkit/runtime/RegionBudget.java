package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.runtime.PresentationPressure;
import art.arcane.animus.core.runtime.WaitingTurns;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Work allowances for one region tick. A region tick runs on a single thread, so a thread-local window keyed by the
 * region and its tick number bounds the total work every task of that region does in that tick. Route turns also
 * remember, per region, who waited longest, because Folia runs the NPC tasks of a region in the same order every tick.
 */
final class RegionBudget {
    enum Kind { OWNER_WORK, ROUTES, MOTION_CELLS, CAPTURE, VIEWERS, PACKETS, WAKES, RIG_PLANS, LOOKS, ROUTE_PLANS, COMBAT_BRAINS, COMBAT_SCANS, COMBAT_PATHS, COMBAT_ALERTS, COMBAT_HELPERS }
    private static final long IDLE_TURNS_NANOS = 60_000_000_000L;
    private static final class Window { Object region; long tick = Long.MIN_VALUE; final int[] used = new int[Kind.values().length]; }
    private static final class Turns { final WaitingTurns turns = new WaitingTurns(); final PresentationPressure pressure = new PresentationPressure(); volatile long seen; }
    private final RegionScheduler regions;
    private final int[] limits = new int[Kind.values().length];
    private final ThreadLocal<Window> windows = ThreadLocal.withInitial(Window::new);
    private final Map<Object, Turns> turns = new ConcurrentHashMap<>(), combatTurns = new ConcurrentHashMap<>();
    private final AtomicInteger refusedRoutes = new AtomicInteger();

    RegionBudget(RegionScheduler regions, Map<Kind, Integer> limits) {
        this.regions = Objects.requireNonNull(regions);
        EnumMap<Kind, Integer> copy = new EnumMap<>(Kind.class);
        copy.putAll(limits);
        for (Kind kind : Kind.values()) {
            Integer limit = copy.get(kind);
            if (limit == null || limit < 1) throw new IllegalArgumentException("Missing or invalid region budget: " + kind);
            this.limits[kind.ordinal()] = limit;
        }
    }

    /** Grants at most {@code wanted} units for this region tick; zero away from a region thread. */
    int take(Kind kind, int wanted) {
        if (wanted <= 0) return 0;
        Window window = window();
        if (window == null) return 0;
        int granted = Math.min(wanted, limits[kind.ordinal()] - window.used[kind.ordinal()]);
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
    }
    /** Authoritative packet work on this region waited for budget this tick: cosmetic work pauses here for a while. */
    void shortOfPackets() {
        RegionScheduler.Tick tick = regions.tick();
        Turns entry = tick == null ? null : turns();
        if (entry != null) entry.pressure.shortOfBudget(tick.number());
    }
    /** Cosmetic work (look-close, rig sampling and bones, hologram text refresh) may run on this region now; true away from a region thread. */
    boolean cosmeticAllowed() {
        RegionScheduler.Tick tick = regions.tick();
        Turns entry = tick == null ? null : turns();
        return entry == null || entry.pressure.allows(PresentationPressure.Tier.COSMETIC, tick.number());
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
        return entry;
    }
    private Window window() {
        RegionScheduler.Tick tick = regions.tick();
        if (tick == null) return null;
        Window window = windows.get();
        if (window.region != tick.region() || window.tick != tick.number()) {
            window.region = tick.region(); window.tick = tick.number(); Arrays.fill(window.used, 0);
        }
        return window;
    }
}
