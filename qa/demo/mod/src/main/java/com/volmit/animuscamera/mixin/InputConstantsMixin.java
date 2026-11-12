package com.volmit.animuscamera.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Grabbing the mouse would warp the real system cursor, so it never happens. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
	private static void animuscamera$noCursorWarp(Window window, int cursorMode, double xpos, double ypos, CallbackInfo info) {
		info.cancel();
	}
}
