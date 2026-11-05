package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.virtual.Vec3;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Chooses route legs for one NPC in every runtime. Last valid route data outlives its execution; an unchanged reload
 * cannot restart a completed once route. Owner thread only.
 */
public final class WaypointProgress {
    /** Leg bounds every runtime enforces; random links and wander targets stay inside them. */
    public static final double LEG_HORIZONTAL = 32, LEG_VERTICAL = 8;
    public static final int RANDOM_RETRY = 100, WANDER_RETRY = 20;
    /**
     * One leg: walk to goal after waitTicks, then report arrival. A pause leg has no goal; once its wait ends the runtime
     * asks {@link #resume} for a fresh leg.
     */
    public record Leg(Vec3 goal, int arrival, int waitTicks) {
        public Leg { if (waitTicks < 0) throw new IllegalArgumentException("Negative wait"); }
        public static Leg pause(int waitTicks) { return new Leg(null, -1, waitTicks); }
        public boolean pause() { return goal == null; }
    }

    private WaypointSettings settings;
    private final Vec3 home;
    private final RandomGenerator random;
    private Leg current;
    private boolean complete;
    private int next, step = 1, node = -1, wanderCentre;
    private final ArrayDeque<Integer> path = new ArrayDeque<>();
    private int[][] links;

    public WaypointProgress(WaypointSettings settings, Vec3 home, RandomGenerator random) {
        this.settings = Objects.requireNonNull(settings); this.home = Objects.requireNonNull(home); this.random = Objects.requireNonNull(random);
    }
    /** A generator of its own for each NPC. */
    public static WaypointProgress of(WaypointSettings settings, Vec3 home, UUID npc) {
        return new WaypointProgress(settings, home, new SplittableRandom(npc.getMostSignificantBits() ^ Long.rotateLeft(npc.getLeastSignificantBits(), 17) ^ System.nanoTime()));
    }
    public WaypointSettings settings() { return settings; }
    public boolean complete() { return complete; }
    /** Random and wander routes retry a failed leg instead of halting. */
    public boolean retries() { return settings.order() == WaypointSettings.Order.RANDOM || settings.order() == WaypointSettings.Order.WANDER; }
    public boolean reload(WaypointSettings replacement) {
        Objects.requireNonNull(replacement);
        if (settings.equals(replacement)) return false;
        settings = replacement; links = null; reset(); complete = false; return true;
    }
    private void reset() { current = null; next = 0; step = 1; node = -1; path.clear(); }

    /** The first leg from the NPC's position; restarts an unfinished route. Empty once a once route is complete. */
    public Optional<Leg> start(Vec3 position) {
        if (complete) return Optional.empty();
        reset();
        return issue(switch (settings.order()) {
            case SEQUENCE, PINGPONG -> point(0, settings.waitTicks());
            case RANDOM -> choose(position, settings.waitTicks());
            case WANDER -> wander(position, settings.waitTicks());
        });
    }
    /** The leg after the current one arrived; empty when the route is complete. */
    public Optional<Leg> arrived(Leg leg, Vec3 position) {
        if (complete || leg == null || leg != current || leg.pause()) throw new IllegalStateException("Waypoint arrival is no longer current");
        WaypointSettings.Order order = settings.order();
        if (order == WaypointSettings.Order.SEQUENCE) {
            int wait = settings.waitAfter(next);
            next++;
            if (next == settings.points().size()) {
                if (!settings.loop()) return finish();
                next = 0;
            }
            return issue(point(next, wait));
        }
        if (order == WaypointSettings.Order.PINGPONG) {
            int size = settings.points().size(), wait = settings.waitAfter(next);
            if (size == 1) return finish();
            if (next + step < 0 || next + step >= size) step = -step;
            next += step;
            return issue(point(next, wait));
        }
        if (order == WaypointSettings.Order.RANDOM) {
            node = path.removeFirst();
            if (!path.isEmpty()) return issue(hop(1));
            return issue(choose(position, node < settings.points().size() ? settings.waitAfter(node) : settings.waitTicks()));
        }
        int wait = settings.points().isEmpty() ? settings.waitTicks() : settings.waitAfter(wanderCentre);
        return issue(wander(position, wait));
    }
    /** After a failed leg: a pause leg for retrying orders, otherwise empty and the route halts. */
    public Optional<Leg> failed(Leg leg) {
        if (complete || leg != current) return Optional.empty();
        if (!retries()) { current = null; return Optional.empty(); }
        node = -1; path.clear();
        return issue(Leg.pause(retryDelay()));
    }
    /** A fresh leg once a pause leg's wait has passed. */
    public Optional<Leg> resume(Leg leg, Vec3 position) {
        if (complete || leg != current || !leg.pause()) return Optional.empty();
        return issue(settings.order() == WaypointSettings.Order.WANDER ? wander(position, 1) : choose(position, 1));
    }
    public int retryDelay() {
        return Math.max(settings.waitTicks(), settings.order() == WaypointSettings.Order.WANDER ? WANDER_RETRY : RANDOM_RETRY);
    }
    private Optional<Leg> issue(Leg leg) { current = leg; return Optional.of(leg); }
    private Optional<Leg> finish() { complete = true; current = null; return Optional.empty(); }
    private Leg point(int index, int wait) { return new Leg(settings.points().get(index).position(), index, wait); }

