package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block clicks and breaks for the wand and server behaviors. The packet handlers around these send the clicked block
 * back after a refusal.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerGameModeHooks {
    @Shadow @Final protected ServerPlayer player;

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void animus$rightClick(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
        if (!ModHooks.wandOnBlock(user, hand) && !ModHooks.clickingBlock(user, false, hand, hit.getBlockPos())) return;
        callback.setReturnValue(InteractionResult.FAIL);
        user.containerMenu.sendAllDataToRemote();
    }

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void animus$leftClick(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction direction, int maxY, int sequence, CallbackInfo callback) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || pos.getY() > maxY || !ModHooks.clickingBlock(player, true, InteractionHand.MAIN_HAND, pos)) return;
        callback.cancel();
        ModHooks.resendBlock(player, pos);
    }

    /** Every survival and creative break ends here; a refusal makes the caller resend the block state, and the block entity is resent too. */
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void animus$breaking(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!ModHooks.breaking(player, pos)) return;
        callback.setReturnValue(false);
        ModHooks.resendBlock(player, pos);
    }
}
