package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The living-entity flags: 0x01 raises the held item. Nothing here creates an entity. */
@Mixin(LivingEntity.class)
public interface LivingDataAccess {
    @Accessor("DATA_LIVING_ENTITY_FLAGS")
    static EntityDataAccessor<Byte> animus$livingFlags() { throw new AssertionError("Mixin accessor was not applied"); }
}
