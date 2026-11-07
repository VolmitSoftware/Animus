package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a llama body carries a chest. Nothing here creates an entity. */
@Mixin(AbstractChestedHorse.class)
public interface ChestedHorseDataAccess {
    @Accessor("DATA_ID_CHEST")
    static EntityDataAccessor<Boolean> animus$chest() { throw new AssertionError("Mixin accessor was not applied"); }
}
