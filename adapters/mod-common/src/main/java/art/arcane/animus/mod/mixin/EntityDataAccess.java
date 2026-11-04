package art.arcane.animus.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Synced data keys a packet-only body needs. Nothing here creates an entity. */
@Mixin(Entity.class)
public interface EntityDataAccess {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> animus$sharedFlags() { throw new AssertionError("Mixin accessor was not applied"); }
    @Accessor("DATA_POSE")
    static EntityDataAccessor<Pose> animus$pose() { throw new AssertionError("Mixin accessor was not applied"); }
}
