package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.EquipmentSlot;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/** What a player holds and wears, for NPCs that mirror their viewer's equipment. On the player's owner. */
public final class BukkitMirror {
    private BukkitMirror() {}

    public static Map<EquipmentSlot, ItemStack> equipment(Player player) {
        EntityEquipment equipment = player.getEquipment();
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        if (equipment == null) return items;
        put(items, EquipmentSlot.MAIN_HAND, equipment.getItemInMainHand());
        put(items, EquipmentSlot.OFF_HAND, equipment.getItemInOffHand());
        put(items, EquipmentSlot.HEAD, equipment.getHelmet());
        put(items, EquipmentSlot.CHEST, equipment.getChestplate());
        put(items, EquipmentSlot.LEGS, equipment.getLeggings());
        put(items, EquipmentSlot.FEET, equipment.getBoots());
        return items;
    }
    private static void put(Map<EquipmentSlot, ItemStack> items, EquipmentSlot slot, ItemStack item) {
        if (item != null && !item.getType().isAir()) items.put(slot, item.clone());
    }
}
