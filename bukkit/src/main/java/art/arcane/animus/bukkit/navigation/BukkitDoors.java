package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.bukkit.protection.RegionBuildProtection;
import art.arcane.animus.bukkit.protection.RegionFlags;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.CollisionCells;
import art.arcane.animus.core.navigation.DoorPassage;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.plugin.Plugin;

/**
 * Opens and closes real doors, fence gates and upper trapdoors for walking NPCs. Opening asks the build protection
 * gate for {@code USE} first and fails closed. Both halves of a door change together, without physics, so redstone next
 * to the door is not disturbed. Never loads a chunk.
 */
public final class BukkitDoors implements DoorPassage.Doors {
    /** Which thread may change a chunk: the main thread everywhere but Folia, the chunk's region owner there. */
    public interface Owners {
        /** The calling thread owns the chunk. */
        boolean owns(World world, int chunkX, int chunkZ);
        /** Runs the work on the chunk's owner later. */
        void run(World world, int chunkX, int chunkZ, Runnable work);
    }

    private final Server server;
    private final BuildProtection.Gate protection;
    private final Owners owners;

    public BukkitDoors(Server server, BuildProtection.Gate protection, Owners owners) {
        this.server = Objects.requireNonNull(server); this.protection = Objects.requireNonNull(protection); this.owners = Objects.requireNonNull(owners);
    }

    /** Doors behind WorldGuard's {@code use} flag, when WorldGuard is installed, and any module protection hook. */
    public static BukkitDoors bind(Plugin plugin, AnimusRuntime runtime, Owners owners) {
        RegionFlags.Binding regions = RegionFlags.bind(plugin.getServer());
        BuildProtection platform = regions == null ? BuildProtection.NONE : new RegionBuildProtection(regions.flags());
        BuildProtection.Gate gate = new BuildProtection.Gate(runtime.moduleHooks().buildProtection(platform), (String message) -> plugin.getLogger().warning("Doors: " + message));
        return new BukkitDoors(plugin.getServer(), gate, owners);
    }

    @Override public boolean open(UUID npc, String world, BlockPos at) {
        World loaded = world(world);
        if (loaded == null || !reachable(loaded, at) || !owners.owns(loaded, at.x() >> 4, at.z() >> 4)) return false;
        Block block = loaded.getBlockAt(at.x(), at.y(), at.z());
        Optional<CollisionCells.Opening> kind = CollisionCells.opening(block.getType().name());
        BlockData data = block.getBlockData();
        if (kind.isEmpty() || !(data instanceof Openable openable)) return false;
        if (openable.isOpen()) return true;
        if (BlockCells.openShape(block.getType().name(), data).isEmpty()) return false;
        if (!protection.allows(new BuildProtection.Request(npc, Optional.empty(), world, at, BuildProtection.Action.USE))) return false;
        set(block, kind.get(), true);
        return true;
    }

    @Override public void close(String world, BlockPos at) {
        World loaded = world(world);
        if (loaded == null || !reachable(loaded, at)) return;
        if (!owners.owns(loaded, at.x() >> 4, at.z() >> 4)) {
            owners.run(loaded, at.x() >> 4, at.z() >> 4, () -> { if (reachable(loaded, at)) shut(loaded, at); });
            return;
        }
        shut(loaded, at);
    }

    private static void shut(World world, BlockPos at) {
        Block block = world.getBlockAt(at.x(), at.y(), at.z());
        Optional<CollisionCells.Opening> kind = CollisionCells.opening(block.getType().name());
        if (kind.isPresent() && block.getBlockData() instanceof Openable openable && openable.isOpen()) set(block, kind.get(), false);
    }

    private static void set(Block block, CollisionCells.Opening kind, boolean open) {
        Openable data = (Openable) block.getBlockData();
        data.setOpen(open);
        block.setBlockData(data, false);
        if (data instanceof Door door) {
            Block other = block.getRelative(door.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
            if (other.getType() == block.getType() && other.getBlockData() instanceof Door half && half.isOpen() != open) {
                half.setOpen(open);
                other.setBlockData(half, false);
            }
        }
        Sound sound = switch (kind) {
            case DOOR -> open ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE;
            case GATE -> open ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE;
            case TRAPDOOR -> open ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE;
        };
        Location centre = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().playSound(centre, sound, SoundCategory.BLOCKS, 1, 1);
    }

    private static boolean reachable(World world, BlockPos at) {
        return at.y() >= world.getMinHeight() && at.y() < world.getMaxHeight() && world.isChunkLoaded(at.x() >> 4, at.z() >> 4);
    }

    private World world(String key) {
        try { return server.getWorld(UUID.fromString(key)); }
        catch (IllegalArgumentException notUuid) { return null; }
    }
}
