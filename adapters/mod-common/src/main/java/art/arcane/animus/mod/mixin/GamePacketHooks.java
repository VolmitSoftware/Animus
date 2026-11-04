package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import java.util.Set;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Virtual body input. Vanilla first re-schedules each of these packets onto the server thread, so the hooks act only
 * on that second pass, where the player and its presentation are owned.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GamePacketHooks {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void animus$interact(ServerboundInteractPacket packet, CallbackInfo callback) {
        if (ModHooks.owner(player) && ModHooks.interact(player, packet.entityId(), false, packet.hand().name(), packet.usingSecondaryAction())) callback.cancel();
    }

    @Inject(method = "handleAttack", at = @At("HEAD"), cancellable = true)
    private void animus$attack(ServerboundAttackPacket packet, CallbackInfo callback) {
        if (ModHooks.owner(player) && ModHooks.interact(player, packet.entityId(), true, "none", false)) callback.cancel();
    }

    @Inject(method = "handlePlayerInput", at = @At("HEAD"))
    private void animus$input(ServerboundPlayerInputPacket packet, CallbackInfo callback) {
        if (ModHooks.owner(player)) ModHooks.input(player, packet.input());
    }

    @Inject(method = "handleClientTickEnd", at = @At("HEAD"))
    private void animus$tickEnd(ServerboundClientTickEndPacket packet, CallbackInfo callback) {
        if (ModHooks.owner(player)) ModHooks.tickEnd(player);
    }

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"))
    private void animus$teleport(PositionMoveRotation destination, Set<Relative> relatives, CallbackInfo callback) {
        net.minecraft.world.phys.Vec3 at = player.position(), to = destination.position();
        ModHooks.teleported(player, relatives.contains(Relative.X) ? at.x() + to.x() : to.x(),
                relatives.contains(Relative.Y) ? at.y() + to.y() : to.y(), relatives.contains(Relative.Z) ? at.z() + to.z() : to.z());
    }
}
