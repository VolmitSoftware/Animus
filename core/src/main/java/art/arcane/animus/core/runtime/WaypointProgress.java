package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.recording.RouteRecordings;
import art.arcane.animus.core.virtual.Vec3;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Chooses route legs for one NPC in every runtime. Last valid route data outlives its execution; an unchanged reload
 * cannot restart a completed once route. Owner thread only.
 */
public final class WaypointProgress {
    public static final int RANDOM_RETRY = 100, WANDER_RETRY = 20;
    /** The route identity of module routes, which have no reusable route id. */
    public static final String INLINE = "inline";
    /**
     * One leg: walk to goal after waitTicks, then report arrival. Goal is the route node's point, or that point moved by
     * the route's jitter. A pause leg has no goal; once its wait ends the runtime asks {@link #resume} for a fresh leg. A
     * step leg has no goal either: after its wait the runtime walks to the segment's first frame, plays the segment and
     * reports arrival where it ends.
     */
    public record Leg(Vec3 goal, Vec3 point, int arrival, int waitTicks, WaypointSettings.Step step) {
        public Leg {
            if (waitTicks < 0) throw new IllegalArgumentException("Negative wait");
            if (step != null && (goal != null || arrival < 0)) throw new IllegalArgumentException("A step leg has no goal of its own");
        }
        public Leg(Vec3 goal, Vec3 point, int arrival, int waitTicks) { this(goal, point, arrival, waitTicks, null); }
        public static Leg pause(int waitTicks) { return new Leg(null, null, -1, waitTicks); }
        public boolean pause() { return goal == null && step == null; }
        /** The goal lies off the point, so a goal that cannot be planned falls back to the point once. */
        public boolean jittered() { return goal != null && !goal.equals(point); }
    }

    private WaypointSettings settings;
    private final Vec3 home;
    private final RandomGenerator random;
    private Leg current;
    private boolean complete;
    private int next, step = 1, node = -1, wanderCentre;
    private final ArrayDeque<Integer> path = new ArrayDeque<>();
    private int[][] links;
    private RouteRecordings recordings;

