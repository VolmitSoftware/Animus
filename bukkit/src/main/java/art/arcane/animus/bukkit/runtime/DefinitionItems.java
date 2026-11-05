package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcItem;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Turns definition items into server item stacks on the owner thread, reporting each unusable field. */
public final class DefinitionItems {
    private DefinitionItems() {}

    static Map<EquipmentSlot, ItemStack> equipment(NpcAppearance appearance, List<String> errors) {
        Map<EquipmentSlot, ItemStack> stacks = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<EquipmentSlot, NpcItem> entry : appearance.equipment().entrySet()) {
            ItemStack stack = stack(entry.getValue(), "$.equipment." + entry.getKey().key(), errors);
            if (stack != null) stacks.put(entry.getKey(), stack);
        }
        return stacks;
    }

    public static ItemStack stack(NpcItem item, String path, List<String> errors) {
        Material material = Material.matchMaterial(item.id());
        if (material == null || !material.isItem() || material.isAir()) { errors.add(path + ".id: unknown item " + item.id() + " on this server"); return null; }
        ItemStack stack = new ItemStack(material, item.count());
        if (item.components().isEmpty()) return stack;
        try { return Bukkit.getUnsafe().modifyItemStack(stack, item.components().get()); }
        catch (RuntimeException invalid) {
            errors.add(path + ".components: " + (invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage()));
            return null;
        }
    }
}
