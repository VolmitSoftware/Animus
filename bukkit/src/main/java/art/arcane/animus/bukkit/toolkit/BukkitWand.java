package art.arcane.animus.bukkit.toolkit;

import art.arcane.animus.core.toolkit.Toolkit;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The selection wand on Bukkit: any item whose persistent data holds {@code animus:tool = "wand"}. Renaming or moving
 * the item keeps it a wand; a renamed blaze rod without the marker is not one.
 */
public final class BukkitWand {
    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString(Toolkit.MARKER));

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

    /** Into the inventory, or at the player's feet when it is full. */
    public static void give(Player player, ItemStack wand) {
        for (ItemStack left : player.getInventory().addItem(mark(wand)).values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
    }
}
