package art.arcane.animus.core.runtime;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.ToLongFunction;

/**
 * Turns for every route on one owner. Ready routes take turns round robin and at most {@code routesPerTick} work in a
 * tick, so a route that missed its turn goes first next tick. A route that waits for a tick sits in a timed queue and
 * costs nothing until then. A route with no player near sleeps: it holds no turn, and a few sleeping routes per tick
 * look again. There is no limit on how many routes exist. Owner thread only. {@link #budgets} changes both budgets
 * from the next tick: a lowered budget leaves the routes it cannot serve ready, never dropped.
 *
 * @param <R> the route; identity is its equality
 */
public final class RouteScheduler<R> {
    public enum Result {
        /** Keep the route: it is ready again or waits for its due tick. */
        KEEP,
        /** The route ended or was replaced; forget it. */
        DONE,
        /** The tick ran out of a budget every route needs; this route and the rest go first next tick. */
        DEFER,
        /** This route's own kind of work is out of budget this tick; others still work, and it goes first next tick. */
        YIELD
    }
    public interface Listener<R> {
        /** False when no player is near enough for the route to matter. */
        boolean awake(R route);
        Result work(R route);
        /** The route fell asleep: release anything it holds. */
        void slept(R route);
        void woke(R route);
    }
    /** What one tick did: routes that worked, ready routes left for a later tick, and routes still asleep. */
    public record Tick(int served, int deferred, int sleeping) {}
    private enum Place { READY, WAITING, SLEEPING, YIELDED }
    private record Timed<R>(R route, long due, long order) {}

    private int routesPerTick, wakeChecksPerTick;
    private final ToLongFunction<R> due;
    private final Deque<R> ready = new ArrayDeque<>(), sleeping = new ArrayDeque<>(), yielded = new ArrayDeque<>();
    private final PriorityQueue<Timed<R>> waiting = new PriorityQueue<>(Comparator.comparingLong((Timed<R> timed) -> timed.due).thenComparingLong(Timed::order));
    private final Map<R, Place> places = new HashMap<>();
    private long order;

    public RouteScheduler(int routesPerTick, int wakeChecksPerTick, ToLongFunction<R> due) {
        budgets(routesPerTick, wakeChecksPerTick);
        this.due = Objects.requireNonNull(due);
    }

    /** New per-tick budgets, used from the next tick. Routes beyond a lowered budget wait their turn as usual. */
    public void budgets(int routesPerTick, int wakeChecksPerTick) {
        if (routesPerTick < 1 || wakeChecksPerTick < 1) throw new IllegalArgumentException("Route budgets must be positive");
        this.routesPerTick = routesPerTick; this.wakeChecksPerTick = wakeChecksPerTick;
    }

    /** Schedules a new route, or moves a known one, by its due tick. */
    public void add(R route, long now) {
        Objects.requireNonNull(route);
        remove(route);
        long when = due.applyAsLong(route);
        if (when > now) { waiting.add(new Timed<>(route, when, order++)); places.put(route, Place.WAITING); }
        else { ready.addLast(route); places.put(route, Place.READY); }
    }
    public void remove(R route) {
        Place place = places.remove(route);
        if (place == null) return;
        switch (place) {
            case READY -> ready.remove(route);
            case SLEEPING -> sleeping.remove(route);
            case YIELDED -> yielded.remove(route);
            case WAITING -> waiting.removeIf((Timed<R> timed) -> timed.route.equals(route));
        }
    }
    public boolean contains(R route) { return places.containsKey(route); }
    public int size() { return places.size(); }
    public int sleeping() { return sleeping.size(); }
    public int waiting() { return waiting.size(); }
    public int ready() { return ready.size(); }
    /** Every route, for shutdown and world unloads. */
    public java.util.List<R> routes() { return java.util.List.copyOf(places.keySet()); }
    public void clear() { ready.clear(); sleeping.clear(); yielded.clear(); waiting.clear(); places.clear(); }

    public Tick tick(long now, Listener<R> listener) {
        while (!waiting.isEmpty() && waiting.peek().due <= now) {
            R route = waiting.poll().route;
            ready.addLast(route); places.put(route, Place.READY);
        }
        for (int i = 0, count = Math.min(wakeChecksPerTick, sleeping.size()); i < count; i++) {
            R route = sleeping.removeFirst();
            if (listener.awake(route)) { ready.addLast(route); places.put(route, Place.READY); listener.woke(route); }
            else sleeping.addLast(route);
        }
        int pending = ready.size(), visited = 0, served = 0;
        while (visited < pending && served < routesPerTick && !ready.isEmpty()) {
            R route = ready.removeFirst(); visited++;
            if (!listener.awake(route)) { sleeping.addLast(route); places.put(route, Place.SLEEPING); listener.slept(route); continue; }
            places.remove(route);
            Result result = listener.work(route);
            if (result == Result.DEFER) { if (!places.containsKey(route)) { ready.addFirst(route); places.put(route, Place.READY); } visited--; break; }
            if (result == Result.YIELD) { if (!places.containsKey(route)) { yielded.addLast(route); places.put(route, Place.YIELDED); } continue; }
            served++;
            if (result == Result.KEEP && !places.containsKey(route)) add(route, now);
        }
        int held = yielded.size();
        while (!yielded.isEmpty()) { R route = yielded.removeLast(); ready.addFirst(route); places.put(route, Place.READY); }
        return new Tick(served, Math.max(0, pending - visited) + held, sleeping.size());
    }
}
