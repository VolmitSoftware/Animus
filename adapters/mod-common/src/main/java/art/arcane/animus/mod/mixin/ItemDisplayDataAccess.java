package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayDataAccess {
    @Accessor("DATA_ITEM_STACK_ID")
    static EntityDataAccessor<ItemStack> animus$item() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_ITEM_DISPLAY_ID")
    static EntityDataAccessor<Byte> animus$itemContext() { throw new AssertionError("Mixin accessor was not applied"); }
}
