package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every projectile a shooter, dispenser or command launches enters the world here; a cancelled launch never spawns. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelHooks {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void animus$adding(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (ModHooks.launching((ServerLevel) (Object) this, entity)) callback.setReturnValue(false);
    }
}
