package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable finite capture for asynchronous navigation. UNAVAILABLE never means air and never triggers chunk loading.
 * Each voxel stores an index into a palette of distinct cells, so a capture costs two bytes per voxel however many
 * shapes it contains.
 */
public final class VoxelSnapshot {
    /**
     * One block state as movement sees it: collision boxes in block-local coordinates, the fluid it holds, whether a
     * body must keep out of it and whether a body can climb it. Boxes may rise to 1.5 so fences and walls overhang the
     * voxel above. The boxes of a climbable cell hold a body up but never stand in its way, as with ladders and
     * scaffolding in the game.
     */
    public static final class Cell {
        public enum Fluid { NONE, WATER, LAVA }
        public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            public Box {
                if (!(minX >= 0 && minZ >= 0 && minY >= 0 && maxX <= 1 && maxZ <= 1 && maxY <= MAX_HEIGHT
                        && minX < maxX && minY < maxY && minZ < maxZ))
                    throw new IllegalArgumentException("Collision box must lie in 0..1 horizontally and 0.." + MAX_HEIGHT + " vertically");
            }
            Box union(Box other) {
                return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                        Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
            }
        }
        public static final double MAX_HEIGHT = 1.5;
        public static final int MAX_BOXES = 16;
        public static final Cell AIR = new Cell(List.of(), Fluid.NONE, false, false, false);
        public static final Cell SOLID = new Cell(List.of(new Box(0, 0, 0, 1, 1, 1)), Fluid.NONE, false, false, false);
        public static final Cell WATER = new Cell(List.of(), Fluid.WATER, false, false, false);
        public static final Cell LAVA = new Cell(List.of(), Fluid.LAVA, true, false, false);
        public static final Cell UNAVAILABLE = new Cell(List.of(), Fluid.NONE, false, true, false);
        private static final List<Cell> CANONICAL = List.of(AIR, SOLID, WATER, LAVA);
        private final List<Box> boxes;
        private final Fluid fluid;
        private final boolean hazard, unavailable, climbable;
        private final double top;

