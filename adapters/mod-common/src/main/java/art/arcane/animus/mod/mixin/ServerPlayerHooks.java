package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerHooks {
    @Unique private ServerLevel animus$teleportFrom;
    @Unique private boolean animus$replacingMenu;

    @Inject(method = "die", at = @At("HEAD"))
    private void animus$died(DamageSource source, CallbackInfo callback) {
        ModHooks.died((ServerPlayer) (Object) this);
        ModHooks.killed((ServerPlayer) (Object) this, source);
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

    /*
     * Opening a menu. Vanilla and Forge build it in openMenu(MenuProvider); NeoForge moves that body into an overload
     * that also takes extra data, so both are named and whichever holds the body is hooked.
     */
    @Inject(method = {"openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
            "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;"}, at = @At("HEAD"))
    private void animus$opening(MenuProvider provider, CallbackInfoReturnable<java.util.OptionalInt> callback) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        animus$replacingMenu = self.containerMenu != self.inventoryMenu;
    }

    /** The menu just built; a cancelled one is released unopened and openMenu takes its no-menu path. */
    @ModifyVariable(method = {"openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
            "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;"}, at = @At("STORE"))
    private AbstractContainerMenu animus$opened(AbstractContainerMenu menu) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (menu == null || !ModHooks.opening(self, menu)) return menu;
        menu.removed(self);
        if (animus$replacingMenu) self.connection.send(new ClientboundContainerClosePacket(menu.containerId));
        return null;
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void animus$closing(CallbackInfo callback) {
        ModHooks.closing((ServerPlayer) (Object) this);
    }

    /** The drop key; the stack has not left the inventory yet. */
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void animus$dropping(boolean all, CallbackInfo callback) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!ModHooks.dropping(self, all)) return;
        callback.cancel();
        self.containerMenu.sendAllDataToRemote();
    }

    /** Finishing eating or drinking, under the same guard vanilla checks before it consumes the item. */
    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void animus$consuming(CallbackInfo callback) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        ItemStack using = self.getUseItem();
        if (using.isEmpty() || !self.isUsingItem() || !ModHooks.consuming(self, using)) return;
        callback.cancel();
        self.stopUsingItem();
        self.containerMenu.sendAllDataToRemote();
    }
}
