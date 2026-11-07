package art.arcane.animus.mod.toolkit;

import art.arcane.animus.core.toolkit.Toolkit;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The selection wand on mod servers: any item whose {@code minecraft:custom_data} holds {@code "animus:tool": "wand"}.
 * Renaming the item keeps it a wand; a renamed blaze rod without the marker is not one.
 */
public final class ModWand {
    private ModWand() {}

    private static CompoundTag marker() {
        CompoundTag tag = new CompoundTag();
        tag.putString(Toolkit.MARKER, Toolkit.WAND);
        return tag;
    }

    /** Marks the stack as a wand, keeping any custom data it already has. */
    public static ItemStack mark(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(Toolkit.MARKER, Toolkit.WAND);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean is(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.matchedBy(marker());
    }
}