    public WaypointProgress(WaypointSettings settings, Vec3 home, RandomGenerator random) {
        this.settings = Objects.requireNonNull(settings); this.home = Objects.requireNonNull(home); this.random = Objects.requireNonNull(random);
    }
    /**
     * A generator of its own for each NPC. Seed npc derives it from the NPC and the route identity (a reusable route id,
     * or {@link #INLINE}), so the NPC makes the same choices after a restart; seed random mixes in the clock.
     */
    public static WaypointProgress of(WaypointSettings settings, Vec3 home, UUID npc, String route) {
        long seed = npc.getMostSignificantBits() ^ Long.rotateLeft(npc.getLeastSignificantBits(), 17) ^ (route.hashCode() * 0x9E3779B97F4A7C15L);
        if (settings.seed() == WaypointSettings.Seed.RANDOM) seed ^= System.nanoTime();
        return new WaypointProgress(settings, home, new SplittableRandom(seed));
    }
    public WaypointSettings settings() { return settings; }
    /** The recordings this route load read for its steps; null until they are read. */
    public RouteRecordings recordings() { return recordings; }
    public void recordings(RouteRecordings loaded) { recordings = Objects.requireNonNull(loaded); }
    public boolean complete() { return complete; }
    /** The leg is the one this progress expects an arrival or failure for. */
    public boolean current(Leg leg) { return !complete && leg != null && leg == current; }
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
        boolean offset = settings.startOffset() == WaypointSettings.StartOffset.RANDOM;
        int wait = offset ? random.nextInt(settings.waitTicks().max() + 1) : draw(settings.waitTicks());
        return issue(switch (settings.order()) {
            case SEQUENCE, PINGPONG -> point(offset ? (next = random.nextInt(settings.points().size())) : 0, wait);
            case RANDOM -> choose(position, wait);
            case WANDER -> wander(wait);
        });
    }
    /** The leg after the current one arrived; empty when the route is complete. */
    public Optional<Leg> arrived(Leg leg, Vec3 position) {
        if (complete || leg == null || leg != current || leg.pause()) throw new IllegalStateException("Waypoint arrival is no longer current");
        WaypointSettings.Order order = settings.order();
        if (order == WaypointSettings.Order.SEQUENCE) {
            int wait = draw(settings.waitAfter(next));
            List<WaypointSettings.Junction> junction = settings.points().get(next).next();
            if (!junction.isEmpty()) next = junction(junction);
            else if (++next == settings.points().size()) {
                if (!settings.loop()) return finish();
                next = 0;
            }
            return issue(point(next, wait));
        }
        if (order == WaypointSettings.Order.PINGPONG) {
            int size = settings.points().size(), wait = draw(settings.waitAfter(next));
            if (size == 1) return finish();
            if (next + step < 0 || next + step >= size) step = -step;
            next += step;
            return issue(point(next, wait));
        }
        if (order == WaypointSettings.Order.RANDOM) {
            node = path.removeFirst();
            if (!path.isEmpty()) return issue(hop(1));
            return issue(choose(position, draw(node < settings.points().size() ? settings.waitAfter(node) : settings.waitTicks())));
        }
        return issue(wander(draw(settings.points().isEmpty() ? settings.waitTicks() : settings.waitAfter(wanderCentre))));
    }
    /** The current jittered leg again, aimed at its exact point and without a wait; empty for any other leg. */
    public Optional<Leg> exact(Leg leg) {
        if (complete || leg == null || leg != current || !leg.jittered()) return Optional.empty();
        return issue(new Leg(leg.point(), leg.point(), leg.arrival(), 0));
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
        return issue(settings.order() == WaypointSettings.Order.WANDER ? wander(1) : choose(position, 1));
    }
    public int retryDelay() {
        return Math.max(settings.waitTicks().max(), settings.order() == WaypointSettings.Order.WANDER ? WANDER_RETRY : RANDOM_RETRY);
    }
    private Optional<Leg> issue(Leg leg) { current = leg; return Optional.of(leg); }
    private Optional<Leg> finish() { complete = true; current = null; return Optional.empty(); }
    private Leg point(int index, int wait) {
        WaypointSettings.Waypoint point = settings.points().get(index);
        if (point.step() != null) return new Leg(null, null, index, wait, point.step());
        return new Leg(jitter(point.position(), settings.jitterAt(index)), point.position(), index, wait);
    }
    /** A fixed wait draws nothing, so routes without ranges keep the choices they made before ranges existed. */
    private int draw(WaypointSettings.Wait wait) { return wait.min() == wait.max() ? wait.min() : random.nextInt(wait.min(), wait.max() + 1); }
    /** A whole-block x and z offset within the radius; the height stays the point's. */
    private Vec3 jitter(Vec3 at, int radius) {
        if (radius == 0) return at;
        int dx, dz;
        do { dx = random.nextInt(-radius, radius + 1); dz = random.nextInt(-radius, radius + 1); } while (dx * dx + dz * dz > radius * radius);
        return new Vec3(at.x() + dx, at.y(), at.z() + dz);
    }
    private int junction(List<WaypointSettings.Junction> junctions) {
        int total = 0;
        for (WaypointSettings.Junction junction : junctions) total += junction.weight();
        int pick = random.nextInt(total);
        for (WaypointSettings.Junction junction : junctions) if ((pick -= junction.weight()) < 0) return junction.to();
        throw new IllegalStateException("Junction weights are positive");
    }

    // ---- Guided random destinations ----

    /** A recording step has no position, so it is no link node: a route goes to it directly and leaves it from where it ended. */
    private boolean step(int index) { return index >= 0 && index < settings.points().size() && settings.points().get(index).step() != null; }
    /** The node's position; null for a recording step. */
    private Vec3 nodePosition(int index) {
        int points = settings.points().size();
        return index < points ? settings.points().get(index).position() : settings.guides().get(index - points);
    }
    private Leg hop(int wait) {
        int target = path.peekFirst();
        return target < settings.points().size() ? point(target, wait) : new Leg(nodePosition(target), nodePosition(target), target, wait);
    }
    private Leg choose(Vec3 position, int wait) {
        path.clear();
        int points = settings.points().size(), excluded = node >= 0 && node < points ? node : -1;
        if (step(node)) node = -1;
        int destination = points == 1 ? 0 : weighted(excluded);
        if (destination == excluded) return Leg.pause(retryDelay());
        List<Integer> found = step(destination) ? List.of(destination) : route(position, destination);
        if (found.isEmpty()) return Leg.pause(retryDelay());
        path.addAll(found);
        return hop(wait);
    }
    /** A point by weight, never the excluded one. With every weight 1 this draws exactly as a uniform choice did. */
    private int weighted(int excluded) {
        List<WaypointSettings.Waypoint> points = settings.points();
        int total = 0;
        for (int i = 0; i < points.size(); i++) if (i != excluded) total += points.get(i).weight();
        int pick = random.nextInt(total);
        for (int i = 0; i < points.size(); i++) if (i != excluded && (pick -= points.get(i).weight()) < 0) return i;
        throw new IllegalStateException("Point weights are positive");
    }
    private boolean linked(Vec3 from, Vec3 to) { return from.distance(to) <= settings.linkDistance(); }
    private int[][] links() {
        if (links != null) return links;
        int count = settings.points().size() + settings.guides().size();
        Vec3[] nodes = new Vec3[count];
        for (int i = 0; i < count; i++) nodes[i] = nodePosition(i);
        List<List<Integer>> adjacent = new ArrayList<>(count);
        for (int i = 0; i < count; i++) adjacent.add(new ArrayList<>());
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++)
            if (nodes[i] != null && nodes[j] != null && linked(nodes[i], nodes[j])) { adjacent.get(i).add(j); adjacent.get(j).add(i); }
        int[][] built = new int[count][];
        for (int i = 0; i < count; i++) built[i] = adjacent.get(i).stream().mapToInt(Integer::intValue).toArray();
        links = built;
        return built;
    }
    /**
     * The hops after the start, ending at the destination. A* over links between guides and points with euclidean costs;
     * from a free position the first hop is a guide whenever the route has guides. When links do not reach the
     * destination, the link distance is only a preference: the route follows links to the reached node closest to the
     * destination and hops from there. Every runtime walks a long hop as a long leg, so a destination with no guide near
     * it is still visited. The fallback reads each node once, so its cost stays linear in the route's size.
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
                if (at == null || !linked(position, at)) continue;
                double start = position.distance(at);
                if (start < cost[i]) { cost[i] = start; open.add(new double[]{start + at.distance(goal), i}); }
            }
        }
        while (!open.isEmpty()) {
            int at = (int) open.poll()[1];
            if (closed[at]) continue;
            closed[at] = true;
            if (at == destination) return hops(at, previous);
            Vec3 from = nodePosition(at);
            for (int neighbour : graph[at]) relax(at, neighbour, from.distance(nodePosition(neighbour)), cost, previous, closed, open, goal);
        }
        // Links do not reach it: the search above closed every node they reach, so hop from the closest of those.
        int closest = node, guidesFrom = settings.guides().isEmpty() ? 0 : points;
        double nearest = node >= 0 ? nodePosition(node).distance(goal) : Double.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            if (!closed[i]) continue;
            double distance = nodePosition(i).distance(goal);
            if (distance < nearest) { nearest = distance; closest = i; }
        }
        if (closest == node && node < 0) {
            // A free position linked to nothing: first the guide (or point) nearest to it, then on to the destination.
            for (int i = guidesFrom; i < count; i++) {
                if (step(i)) continue;
                double distance = position.distance(nodePosition(i));
                if (closest < 0 || distance < nearest) { nearest = distance; closest = i; }
            }
            return closest == destination ? List.of(destination) : List.of(closest, destination);
        }
        List<Integer> hops = new ArrayList<>(closest == node ? List.of() : hops(closest, previous));
        hops.add(destination);
        return List.copyOf(hops);
    }
    private List<Integer> hops(int end, int[] previous) {
        ArrayDeque<Integer> hops = new ArrayDeque<>();
        for (int cursor = end; cursor != -1 && cursor != node; cursor = previous[cursor]) hops.addFirst(cursor);
        return List.copyOf(hops);
    }
    private void relax(int at, int neighbour, double length, double[] cost, int[] previous, boolean[] closed, PriorityQueue<double[]> open, Vec3 goal) {
        if (closed[neighbour]) return;
        double through = cost[at] + length;
        if (through < cost[neighbour]) {
            cost[neighbour] = through; previous[neighbour] = at;
            open.add(new double[]{through + nodePosition(neighbour).distance(goal), neighbour});
        }
    }

    // ---- Wander ----

    private Leg wander(int wait) {
        List<WaypointSettings.Waypoint> centres = settings.points();
        wanderCentre = centres.isEmpty() ? 0 : random.nextInt(centres.size());
        Vec3 centre = centres.isEmpty() ? home : centres.get(wanderCentre).position();
        WaypointSettings.Wander area = settings.wander();
        int x = (int) Math.floor(centre.x()) + random.nextInt(-area.radius(), area.radius() + 1);
        int z = (int) Math.floor(centre.z()) + random.nextInt(-area.radius(), area.radius() + 1);
        int y = (int) Math.floor(centre.y()) + random.nextInt(-area.height(), area.height() + 1);
        Vec3 goal = new Vec3(x + .5, y, z + .5);
        return new Leg(goal, goal, wanderCentre, wait);
    }
}
