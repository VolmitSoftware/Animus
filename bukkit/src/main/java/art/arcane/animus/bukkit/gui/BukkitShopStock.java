package art.arcane.animus.bukkit.gui;

import art.arcane.animus.bukkit.BukkitWorldLookup;

import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopStock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Opens an owned shop's containers for one trade on the buyer's thread. Each block must be in a loaded chunk this
 * thread owns, still hold an inventory, and protection must let the shop's owner open it; otherwise nothing opens.
 * Two listed halves of one double chest open once.
 */
final class BukkitShopStock {
    private final Server server;
    private final BiPredicate<UUID, Location> ownerMayOpen;
    private final Method chunkOwned;

    /** @param ownerMayOpen whether protection lets the owner open the block; it must deny when it cannot decide */
    BukkitShopStock(Server server, BiPredicate<UUID, Location> ownerMayOpen) {
        this.server = Objects.requireNonNull(server);
        this.ownerMayOpen = Objects.requireNonNull(ownerMayOpen);
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
            String where = container.world() + " " + container.x() + " " + container.y() + " " + container.z();
            World world = world(container.world());
            if (world == null) return ShopStock.refused("world " + container.world() + " is not loaded");
            if (!world.isChunkLoaded(container.x() >> 4, container.z() >> 4)) return ShopStock.refused("the chest at " + where + " is not loaded");
            if (!owned(world, container.x() >> 4, container.z() >> 4)) return ShopStock.refused("the chest at " + where + " is in another region");
            Block block = world.getBlockAt(container.x(), container.y(), container.z());
            BlockState state = block.getState();
            if (!(state instanceof Container holder)) return ShopStock.refused("there is no chest at " + where);
            boolean allowed;
            try { allowed = ownerMayOpen.test(owner, block.getLocation()); }
            catch (RuntimeException | LinkageError failure) { allowed = false; }
            if (!allowed) return ShopStock.refused("protection does not let the owner open the chest at " + where);
            Inventory inventory = holder.getInventory();
            Location key = inventory.getLocation() == null ? block.getLocation() : inventory.getLocation();
            if (opened.add(key)) parts.add(storage(inventory));
        }
        if (parts.isEmpty()) return ShopStock.refused("the shop lists no chest");
        return ShopStock.of(ShopStock.combined(parts));
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
