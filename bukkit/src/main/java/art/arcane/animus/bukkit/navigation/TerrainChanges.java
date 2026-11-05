package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.List;
import java.util.Objects;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Block removals that can take the ground from under an NPC. Events fire before the block changes, on the thread that
 * owns the block, and the sink only marks NPCs for a later support check. Block physics updates are not followed:
 * they fire for every neighbour update and the removals that matter arrive through these events.
 */
public final class TerrainChanges implements Listener {
    /** Called on the block's owner thread with the world UUID key. */
    @FunctionalInterface public interface Sink { void changed(String world, BlockPos at); }
    private final Sink sink;

    public TerrainChanges(Sink sink) { this.sink = Objects.requireNonNull(sink, "sink"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void broken(BlockBreakEvent event) { changed(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void burned(BlockBurnEvent event) { changed(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void faded(BlockFadeEvent event) { changed(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void decayed(LeavesDecayEvent event) { changed(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void exploded(BlockExplodeEvent event) { changed(event.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void exploded(EntityExplodeEvent event) { changed(event.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void pushed(BlockPistonExtendEvent event) { changed(event.getBlocks()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void pulled(BlockPistonRetractEvent event) { changed(event.getBlocks()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void taken(EntityChangeBlockEvent event) { changed(event.getBlock()); }

    private void changed(List<Block> blocks) { for (Block block : blocks) changed(block); }
    private void changed(Block block) {
        sink.changed(block.getWorld().getUID().toString(), new BlockPos(block.getX(), block.getY(), block.getZ()));
    }
}
