package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An NPC's helper projectile resolves its own hit; vanilla never damages, explodes, splashes or hatches anything with
 * it. Every projectile's tick reaches its hit through this method, before any subclass's own hit handling. A real
 * projectile's hit publishes to server triggers, and a cancelled one passes as if nothing was hit. Each server tick of a
 * projectile also reports where it is, so a player's or mob's shot can strike an NPC body it cannot touch.
 */
@Mixin(Projectile.class)
public abstract class ProjectileHooks {
    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void animus$hit(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> callback) {
        Projectile self = (Projectile) (Object) this;
        if (ModHooks.helperHit(self, hit) || ModHooks.projectileHit(self, hit)) callback.setReturnValue(ProjectileDeflection.NONE);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void animus$ticked(CallbackInfo callback) { ModHooks.projectileTicked((Projectile) (Object) this); }
}
