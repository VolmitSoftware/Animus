package art.arcane.animus.mod.mixin;

import net.minecraft.core.Holder;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.frog.FrogVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The variant of frog bodies. Nothing here creates an entity. */
@Mixin(Frog.class)
public interface FrogDataAccess {
    @Accessor("DATA_VARIANT_ID")
    static EntityDataAccessor<Holder<FrogVariant>> animus$variant() { throw new AssertionError("Mixin accessor was not applied"); }
}
