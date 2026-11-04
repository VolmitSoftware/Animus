package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;

/** Voxel containing the NPC's feet. Paths use the cell center horizontally and its bottom vertically. */
public record BlockPos(int x, int y, int z) {
    public BlockPos {
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) y) > 30_000_000 || Math.abs((long) z) > 30_000_000)
            throw new IllegalArgumentException("Navigation coordinates must be within ±30000000");
    }
    public static BlockPos containing(Vec3 point) {
        return new BlockPos((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
    }
    public WorldPosition center(String world) { return new WorldPosition(world, new Vec3(x + 0.5, y, z + 0.5)); }
    public double distance(BlockPos other) { return Math.hypot(Math.hypot((double) x - other.x, (double) y - other.y), (double) z - other.z); }
}
