package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Movement cells from a block's real collision shape, fluid, hazard and whether it can be climbed. Every platform reads the shape itself and
 * names the block by its uppercase vanilla path, such as {@code MAGMA_BLOCK}. A shape that cannot be read is a full block.
 */
public final class CollisionCells {
    private static final Set<String> HAZARDS = Set.of("LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS",
            "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW", "POINTED_DRIPSTONE");
    private static final Set<String> CLIMBABLE = Set.of("LADDER", "VINE", "SCAFFOLDING", "TWISTING_VINES", "TWISTING_VINES_PLANT",
            "WEEPING_VINES", "WEEPING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT");
    /** The game gives scaffolding a top only to a body above it; movement always lets a body stand on that top. */
    private static final List<Cell.Box> SCAFFOLD_TOP = List.of(new Cell.Box(0, .875, 0, 1, 1, 1));
    private CollisionCells() {}

    /** Collision boxes are block-local; fluid comes from the material or a waterlogged state. */
    public static Cell classify(String material, Collection<Cell.Box> boxes, boolean waterlogged) {
        Objects.requireNonNull(material, "material"); Objects.requireNonNull(boxes, "boxes");
        Cell.Fluid fluid = material.equals("LAVA") ? Cell.Fluid.LAVA
                : waterlogged || material.equals("WATER") || material.equals("BUBBLE_COLUMN") ? Cell.Fluid.WATER : Cell.Fluid.NONE;
        List<Cell.Box> shape = material.equals("SCAFFOLDING") ? SCAFFOLD_TOP : List.copyOf(boxes);
        return Cell.of(shape, fluid, HAZARDS.contains(material), CLIMBABLE.contains(material));
    }

    /** Keeps a box inside the voxel and the 1.5 overhang a fence or wall needs; an empty remainder is dropped. */
    public static Cell.Box clamp(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double x0 = Math.max(0, minX), y0 = Math.max(0, minY), z0 = Math.max(0, minZ);
        double x1 = Math.min(1, maxX), y1 = Math.min(Cell.MAX_HEIGHT, maxY), z1 = Math.min(1, maxZ);
        return x0 < x1 && y0 < y1 && z0 < z1 ? new Cell.Box(x0, y0, z0, x1, y1, z1) : null;
    }
}
