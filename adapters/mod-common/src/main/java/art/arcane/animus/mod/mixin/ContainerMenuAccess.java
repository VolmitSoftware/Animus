package art.arcane.animus.mod.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A menu's type without {@code getType()}, which throws for menus that have none, such as the player's own inventory. */
@Mixin(AbstractContainerMenu.class)
public interface ContainerMenuAccess {
    @Accessor("menuType")
    MenuType<?> animus$menuType();
}