        private Cell(List<Box> boxes, Fluid fluid, boolean hazard, boolean unavailable, boolean climbable) {
            this.boxes = boxes; this.fluid = fluid; this.hazard = hazard; this.unavailable = unavailable; this.climbable = climbable;
            double highest = 0;
            for (Box box : boxes) highest = Math.max(highest, box.maxY());
            top = highest;
        }
        /** Lava is always a hazard. More than {@link #MAX_BOXES} boxes collapse into their union, which only adds collision. */
        public static Cell of(List<Box> boxes, Fluid fluid, boolean hazard) { return of(boxes, fluid, hazard, false); }
        /** A cell a body can climb, such as a ladder, vine or scaffolding, when {@code climbable} is true. */
        public static Cell of(List<Box> boxes, Fluid fluid, boolean hazard, boolean climbable) {
            Objects.requireNonNull(boxes, "boxes"); Objects.requireNonNull(fluid, "fluid");
            List<Box> kept = List.copyOf(boxes);
            if (kept.size() > MAX_BOXES) {
                Box union = kept.getFirst();
                for (Box box : kept) union = union.union(box);
                kept = List.of(union);
            }
            Cell cell = new Cell(kept, fluid, hazard || fluid == Fluid.LAVA, false, climbable);
            for (Cell known : CANONICAL) if (known.equals(cell)) return known;
            return cell;
        }
        /** A full-footprint surface of the given height, such as a slab, snow layer, soul sand or wall. */
        public static Cell layer(double height) { return of(List.of(new Box(0, 0, 0, 1, height, 1)), Fluid.NONE, false); }
        public List<Box> boxes() { return boxes; }
        public Fluid fluid() { return fluid; }
        public boolean hazard() { return hazard; }
        public boolean unavailable() { return unavailable; }
        /** A body can hold onto this cell and move up and down in it; its boxes never obstruct a body. */
        public boolean climbable() { return climbable; }
        public boolean water() { return fluid == Fluid.WATER; }
        public boolean collides() { return !boxes.isEmpty(); }
        /** Highest collision surface relative to the voxel floor, or 0 without collision. */
        public double top() { return top; }
        @Override public boolean equals(Object other) {
            return other instanceof Cell cell && cell.fluid == fluid && cell.hazard == hazard && cell.unavailable == unavailable
                    && cell.climbable == climbable && cell.boxes.equals(boxes);
        }
        @Override public int hashCode() { return Objects.hash(boxes, fluid, hazard, unavailable, climbable); }
        @Override public String toString() {
            if (unavailable) return "UNAVAILABLE";
            return "Cell[boxes=" + boxes + ", fluid=" + fluid + (hazard ? ", hazard" : "") + (climbable ? ", climbable" : "") + "]";
        }
    }

    public static final int MAX_CELLS = 16 * 1024 * 1024;
    private final String world;
    private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    private final short[] cells;
    private final Cell[] palette;
    private final Set<ChunkPosition> availableChunks;
    private final int minChunkX, minChunkZ, chunkWidth;
    private final BitSet availableMask;

    private VoxelSnapshot(Builder builder) {
        world = builder.world; minX = builder.minX; minY = builder.minY; minZ = builder.minZ;
        sizeX = builder.sizeX; sizeY = builder.sizeY; sizeZ = builder.sizeZ;
        cells = builder.cells.clone(); palette = builder.palette.toArray(new Cell[0]);
        availableChunks = Set.copyOf(builder.availableChunks);
        minChunkX = Math.floorDiv(minX, 16); minChunkZ = Math.floorDiv(minZ, 16);
        chunkWidth = Math.floorDiv(minX + sizeX - 1, 16) - minChunkX + 1;
        availableMask = new BitSet();
        for (ChunkPosition chunk : availableChunks) availableMask.set((chunk.z() - minChunkZ) * chunkWidth + chunk.x() - minChunkX);
    }
    public String world() { return world; }
    public int cellCount() { return cells.length; }
    /** Distinct cells in this capture, including the unavailable marker. */
    public int paletteSize() { return palette.length; }
    public Set<ChunkPosition> availableChunks() { return availableChunks; }
    public boolean contains(int x, int y, int z) {
        return x >= minX && y >= minY && z >= minZ && (long) x < (long) minX + sizeX
                && (long) y < (long) minY + sizeY && (long) z < (long) minZ + sizeZ;
    }
    public Cell cell(BlockPos position) { return cell(position.x(), position.y(), position.z()); }
    public Cell cell(int x, int y, int z) {
        if (!contains(x, y, z) || !availableMask.get((Math.floorDiv(z, 16) - minChunkZ) * chunkWidth + Math.floorDiv(x, 16) - minChunkX))
            return Cell.UNAVAILABLE;
        return palette[cells[((y - minY) * sizeZ + z - minZ) * sizeX + x - minX]];
    }

    /** Mutable capture builder. The platform fills this on its owning scheduler, then publishes build(). */
    public static final class Builder {
        private static final int MAX_PALETTE = Short.MAX_VALUE;
        private final String world;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        private final short[] cells;
        private final List<Cell> palette = new ArrayList<>();
        private final Map<Cell, Short> indices = new HashMap<>();
        private final Set<ChunkPosition> availableChunks = new HashSet<>();
        public Builder(String world, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.world = Objects.requireNonNull(world, "world");
            if (world.isBlank()) throw new IllegalArgumentException("World must not be blank");
            if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || sizeX > 4096 || sizeY > 4096 || sizeZ > 4096
                    || (long) sizeX * sizeY * sizeZ > MAX_CELLS) throw new IllegalArgumentException("Snapshot exceeds dimension/cell bounds");
            if (minX < -30_000_000 || minY < -30_000_000 || minZ < -30_000_000
                    || (long) minX + sizeX > 30_000_000 || (long) minY + sizeY > 30_000_000 || (long) minZ + sizeZ > 30_000_000)
                throw new IllegalArgumentException("Snapshot lies outside world bounds");
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
            cells = new short[sizeX * sizeY * sizeZ];
            index(Cell.UNAVAILABLE);
        }
        public Builder availableChunk(int x, int z) {
            if (x < Math.floorDiv(minX, 16) || x > Math.floorDiv(minX + sizeX - 1, 16)
                    || z < Math.floorDiv(minZ, 16) || z > Math.floorDiv(minZ + sizeZ - 1, 16))
                throw new IllegalArgumentException("Chunk is outside snapshot bounds");
            availableChunks.add(new ChunkPosition(world, x, z)); return this;
        }
        /** Explicit synthetic/default fill; real captures should set every sampled cell instead. */
        public Builder fill(Cell cell) {
            Arrays.fill(cells, index(available(cell))); return this;
        }
        public Builder set(int x, int y, int z, Cell cell) {
            short value = index(available(cell));
            if (x < minX || y < minY || z < minZ || (long) x >= (long) minX + sizeX
                    || (long) y >= (long) minY + sizeY || (long) z >= (long) minZ + sizeZ)
                throw new IllegalArgumentException("Cell is outside snapshot bounds");
            cells[((y - minY) * sizeZ + z - minZ) * sizeX + x - minX] = value; return this;
        }
        public VoxelSnapshot build() { return new VoxelSnapshot(this); }
        private static Cell available(Cell cell) {
            Objects.requireNonNull(cell, "cell");
            if (cell.unavailable()) throw new IllegalArgumentException("Availability is captured per chunk, not as a voxel");
            return cell;
        }
        private short index(Cell cell) {
            Short known = indices.get(cell);
            if (known != null) return known;
            if (palette.size() >= MAX_PALETTE) throw new IllegalStateException("Snapshot holds too many distinct block shapes");
            short next = (short) palette.size();
            palette.add(cell); indices.put(cell, next);
            return next;
        }
    }
}
