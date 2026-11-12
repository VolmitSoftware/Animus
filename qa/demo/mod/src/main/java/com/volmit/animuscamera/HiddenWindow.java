package com.volmit.animuscamera;

import java.nio.ByteBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.macosx.ObjCRuntime;

/** Keeps the game window invisible and stops macOS from giving the process a dock icon or focus. */
public final class HiddenWindow {
	private static final int GLFW_COCOA_MENUBAR = 0x00051002;
	private static final int GLFW_COCOA_RETINA_FRAMEBUFFER = 0x00023001;
	private static final int GLFW_FOCUS_ON_SHOW = 0x0002000C;
	private static final long NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY = 1L;
	private static final long NS_ACTIVITY_USER_INITIATED_ALLOWING_IDLE_SYSTEM_SLEEP = 0x00FFFFFFL;
	private static final long NS_ACTIVITY_LATENCY_CRITICAL = 0xFF00000000L;
	private static long activityToken;

	private HiddenWindow() {
	}

	public static void beforeGlfwInit() {
		if (Platform.get() == Platform.MACOSX) {
			GLFW.glfwInitHint(GLFW_COCOA_MENUBAR, GLFW.GLFW_FALSE);
		}
	}

	public static void afterGlfwInit() {
		if (Platform.get() == Platform.MACOSX) {
			setAccessoryPolicy();
			disableAppNap();
		}
	}

	public static void applyWindowHints() {
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);
	}

	public static void hide(long handle) {
		if (handle != 0L) {
			GLFW.glfwHideWindow(handle);
			int[] width = new int[1];
			int[] height = new int[1];
			GLFW.glfwGetFramebufferSize(handle, width, height);
			AnimusCamera.LOGGER.info("Hidden window created, framebuffer {}x{}", width[0], height[0]);
		}
	}

	private static void setAccessoryPolicy() {
		try {
			long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long app = JNI.invokePPP(ObjCRuntime.objc_getClass("NSApplication"), ObjCRuntime.sel_getUid("sharedApplication"), msgSend);
			JNI.invokePPPP(app, ObjCRuntime.sel_getUid("setActivationPolicy:"), NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY, msgSend);
		} catch (Throwable t) {
			AnimusCamera.LOGGER.warn("Could not set the activation policy", t);
		}
	}

	private static void disableAppNap() {
		if (activityToken != 0L) {
			return;
		}

		try {
			long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long processInfo = JNI.invokePPP(ObjCRuntime.objc_getClass("NSProcessInfo"), ObjCRuntime.sel_getUid("processInfo"), msgSend);
			ByteBuffer reasonText = MemoryUtil.memUTF8("Recording tutorial clips");
			long reason = JNI.invokePPPP(ObjCRuntime.objc_getClass("NSString"), ObjCRuntime.sel_getUid("stringWithUTF8String:"), MemoryUtil.memAddress(reasonText), msgSend);
			long token = JNI.invokePPPPP(
				processInfo,
				ObjCRuntime.sel_getUid("beginActivityWithOptions:reason:"),
				NS_ACTIVITY_USER_INITIATED_ALLOWING_IDLE_SYSTEM_SLEEP | NS_ACTIVITY_LATENCY_CRITICAL,
				reason,
				msgSend
			);
			JNI.invokePPP(token, ObjCRuntime.sel_getUid("retain"), msgSend);
			MemoryUtil.memFree(reasonText);
			activityToken = token;
		} catch (Throwable t) {
			AnimusCamera.LOGGER.warn("Could not disable App Nap", t);
		}
	}
}
