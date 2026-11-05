package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.CollisionCells;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;

/** Reads one loaded Bukkit block's collision shape into a movement cell. */
public final class BlockCells {
    private BlockCells() {}

    /** Reads one loaded block on its owning thread. */
    public static Cell of(Block block) {
        Objects.requireNonNull(block, "block");
        BlockData data = block.getBlockData();
        List<Cell.Box> boxes = new ArrayList<>();
        try {
            for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                Cell.Box clamped = CollisionCells.clamp(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
                if (clamped != null) boxes.add(clamped);
            }
        } catch (RuntimeException unreadable) {
            boxes = List.of(Cell.SOLID.boxes().getFirst());
        }
        return CollisionCells.classify(block.getType().name(), boxes, data instanceof Waterlogged water && water.isWaterlogged());
    }
}
