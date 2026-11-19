package art.arcane.animus.bukkit.gui;

import art.arcane.animus.bukkit.BukkitChat;
import art.arcane.animus.bukkit.toolkit.BukkitWand;
import art.arcane.animus.core.shop.PlayerShops;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopSpot;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.volmlib.util.event.ProtectionProbe;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.DoubleChestInventory;

/**
 * Players' shops on Bukkit. Sneaking with the shop item, a right-click on a container starts New shop there, and the
 * next right-click on a container after Add chest answers it; either reads what protection plugins decided about the
 * click first. A shop's chests stay its own: only the owner and staff who run any shop open or break them, explosions
 * leave them, no hopper takes from them, and no chest joins one from beside it. An owner breaking a chest takes it out of
 * the shop. Other plugins' protection probes get the same answers, silently, and change nothing. Runs on each event's own
 * thread; the lookups read the last loaded shops without locks.
 */
public final class BukkitPlayerShops implements Listener {
    private final PlayerShops shops;
    private final Toolkit<?, ?> toolkit;

    public BukkitPlayerShops(PlayerShops shops, Toolkit<?, ?> toolkit) { this.shops = Objects.requireNonNull(shops); this.toolkit = Objects.requireNonNull(toolkit); }

    /** Last, so the protection plugins have said whether the player may use the block. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (ProtectionProbe.isProbe(event) || event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        boolean item = BukkitWand.is(player.getInventory().getItemInMainHand(), Toolkit.SHOP_ITEM), picking = toolkit.pickingChest(player.getUniqueId());
        if (!picking && !(item && player.isSneaking())) return;
        Block block = event.getClickedBlock();
        if (!BukkitShopStock.container(block)) {
            if (!picking) BukkitChat.send(player, Palette.error("That is not a chest, barrel or other container"));
            return;
        }
        boolean refused = event.useInteractedBlock() == Event.Result.DENY;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (refused) { BukkitChat.send(player, Palette.error("You cannot use that chest: protection does not let you open it")); return; }
        Optional<ShopSpot> spot = BukkitShopStock.spot(player, block);
        if (spot.isEmpty()) { BukkitChat.send(player, Palette.error("Leave a free space beside the chest for the shop's NPC")); return; }
        if (picking) toolkit.chestClicked(player.getUniqueId(), spot.get());
        else toolkit.newShop(player.getUniqueId(), spot.get());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (!shops.any() || !(event.getPlayer() instanceof Player player)) return;
        Optional<ShopDocument> shop = holder(event.getInventory());
        if (shop.isEmpty() || mayRun(player, shop.get())) return;
        event.setCancelled(true);
        BukkitChat.send(player, Palette.error("This chest belongs to " + owner(shop.get()) + "'s shop"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breaking(BlockBreakEvent event) {
        if (!shops.any()) return;
        Block block = event.getBlock();
        Optional<ShopDocument> shop = at(block).or(() -> partner(block).flatMap(this::at));
        if (shop.isEmpty() || mayRun(event.getPlayer(), shop.get())) return;
        event.setCancelled(true);
        if (!ProtectionProbe.isProbe(event)) BukkitChat.send(event.getPlayer(), Palette.error("This chest belongs to " + owner(shop.get()) + "'s shop"));
    }

    /** Last, so a break that protection or anything else cancelled leaves the chest in its shop. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        if (!shops.any() || ProtectionProbe.isProbe(event)) return;
        Block block = event.getBlock();
        at(block).ifPresent((ShopDocument shop) -> shops.broken(shop, block(shop, block)));
    }

    /** A chest placed beside a shop's chest would join it and open its stock to whoever placed it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        if (!shops.any() || !(event.getBlockPlaced().getType() == Material.CHEST || event.getBlockPlaced().getType() == Material.TRAPPED_CHEST)) return;
        Block placed = event.getBlockPlaced();
        for (BlockFace side : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block beside = placed.getRelative(side);
            if (beside.getType() != placed.getType()) continue;
            Optional<ShopDocument> shop = at(beside);
            if (shop.isPresent() && !mayRun(event.getPlayer(), shop.get())) {
                event.setCancelled(true);
                if (!ProtectionProbe.isProbe(event)) BukkitChat.send(event.getPlayer(), Palette.error("A chest here would join " + owner(shop.get()) + "'s shop"));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void exploded(EntityExplodeEvent event) { if (shops.any()) event.blockList().removeIf(this::kept); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void exploded(BlockExplodeEvent event) { if (shops.any()) event.blockList().removeIf(this::kept); }

    /** No hopper or hopper minecart takes items out of a shop's chests; putting items in is left alone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drained(InventoryMoveItemEvent event) {
        if (shops.any() && holder(event.getSource()).isPresent()) event.setCancelled(true);
    }

    private boolean kept(Block block) { return at(block).isPresent() || partner(block).flatMap(this::at).isPresent(); }

    /** The shop whose stock an inventory is: a container block's, or either half's of a double chest. Reads no block state, as hoppers ask often. */
    private Optional<ShopDocument> holder(Inventory inventory) {
        if (inventory instanceof DoubleChestInventory pair) return at(pair.getLeftSide().getLocation()).or(() -> at(pair.getRightSide().getLocation()));
        return at(inventory.getLocation());
    }

    private Optional<ShopDocument> at(Location location) {
        if (location == null || location.getWorld() == null) return Optional.empty();
        World world = location.getWorld();
        return shops.at(Set.of(world.getName(), world.getKey().toString()), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private Optional<ShopDocument> at(Block block) {
        World world = block.getWorld();
        return shops.at(Set.of(world.getName(), world.getKey().toString()), block.getX(), block.getY(), block.getZ());
    }

    /** The other half of a double chest, or empty. */
    private static Optional<Block> partner(Block block) {
        if (!(block.getState() instanceof Chest chest) || !(chest.getInventory() instanceof DoubleChestInventory pair)) return Optional.empty();
        for (Inventory side : List.of(pair.getLeftSide(), pair.getRightSide())) {
            Location at = side.getLocation();
            if (at != null && (at.getBlockX() != block.getX() || at.getBlockY() != block.getY() || at.getBlockZ() != block.getZ())) return Optional.of(at.getBlock());
        }
        return Optional.empty();
    }

    /** The block as the shop lists it, under the world name the shop file uses. */
    private static PlayerShops.Block block(ShopDocument shop, Block block) {
        for (ShopDocument.Container container : shop.containers())
            if (container.x() == block.getX() && container.y() == block.getY() && container.z() == block.getZ()) return PlayerShops.Block.of(container);
        return new PlayerShops.Block(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static boolean mayRun(Player player, ShopDocument shop) {
        return shop.owns(player.getUniqueId()) || PlayerShops.manager(Optional.of(player.getUniqueId()), player::hasPermission);
    }

    private static String owner(ShopDocument shop) { return shop.owner().flatMap(ShopDocument.Owner::name).orElse("a player"); }
}
