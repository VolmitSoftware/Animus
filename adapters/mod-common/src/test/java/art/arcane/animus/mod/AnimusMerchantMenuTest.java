package art.arcane.animus.mod;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A shop's trading screen shows the chosen offer's result without any payment moving in. */
class AnimusMerchantMenuTest {
    /** Vanilla's items need its bootstrap; NeoForge's patched classes bootstrap only inside FML (a second try fails to link), so this runs on Fabric and Forge. */
    @BeforeAll
    static void bootstrap() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (LinkageError outsideLoader) {
            org.junit.jupiter.api.Assumptions.abort("vanilla cannot bootstrap outside this loader: " + outsideLoader.getCause());
        }
    }

    @Test
    void choosingAnOfferShowsItsResultWithEmptyPaymentSlots() {
        AnimusMerchantMenu.Offer offer = new AnimusMerchantMenu.Offer(stack(Items.EMERALD), ItemStack.EMPTY, stack(Items.DIAMOND), 0, Integer.MAX_VALUE);
        AnimusMerchantMenu menu = AnimusMerchantMenu.create(1, new Inventory(null, new EntityEquipment()), List.of(offer),
                (AnimusMerchantMenu clicked, int chosen, art.arcane.animus.core.gui.GuiModel.Click click) -> {},
                (AnimusMerchantMenu closed, net.minecraft.world.entity.player.Player player) -> {});
        menu.setSelectionHint(0);
        menu.tryMoveItems(0);
        assertTrue(menu.getSlot(0).getItem().isEmpty(), "payment slot A stays empty");
        assertEquals(Items.DIAMOND, menu.getSlot(2).getItem().getItem(), "the result slot shows the offer's result");
    }

    /** A stack whose item carries its own components: outside a server no registry has bound the built-in ones. */
    private static ItemStack stack(Item item) {
        return new ItemStack(Holder.direct(item, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()), 1);
    }
}
