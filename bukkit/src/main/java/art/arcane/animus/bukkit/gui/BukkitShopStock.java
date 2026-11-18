package art.arcane.animus.bukkit.gui;

import art.arcane.animus.bukkit.BukkitWorldLookup;

import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopSpot;
import art.arcane.animus.core.shop.ShopStock;
import art.arcane.animus.core.virtual.Vec3;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

/**
 * An owned shop's containers on Bukkit. For a trade each block must be in a loaded chunk this thread owns, still hold
 * an inventory, and protection must let the shop's owner open it; a block that is no longer a container, or that
 * protection now keeps from the owner, is lost and closes the shop. Two listed halves of one double chest open once.
 * The same checks decide which blocks a player may make a shop on, add to one, or open as its stock.
 */
final class BukkitShopStock {
    /** How far a player reaches to pick a container, as for block use. */
    static final int REACH = 5;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final Server server;
    private final BiPredicate<UUID, Location> mayOpen;
    private final Method chunkOwned;

    /** One block checked: its inventory, or why it cannot be used and whether that will not pass on its own. */
    private record Checked(Optional<Inventory> inventory, String reason, boolean lost) {
        static Checked refused(String reason, boolean lost) { return new Checked(Optional.empty(), reason, lost); }
    }

    /** @param mayOpen whether protection lets a player, online or not, open the block; it must deny when it cannot decide */
    BukkitShopStock(Server server, BiPredicate<UUID, Location> mayOpen) {
        this.server = Objects.requireNonNull(server);
        this.mayOpen = Objects.requireNonNull(mayOpen);
        Method owned = null;
        try { owned = Server.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class); }
        catch (NoSuchMethodException spigot) { /* Spigot has one main thread; isPrimaryThread decides. */ }
        chunkOwned = owned;
    }

    ShopStock<ItemStack> open(ShopDocument shop) {
        if (shop.owner().isEmpty()) return ShopStock.refused("the shop has no owner");
        UUID owner = shop.owner().get().uuid();
        List<TradeInventory<ItemStack>> parts = new ArrayList<>();
        Set<Location> opened = new HashSet<>();
        for (ShopDocument.Container container : shop.containers()) {
            Checked checked = check(owner, container, "the owner");
            if (checked.inventory().isEmpty()) return checked.lost() ? ShopStock.lost(checked.reason()) : ShopStock.refused(checked.reason());
            Inventory inventory = checked.inventory().get();
            Location key = inventory.getLocation() == null ? new Location(world(container.world()), container.x(), container.y(), container.z()) : inventory.getLocation();
            if (opened.add(key)) parts.add(storage(inventory));
        }
        if (parts.isEmpty()) return ShopStock.lost("the shop lists no chest");
        return ShopStock.of(ShopStock.combined(parts));
    }

    /** Why {@code player} may not keep shop stock in the block now; empty when they may. */
    Optional<String> refusal(UUID player, ShopDocument.Container container) {
        Checked checked = check(player, container, "you");
        return checked.inventory().isPresent() ? Optional.empty() : Optional.of(checked.reason());
    }

    /** Whether the block is loaded on this thread and no longer holds an inventory, as after it was broken. */
    boolean gone(ShopDocument.Container container) {
        World world = world(container.world());
        if (world == null || !world.isChunkLoaded(container.x() >> 4, container.z() >> 4) || !owned(world, container.x() >> 4, container.z() >> 4)) return false;
        return !(world.getBlockAt(container.x(), container.y(), container.z()).getState() instanceof Container);
    }

    /** Opens the block's inventory for the player as if they opened it by hand; empty when it opened, else why not. */
    Optional<String> show(Player player, ShopDocument.Container container) {
        Checked checked = check(player.getUniqueId(), container, "you");
        if (checked.inventory().isEmpty()) return Optional.of(checked.reason());
        player.openInventory(checked.inventory().get());
        return Optional.empty();
    }

    /** {@code who} names the player in a protection refusal: "the owner", or "you". */
    private Checked check(UUID player, ShopDocument.Container container, String who) {
        String where = container.x() + " " + container.y() + " " + container.z();
        World world = world(container.world());
        if (world == null) return Checked.refused("world " + container.world() + " is not loaded", false);
        if (!world.isChunkLoaded(container.x() >> 4, container.z() >> 4)) return Checked.refused("the chest at " + where + " is not loaded", false);
        if (!owned(world, container.x() >> 4, container.z() >> 4)) return Checked.refused("the chest at " + where + " is too far away", false);
        Block block = world.getBlockAt(container.x(), container.y(), container.z());
        BlockState state = block.getState();
        if (!(state instanceof Container holder)) return Checked.refused("there is no chest at " + where, true);
        boolean allowed;
        try { allowed = mayOpen.test(player, block.getLocation()); }
        catch (RuntimeException | LinkageError failure) { allowed = false; }
        if (!allowed) return Checked.refused("protection does not let " + who + " open the chest at " + where, true);
        return new Checked(Optional.of(holder.getInventory()), "", false);
    }

    /**
     * The container block the player looks at within {@link #REACH}, with the free spot beside it nearest the player
     * where a shop's NPC stands, turned toward them. Empty for any other block, or when no side of it is free.
     */
    static Optional<ShopSpot> looked(Player player) {
        RayTraceResult hit;
        try { hit = player.rayTraceBlocks(REACH); }
        catch (RuntimeException refused) { return Optional.empty(); }
        return hit == null || hit.getHitBlock() == null ? Optional.empty() : spot(player, hit.getHitBlock());
    }

    /** Whether the block holds an inventory, as chests, barrels and shulker boxes do. */
    static boolean container(Block block) { return block.getState() instanceof Container; }

    /** The block as a shop container, with the free side nearest the player for its NPC; empty when it is no container or boxed in. */
    static Optional<ShopSpot> spot(Player player, Block block) {
        if (!container(block)) return Optional.empty();
        Location at = player.getLocation();
        double dx = at.getX() - (block.getX() + 0.5), dz = at.getZ() - (block.getZ() + 0.5);
        List<BlockFace> sides = new ArrayList<>(List.of(SIDES));
        sides.sort(java.util.Comparator.comparingDouble((BlockFace side) -> -(side.getModX() * dx + side.getModZ() * dz)));
        for (BlockFace side : sides) {
            Block feet = block.getRelative(side);
            if (!feet.isPassable() || !feet.getRelative(BlockFace.UP).isPassable()) continue;
            double x = feet.getX() + 0.5, z = feet.getZ() + 0.5;
            float yaw = (float) Math.toDegrees(Math.atan2(-(at.getX() - x), at.getZ() - z));
            World world = block.getWorld();
            return Optional.of(new ShopSpot(Set.of(world.getName(), world.getKey().toString()), block.getX(), block.getY(), block.getZ(), new Vec3(x, feet.getY(), z), yaw));
        }
        return Optional.empty();
    }

    private World world(String name) { return BukkitWorldLookup.find(server, name); }

    private boolean owned(World world, int chunkX, int chunkZ) {
        if (chunkOwned == null) return server.isPrimaryThread();
        try { return (boolean) chunkOwned.invoke(server, world, chunkX, chunkZ); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    private static TradeInventory<ItemStack> storage(Inventory inventory) {
        return new TradeInventory<>() {
            @Override public int size() { return inventory.getSize(); }
            @Override public ItemStack get(int slot) { ItemStack stack = inventory.getItem(slot); return stack == null || stack.getType().isAir() ? null : stack.clone(); }
            @Override public void set(int slot, ItemStack stack) { inventory.setItem(slot, stack); }
        };
    }
}
