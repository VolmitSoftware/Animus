package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An NPC's helper arrow resolves its own hit; vanilla never damages anything with it. */
@Mixin(Projectile.class)
public abstract class ProjectileHooks {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void animus$hit(HitResult hit, CallbackInfo callback) {
        if (ModHooks.helperHit((Projectile) (Object) this, hit)) callback.cancel();
    }
}
