package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded A* against one immutable capture. All search state is local, so calls may run concurrently.
 *
 * <p>Walking bodies move between column centres. Each move is followed across in quarter-block samples, so a body
 * climbs a stair half by half and stands at the real height of slabs, snow, paths and soul sand. Every accepted move is
 * then checked with the same legs and clearance test that execution uses. A body that climbs also moves one block
 * straight up or down while it holds a ladder, vine or scaffolding, and stands on the top of a scaffolding column.
 */
public final class PathPlanner {
    public enum Status { FOUND, UNREACHABLE, BUDGET_EXHAUSTED, CANCELLED, UNAVAILABLE_TERRAIN }
    public enum Motion { START, WALK, STEP, JUMP, FALL, SWIM, FLY, CLIMB }
    public record Limits(int maximumVisitedNodes, int maximumFrontier, int maximumPathLength) {
        public Limits {
            if (maximumVisitedNodes < 1 || maximumVisitedNodes > 100000 || maximumFrontier < 1 || maximumFrontier > 100000
                    || maximumPathLength < 1 || maximumPathLength > 100000)
                throw new IllegalArgumentException("Path budgets must be within 1..100000");
        }
    }
    /**
     * One resting place: its voxel, the exact feet height, the highest point of the move that reached it and whether the
     * body swims there.
     */
    public record Step(BlockPos feet, double y, double peak, Motion motion, boolean swimming) {
        public Step { Objects.requireNonNull(feet, "feet"); Objects.requireNonNull(motion, "motion"); }
        public Vec3 point() { return new Vec3(feet.x() + 0.5, y, feet.z() + 0.5); }
        public WorldPosition position(String world) { return new WorldPosition(world, point()); }
    }
    public record Result(Status status, List<Step> path, int visitedNodes, int peakFrontier, boolean encounteredUnavailableTerrain) {
        public Result { Objects.requireNonNull(status, "status"); path = List.copyOf(path); }
    }
    private static final int CELLS = 4096;
    private static final double EPSILON = MotionValidator.EPSILON, SAMPLE = 0.25;
    private enum Kind { OPEN, BLOCKED, UNKNOWN }
    private record Place(Kind kind, double y, boolean swimming) {}
    private record Edge(BlockPos target, double y, double peak, Motion motion, boolean swimming) {}
    private record Node(BlockPos feet, double y, double peak, boolean swimming, Node parent, Motion motion, double cost, double estimate, int length) {
        double score() { return cost + estimate; }
    }
    private static final Comparator<Node> ORDER = Comparator.comparingDouble(Node::score).thenComparingDouble(Node::estimate)
            .thenComparingInt(n -> n.feet().x()).thenComparingInt(n -> n.feet().y()).thenComparingInt(n -> n.feet().z());

