package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether an armadillo body is rolled up. Nothing here creates an entity. */
@Mixin(Armadillo.class)
public interface ArmadilloDataAccess {
    @Accessor("ARMADILLO_STATE")
    static EntityDataAccessor<Armadillo.ArmadilloState> animus$state() { throw new AssertionError("Mixin accessor was not applied"); }
}
