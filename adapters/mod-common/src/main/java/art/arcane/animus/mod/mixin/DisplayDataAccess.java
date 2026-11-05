package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.class)
public interface DisplayDataAccess {
    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> animus$translation() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3fc> animus$scale() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_LEFT_ROTATION_ID")
    static EntityDataAccessor<Quaternionfc> animus$leftRotation() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_RIGHT_ROTATION_ID")
    static EntityDataAccessor<Quaternionfc> animus$rightRotation() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID")
    static EntityDataAccessor<Integer> animus$interpolationStart() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID")
    static EntityDataAccessor<Integer> animus$interpolationDuration() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    static EntityDataAccessor<Byte> animus$billboard() { throw new AssertionError("Mixin accessor was not applied"); }
}
