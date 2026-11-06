package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Combat facts about real entities: who hurt whom, player damage and kills for server behaviors, and deaths whose drops an NPC disallowed. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHooks {
    @Shadow protected boolean dead;

    /** Players reach here through their own overrides, after the PvP and difficulty adjustments. Damage vanilla would ignore publishes nothing. */
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void animus$hurting(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        if (ModHooks.hurting((LivingEntity) (Object) this, level, source, amount)) callback.setReturnValue(false);
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void animus$hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) ModHooks.realDamaged((LivingEntity) (Object) this, source);
    }

    /** Players override die without calling this one; their kills are published from that override. */
    @Inject(method = "die", at = @At("HEAD"))
    private void animus$dying(DamageSource source, CallbackInfo callback) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer) && !self.isRemoved() && !dead && !self.level().isClientSide()) ModHooks.killed(self, source);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void animus$drops(ServerLevel level, DamageSource source, CallbackInfo callback) {
        if (ModHooks.suppressesDrops((LivingEntity) (Object) this)) callback.cancel();
    }
}
