package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.CollisionCells;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Door;
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
        String material = block.getType().name();
        return CollisionCells.classify(material, boxes, data instanceof Waterlogged water && water.isWaterlogged(), openShape(material, data).orElse(null));
    }

    /**
     * The collision of a closed door, fence gate or upper trapdoor once opened, when a body may open it by hand. A lower
     * trapdoor is a floor or a step, so it is never opened.
     */
    static Optional<List<Cell.Box>> openShape(String material, BlockData data) {
        Optional<CollisionCells.Opening> opening = CollisionCells.opening(material);
        if (opening.isEmpty() || !(data instanceof Openable openable) || openable.isOpen() || !(data instanceof Directional directional)) return Optional.empty();
        if (opening.get() == CollisionCells.Opening.TRAPDOOR && (!(data instanceof Bisected half) || half.getHalf() != Bisected.Half.TOP)) return Optional.empty();
        boolean right = data instanceof Door door && door.getHinge() == Door.Hinge.RIGHT;
        return Optional.of(CollisionCells.openShape(opening.get(), directional.getFacing().name(), right));
    }
}
