package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.*;
import java.util.function.BooleanSupplier;
import static art.arcane.animus.core.navigation.MovementProfile.Mode.*;
import static art.arcane.animus.core.navigation.VoxelSnapshot.Cell.*;

/** Bounded A* against one immutable capture. All search state is local, so calls may run concurrently. */
public final class PathPlanner {
    public enum Status { FOUND, UNREACHABLE, BUDGET_EXHAUSTED, CANCELLED, UNAVAILABLE_TERRAIN }
    public enum Motion { START, WALK, STEP, JUMP, FALL, SWIM, FLY }
    public record Limits(int maximumVisitedNodes, int maximumFrontier, int maximumPathLength) {
        public Limits {
            if (maximumVisitedNodes < 1 || maximumVisitedNodes > 100000 || maximumFrontier < 1 || maximumFrontier > 100000
                    || maximumPathLength < 1 || maximumPathLength > 100000)
                throw new IllegalArgumentException("Path budgets must be within 1..100000");
        }
    }
    public record Step(BlockPos feet, Motion motion) {
        public Step { Objects.requireNonNull(feet, "feet"); Objects.requireNonNull(motion, "motion"); }
        public WorldPosition position(String world) { return feet.center(world); }
    }
    public record Result(Status status, List<Step> path, int visitedNodes, int peakFrontier, boolean encounteredUnavailableTerrain) {
        public Result { Objects.requireNonNull(status, "status"); path = List.copyOf(path); }
    }
    private enum Place { LAND, SWIMMING, FLYING, BLOCKED, UNKNOWN }
    private enum Clearance { CLEAR, BLOCKED, UNKNOWN }
    private record Edge(BlockPos target, Motion motion) {}
    private record Node(BlockPos feet, Node parent, Motion motion, double cost, double estimate, int length) {
        double score() { return cost + estimate; }
    }
    private static final Comparator<Node> ORDER = Comparator.comparingDouble(Node::score).thenComparingDouble(Node::estimate)
            .thenComparingInt(n -> n.feet().x()).thenComparingInt(n -> n.feet().y()).thenComparingInt(n -> n.feet().z());