    public Result plan(VoxelSnapshot snapshot, BlockPos start, BlockPos goal, MovementProfile profile, Limits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(start, "start"); Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(profile, "profile"); Objects.requireNonNull(limits, "limits"); Objects.requireNonNull(cancelled, "cancelled");
        Search search = new Search(snapshot, profile, cancelled);
        if (cancelled.getAsBoolean()) return result(Status.CANCELLED, 0, 0, false);
        Place from = search.resolve(start, true), to = search.resolve(goal, false);
        if (cancelled.getAsBoolean()) return result(Status.CANCELLED, 0, 0, search.unavailable);
        if (from.kind() == Kind.UNKNOWN || to.kind() == Kind.UNKNOWN) return result(Status.UNAVAILABLE_TERRAIN, 0, 0, true);
        if (from.kind() == Kind.BLOCKED || to.kind() == Kind.BLOCKED) return result(Status.UNREACHABLE, 0, 0, search.unavailable);
        BlockPos origin = new BlockPos(start.x(), floor(from.y()), start.z()), target = new BlockPos(goal.x(), floor(to.y()), goal.z());
        PriorityQueue<Node> frontier = new PriorityQueue<>(ORDER);
        Map<BlockPos, Node> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        Node initial = new Node(origin, from.y(), from.y(), from.swimming(), null, Motion.START, 0, origin.distance(target), 1);
        frontier.add(initial); best.put(origin, initial);
        int visited = 0, peak = 1;
        boolean pathLimited = false;
        while (!frontier.isEmpty()) {
            if (cancelled.getAsBoolean()) return result(Status.CANCELLED, visited, peak, search.unavailable);
            Node current = frontier.poll();
            if (best.get(current.feet()) != current || closed.contains(current.feet())) continue;
            if (visited == limits.maximumVisitedNodes()) return result(Status.BUDGET_EXHAUSTED, visited, peak, search.unavailable);
            visited++; closed.add(current.feet());
            if (current.feet().equals(target)) {
                List<Step> path = new ArrayList<>();
                for (Node node = current; node != null; node = node.parent())
                    path.add(new Step(node.feet(), node.y(), node.peak(), node.motion(), node.swimming()));
                Collections.reverse(path);
                return new Result(Status.FOUND, path, visited, peak, search.unavailable);
            }
            List<Edge> neighbors = search.neighbors(current);
            if (cancelled.getAsBoolean()) return result(Status.CANCELLED, visited, peak, search.unavailable);
            for (Edge edge : neighbors) {
                if (cancelled.getAsBoolean()) return result(Status.CANCELLED, visited, peak, search.unavailable);
                if (closed.contains(edge.target())) continue;
                int length = current.length() + 1;
                if (length > limits.maximumPathLength()) { pathLimited = true; continue; }
                double cost = current.cost() + current.feet().distance(edge.target());
                Node prior = best.get(edge.target());
                if (prior != null && prior.cost() <= cost) continue;
                if (frontier.size() == limits.maximumFrontier()) return result(Status.BUDGET_EXHAUSTED, visited, peak, search.unavailable);
                Node next = new Node(edge.target(), edge.y(), edge.peak(), edge.swimming(), current, edge.motion(), cost, edge.target().distance(target), length);
                best.put(edge.target(), next); frontier.add(next); peak = Math.max(peak, frontier.size());
            }
        }
        if (cancelled.getAsBoolean()) return result(Status.CANCELLED, visited, peak, search.unavailable);
        return result(pathLimited ? Status.BUDGET_EXHAUSTED : search.unavailable ? Status.UNAVAILABLE_TERRAIN : Status.UNREACHABLE,
                visited, peak, search.unavailable);
    }

    private static Result result(Status status, int visited, int peak, boolean unavailable) { return new Result(status, List.of(), visited, peak, unavailable); }
    private static int floor(double value) { return (int) Math.floor(value + EPSILON); }

    private static final class Search {
        private final VoxelSnapshot snapshot;
        private final MovementProfile profile;
        private final BooleanSupplier cancelled;
        private final BodyTerrain terrain;
        private final boolean flies, walks, climbs;
        private boolean unavailable;
        private Search(VoxelSnapshot snapshot, MovementProfile profile, BooleanSupplier cancelled) {
            this.snapshot = snapshot; this.profile = profile; this.cancelled = cancelled;
            flies = profile.supports(MovementProfile.Mode.FLY); walks = profile.supports(MovementProfile.Mode.WALK);
            climbs = walks && !flies && profile.climbs();
            terrain = new BodyTerrain(profile.width(), profile.height(), profile.supports(MovementProfile.Mode.SWIM), CELLS, snapshot::cell);
        }
        /** Unavailable terrain is noted only when it decided an answer, so a search that never needed it can be unreachable. */
        private MotionValidator.Result check(Vec3 from, Vec3 to, MotionValidator.Medium medium, boolean support) {
            MotionValidator.Result result = MotionValidator.check(from, to, profile.width(), profile.height(), medium, support, CELLS, snapshot::cell);
            if (result.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) unavailable = true;
            return result;
        }
        private BodyTerrain.Rest rest(double x, double z, double feet, double climb, double drop, boolean allowHazard) {
            BodyTerrain.Rest rest = terrain.rest(x, z, feet, climb, drop, allowHazard);
            if (rest.kind() == BodyTerrain.Kind.UNAVAILABLE) unavailable = true;
            return rest;
        }
        private static Vec3 centre(BlockPos column, double y) { return new Vec3(column.x() + 0.5, y, column.z() + 0.5); }

