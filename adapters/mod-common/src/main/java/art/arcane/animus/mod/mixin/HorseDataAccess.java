package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.equine.Horse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The packed coat colour and markings of horse bodies. Nothing here creates an entity. */
@Mixin(Horse.class)
public interface HorseDataAccess {
    @Accessor("DATA_ID_TYPE_VARIANT")
    static EntityDataAccessor<Integer> animus$variant() { throw new AssertionError("Mixin accessor was not applied"); }
}
