package art.arcane.animus.core.virtual;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed points bucketed by world and chunk, each seen from up to its own reach. Finding the points that reach a
 * position visits the chunks within the largest reach instead of every point. Immutable once built; build a new grid
 * when the points change.
 *
 * @param <T> what each point carries
 */
public final class PointGrid<T> {
    public record Point<T>(String world, double x, double y, double z, double reach, T value) {
        public Point {
            Objects.requireNonNull(world, "world"); Objects.requireNonNull(value, "value");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(reach) || reach < 0 || reach > 4096)
                throw new IllegalArgumentException("A point needs finite coordinates and a reach within 0..4096");
        }
    }
    private final Map<String, Map<Long, List<Point<T>>>> cells = new HashMap<>();
    private final double maximumReach;

    public PointGrid(Collection<Point<T>> points) {
        double reach = 0;
        for (Point<T> point : points) {
            cells.computeIfAbsent(point.world(), (String world) -> new HashMap<>())
                    .computeIfAbsent(ChunkPosition.key(chunk(point.x()), chunk(point.z())), (Long key) -> new ArrayList<>()).add(point);
            reach = Math.max(reach, point.reach());
        }
        maximumReach = reach;
    }

    /** Points in this world whose reach covers the position, nearest first, at most {@code limit}. */
    public List<T> reaching(String world, double x, double y, double z, int limit) {
        Map<Long, List<Point<T>>> inWorld = cells.get(world);
        if (inWorld == null || limit <= 0) return List.of();
        List<Point<T>> found = new ArrayList<>();
        for (int cx = chunk(x - maximumReach), maxX = chunk(x + maximumReach); cx <= maxX; cx++)
            for (int cz = chunk(z - maximumReach), maxZ = chunk(z + maximumReach); cz <= maxZ; cz++) {
                List<Point<T>> bucket = inWorld.get(ChunkPosition.key(cx, cz));
                if (bucket == null) continue;
                for (Point<T> point : bucket) if (distanceSquared(point, x, y, z) <= point.reach() * point.reach()) found.add(point);
            }
        found.sort(Comparator.comparingDouble((Point<T> point) -> distanceSquared(point, x, y, z)));
        List<T> values = new ArrayList<>(Math.min(limit, found.size()));
        for (int i = 0; i < found.size() && i < limit; i++) values.add(found.get(i).value());
        return values;
    }

    private static double distanceSquared(Point<?> point, double x, double y, double z) {
        double dx = point.x() - x, dy = point.y() - y, dz = point.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
    private static int chunk(double coordinate) { return (int) Math.floor(coordinate / 16); }
}
