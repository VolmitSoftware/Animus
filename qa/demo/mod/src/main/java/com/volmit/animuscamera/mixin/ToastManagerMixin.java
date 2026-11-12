package com.volmit.animuscamera.mixin;

import com.volmit.animuscamera.AnimusCamera;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops toasts (recipes, advancements, tutorial hints) so they never land in a clip. */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void animuscamera$suppressToast(Toast toast, CallbackInfo info) {
		if (!AnimusCamera.toastsEnabled()) {
			info.cancel();
		}
	}
}
