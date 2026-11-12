package com.volmit.animuscamera.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("missTime")
	void animuscamera$setMissTime(int missTime);

	@Invoker("pick")
	void animuscamera$pick(float partialTicks);
}
