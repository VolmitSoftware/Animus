package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.BitSet;
import java.util.Objects;

/**
 * Bounded swept clearance against real collision boxes. The swept volume is the box spanning the body at both ends of
 * one segment of at most two blocks. Support means a collision surface exactly at the feet under any part of the body,
 * as with a vanilla entity. A moving body also counts a surface within {@link #BRIDGE} of its sides, so it walks across a
 * strip without boxes narrower than the planner's quarter-block samples, such as the open front of a shelf beside a slab,
 * as a vanilla entity does with a dip too small to see. A body may not move into a hazard it was not already touching.
 * Climbable cells hold a body up but never obstruct it.
 */
public final class MotionValidator {
    /**
     * GROUND allows wading while the top of the body stays out of water; AIR allows no fluid; WATER needs fluid at the
     * feet; CLIMB is GROUND that ends either supported or holding a climbable cell at the feet.
     */
    public enum Medium { GROUND, AIR, WATER, AMPHIBIOUS_AIR, CLIMB }
    public enum Status { CLEAR, BLOCKED, HAZARD, UNAVAILABLE_TERRAIN, UNSUPPORTED_GEOMETRY, BUDGET_EXHAUSTED }
    @FunctionalInterface public interface Sampler { Cell sample(int x, int y, int z); }
    public record Result(Status status, int sampledCells, String detail) {
        public boolean clear() { return status == Status.CLEAR; }
    }
    public static final double MAX_SEGMENT = 2;
    /** How far beside a moving body a surface still holds it up: half the planner's quarter-block sample spacing. */
    public static final double BRIDGE = 0.125;
    static final double EPSILON = 0.000001;
    private static final double TOUCH = 0.01;
    private MotionValidator() {}

    /**
     * The most cells {@link #check} can read for this body moving at most {@code distance}, so a caller can size a budget
     * for a large body instead of failing it. It is at most 768 for the largest hull and longest segment.
     */
    public static int cellBudget(double width, double height, double distance) {
        if (!(width > 0 && width <= MovementProfile.MAX_WIDTH) || !(height > 0 && height <= MovementProfile.MAX_HEIGHT)
                || !(distance >= 0 && distance <= MAX_SEGMENT + EPSILON))
            throw new IllegalArgumentException("Requires width<=4, height<=8 and a segment of at most 2 blocks");
        int across = (int) Math.ceil(width + distance + (distance > 0 ? 2 * BRIDGE : 0)) + 1, up = (int) Math.ceil(height + distance) + 2;
        return across * across * up;
    }

