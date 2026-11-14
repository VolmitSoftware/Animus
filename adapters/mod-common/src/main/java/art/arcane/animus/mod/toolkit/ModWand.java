package art.arcane.animus.mod.toolkit;

import art.arcane.animus.core.toolkit.Toolkit;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The selection wand on mod servers: any item whose {@code minecraft:custom_data} holds {@code "animus:tool": "wand"}.
 * Renaming the item keeps it a wand; a renamed stick without the marker is not one.
 */
public final class ModWand {
    /** The hotbar and storage slots of a player's inventory, before armor and the off hand. */
    private static final int CARRIED = 36;

    private ModWand() {}

    /** Moves a wand the player carries into the main hand by swapping it with what is there; false when they carry none. */
    public static boolean carried(ServerPlayer player) {
        if (is(player.getMainHandItem())) return true;
        ItemStack held = player.getMainHandItem();
        if (is(player.getOffhandItem())) {
            ItemStack wand = player.getOffhandItem();
            player.setItemInHand(InteractionHand.OFF_HAND, held);
            player.setItemInHand(InteractionHand.MAIN_HAND, wand);
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < CARRIED; slot++) {
            ItemStack wand = inventory.getItem(slot);
            if (!is(wand)) continue;
            inventory.setItem(slot, held);
            player.setItemInHand(InteractionHand.MAIN_HAND, wand);
            return true;
        }
        return false;
    }

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
