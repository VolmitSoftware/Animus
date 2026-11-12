package com.volmit.animuscamera.mixin;

import com.volmit.animuscamera.AnimusCamera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V", remap = false))
	private void animuscamera$neverShowWindow(long handle) {
		AnimusCamera.LOGGER.info("Window show suppressed");
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void animuscamera$onFrameStart(boolean advanceGameTime, CallbackInfo info) {
		AnimusCamera.onFrameStart((Minecraft) (Object) this);
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDynamicUniforms()Lnet/minecraft/client/renderer/DynamicUniforms;"))
	private void animuscamera$onFrameRendered(boolean advanceGameTime, CallbackInfo info) {
		AnimusCamera.onFrameRendered((Minecraft) (Object) this);
	}
}
