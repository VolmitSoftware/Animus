package art.arcane.animus.mod.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An arrow's base damage, which vanilla multiplies by its speed when it hits. Read only. */
@Mixin(AbstractArrow.class)
public interface ArrowDataAccess {
    @Accessor("baseDamage")
    double animus$baseDamage();
}
