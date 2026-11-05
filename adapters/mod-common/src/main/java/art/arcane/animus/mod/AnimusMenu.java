package art.arcane.animus.mod;

import art.arcane.animus.core.gui.GuiModel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A server-side chest window that never lets an item move. Every click is refused before vanilla handles it and
 * the full window and cursor are sent again, so a client that predicted a pickup is corrected. The four bound
 * clicks on the window's own slots go to the screen.
 */
final class AnimusMenu extends ChestMenu {
    /** Receives a bound click on one of the window's own slots. */
    @FunctionalInterface interface Clicks { void clicked(AnimusMenu menu, int slot, GuiModel.Click click); }
    /** Receives the close of this window. */
    @FunctionalInterface interface Closes { void closed(AnimusMenu menu, Player player); }

    private final int size;
    private final Clicks clicks;
    private final Closes closes;
    private final String resolution;
    boolean replaced;

    private AnimusMenu(MenuType<ChestMenu> type, int id, Inventory inventory, SimpleContainer container, int rows, String resolution, Clicks clicks, Closes closes) {
        super(type, id, inventory, container, rows);
        this.size = rows * 9;
        this.resolution = resolution;
        this.clicks = clicks;
        this.closes = closes;
    }

    /** A 9-wide window; other resolutions are drawn only on Bukkit. */
    static AnimusMenu create(int id, Inventory inventory, int rows, String resolution, Clicks clicks, Closes closes) {
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        return new AnimusMenu(type, id, inventory, new SimpleContainer(rows * 9), rows, resolution, clicks, closes);
    }

    String resolution() { return resolution; }
    int windowSize() { return size; }

    void show(int slot, ItemStack stack) { getContainer().setItem(slot, stack); }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        GuiModel.Click click = null;
        if (slot >= 0 && slot < size) {
            if (input == ContainerInput.PICKUP) click = button == 0 ? GuiModel.Click.LEFT : button == 1 ? GuiModel.Click.RIGHT : null;
            else if (input == ContainerInput.QUICK_MOVE) click = button == 0 ? GuiModel.Click.SHIFT_LEFT : button == 1 ? GuiModel.Click.SHIFT_RIGHT : null;
        }
        sendAllDataToRemote();
        if (click != null) clicks.clicked(this, slot, click);
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }

    @Override public void removed(Player player) {
        super.removed(player);
        if (!replaced) closes.closed(this, player);
    }
}
