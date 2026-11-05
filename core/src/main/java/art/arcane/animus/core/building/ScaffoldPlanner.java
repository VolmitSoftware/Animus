package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a builder puts up scaffolding to reach a block that no place on the ground reaches: a column of scaffolding from
 * the ground, and when no column fits right under the place to stand, a walkway of at most {@link #MAXIMUM_SPAN}
 * scaffolding blocks out from the top of a column, as the game lets scaffolding reach. The builder stands on the last
 * block. Among the places within reach of the block, the one that also reaches the most of the next blocks wins, then
 * the shortest scaffold. Pure: every world read goes through {@link Site} and counts against a read budget.
 */
public final class ScaffoldPlanner {
    public static final String BLOCK = "minecraft:scaffolding";
    public static final int MAXIMUM_HEIGHT = 48, MAXIMUM_SPAN = 6;
    private static final int RINGS = 3;
    private static final double MARGIN = .1;

    /** What the scaffold may use. */
    public interface Site {
        /** The cell is empty now, and the build will not fill it while scaffolding stands there. */
        boolean free(BlockPos at);
        /** A body can stand on the block in this cell. */
        boolean ground(BlockPos at);
    }

    /**
     * Scaffolding cells in the order they go up, column bottom first and then the walkway out from its top, and the feet
     * cell to work from, on top of the last one.
     */
    public record Layout(List<BlockPos> column, List<BlockPos> walkway, BlockPos stand) {
        public Layout {
            column = List.copyOf(column); walkway = List.copyOf(walkway); Objects.requireNonNull(stand);
            if (column.isEmpty()) throw new IllegalArgumentException("A scaffold needs a column");
        }
        public List<BlockPos> cells() {
            List<BlockPos> cells = new ArrayList<>(column);
            cells.addAll(walkway);
            return cells;
        }
        /** The feet cell at the foot of the column, where climbing starts. */
        public BlockPos base() { return column.getFirst(); }
        public int size() { return column.size() + walkway.size(); }
    }

    private ScaffoldPlanner() {}

    /** Whether a body standing with its feet at {@code feet} reaches the block, measured from the feet as builds do. */
    public static boolean reaches(BlockPos feet, BlockPos block, double reach) {
        return new Vec3(feet.x() + .5, feet.y(), feet.z() + .5).distance(StandPoints.centre(block)) <= reach - MARGIN;
    }

    /**
     * @param upcoming the next blocks of the build, to prefer a place that reaches many of them
     * @param maximumReads how many {@link Site} answers the search may ask for
     */
    public static Optional<Layout> find(BlockPos target, double reach, List<BlockPos> upcoming, Site site, int maximumReads) {
        Objects.requireNonNull(target); Objects.requireNonNull(upcoming); Objects.requireNonNull(site);
        Budgeted cells = new Budgeted(site, maximumReads);
        List<BlockPos> stands = new ArrayList<>();
        for (int dx = -RINGS; dx <= RINGS; dx++)
            for (int dz = -RINGS; dz <= RINGS; dz++)
                for (int dy = -1; dy <= 2; dy++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos stand = new BlockPos(target.x() + dx, target.y() + dy, target.z() + dz);
                    if (reaches(stand, target, reach)) stands.add(stand);
                }
        Map<BlockPos, Integer> coverage = new HashMap<>();
        for (BlockPos stand : stands) {
            int count = 0;
            for (BlockPos next : upcoming) if (reaches(stand, next, reach) && !overlaps(stand, next)) count++;
            coverage.put(stand, count);
        }
        stands.sort(Comparator.comparingInt((BlockPos stand) -> -coverage.get(stand)).thenComparingInt(BlockPos::y)
                .thenComparingInt((BlockPos stand) -> Math.abs(stand.x() - target.x()) + Math.abs(stand.z() - target.z()))
                .thenComparingInt(BlockPos::x).thenComparingInt(BlockPos::z));
        for (BlockPos stand : stands) {
            if (cells.spent()) return Optional.empty();
            if (!cells.free(stand) || !cells.free(up(stand, 1))) continue;
            BlockPos top = up(stand, -1);
            if (!cells.free(top)) continue;
            Optional<List<BlockPos>> column = column(top, cells);
            if (column.isPresent()) return Optional.of(new Layout(column.get(), List.of(), stand));
            Optional<Layout> walkway = walkway(top, stand, cells);
            if (walkway.isPresent()) return walkway;
        }
        return Optional.empty();
    }

    /** Free cells straight down from {@code top} to a cell with ground under it, bottom first. */
    private static Optional<List<BlockPos>> column(BlockPos top, Budgeted cells) {
        List<BlockPos> column = new ArrayList<>();
        BlockPos at = top;
        for (int depth = 0; depth < MAXIMUM_HEIGHT; depth++) {
            column.add(at);
            BlockPos below = up(at, -1);
            if (cells.ground(below)) return Optional.of(column.reversed());
            if (!cells.free(below)) return Optional.empty();
            at = below;
        }
        return Optional.empty();
    }

    /** A level walkway from the top of some column to {@code end}, each block with room to walk on it. */
    private static Optional<Layout> walkway(BlockPos end, BlockPos stand, Budgeted cells) {
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        open.add(end);
        depth.put(end, 0);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            BlockPos at = open.poll();
            int distance = depth.get(at);
            if (distance >= MAXIMUM_SPAN) continue;
            for (int[] side : sides) {
                BlockPos next = new BlockPos(at.x() + side[0], at.y(), at.z() + side[1]);
                if (depth.containsKey(next) || cells.spent()) continue;
                if (!cells.free(next) || !cells.free(up(next, 1)) || !cells.free(up(next, 2))) continue;
                depth.put(next, distance + 1);
                parent.put(next, at);
                Optional<List<BlockPos>> column = column(next, cells);
                if (column.isPresent()) {
                    List<BlockPos> walk = new ArrayList<>();
                    for (BlockPos step = at; step != null; step = parent.get(step)) walk.add(step);
                    return Optional.of(new Layout(column.get(), walk, stand));
                }
                open.add(next);
            }
        }
        return Optional.empty();
    }

    private static boolean overlaps(BlockPos feet, BlockPos block) {
        return feet.x() == block.x() && feet.z() == block.z() && (block.y() == feet.y() || block.y() == feet.y() + 1);
    }
    private static BlockPos up(BlockPos at, int blocks) { return new BlockPos(at.x(), at.y() + blocks, at.z()); }

    /** Counts answers and remembers them, so a cell asked about twice costs once. */
    private static final class Budgeted {
        private final Site site;
        private final Map<BlockPos, Boolean> free = new HashMap<>(), ground = new HashMap<>();
        private int left;
        Budgeted(Site site, int maximumReads) { this.site = site; left = maximumReads; }
        boolean spent() { return left <= 0; }
        boolean free(BlockPos at) {
            Boolean known = free.get(at);
            if (known != null) return known;
            if (left-- <= 0) return false;
            boolean answer = site.free(at);
            free.put(at, answer);
            return answer;
        }
        boolean ground(BlockPos at) {
            Boolean known = ground.get(at);
            if (known != null) return known;
            if (left-- <= 0) return false;
            boolean answer = site.ground(at);
            ground.put(at, answer);
            return answer;
        }
    }
}
