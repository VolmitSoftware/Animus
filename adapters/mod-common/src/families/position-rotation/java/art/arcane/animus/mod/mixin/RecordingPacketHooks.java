package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The arm swings and block use performance recordings keep, on the owner pass of each packet. Before 26.3 the swing packet names its hand.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RecordingPacketHooks {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void animus$swing(ServerboundSwingPacket packet, CallbackInfo callback) {
        if (ModHooks.owner(player)) ModHooks.swung(player, packet.getHand());
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void animus$useOn(ServerboundUseItemOnPacket packet, CallbackInfo callback) {
        if (ModHooks.owner(player)) ModHooks.usedBlock(player, packet.getHand(), packet.getHitResult().getBlockPos());
    }
}
