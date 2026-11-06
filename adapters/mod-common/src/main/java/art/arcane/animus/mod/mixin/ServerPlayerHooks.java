package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerHooks {
    @Unique private ServerLevel animus$teleportFrom;

    @Inject(method = "die", at = @At("HEAD"))
    private void animus$died(DamageSource source, CallbackInfo callback) {
        ModHooks.died((ServerPlayer) (Object) this);
    }

    /** Every dimension change of a living player passes here. Optional so a loader patch cannot stop the server. */
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"), require = 0)
    private void animus$teleporting(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> callback) {
        animus$teleportFrom = ((ServerPlayer) (Object) this).level();
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("RETURN"), require = 0)
    private void animus$teleported(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> callback) {
        ServerLevel from = animus$teleportFrom;
        animus$teleportFrom = null;
        if (callback.getReturnValue() != null) ModHooks.teleportedFrom(callback.getReturnValue(), from);
    }
}
