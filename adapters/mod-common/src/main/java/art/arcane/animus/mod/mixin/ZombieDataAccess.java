package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The baby flag of zombie bodies. Nothing here creates an entity. */
@Mixin(Zombie.class)
public interface ZombieDataAccess {
    @Accessor("DATA_BABY_ID")
    static EntityDataAccessor<Boolean> animus$baby() { throw new AssertionError("Mixin accessor was not applied"); }
}
