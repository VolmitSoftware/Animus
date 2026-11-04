package art.arcane.animus.mod.mixin;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server's own entity ID allocator, so virtual bodies never share an ID with a real entity. */
@Mixin(Entity.class)
public interface EntityCounterAccessor {
    @Accessor("ENTITY_COUNTER")
    static AtomicInteger animus$counter() { throw new AssertionError("Mixin accessor was not applied"); }
}