        /** Where a body asked to be in this voxel actually rests; ground anywhere from one block below counts. */
        private Place resolve(BlockPos voxel, boolean start) {
            Vec3 point = centre(voxel, voxel.y());
            if (flies || !walks) {
                MotionValidator.Result here = check(point, point, flies ? MotionValidator.Medium.AIR : MotionValidator.Medium.WATER, false);
                if (here.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) return new Place(Kind.UNKNOWN, voxel.y(), false);
                if (here.clear() || (start && here.status() == MotionValidator.Status.HAZARD)) return new Place(Kind.OPEN, voxel.y(), !flies);
                if (!flies) return new Place(Kind.BLOCKED, voxel.y(), true);
            }
            BodyTerrain.Rest rest = rest(point.x(), point.z(), voxel.y(), 1 - 0.0001, 1, start);
            if (rest.kind() == BodyTerrain.Kind.UNAVAILABLE) return new Place(Kind.UNKNOWN, voxel.y(), false);
            // A climber may start or end holding a ladder or vine above the ground.
            if (climbs && (!rest.usable() || floor(rest.feet()) < voxel.y()) && climbable(voxel.x(), voxel.y(), voxel.z())
                    && check(point, point, MotionValidator.Medium.CLIMB, false).clear()) return new Place(Kind.OPEN, voxel.y(), false);
            if (!rest.usable() || floor(rest.feet()) < voxel.y() - 1) return new Place(Kind.BLOCKED, voxel.y(), false);
            return new Place(Kind.OPEN, rest.feet(), rest.kind() == BodyTerrain.Kind.SWIM);
        }

