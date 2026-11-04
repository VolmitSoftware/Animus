package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Combat facts about real entities: who hurt whom, and deaths whose drops an NPC disallowed. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHooks {
    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void animus$hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) ModHooks.realDamaged((LivingEntity) (Object) this, source);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void animus$drops(ServerLevel level, DamageSource source, CallbackInfo callback) {
        if (ModHooks.suppressesDrops((LivingEntity) (Object) this)) callback.cancel();
    }
}
