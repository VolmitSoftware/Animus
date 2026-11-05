package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Finds where a body comes to rest over one horizontal position: on the highest surface within its climb that it fits
 * on, otherwise on the first surface below its feet, or at the surface of water too deep to wade when it swims. Layers
 * are read from the top down and reading stops once the answer is settled, so terrain below a floor is never needed.
 * Every read is charged to an explicit cell budget.
 */
public final class BodyTerrain {
    public enum Kind { GROUND, SWIM, NONE, BLOCKED, HAZARD, UNAVAILABLE, BUDGET }
    public record Rest(Kind kind, double feet, int sampled, String detail) {
        public boolean usable() { return kind == Kind.GROUND || kind == Kind.SWIM; }
    }
    private static final double EPSILON = MotionValidator.EPSILON, TOUCH = 0.01;
    private final double half, height;
    private final boolean swims;
    private final int maximumCells;
    private final MotionValidator.Sampler terrain;

    public BodyTerrain(double width, double height, boolean swims, int maximumCells, MotionValidator.Sampler terrain) {
        if (!(width > 0 && width <= MovementProfile.MAX_WIDTH) || !(height > 0 && height <= MovementProfile.MAX_HEIGHT))
            throw new IllegalArgumentException("Body hull exceeds width 4 or height 8");
        if (maximumCells < 1 || maximumCells > 4096) throw new IllegalArgumentException("Cell budget must be 1..4096");
        this.half = width / 2; this.height = height; this.swims = swims; this.maximumCells = maximumCells;
        this.terrain = Objects.requireNonNull(terrain, "terrain");
    }

