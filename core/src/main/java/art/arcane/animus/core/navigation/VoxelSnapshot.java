package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.*;

/** Immutable finite capture for asynchronous navigation. UNKNOWN never means air and never triggers chunk loading. */
public final class VoxelSnapshot {
    /** OBSTACLE blocks clearance but is not verified full-footprint support (plants, partial/unknown shapes). */
    public enum Cell { AIR, SOLID, WATER, LAVA, UNAVAILABLE, OBSTACLE }
    public static final int MAX_CELLS = 16 * 1024 * 1024;
    private static final Cell[] CELL_TYPES = Cell.values();
    private final String world;
    private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    private final byte[] cells;
    private final Set<ChunkPosition> availableChunks;
    private final int minChunkX, minChunkZ, chunkWidth;
    private final BitSet availableMask;

    private VoxelSnapshot(Builder builder) {
        world = builder.world; minX = builder.minX; minY = builder.minY; minZ = builder.minZ;
        sizeX = builder.sizeX; sizeY = builder.sizeY; sizeZ = builder.sizeZ;
        cells = builder.cells.clone(); availableChunks = Set.copyOf(builder.availableChunks);
        minChunkX = Math.floorDiv(minX, 16); minChunkZ = Math.floorDiv(minZ, 16);
        chunkWidth = Math.floorDiv(minX + sizeX - 1, 16) - minChunkX + 1;
        availableMask = new BitSet();
        for (ChunkPosition chunk : availableChunks) availableMask.set((chunk.z() - minChunkZ) * chunkWidth + chunk.x() - minChunkX);
    }
    public String world() { return world; }
    public int cellCount() { return cells.length; }
    public Set<ChunkPosition> availableChunks() { return availableChunks; }
    public boolean contains(int x, int y, int z) {
        return x >= minX && y >= minY && z >= minZ && (long) x < (long) minX + sizeX
                && (long) y < (long) minY + sizeY && (long) z < (long) minZ + sizeZ;
    }
    public Cell cell(BlockPos position) { return cell(position.x(), position.y(), position.z()); }
    public Cell cell(int x, int y, int z) {
        if (!contains(x, y, z) || !availableMask.get((Math.floorDiv(z, 16) - minChunkZ) * chunkWidth + Math.floorDiv(x, 16) - minChunkX))
            return Cell.UNAVAILABLE;
        return CELL_TYPES[cells[((y - minY) * sizeZ + z - minZ) * sizeX + x - minX]];
    }

    /** Mutable capture builder. The platform fills this on its owning scheduler, then publishes build(). */
    public static final class Builder {
        private final String world;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        private final byte[] cells;
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
            cells = new byte[sizeX * sizeY * sizeZ];
            Arrays.fill(cells, (byte) Cell.UNAVAILABLE.ordinal());
        }
        public Builder availableChunk(int x, int z) {
            if (x < Math.floorDiv(minX, 16) || x > Math.floorDiv(minX + sizeX - 1, 16)
                    || z < Math.floorDiv(minZ, 16) || z > Math.floorDiv(minZ + sizeZ - 1, 16))
                throw new IllegalArgumentException("Chunk is outside snapshot bounds");
            availableChunks.add(new ChunkPosition(world, x, z)); return this;
        }
        /** Explicit synthetic/default fill; real captures should set every sampled cell instead. */
        public Builder fill(Cell cell) {
            Objects.requireNonNull(cell, "cell");
            Arrays.fill(cells, (byte) cell.ordinal()); return this;
        }
        public Builder set(int x, int y, int z, Cell cell) {
            Objects.requireNonNull(cell, "cell");
            if (cell == Cell.UNAVAILABLE) throw new IllegalArgumentException("Availability is captured per chunk, not as a voxel");
            if (x < minX || y < minY || z < minZ || (long) x >= (long) minX + sizeX
                    || (long) y >= (long) minY + sizeY || (long) z >= (long) minZ + sizeZ)
                throw new IllegalArgumentException("Cell is outside snapshot bounds");
            cells[((y - minY) * sizeZ + z - minZ) * sizeX + x - minX] = (byte) cell.ordinal(); return this;
        }
        public VoxelSnapshot build() { return new VoxelSnapshot(this); }
    }
}
