package art.arcane.animus.bukkit.toolkit;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.toolkit.Toolkit;
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
 * the item keeps it a wand; a renamed stick without the marker is not one. The shop item carries {@code "shop"} the same way.
 */
public final class BukkitWand {
    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString(Toolkit.MARKER));

    private static final int HOTBAR = 9;

    private BukkitWand() {}

    /** Marks the stack as the tool {@code tool}: {@link Toolkit#WAND} or {@link Toolkit#SHOP_ITEM}. */
    public static ItemStack mark(ItemStack stack, String tool) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, tool);
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean is(ItemStack stack) { return is(stack, Toolkit.WAND); }

    /** Whether the stack carries the marker of {@code tool}. */
    public static boolean is(ItemStack stack, String tool) {
        if (stack == null || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && marked(meta.getPersistentDataContainer(), tool);
    }

    static boolean marked(PersistentDataContainer data) { return marked(data, Toolkit.WAND); }

    static boolean marked(PersistentDataContainer data, String tool) { return tool.equals(data.get(KEY, PersistentDataType.STRING)); }

    public static boolean held(Player player) { return is(player.getInventory().getItemInMainHand()); }

    /**
     * A wand the player already carries goes to the main hand: on the hotbar by selecting its slot, elsewhere by
     * swapping it with the held item. Otherwise {@code wand} lands in the main hand too (see {@link #landing}), or at the
     * player's feet when the inventory is full.
     */
    public static ScreenHost.WandGift give(Player player, ItemStack wand) { return give(player, wand, Toolkit.WAND); }

    /** As {@link #give(Player, ItemStack)}, for the tool {@code tool}. */
    public static ScreenHost.WandGift give(Player player, ItemStack item, String tool) {
        PlayerInventory inventory = player.getInventory();
        if (is(inventory.getItemInMainHand(), tool)) return ScreenHost.WandGift.MOVED;
        if (is(inventory.getItemInOffHand(), tool)) {
            ItemStack main = inventory.getItemInMainHand();
            inventory.setItemInMainHand(inventory.getItemInOffHand());
            inventory.setItemInOffHand(main);
            return ScreenHost.WandGift.MOVED;
        }
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (!is(contents[slot], tool)) continue;
            if (slot < HOTBAR) inventory.setHeldItemSlot(slot);
            else {
                ItemStack main = inventory.getItemInMainHand();
                inventory.setItemInMainHand(contents[slot]);
                inventory.setItem(slot, main);
            }
            return ScreenHost.WandGift.MOVED;
        }
        boolean[] empty = new boolean[contents.length];
        for (int slot = 0; slot < contents.length; slot++) empty[slot] = contents[slot] == null || contents[slot].getType().isAir();
        ItemStack marked = mark(item, tool);
        Landing landing = landing(inventory.getHeldItemSlot(), empty);
        switch (landing.how()) {
            case HAND -> inventory.setItem(landing.slot(), marked);
            case SELECT -> { inventory.setItem(landing.slot(), marked); inventory.setHeldItemSlot(landing.slot()); }
            case SWAP -> { inventory.setItem(landing.slot(), inventory.getItemInMainHand()); inventory.setItemInMainHand(marked); }
            case DROP -> { player.getWorld().dropItemNaturally(player.getLocation(), marked); return ScreenHost.WandGift.DROPPED; }
        }
        return ScreenHost.WandGift.GIVEN;
    }

    /** Where a new tool goes: the held slot, a hotbar slot that is then selected, a free slot the held item moves to, or the ground. */
    enum How { HAND, SELECT, SWAP, DROP }

    record Landing(How how, int slot) {}

    /**
     * Where a new tool goes so it lands in the main hand: the held slot when it is empty, else the first empty hotbar slot,
     * selected; with a full hotbar the held item moves to the first empty storage slot and the tool takes its place; with
     * no empty slot at all it drops. {@code empty} covers the 36 storage slots, the hotbar first.
     */
    static Landing landing(int held, boolean[] empty) {
        if (held >= 0 && held < empty.length && empty[held]) return new Landing(How.HAND, held);
        for (int slot = 0; slot < Math.min(HOTBAR, empty.length); slot++) if (empty[slot]) return new Landing(How.SELECT, slot);
        for (int slot = HOTBAR; slot < empty.length; slot++) if (empty[slot]) return new Landing(How.SWAP, slot);
        return new Landing(How.DROP, -1);
    }
}
