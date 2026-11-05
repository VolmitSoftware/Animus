package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Player lifecycle on every loader: join, leave and respawn, which replaces the player object. */
@Mixin(PlayerList.class)
public abstract class PlayerListHooks {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void animus$joined(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callback) {
        ModHooks.joined(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void animus$quit(ServerPlayer player, CallbackInfo callback) {
        ModHooks.quit(player);
    }

    @Inject(method = "respawn", at = @At("RETURN"))
    private void animus$respawned(ServerPlayer old, boolean keepInventory, Entity.RemovalReason reason, CallbackInfoReturnable<ServerPlayer> callback) {
        ModHooks.respawned(callback.getReturnValue());
    }
}
