package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.*;
import art.arcane.animus.core.virtual.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.Objects;

/** Collision shapes of already-loaded voxels on the owning thread; no neighbor or chunk-loading APIs. */
public final class LiveMovementProbe {
    private LiveMovementProbe() {}
    public static MotionValidator.Result check(World world, Vec3 from, Vec3 to, double width, double height,
                                               MotionValidator.Medium medium, boolean requireSupport, int maximumCells) {
        return MotionValidator.check(from, to, width, height, medium, requireSupport, maximumCells, sampler(world));
    }
    /** Folia: every read first checks that the calling region owns the voxel's chunk. */
    public static MotionValidator.Result check(World world, ChunkOwnership owned, Vec3 from, Vec3 to, double width, double height,
                                               MotionValidator.Medium medium, boolean requireSupport, int maximumCells) {
        return MotionValidator.check(from, to, width, height, medium, requireSupport, maximumCells, owned(world, owned));
    }
    /** Reads already-loaded voxels; only valid on the owning thread for the duration of one check. */
    public static MotionValidator.Sampler sampler(World world) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Live movement validation requires the owner thread");
        Objects.requireNonNull(world);
        return (x, y, z) -> {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))
                return VoxelSnapshot.Cell.UNAVAILABLE;
            return CollisionCells.of(world.getBlockAt(x, y, z));
        };
    }
    /** Region owner only: an unowned or unloaded chunk reads as unavailable and is never touched. */
    public static MotionValidator.Sampler owned(World world, ChunkOwnership owned) {
        Objects.requireNonNull(world); Objects.requireNonNull(owned);
        return (x, y, z) -> {
            int chunkX = Math.floorDiv(x, 16), chunkZ = Math.floorDiv(z, 16);
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !owned.owns(chunkX, chunkZ) || !world.isChunkLoaded(chunkX, chunkZ))
                return VoxelSnapshot.Cell.UNAVAILABLE;
            return CollisionCells.of(world.getBlockAt(x, y, z));
        };
    }
}