    /**
     * @param feet  current feet height
     * @param climb how far above {@code feet} a surface may be
     * @param drop  how far below {@code feet} a surface may be
     * @param allowHazard true only to place a body that already stands where it is
     */
    public Rest rest(double x, double z, double feet, double climb, double drop, boolean allowHazard) {
        int minX = MotionValidator.floor(x - half + EPSILON), maxX = MotionValidator.floor(x + half - EPSILON);
        int minZ = MotionValidator.floor(z - half + EPSILON), maxZ = MotionValidator.floor(z + half - EPSILON);
        // Surfaces down to feet - drop come from this layer up. The layer below only matters for a box taller than a block,
        // so an unavailable cell there, such as one below the world, is read as empty rather than suspending movement.
        int lowest = (int) Math.ceil(feet - drop - 1 - EPSILON), minY = lowest - 1;
        int maxY = Math.max(MotionValidator.floor(feet + climb + height - EPSILON), MotionValidator.floor(feet + climb + EPSILON) + 1);
        int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > maximumCells) return new Rest(Kind.BUDGET, feet, 0, "Resting volume exceeds cell budget");
        Area area = new Area(x, z, minX, minY, minZ, maxX, maxY, maxZ, sizeX, sizeZ, new Cell[(int) volume]);
        int count = 0;
        for (int cy = maxY; cy >= minY; cy--) {
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
                Cell cell = terrain.sample(cx, cy, cz); count++;
                if ((cell == null || cell.unavailable()) && cy == minY) cell = Cell.AIR;
                if (cell == null || cell.unavailable()) return new Rest(Kind.UNAVAILABLE, feet, count, "Terrain unavailable");
                area.cells[((cy - minY) * sizeZ + cz - minZ) * sizeX + cx - minX] = cell;
            }
            area.low = cy;
            Rest settled = decide(area, feet, climb, drop, allowHazard, count, cy == minY);
            if (settled != null) return settled;
        }
        throw new IllegalStateException("A complete read always decides");
    }

    /** @return null while a lower, unread layer could still change the answer */
    private Rest decide(Area area, double feet, double climb, double drop, boolean allowHazard, int count, boolean complete) {
        // Boxes in unread layers top out at least this low, so a surface above it is final.
        double settledAbove = complete ? Double.NEGATIVE_INFINITY : area.low + Cell.MAX_HEIGHT - 1 + EPSILON;
        List<double[]> boxes = area.boxes();
        double ceiling = feet + climb + EPSILON, floor = feet - drop - EPSILON;
        // A box filling the body's space at both ends of the reach fills it at every height between, so nothing below matters.
        double lowestFeet = swims ? Math.min(feet - drop, Math.floor(feet - drop - EPSILON)) : feet - drop;
        for (double[] box : boxes)
            if (MotionValidator.overlaps(box[0], box[1], lowestFeet, lowestFeet + height)
                    && MotionValidator.overlaps(box[0], box[1], feet + climb, feet + climb + height))
                return new Rest(Kind.BLOCKED, feet, count, "No room for the body");
        List<Double> tops = new ArrayList<>();
        for (double[] box : boxes) if (box[1] <= ceiling && box[1] >= floor && !tops.contains(box[1])) tops.add(box[1]);
        tops.sort((a, b) -> Double.compare(b, a));
        for (double top : tops) {
            if (top <= settledAbove) return null;
            boolean landing = top <= feet + EPSILON;
            if (!area.clear(boxes, top, height)) {
                // Anything lower than the first surface under the feet lies beyond it, out of reach.
                if (landing) return new Rest(Kind.BLOCKED, top, count, "No room for the body");
                continue;
            }
            if (!allowHazard && area.hazard(top, height)) return new Rest(Kind.HAZARD, top, count, "Lava, fire or another hazard");
            if (!area.water(MotionValidator.floor(top + height - EPSILON))) return new Rest(Kind.GROUND, top, count, "ground");
            if (!swims) return new Rest(Kind.BLOCKED, top, count, "Water too deep to wade");
            return surface(area, boxes, feet, climb, drop, allowHazard, count);
        }
        if (!complete) {
            // Deep water: any unread ground lies so far down that the body's head would be under the open surface,
            // so the surface is final and the bottom, which may be outside the capture, is never needed.
            if (!swims) return null;
            Rest swim = surface(area, boxes, feet, climb, drop, allowHazard, count);
            return swim.kind() == Kind.SWIM && submerged(area, MotionValidator.floor(feet - drop + height - EPSILON), (int) swim.feet()) ? swim : null;
        }
        if (swims) {
            Rest swim = surface(area, boxes, feet, climb, drop, allowHazard, count);
            if (swim.kind() != Kind.NONE) return swim;
        }
        return tops.isEmpty() ? new Rest(Kind.NONE, feet, count, "No ground within reach")
                : new Rest(Kind.BLOCKED, feet, count, "No room for the body");
    }

    /**
     * True when no unread landing could keep the head above the surface: the highest unread box top leaves the head at or
     * below the surface layer, and every layer such a head could occupy, from {@code from} up, is read and holds water.
     */
    private boolean submerged(Area area, int from, int surface) {
        if (MotionValidator.floor(area.low - 1 + Cell.MAX_HEIGHT + height - EPSILON) > surface) return false;
        for (int y = from; y <= surface; y++) if (y < area.low || !area.water(y)) return false;
        return true;
    }

    /** The bottom of the highest water block whose top is open, so a swimming body keeps its head out. */
    private Rest surface(Area area, List<double[]> boxes, double feet, double climb, double drop, boolean allowHazard, int count) {
        for (int y = MotionValidator.floor(feet + climb + EPSILON); y >= Math.max(area.low, MotionValidator.floor(feet - drop - EPSILON)); y--) {
            if (!area.water(y) || area.water(y + 1)) continue;
            if (!area.clear(boxes, y, height)) return new Rest(Kind.BLOCKED, y, count, "No room at the water surface");
            if (!allowHazard && area.hazard(y, height)) return new Rest(Kind.HAZARD, y, count, "Lava, fire or another hazard");
            return new Rest(Kind.SWIM, y, count, "swimming");
        }
        return new Rest(Kind.NONE, feet, count, "No ground or water within reach");
    }

    private final class Area {
        final double x, z;
        final int minX, minY, minZ, maxX, maxY, maxZ, sizeX, sizeZ;
        final Cell[] cells;
        int low;
        Area(double x, double z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int sizeX, int sizeZ, Cell[] cells) {
            this.x = x; this.z = z; this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.sizeX = sizeX; this.sizeZ = sizeZ; this.cells = cells; low = maxY + 1;
        }
        Cell at(int cx, int cy, int cz) { return cells[((cy - minY) * sizeZ + cz - minZ) * sizeX + cx - minX]; }
        /** Absolute {bottom, top} of every read box under the footprint. */
        List<double[]> boxes() {
            List<double[]> found = new ArrayList<>();
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) for (int cy = low; cy <= maxY; cy++)
                for (Cell.Box box : at(cx, cy, cz).boxes())
                    if (MotionValidator.overlaps(cx + box.minX(), cx + box.maxX(), x - half, x + half)
                            && MotionValidator.overlaps(cz + box.minZ(), cz + box.maxZ(), z - half, z + half))
                        found.add(new double[]{cy + box.minY(), cy + box.maxY()});
            return found;
        }
        boolean clear(List<double[]> boxes, double feet, double bodyHeight) {
            if (MotionValidator.floor(feet + bodyHeight - EPSILON) > maxY) return false;
            for (double[] box : boxes) if (MotionValidator.overlaps(box[0], box[1], feet, feet + bodyHeight)) return false;
            return true;
        }
        boolean hazard(double feet, double bodyHeight) {
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++)
                for (int cy = Math.max(low, MotionValidator.floor(feet - TOUCH)); cy <= Math.min(maxY, MotionValidator.floor(feet + bodyHeight - EPSILON)); cy++)
                    if (at(cx, cy, cz).hazard()) return true;
            return false;
        }
        boolean water(int cy) {
            if (cy < low || cy > maxY) return false;
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) if (at(cx, cy, cz).water()) return true;
            return false;
        }
    }
}