        private List<Edge> neighbors(Node from) {
            List<Edge> edges = new ArrayList<>(26);
            if (flies || !walks) {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes == 0 || ((!profile.diagonals() || !flies) && axes != 1) || cancelled.getAsBoolean()) continue;
                    BlockPos target = offset(from.feet(), dx, dy, dz);
                    if (target == null) continue;
                    Vec3 start = centre(from.feet(), from.y()), end = centre(target, target.y());
                    MotionValidator.Medium medium = flies ? MotionValidator.Medium.AIR : MotionValidator.Medium.WATER;
                    if (check(start, end, medium, false).clear()) {
                        edges.add(new Edge(target, target.y(), Math.max(from.y(), target.y()), flies ? Motion.FLY : Motion.SWIM, !flies));
                        continue;
                    }
                    if (!flies) continue;
                    // A flyer may also settle on a partial surface inside the voxel, such as a slab or a path.
                    BodyTerrain.Rest rest = rest(end.x(), end.z(), target.y(), 1 - 0.0001, 0, false);
                    if (rest.kind() != BodyTerrain.Kind.GROUND || floor(rest.feet()) != target.y() || rest.feet() - target.y() <= EPSILON) continue;
                    Vec3 landing = centre(target, rest.feet());
                    if (start.distance(landing) <= MotionValidator.MAX_SEGMENT && check(start, landing, medium, false).clear())
                        edges.add(new Edge(target, rest.feet(), Math.max(from.y(), rest.feet()), Motion.FLY, false));
                }
                return edges;
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || cancelled.getAsBoolean()) continue;
                if (dx != 0 && dz != 0 && !profile.diagonals()) continue;
                Edge edge = cross(from, dx, dz, false);
                if (edge == null && profile.jumpHeight() > profile.stepHeight()) edge = cross(from, dx, dz, true);
                if (edge != null) edges.add(edge);
            }
            if (climbs && !from.swimming()) {
                Edge up = climb(from, 1), down = climb(from, -1);
                if (up != null) edges.add(up);
                if (down != null) edges.add(down);
            }
            return edges;
        }

        /**
         * One block straight up while the body holds the climbable cell its feet are in, or one block down into a climbable
         * cell. The body ends standing on something or holding the cell at its feet.
         */
        private Edge climb(Node from, int direction) {
            int layer = floor(from.y());
            int target = direction > 0 ? layer + 1 : Math.abs(from.y() - layer) <= EPSILON ? layer - 1 : layer;
            if (!climbable(from.feet().x(), direction > 0 ? layer : target, from.feet().z())) return null;
            BlockPos column = offset(from.feet(), 0, target - from.feet().y(), 0);
            if (column == null) return null;
            Vec3 previous = centre(from.feet(), from.y()), end = centre(column, target);
            for (Maneuver.Leg leg : Maneuver.climb(previous, end)) {
                if (!check(previous, leg.target(), leg.medium(), leg.support()).clear()) return null;
                previous = leg.target();
            }
            return new Edge(column, target, Math.max(from.y(), target), Motion.CLIMB, false);
        }
        private boolean climbable(int x, int y, int z) {
            VoxelSnapshot.Cell cell = snapshot.cell(x, y, z);
            if (cell.unavailable()) { unavailable = true; return false; }
            return cell.climbable();
        }

        /** Follows the surface from one column centre to the next and checks the legs execution will use. */
        private Edge cross(Node from, int dx, int dz, boolean jumping) {
            BlockPos column = offset(from.feet(), dx, 0, dz);
            if (column == null) return null;
            Vec3 start = centre(from.feet(), from.y());
            int samples = (int) Math.ceil(Math.hypot(dx, dz) / SAMPLE - EPSILON);
            double feet = from.y(), peak = from.y(), drop = Math.max(profile.stepHeight(), profile.maximumFall());
            BodyTerrain.Rest rest = null;
            for (int i = 1; i <= samples; i++) {
                double climb = jumping ? Math.max(0, profile.jumpHeight() - (feet - from.y())) : profile.stepHeight();
                double x = start.x() + dx * (double) i / samples, z = start.z() + dz * (double) i / samples;
                rest = rest(x, z, feet, climb, drop, false);
                // A body already touching a hazard may pass through it on the way out; it may not end the move in one.
                if (rest.kind() == BodyTerrain.Kind.HAZARD && i < samples && scorched(from)) rest = rest(x, z, feet, climb, drop, true);
                if (!rest.usable()) return null;
                feet = rest.feet(); peak = Math.max(peak, feet);
            }
            if (rest == null || from.y() - feet > drop + EPSILON) return null;
            boolean swimming = rest.kind() == BodyTerrain.Kind.SWIM;
            Motion motion = jumping ? Motion.JUMP : peak - from.y() > EPSILON ? Motion.STEP
                    : from.y() - feet > profile.stepHeight() + EPSILON ? Motion.FALL : swimming ? Motion.SWIM : Motion.WALK;
            if (jumping && peak - from.y() <= profile.stepHeight() + EPSILON) return null;
            Vec3 end = centre(column, feet), previous = start;
            for (Maneuver.Leg leg : Maneuver.legs(start, end, peak, from.swimming(), swimming, profile)) {
                if (!check(previous, leg.target(), leg.medium(), leg.support()).clear()) return null;
                previous = leg.target();
            }
            BlockPos target = new BlockPos(column.x(), floor(feet), column.z());
            return new Edge(target, feet, peak, motion, swimming);
        }
        private boolean scorched(Node node) {
            Vec3 at = centre(node.feet(), node.y());
            return rest(at.x(), at.z(), node.y(), 0, 0, false).kind() == BodyTerrain.Kind.HAZARD;
        }
        private BlockPos offset(BlockPos from, int dx, int dy, int dz) {
            int x = from.x() + dx, y = from.y() + dy, z = from.z() + dz;
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) y) > 30_000_000 || Math.abs((long) z) > 30_000_000) { unavailable = true; return null; }
            return new BlockPos(x, y, z);
        }
    }
}