    public static Result check(Vec3 from, Vec3 to, double width, double height, Medium medium,
                               boolean requireSupport, int maximumCells, Sampler terrain) {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(medium); Objects.requireNonNull(terrain);
        if (maximumCells < 1 || maximumCells > 4096) throw new IllegalArgumentException("Cell budget must be 1..4096");
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || width > MovementProfile.MAX_WIDTH
                || height <= 0 || height > MovementProfile.MAX_HEIGHT || from.distance(to) > MAX_SEGMENT + EPSILON)
            return new Result(Status.UNSUPPORTED_GEOMETRY, 0, "Requires width<=4, height<=8 and a segment of at most 2 blocks");
        double half = width / 2;
        double lowX = Math.min(from.x(), to.x()) - half, highX = Math.max(from.x(), to.x()) + half;
        double lowZ = Math.min(from.z(), to.z()) - half, highZ = Math.max(from.z(), to.z()) + half;
        double lowY = Math.min(from.y(), to.y()), highY = Math.max(from.y(), to.y()) + height;
        double reach = from.distance(to) > EPSILON ? BRIDGE : 0;
        int minX = floor(lowX - reach + EPSILON), maxX = floor(highX + reach - EPSILON), minZ = floor(lowZ - reach + EPSILON), maxZ = floor(highZ + reach - EPSILON);
        // The layer under the feet holds support, and its boxes may overhang upwards by half a block.
        int minY = floor(lowY + EPSILON) - 1, maxY = floor(highY - EPSILON);
        int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > maximumCells) return new Result(Status.BUDGET_EXHAUSTED, 0, "Swept volume exceeds cell budget");
        Cell[] cells = new Cell[(int) volume];
        int count = 0;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = maxY; y >= minY; y--) {
            Cell cell = terrain.sample(x, y, z); count++;
            // Only the layer under the feet can lie below the world while the body is in it; there is nothing there.
            if ((cell == null || cell.unavailable()) && y == minY) cell = Cell.AIR;
            if (cell == null || cell.unavailable()) return new Result(Status.UNAVAILABLE_TERRAIN, count, "Swept volume reaches unavailable terrain");
            cells[((y - minY) * sizeZ + z - minZ) * sizeX + x - minX] = cell;
        }
        Region region = new Region(minX, minY, minZ, sizeX, sizeZ, cells);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) {
            Cell cell = region.at(x, y, z);
            if (!cell.climbable()) for (Cell.Box box : cell.boxes())
                if (overlaps(x + box.minX(), x + box.maxX(), lowX, highX) && overlaps(z + box.minZ(), z + box.maxZ(), lowZ, highZ)
                        && overlaps(y + box.minY(), y + box.maxY(), lowY, highY)
                        && !(depth(x, y, z, box, to, half, height) < depth(x, y, z, box, from, half, height) - EPSILON))
                    return new Result(Status.BLOCKED, count, "Swept body is obstructed");
            if (medium == Medium.AIR && cell.water() && overlaps(y, y + 1, lowY, highY))
                return new Result(Status.BLOCKED, count, "Water in the way of an airborne body");
        }
        BitSet before = region.hazards(from, half, height), after = region.hazards(to, half, height);
        if (from.distance(to) <= EPSILON ? !after.isEmpty() : !subset(after, before))
            return new Result(Status.HAZARD, count, "Lava, fire or another hazard in the way");
        if ((medium == Medium.GROUND || medium == Medium.CLIMB) && region.water(to, half, floor(to.y() + height - EPSILON)))
            return new Result(Status.BLOCKED, count, "Water too deep to wade");
        boolean supported = region.supported(to, half + reach);
        // A climbable cell at the feet holds a walking body too, so one hanging on a vine steps off it onto a ledge.
        if (requireSupport && !supported && !(medium == Medium.GROUND && region.holds(to, half)))
            return new Result(Status.BLOCKED, count, "No ground under the body");
        if (medium == Medium.CLIMB && !supported && !region.holds(to, half)) return new Result(Status.BLOCKED, count, "Nothing to climb");
        if (medium == Medium.WATER && !supported && !region.water(to, half, floor(to.y() + EPSILON)))
            return new Result(Status.BLOCKED, count, "No swimming fluid or support");
        return new Result(Status.CLEAR, count, "clear");
    }

    private record Region(int minX, int minY, int minZ, int sizeX, int sizeZ, Cell[] cells) {
        Cell at(int x, int y, int z) { return cells[((y - minY) * sizeZ + z - minZ) * sizeX + x - minX]; }
        int columns(double centre, double half, boolean high) { return floor(high ? centre + half - EPSILON : centre - half + EPSILON); }
        BitSet hazards(Vec3 feet, double half, double height) {
            BitSet touched = new BitSet();
            int y0 = floor(feet.y() - TOUCH), y1 = floor(feet.y() + height - EPSILON);
            for (int x = columns(feet.x(), half, false); x <= columns(feet.x(), half, true); x++)
                for (int z = columns(feet.z(), half, false); z <= columns(feet.z(), half, true); z++)
                    for (int y = Math.max(minY, y0); y <= y1; y++)
                        if (at(x, y, z).hazard()) touched.set(((y - minY) * sizeZ + z - minZ) * sizeX + x - minX);
            return touched;
        }
        boolean water(Vec3 feet, double half, int y) {
            for (int x = columns(feet.x(), half, false); x <= columns(feet.x(), half, true); x++)
                for (int z = columns(feet.z(), half, false); z <= columns(feet.z(), half, true); z++)
                    if (at(x, y, z).water()) return true;
            return false;
        }
        /** A climbable cell in the layer of the feet under any part of the body. */
        boolean holds(Vec3 feet, double half) {
            int y = floor(feet.y() + EPSILON);
            for (int x = columns(feet.x(), half, false); x <= columns(feet.x(), half, true); x++)
                for (int z = columns(feet.z(), half, false); z <= columns(feet.z(), half, true); z++)
                    if (at(x, y, z).climbable()) return true;
            return false;
        }
        boolean supported(Vec3 feet, double half) {
            double lowX = feet.x() - half, highX = feet.x() + half, lowZ = feet.z() - half, highZ = feet.z() + half;
            int y0 = Math.max(minY, floor(feet.y() - Cell.MAX_HEIGHT + EPSILON)), y1 = floor(feet.y() + EPSILON);
            for (int x = columns(feet.x(), half, false); x <= columns(feet.x(), half, true); x++)
                for (int z = columns(feet.z(), half, false); z <= columns(feet.z(), half, true); z++)
                    for (int y = y0; y <= y1; y++)
                        for (Cell.Box box : at(x, y, z).boxes())
                            if (Math.abs(y + box.maxY() - feet.y()) <= EPSILON
                                    && overlaps(x + box.minX(), x + box.maxX(), lowX, highX) && overlaps(z + box.minZ(), z + box.maxZ(), lowZ, highZ))
                                return true;
            return false;
        }
    }
    /**
     * How deep, up and down, the body at {@code feet} is inside this box; 0 when apart. A box the body already stands
     * inside, such as a closed trapdoor under an NPC placed at a whole height, does not stop a move that goes less deep.
     */
    private static double depth(int x, int y, int z, Cell.Box box, Vec3 feet, double half, double height) {
        if (!overlaps(x + box.minX(), x + box.maxX(), feet.x() - half, feet.x() + half) || !overlaps(z + box.minZ(), z + box.maxZ(), feet.z() - half, feet.z() + half))
            return 0;
        return Math.max(0, Math.min(y + box.maxY(), feet.y() + height) - Math.max(y + box.minY(), feet.y()));
    }
    private static boolean subset(BitSet inner, BitSet outer) {
        BitSet extra = (BitSet) inner.clone(); extra.andNot(outer); return extra.isEmpty();
    }
    static boolean overlaps(double min, double max, double low, double high) { return min < high - EPSILON && max > low + EPSILON; }
    static int floor(double value) {
        if (value < -30_000_000 || value > 30_000_000) throw new IllegalArgumentException("Motion outside world bounds");
        return (int) Math.floor(value);
    }
}