    public Result plan(VoxelSnapshot snapshot, BlockPos start, BlockPos goal, MovementProfile profile, Limits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(start, "start"); Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(profile, "profile"); Objects.requireNonNull(limits, "limits"); Objects.requireNonNull(cancelled, "cancelled");
        Search search = new Search(snapshot, profile, cancelled);
        if (cancelled.getAsBoolean()) return result(Status.CANCELLED, 0, 0, false);
        Place from = search.place(start), to = search.place(goal);
        if (cancelled.getAsBoolean()) return result(Status.CANCELLED, 0, 0, search.unavailable);
        if (from == Place.UNKNOWN || to == Place.UNKNOWN) return result(Status.UNAVAILABLE_TERRAIN, 0, 0, true);
        if (from == Place.BLOCKED || to == Place.BLOCKED) return result(Status.UNREACHABLE, 0, 0, search.unavailable);
        PriorityQueue<Node> frontier = new PriorityQueue<>(ORDER);
        Map<BlockPos, Node> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        Node initial = new Node(start, null, Motion.START, 0, start.distance(goal), 1);
        frontier.add(initial); best.put(start, initial);
        int visited = 0, peak = 1;
        boolean pathLimited = false;
        while (!frontier.isEmpty()) {
            if (cancelled.getAsBoolean()) return result(Status.CANCELLED, visited, peak, search.unavailable);
            Node current = frontier.poll();
            if (best.get(current.feet()) != current || closed.contains(current.feet())) continue;
            if (visited == limits.maximumVisitedNodes()) return result(Status.BUDGET_EXHAUSTED, visited, peak, search.unavailable);
            visited++; closed.add(current.feet());
            if (current.feet().equals(goal)) {
                List<Step> path = new ArrayList<>();
                for (Node node = current; node != null; node = node.parent()) path.add(new Step(node.feet(), node.motion()));
                Collections.reverse(path);
                return new Result(Status.FOUND, path, visited, peak, search.unavailable);
            }
            List<Edge> neighbors = search.neighbors(current.feet());
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
                Node next = new Node(edge.target(), current, edge.motion(), cost, edge.target().distance(goal), length);
                best.put(edge.target(), next); frontier.add(next); peak = Math.max(peak, frontier.size());
            }
        }
        if (cancelled.getAsBoolean()) return result(Status.CANCELLED, visited, peak, search.unavailable);
        return result(pathLimited ? Status.BUDGET_EXHAUSTED : search.unavailable ? Status.UNAVAILABLE_TERRAIN : Status.UNREACHABLE,
                visited, peak, search.unavailable);
    }

    private static Result result(Status status, int visited, int peak, boolean unavailable) { return new Result(status, List.of(), visited, peak, unavailable); }

    private static final class Search {
        private final VoxelSnapshot snapshot;
        private final MovementProfile profile;
        private final BooleanSupplier cancelled;
        private boolean unavailable;
        private Search(VoxelSnapshot snapshot, MovementProfile profile, BooleanSupplier cancelled) {
            this.snapshot = snapshot; this.profile = profile; this.cancelled = cancelled;
        }
        private VoxelSnapshot.Cell sample(int x, int y, int z) {
            var cell = snapshot.cell(x, y, z);
            if (cell == UNAVAILABLE) unavailable = true;
            return cell;
        }
        private Clearance clearance(BlockPos position) {
            int radius = profile.bodyRadius();
            for (int x = position.x() - radius; x <= position.x() + radius; x++)
                for (int z = position.z() - radius; z <= position.z() + radius; z++)
                    for (int y = position.y(); y < position.y() + profile.bodyHeight(); y++) {
                        var cell = sample(x, y, z);
                        if (cell == UNAVAILABLE) return Clearance.UNKNOWN;
                        if (cell == SOLID || cell == OBSTACLE || cell == LAVA || (cell == WATER && !profile.supports(SWIM))) return Clearance.BLOCKED;
                    }
            return Clearance.CLEAR;
        }
        private Place place(BlockPos position) {
            Clearance free = clearance(position);
            if (free == Clearance.UNKNOWN) return Place.UNKNOWN;
            if (free == Clearance.BLOCKED) return Place.BLOCKED;
            if (sample(position.x(), position.y(), position.z()) == WATER) return profile.supports(SWIM) ? Place.SWIMMING : Place.BLOCKED;
            if (profile.supports(WALK)) {
                boolean supported = true, unknown = false;
                for (int x = position.x() - profile.bodyRadius(); x <= position.x() + profile.bodyRadius(); x++)
                    for (int z = position.z() - profile.bodyRadius(); z <= position.z() + profile.bodyRadius(); z++) {
                        var floor = sample(x, position.y() - 1, z);
                        supported &= floor == SOLID; unknown |= floor == UNAVAILABLE;
                    }
                if (supported) return Place.LAND;
                if (!profile.supports(FLY) && unknown) return Place.UNKNOWN;
            }
            return profile.supports(FLY) ? Place.FLYING : Place.BLOCKED;
        }
        private List<Edge> neighbors(BlockPos from) {
            List<Edge> edges = new ArrayList<>(32);
            Place origin = place(from);
            if (profile.supports(FLY)) {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes == 0 || (!profile.diagonals() && axes != 1) || cancelled.getAsBoolean()) continue;
                    BlockPos target = offset(from, dx, dy, dz);
                    if (target == null) continue;
                    Place kind = place(target);
                    if (traversable(kind) && diagonalClear(from, dx, dy, dz)) edges.add(new Edge(target, kind == Place.SWIMMING ? Motion.SWIM : Motion.FLY));
                }
                return edges;
            }
            if (origin == Place.SWIMMING) {
                int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
                for (int[] delta : directions) {
                    BlockPos target = offset(from, delta[0], delta[1], delta[2]);
                    if (target == null || cancelled.getAsBoolean()) continue;
                    Place kind = place(target);
                    if (kind == Place.SWIMMING || kind == Place.LAND) edges.add(new Edge(target, kind == Place.SWIMMING ? Motion.SWIM : Motion.WALK));
                }
                return edges;
            }
            if (origin != Place.LAND || !profile.supports(WALK)) return edges;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || cancelled.getAsBoolean()) continue;
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal && !profile.diagonals()) continue;
                BlockPos adjacent = offset(from, dx, 0, dz);
                if (adjacent == null) continue;
                Place sameLevel = place(adjacent);
                if ((sameLevel == Place.LAND || sameLevel == Place.SWIMMING) && (!diagonal || walkingCornerClear(from, dx, dz)))
                    edges.add(new Edge(adjacent, sameLevel == Place.SWIMMING ? Motion.SWIM : Motion.WALK));
                if (diagonal) continue; // Vertical walking transitions use cardinal corridors only.
                int riseLimit = Math.max(profile.maximumStepUp(), profile.maximumJumpUp());
                for (int rise = 1; rise <= riseLimit; rise++) {
                    BlockPos above = offset(from, 0, rise, 0), target = offset(from, dx, rise, dz);
                    if (above == null || target == null || clearance(above) != Clearance.CLEAR) break;
                    if (place(target) == Place.LAND)
                        edges.add(new Edge(target, rise <= profile.maximumStepUp() ? Motion.STEP : Motion.JUMP));
                }
                if (sameLevel == Place.LAND || sameLevel == Place.SWIMMING || clearance(adjacent) != Clearance.CLEAR) continue;
                for (int drop = 1; drop <= profile.maximumFall(); drop++) {
                    BlockPos target = offset(from, dx, -drop, dz);
                    if (target == null || clearance(target) != Clearance.CLEAR) break;
                    Place landing = place(target);
                    if (landing == Place.LAND || landing == Place.SWIMMING) { edges.add(new Edge(target, Motion.FALL)); break; }
                    if (landing == Place.UNKNOWN) break;
                }
            }
            return edges;
        }
        private boolean walkingCornerClear(BlockPos from, int dx, int dz) {
            BlockPos first = offset(from, dx, 0, 0), second = offset(from, 0, 0, dz);
            if (first == null || second == null) return false;
            Place a = place(first), b = place(second);
            return (a == Place.LAND || a == Place.SWIMMING) && (b == Place.LAND || b == Place.SWIMMING);
        }
        private boolean diagonalClear(BlockPos from, int dx, int dy, int dz) {
            int[] values = {dx, dy, dz}; int mask = 0;
            for (int axis = 0; axis < 3; axis++) if (values[axis] != 0) mask |= 1 << axis;
            for (int subset = (mask - 1) & mask; subset > 0; subset = (subset - 1) & mask) {
                BlockPos corner = offset(from, (subset & 1) != 0 ? dx : 0, (subset & 2) != 0 ? dy : 0, (subset & 4) != 0 ? dz : 0);
                if (corner == null || clearance(corner) != Clearance.CLEAR) return false;
            }
            return true;
        }
        private BlockPos offset(BlockPos from, int dx, int dy, int dz) {
            int x = from.x() + dx, y = from.y() + dy, z = from.z() + dz;
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) y) > 30_000_000 || Math.abs((long) z) > 30_000_000) { unavailable = true; return null; }
            return new BlockPos(x, y, z);
        }
        private static boolean traversable(Place place) { return place == Place.LAND || place == Place.SWIMMING || place == Place.FLYING; }
    }
}
