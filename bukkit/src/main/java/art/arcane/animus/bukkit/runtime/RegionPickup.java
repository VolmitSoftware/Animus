package art.arcane.animus.bukkit.runtime;

import java.util.Objects;

/**
 * A value any thread may publish, which each Folia region adopts at its own next region tick: every read within one
 * region tick sees the value that tick first read. Away from a region thread the newest value is read.
 *
 * @param <T> an immutable value
 */
final class RegionPickup<T> {
    private static final class Window { Object region; long tick = Long.MIN_VALUE; Object value; }
    private final RegionScheduler regions;
    private final ThreadLocal<Window> windows = ThreadLocal.withInitial(Window::new);
    private volatile T published;

    RegionPickup(RegionScheduler regions, T initial) {
        this.regions = Objects.requireNonNull(regions, "regions");
        published = Objects.requireNonNull(initial, "initial");
    }

    /** Any thread: regions pick the value up on their next tick. */
    void publish(T next) { published = Objects.requireNonNull(next, "next"); }

    @SuppressWarnings("unchecked")
    T current() {
        RegionScheduler.Tick tick = regions.tick();
        if (tick == null) return published;
        Window window = windows.get();
        if (window.region != tick.region() || window.tick != tick.number()) {
            window.region = tick.region(); window.tick = tick.number(); window.value = published;
        }
        return (T) window.value;
    }
}
