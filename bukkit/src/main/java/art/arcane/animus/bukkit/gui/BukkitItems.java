package art.arcane.animus.bukkit.gui;

import art.arcane.animus.bukkit.runtime.DefinitionItems;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.toolkit.SkinLookup;
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
    static final String HEAD = "minecraft:player_head";
    private static final int STORAGE = 36;
    private static final String RESET = "§r";

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

    /** A stack to store, give or compare, such as a GUI's give and take: its name and lore as written. */
    static Optional<ItemStack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) { return stack(item, name, lore, errors, false); }

    /** A stack a menu only draws, with its name and lore upright; see {@link #dress}. */
    static Optional<ItemStack> display(NpcItem item, Optional<String> name, List<String> lore, List<String> errors) { return stack(item, name, lore, errors, true); }

    private static Optional<ItemStack> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors, boolean upright) {
        ItemStack stack = DefinitionItems.stack(item, "$.item", errors);
        if (stack == null) return Optional.empty();
        if (name.isEmpty() && lore.isEmpty()) return Optional.of(stack);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return Optional.of(stack);
        if (upright) dress(meta, name, lore); else label(meta, name, lore);
        stack.setItemMeta(meta);
        return Optional.of(stack);
    }

    /**
     * Sets the name and lore as written. Items a GUI gives and takes back are compared whole, so these never change:
     * a key handed out by an older version still matches the one its take builds.
     */
    static void label(ItemMeta meta, Optional<String> name, List<String> lore) {
        name.ifPresent(meta::setDisplayName);
        if (!lore.isEmpty()) meta.setLore(lore);
    }

    /**
     * Sets the name and lore upright, for menu items no one keeps. The server reads a section-coded line from its first
     * reset or colour code with italic set false, but leaves text before any code unset, which vanilla draws italic; a
     * leading reset covers that.
     */
    static void dress(ItemMeta meta, Optional<String> name, List<String> lore) {
        label(meta, name.map(BukkitItems::upright), lore.stream().map(BukkitItems::upright).toList());
    }

    private static String upright(String line) { return line.startsWith(RESET) ? line : RESET + line; }

    /**
     * A player head that shows {@code owner}'s skin through a name-only profile, which the client resolves itself as it
     * does for vanilla heads, so nothing is looked up on the server. The profile is hidden from the tooltip, where it
     * would add a "Dynamic" line. Other items, heads that already wear a profile and names no account can have are
     * returned unchanged, and show the default head.
     */
    static NpcItem owned(NpcItem item, String owner) {
        if (!HEAD.equals(MinecraftIds.normalize(item.id())) || !owner.matches(SkinLookup.NAME)) return item;
        String inner = item.components().map((String text) -> text.substring(1, text.length() - 1)).orElse("");
        if (inner.contains("profile=")) return item;
        String hidden = "\"minecraft:profile\"";
        String merged;
        if (inner.contains("hidden_components:[]")) merged = inner.replace("hidden_components:[]", "hidden_components:[" + hidden + "]");
        else if (inner.contains("hidden_components:[")) merged = inner.replace("hidden_components:[", "hidden_components:[" + hidden + ",");
        else if (inner.contains("tooltip_display=")) merged = inner;
        else merged = inner + (inner.isEmpty() ? "" : ",") + "tooltip_display={hidden_components:[" + hidden + "]}";
        return new NpcItem(item.id(), item.count(), Optional.of("[profile=\"" + owner + "\"," + merged + "]"), item.imported());
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
