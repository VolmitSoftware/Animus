package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.class)
public interface DisplayDataAccess {
    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> animus$translation() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    static EntityDataAccessor<Byte> animus$billboard() { throw new AssertionError("Mixin accessor was not applied"); }
}
