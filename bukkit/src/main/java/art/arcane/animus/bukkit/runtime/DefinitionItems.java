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

    /** Whether this server reads item components as Paper does, alone after an item id it adds itself. */
    private static final boolean PAPER = paper();

    /** The components as the server's item parser reads them: alone on Paper, after the item id on Spigot, as in a give command. */
    static String arguments(String id, String components, boolean paper) { return paper ? components : id + components; }

    private static boolean paper() {
        try {
            Class.forName("io.papermc.paper.ServerBuildInfo", false, DefinitionItems.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) { return false; }
    }

    public static ItemStack stack(NpcItem item, String path, List<String> errors) {
        Material material = Material.matchMaterial(item.id());
        if (material == null || !material.isItem() || material.isAir()) { errors.add(path + ".id: unknown item " + item.id() + " on this server"); return null; }
        ItemStack stack = new ItemStack(material, item.count());
        if (item.components().isEmpty()) return stack;
        try { return Bukkit.getUnsafe().modifyItemStack(stack, arguments(material.getKey().toString(), item.components().get(), PAPER)); }
        catch (RuntimeException invalid) {
            errors.add(path + ".components: " + (invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage()));
            return null;
        }
    }
}
