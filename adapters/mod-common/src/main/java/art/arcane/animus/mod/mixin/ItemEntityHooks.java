package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item pickup for server behaviors. Touching runs every tick, so only a touch that vanilla would turn into a pickup publishes. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityHooks {
    @Shadow private int pickupDelay;
    @Shadow private UUID target;

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void animus$touch(Player player, CallbackInfo callback) {
        if (pickupDelay != 0 || !(player instanceof ServerPlayer server) || target != null && !target.equals(server.getUUID())) return;
        if (ModHooks.pickingUp(server, ((ItemEntity) (Object) this).getItem())) callback.cancel();
    }
}