    // ---- Guided random destinations ----

    private Vec3 nodePosition(int index) {
        int points = settings.points().size();
        return index < points ? settings.points().get(index).position() : settings.guides().get(index - points);
    }
    private Leg hop(int wait) { int target = path.peekFirst(); return new Leg(nodePosition(target), target, wait); }
    private Leg choose(Vec3 position, int wait) {
        path.clear();
        int points = settings.points().size();
        int destination;
        if (points == 1) destination = 0;
        else if (node >= 0 && node < points) { destination = random.nextInt(points - 1); if (destination >= node) destination++; }
        else destination = random.nextInt(points);
        if (destination == node) return Leg.pause(retryDelay());
        List<Integer> found = route(position, destination);
        if (found.isEmpty()) return Leg.pause(retryDelay());
        path.addAll(found);
        return hop(wait);
    }
    private boolean linked(Vec3 from, Vec3 to) {
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        return Math.abs(dx) <= LEG_HORIZONTAL && Math.abs(dz) <= LEG_HORIZONTAL && Math.abs(dy) <= LEG_VERTICAL
                && Math.sqrt(dx * dx + dy * dy + dz * dz) <= settings.linkDistance();
    }
    private int[][] links() {
        if (links != null) return links;
        int count = settings.points().size() + settings.guides().size();
        Vec3[] nodes = new Vec3[count];
        for (int i = 0; i < count; i++) nodes[i] = nodePosition(i);
        List<List<Integer>> adjacent = new ArrayList<>(count);
        for (int i = 0; i < count; i++) adjacent.add(new ArrayList<>());
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++)
            if (linked(nodes[i], nodes[j])) { adjacent.get(i).add(j); adjacent.get(j).add(i); }
        int[][] built = new int[count][];
        for (int i = 0; i < count; i++) built[i] = adjacent.get(i).stream().mapToInt(Integer::intValue).toArray();
        links = built;
        return built;
    }
    /**
     * A* over guides and points with euclidean costs; the hops after the start, ending at the destination. From a free
     * position the first hop is a guide whenever the route has guides.
     */
    private List<Integer> route(Vec3 position, int destination) {
        int[][] graph = links();
        int count = graph.length, points = settings.points().size();
        Vec3 goal = nodePosition(destination);
        double[] cost = new double[count];
        int[] previous = new int[count];
        boolean[] closed = new boolean[count];
        Arrays.fill(cost, Double.POSITIVE_INFINITY); Arrays.fill(previous, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble((double[] entry) -> entry[0]));
        if (node >= 0) { cost[node] = 0; open.add(new double[]{nodePosition(node).distance(goal), node}); }
        else {
            boolean guided = !settings.guides().isEmpty();
            for (int i = guided ? points : 0; i < count; i++) {
                Vec3 at = nodePosition(i);
                if (!linked(position, at)) continue;
                double start = position.distance(at);
                if (start < cost[i]) { cost[i] = start; open.add(new double[]{start + at.distance(goal), i}); }
            }
        }
        while (!open.isEmpty()) {
            int at = (int) open.poll()[1];
            if (closed[at]) continue;
            closed[at] = true;
            if (at == destination) {
                ArrayDeque<Integer> hops = new ArrayDeque<>();
                for (int cursor = at; cursor != -1 && cursor != node; cursor = previous[cursor]) hops.addFirst(cursor);
                return List.copyOf(hops);
            }
            Vec3 from = nodePosition(at);
            for (int neighbour : graph[at]) {
                if (closed[neighbour]) continue;
                Vec3 to = nodePosition(neighbour);
                double through = cost[at] + from.distance(to);
                if (through < cost[neighbour]) { cost[neighbour] = through; previous[neighbour] = at; open.add(new double[]{through + to.distance(goal), neighbour}); }
            }
        }
        return List.of();
    }

    // ---- Wander ----

    private Leg wander(Vec3 position, int wait) {
        List<WaypointSettings.Waypoint> centres = settings.points();
        wanderCentre = centres.isEmpty() ? 0 : random.nextInt(centres.size());
        Vec3 centre = centres.isEmpty() ? home : centres.get(wanderCentre).position();
        WaypointSettings.Wander area = settings.wander();
        int x = (int) Math.floor(centre.x()) + random.nextInt(-area.radius(), area.radius() + 1);
        int z = (int) Math.floor(centre.z()) + random.nextInt(-area.radius(), area.radius() + 1);
        int y = (int) Math.floor(centre.y()) + random.nextInt(-area.height(), area.height() + 1);
        x = clamp(x, (int) Math.ceil(position.x() - LEG_HORIZONTAL - .5), (int) Math.floor(position.x() + LEG_HORIZONTAL - .5));
        z = clamp(z, (int) Math.ceil(position.z() - LEG_HORIZONTAL - .5), (int) Math.floor(position.z() + LEG_HORIZONTAL - .5));
        y = clamp(y, (int) Math.ceil(position.y() - LEG_VERTICAL), (int) Math.floor(position.y() + LEG_VERTICAL));
        return new Leg(new Vec3(x + .5, y, z + .5), wanderCentre, wait);
    }
    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
}
