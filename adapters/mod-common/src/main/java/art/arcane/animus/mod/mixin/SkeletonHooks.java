package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skeletons shoot their bow without the weapon's own shot; the arrow spawned inside belongs to this shot. */
@Mixin(AbstractSkeleton.class)
public abstract class SkeletonHooks {
    @Inject(method = "performRangedAttack", at = @At("HEAD"))
    private void animus$aiming(LivingEntity target, float power, CallbackInfo callback) {
        AbstractSkeleton self = (AbstractSkeleton) (Object) this;
        ModHooks.aiming(self, InteractionHand.MAIN_HAND, self.getMainHandItem(), 1);
    }

    @Inject(method = "performRangedAttack", at = @At("RETURN"))
    private void animus$aimed(LivingEntity target, float power, CallbackInfo callback) {
        ModHooks.aimed();
    }
}
