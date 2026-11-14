package art.arcane.animus.bukkit.toolkit;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.toolkit.Toolkit;
import java.util.Collection;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The selection wand on Bukkit: any item whose persistent data holds {@code animus:tool = "wand"}. Renaming or moving
 * the item keeps it a wand; a renamed stick without the marker is not one.
 */
public final class BukkitWand {
    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString(Toolkit.MARKER));

    private static final int HOTBAR = 9;

    private BukkitWand() {}

    /** Marks the stack as a wand. */
    public static ItemStack mark(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, Toolkit.WAND);
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean is(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && marked(meta.getPersistentDataContainer());
    }

    static boolean marked(PersistentDataContainer data) { return Toolkit.WAND.equals(data.get(KEY, PersistentDataType.STRING)); }

    public static boolean held(Player player) { return is(player.getInventory().getItemInMainHand()); }

    /**
     * A wand the player already carries goes to the main hand: on the hotbar by selecting its slot, elsewhere by
     * swapping it with the held item. Otherwise {@code wand} goes into the inventory, or at the player's feet when full.
     */
    public static ScreenHost.WandGift give(Player player, ItemStack wand) {
        PlayerInventory inventory = player.getInventory();
        if (held(player)) return ScreenHost.WandGift.MOVED;
        if (is(inventory.getItemInOffHand())) {
            ItemStack main = inventory.getItemInMainHand();
            inventory.setItemInMainHand(inventory.getItemInOffHand());
            inventory.setItemInOffHand(main);
            return ScreenHost.WandGift.MOVED;
        }
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (!is(contents[slot])) continue;
            if (slot < HOTBAR) inventory.setHeldItemSlot(slot);
            else {
                ItemStack main = inventory.getItemInMainHand();
                inventory.setItemInMainHand(contents[slot]);
                inventory.setItem(slot, main);
            }
            return ScreenHost.WandGift.MOVED;
        }
        Collection<ItemStack> left = inventory.addItem(mark(wand)).values();
        for (ItemStack rest : left) player.getWorld().dropItemNaturally(player.getLocation(), rest);
        return left.isEmpty() ? ScreenHost.WandGift.GIVEN : ScreenHost.WandGift.DROPPED;
    }
}
