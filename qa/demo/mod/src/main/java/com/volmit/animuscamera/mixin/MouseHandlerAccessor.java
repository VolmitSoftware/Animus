package com.volmit.animuscamera.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void animuscamera$setXpos(double xpos);

	@Accessor("ypos")
	void animuscamera$setYpos(double ypos);
}
