package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An NPC's helper projectile resolves its own hit; vanilla never damages, explodes, splashes or hatches anything with
 * it. Every projectile's tick reaches its hit through this method, before any subclass's own hit handling.
 */
@Mixin(Projectile.class)
public abstract class ProjectileHooks {
    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void animus$hit(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> callback) {
        if (ModHooks.helperHit((Projectile) (Object) this, hit)) callback.setReturnValue(ProjectileDeflection.NONE);
    }
}
