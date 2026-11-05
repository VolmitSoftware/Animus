package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Zoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The baby flag of zoglin bodies. Nothing here creates an entity. */
@Mixin(Zoglin.class)
public interface ZoglinDataAccess {
    @Accessor("DATA_BABY_ID")
    static EntityDataAccessor<Boolean> animus$baby() { throw new AssertionError("Mixin accessor was not applied"); }
}
