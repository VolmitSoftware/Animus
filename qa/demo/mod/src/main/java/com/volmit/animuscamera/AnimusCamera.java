package com.volmit.animuscamera;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AnimusCamera implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("animuscamera");
	private static volatile FrameRecorder recorder;
	private static ScriptRunner runner;
	private static volatile boolean toasts;

	@Override
	public void onInitializeClient() {
		Path root = FabricLoader.getInstance().getConfigDir().resolve("animuscamera");
		runner = new ScriptRunner(root);
		runner.start();
		LOGGER.info("Animus Camera watching {}", root.resolve("queue"));
	}

	public static void onFrameStart(Minecraft minecraft) {
		Overlay.onFrame(minecraft);
	}

	public static void onFrameRendered(Minecraft minecraft) {
		FrameRecorder active = recorder;
		if (active != null) {
			active.onFrameRendered(minecraft);
		}
	}

	public static boolean toastsEnabled() {
		return toasts;
	}

	public static void setToastsEnabled(boolean value) {
		toasts = value;
	}

	public static FrameRecorder recorder() {
		return recorder;
	}

	public static void setRecorder(FrameRecorder value) {
		recorder = value;
	}
}
