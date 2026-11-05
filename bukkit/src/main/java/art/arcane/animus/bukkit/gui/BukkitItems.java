package art.arcane.animus.bukkit.gui;

import art.arcane.animus.bukkit.runtime.DefinitionItems;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.WritableBookMeta;

/** Bukkit item stacks for screens and shop trades. Stacks handed in are never changed. */
final class BukkitItems implements ItemOps<ItemStack> {
    static final BukkitItems INSTANCE = new BukkitItems();
    private static final int STORAGE = 36;

    private BukkitItems() {}

    @Override public boolean empty(ItemStack stack) { return stack == null || stack.getType().isAir() || stack.getAmount() <= 0; }
    @Override public int count(ItemStack stack) { return empty(stack) ? 0 : stack.getAmount(); }
    @Override public ItemStack withCount(ItemStack stack, int count) { ItemStack copy = stack.clone(); copy.setAmount(count); return copy; }
    @Override public int maxStackSize(ItemStack stack) { return Math.max(1, stack.getMaxStackSize()); }

    @Override public boolean matches(ItemStack held, ItemStack wanted, MatchRule rule) {
        if (empty(held) || wanted == null || held.getType() != wanted.getType()) return false;
        return switch (rule) {
            case MATERIAL -> true;
            case STRICT -> held.isSimilar(wanted);
            case LEGACY -> legacy(held, wanted);
        };
    }

    /** Type, damage, name, lore, enchantments, potion and book pages; hidden data and model data are not compared. */
    private static boolean legacy(ItemStack held, ItemStack wanted) {
        ItemMeta a = held.getItemMeta(), b = wanted.getItemMeta();
        if (a == null || b == null) return a == b;
        if (a.hasDisplayName() != b.hasDisplayName() || (a.hasDisplayName() && !a.getDisplayName().equals(b.getDisplayName()))) return false;
        if (!Objects.equals(a.getLore(), b.getLore()) || !a.getEnchants().equals(b.getEnchants())) return false;
        if (a instanceof Damageable da && b instanceof Damageable db && da.getDamage() != db.getDamage()) return false;
        if (a instanceof PotionMeta pa && b instanceof PotionMeta pb && pa.getBasePotionType() != pb.getBasePotionType()) return false;
        return !(a instanceof WritableBookMeta ba && b instanceof WritableBookMeta bb) || ba.getPages().equals(bb.getPages());
    }

    @Override public String describe(ItemStack stack, int count) {
        ItemMeta meta = stack.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : readable(stack.getType());
        return count + "x " + name;
    }

    private static String readable(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) if (!word.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return out.toString();
    }

    static Optional<ItemStack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) {
        ItemStack stack = DefinitionItems.stack(item, "$.item", errors);
        if (stack == null) return Optional.empty();
        if (name.isEmpty() && lore.isEmpty()) return Optional.of(stack);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return Optional.of(stack);
        name.ifPresent(meta::setDisplayName);
        if (!lore.isEmpty()) meta.setLore(lore);
        stack.setItemMeta(meta);
        return Optional.of(stack);
    }

    /** The hotbar and the three storage rows; armour, offhand and the cursor are never touched. */
    static TradeInventory<ItemStack> storage(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new TradeInventory<>() {
            @Override public int size() { return STORAGE; }
            @Override public ItemStack get(int slot) { ItemStack stack = inventory.getItem(slot); return stack == null || stack.getType().isAir() ? null : stack.clone(); }
            @Override public void set(int slot, ItemStack stack) { inventory.setItem(slot, stack); }
        };
    }
}
