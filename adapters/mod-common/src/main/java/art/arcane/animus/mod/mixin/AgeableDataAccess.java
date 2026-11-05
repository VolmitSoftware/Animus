package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.AgeableMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The baby flag of ageable bodies such as horses, llamas and villagers. Nothing here creates an entity. */
@Mixin(AgeableMob.class)
public interface AgeableDataAccess {
    @Accessor("DATA_BABY_ID")
    static EntityDataAccessor<Boolean> animus$baby() { throw new AssertionError("Mixin accessor was not applied"); }
}
