package art.arcane.animus.bukkit.runtime;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Work allowances for one region tick. A region tick runs on a single thread, so a thread-local window keyed by the
 * region and its tick number bounds the total work every task of that region does in that tick.
 */
final class RegionBudget {
    enum Kind { OWNER_WORK, ROUTES, MOTION_CELLS, CAPTURE, VIEWERS, PACKETS, WAKES, RIG_PLANS, LOOKS }
    private static final class Window { Object region; long tick = Long.MIN_VALUE; final int[] used = new int[Kind.values().length]; }
    private final RegionScheduler regions;
    private final int[] limits = new int[Kind.values().length];
    private final ThreadLocal<Window> windows = ThreadLocal.withInitial(Window::new);

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
