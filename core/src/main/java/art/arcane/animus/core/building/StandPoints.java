package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.virtual.Vec3;
import java.util.Optional;

/** Finds a cell next to a block where an NPC can stand and still reach it. Reads at most a few hundred loaded cells. */
public final class StandPoints {
    private static final int RINGS = 3, RISE = 2;

    private StandPoints() {}

    /**
     * The standable feet cell within {@code reach} of the block's centre that is nearest to {@code from}, never the
     * block's own cell or the cell above it.
     */
    public static Optional<BlockPos> near(MotionValidator.Sampler cells, BlockPos block, Vec3 from, double reach, double height) {
        Vec3 centre = centre(block);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int headroom = Math.max(1, (int) Math.ceil(height));
        for (int ring = 1; ring <= RINGS; ring++) {
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy = -RISE; dy <= RISE; dy++) {
                        BlockPos feet = new BlockPos(block.x() + dx, block.y() + dy, block.z() + dz);
                        Vec3 standing = new Vec3(feet.x() + .5, feet.y(), feet.z() + .5);
                        if (standing.add(new Vec3(0, Math.min(height, 1.6), 0)).distance(centre) > reach) continue;
                        double distance = standing.distance(from);
                        if (distance >= bestDistance || !standable(cells, feet, headroom)) continue;
                        best = feet;
                        bestDistance = distance;
                    }
                }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    public static Vec3 centre(BlockPos block) { return new Vec3(block.x() + .5, block.y() + .5, block.z() + .5); }

    static boolean standable(MotionValidator.Sampler cells, BlockPos feet, int headroom) {
        VoxelSnapshot.Cell below = cells.sample(feet.x(), feet.y() - 1, feet.z());
        if (below.unavailable() || !below.collides() || below.hazard()) return false;
        for (int up = 0; up < headroom; up++) {
            VoxelSnapshot.Cell space = cells.sample(feet.x(), feet.y() + up, feet.z());
            if (space.unavailable() || space.collides() || space.hazard() || space.fluid() == VoxelSnapshot.Cell.Fluid.LAVA) return false;
        }
        return true;
    }
}
