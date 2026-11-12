package com.volmit.animuscamera.mixin;

import com.mojang.blaze3d.platform.Window;
import com.volmit.animuscamera.HiddenWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(
		method = "createGlfwWindow",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false)
	)
	private static void animuscamera$hideBeforeCreate(CallbackInfoReturnable<Long> info) {
		HiddenWindow.applyWindowHints();
	}

	@Inject(method = "createGlfwWindow", at = @At("RETURN"))
	private static void animuscamera$hideAfterCreate(CallbackInfoReturnable<Long> info) {
		HiddenWindow.hide(info.getReturnValueJ());
	}

	@Inject(method = "onFocus", at = @At("HEAD"), cancellable = true)
	private void animuscamera$keepFocused(long handle, boolean focused, CallbackInfo info) {
		info.cancel();
	}

	@Inject(method = "isMinimized", at = @At("HEAD"), cancellable = true)
	private void animuscamera$neverMinimized(CallbackInfoReturnable<Boolean> info) {
		info.setReturnValue(false);
	}

	@Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
	private void animuscamera$neverIconified(CallbackInfoReturnable<Boolean> info) {
		info.setReturnValue(false);
	}
}
