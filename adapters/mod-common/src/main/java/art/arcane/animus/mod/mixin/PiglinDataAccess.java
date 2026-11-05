package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.piglin.Piglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The baby flag of piglin bodies. Nothing here creates an entity. */
@Mixin(Piglin.class)
public interface PiglinDataAccess {
    @Accessor("DATA_BABY_ID")
    static EntityDataAccessor<Boolean> animus$baby() { throw new AssertionError("Mixin accessor was not applied"); }
}
