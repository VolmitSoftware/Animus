package com.volmit.animuscamera.mixin;

import com.mojang.blaze3d.platform.GLX;
import com.volmit.animuscamera.HiddenWindow;
import java.util.function.LongSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GLX.class)
public abstract class GlxMixin {
	@Inject(method = "_initGlfw", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwInit()Z", remap = false))
	private static void animuscamera$beforeGlfwInit(CallbackInfoReturnable<LongSupplier> info) {
		HiddenWindow.beforeGlfwInit();
	}

	@Inject(method = "_initGlfw", at = @At("RETURN"))
	private static void animuscamera$afterGlfwInit(CallbackInfoReturnable<LongSupplier> info) {
		HiddenWindow.afterGlfwInit();
	}
}
