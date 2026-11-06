package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block placement for server behaviors, just before the block is set: the placement checks have passed, so only real
 * placements publish. The use packet handler sends the clicked blocks back after a refusal.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemHooks {
    @Inject(method = "place", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private void animus$placing(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> callback) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || !ModHooks.placing(player, ((BlockItem) (Object) this).getBlock(), context.getClickedPos())) return;
        callback.setReturnValue(InteractionResult.FAIL);
        player.containerMenu.sendAllDataToRemote();
    }
}
