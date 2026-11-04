package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.*;
import art.arcane.animus.core.virtual.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.Objects;

/** No collision-shape/neighbor APIs: only already-loaded voxels on the owning Bukkit thread. */
public final class LiveMovementProbe {
    private LiveMovementProbe() {}
    public static MotionValidator.Result check(World world, Vec3 from, Vec3 to, double width, double height,
                                               MotionValidator.Medium medium, boolean requireSupport, int maximumCells) {
        return MotionValidator.check(from, to, width, height, medium, requireSupport, maximumCells, sampler(world));
    }
    /** Reads already-loaded voxels; only valid on the owning thread for the duration of one check. */
    public static MotionValidator.Sampler sampler(World world) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Live movement validation requires the owner thread");
        Objects.requireNonNull(world);
        return (x, y, z) -> {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))
                return VoxelSnapshot.Cell.UNAVAILABLE;
            return ConservativeVoxelClassifier.classify(world.getBlockAt(x, y, z).getType().name());
        };
    }
}
