package art.arcane.animus.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The mod window maps the same four clicks as Bukkit (BukkitScreensTest) and never lets an item move either way. */
class AnimusMenuTest {
    /** Vanilla's menus need its bootstrap; NeoForge's patched classes bootstrap only inside FML, so this runs on Fabric and Forge. */
    @BeforeAll
    static void bootstrap() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (LinkageError outsideLoader) {
            org.junit.jupiter.api.Assumptions.abort("vanilla cannot bootstrap outside this loader: " + outsideLoader.getCause());
        }
    }

    static final int ROWS = 6, SIZE = ROWS * 9;

    @Test void onlyLeftRightAndTheirShiftClicksOnTheWindowReachAScreen() {
        for (ContainerInput input : ContainerInput.values()) for (int button = -1; button <= 40; button++) for (int slot : new int[]{-999, -1, 0, 30, SIZE - 1, SIZE, SIZE + 20}) {
            Optional<GuiModel.Click> expected = Optional.empty();
            if (slot >= 0 && slot < SIZE && input == ContainerInput.PICKUP && button == 0) expected = Optional.of(GuiModel.Click.LEFT);
            if (slot >= 0 && slot < SIZE && input == ContainerInput.PICKUP && button == 1) expected = Optional.of(GuiModel.Click.RIGHT);
            if (slot >= 0 && slot < SIZE && input == ContainerInput.QUICK_MOVE && button == 0) expected = Optional.of(GuiModel.Click.SHIFT_LEFT);
            if (slot >= 0 && slot < SIZE && input == ContainerInput.QUICK_MOVE && button == 1) expected = Optional.of(GuiModel.Click.SHIFT_RIGHT);
            assertEquals(expected, AnimusMenu.click(slot, SIZE, button, input), input + " button " + button + " on " + slot);
        }
    }

    @Test void noClickOrDragMovesAMenuItemOrAPlayerItem() {
        Inventory own = new Inventory(null, new EntityEquipment());
        List<GuiModel.Click> heard = new ArrayList<>();
        AnimusMenu menu = AnimusMenu.create(1, own, ROWS, "9x6", (AnimusMenu clicked, int slot, GuiModel.Click click) -> heard.add(click),
                (AnimusMenu closed, net.minecraft.world.entity.player.Player who) -> {});
        menu.show(0, stack(Items.DIAMOND));
        own.setItem(0, stack(Items.EMERALD));
        int ownHotbar = SIZE + 27;
        for (ContainerInput input : ContainerInput.values()) for (int button = 0; button <= 40; button++) for (int slot : new int[]{0, 1, ownHotbar, -999}) {
            menu.clicked(slot, button, input, null);
            assertEquals(Items.DIAMOND, menu.getSlot(0).getItem().getItem(), input + " " + button + " on " + slot + " keeps the button");
            assertTrue(menu.getSlot(1).getItem().isEmpty(), input + " " + button + " on " + slot + " puts nothing in the window");
            assertEquals(Items.EMERALD, own.getItem(0).getItem(), input + " " + button + " on " + slot + " keeps the player's item");
            assertTrue(menu.getCarried().isEmpty(), input + " " + button + " on " + slot + " leaves the cursor empty");
        }
        assertTrue(menu.quickMoveStack(null, ownHotbar).isEmpty());
        assertEquals(4, heard.stream().distinct().count(), "the four bound clicks were heard: " + heard);
    }

    /** A stack whose item carries its own components: outside a server no registry has bound the built-in ones. */
    static ItemStack stack(Item item) {
        return new ItemStack(Holder.direct(item, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()), 1);
    }
}
