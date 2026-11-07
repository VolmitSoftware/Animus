package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.equine.Llama;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The wool colour of llama bodies. Nothing here creates an entity. */
@Mixin(Llama.class)
public interface LlamaDataAccess {
    @Accessor("DATA_VARIANT_ID")
    static EntityDataAccessor<Integer> animus$variant() { throw new AssertionError("Mixin accessor was not applied"); }
}
