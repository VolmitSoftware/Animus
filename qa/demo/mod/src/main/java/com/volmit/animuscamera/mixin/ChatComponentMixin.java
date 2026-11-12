package com.volmit.animuscamera.mixin;

import com.volmit.animuscamera.ChatCapture;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(method = "addMessage", at = @At("HEAD"))
	private void animuscamera$captureChat(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo info) {
		ChatCapture.record(contents.getString());
	}
}
