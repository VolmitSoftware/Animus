package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;

/** Movement cells from a block's real collision shape, fluid and hazard. A shape that cannot be read is a full block. */
public final class CollisionCells {
    private static final Set<String> HAZARDS = Set.of("LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS",
            "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW", "POINTED_DRIPSTONE");
    private CollisionCells() {}

    /** Reads one loaded block on its owning thread. */
    public static Cell of(Block block) {
        Objects.requireNonNull(block, "block");
        BlockData data = block.getBlockData();
        List<Cell.Box> boxes = new ArrayList<>();
        try {
            for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                Cell.Box clamped = clamp(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
                if (clamped != null) boxes.add(clamped);
            }
        } catch (RuntimeException unreadable) {
            boxes = List.of(Cell.SOLID.boxes().getFirst());
        }
        return classify(block.getType().name(), boxes, data instanceof Waterlogged water && water.isWaterlogged());
    }

    /** Collision boxes are block-local; fluid comes from the material or a waterlogged state. */
    public static Cell classify(String material, Collection<Cell.Box> boxes, boolean waterlogged) {
        Objects.requireNonNull(material, "material"); Objects.requireNonNull(boxes, "boxes");
        Cell.Fluid fluid = material.equals("LAVA") ? Cell.Fluid.LAVA
                : waterlogged || material.equals("WATER") || material.equals("BUBBLE_COLUMN") ? Cell.Fluid.WATER : Cell.Fluid.NONE;
        return Cell.of(List.copyOf(boxes), fluid, HAZARDS.contains(material));
    }

    /** Keeps a box inside the voxel and the 1.5 overhang a fence or wall needs; an empty remainder is dropped. */
    static Cell.Box clamp(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double x0 = Math.max(0, minX), y0 = Math.max(0, minY), z0 = Math.max(0, minZ);
        double x1 = Math.min(1, maxX), y1 = Math.min(Cell.MAX_HEIGHT, maxY), z1 = Math.min(1, maxZ);
        return x0 < x1 && y0 < y1 && z0 < z1 ? new Cell.Box(x0, y0, z0, x1, y1, z1) : null;
    }
}
