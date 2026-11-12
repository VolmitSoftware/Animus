package com.volmit.animuscamera;

import com.mojang.blaze3d.platform.Window;
import com.volmit.animuscamera.mixin.MouseHandlerAccessor;
import com.volmit.animuscamera.motion.CursorState;
import com.volmit.animuscamera.motion.Easing;
import com.volmit.animuscamera.motion.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Client thread state for the virtual cursor, smooth camera turns and captions.
 * Every method here must run on the client thread.
 */
public final class Overlay {
	private static final int BLACK = 0xFF000000;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int CAPTION_BACKGROUND = 0xD0101010;
	private static final float CURSOR_CELL_PIXELS = 1.7F;
	private static final float CAPTION_TEXT_SCALE = 3.0F;
	private static final int CAPTION_MARGIN_PIXELS = 48;
	private static final int CAPTION_PADDING = 5;
	private static final String[] ARROW = {
		"B...........",
		"BB..........",
		"BWB.........",
		"BWWB........",
		"BWWWB.......",
		"BWWWWB......",
		"BWWWWWB.....",
		"BWWWWWWB....",
		"BWWWWWWWB...",
		"BWWWWWWWWB..",
		"BWWWWWWWWWB.",
		"BWWWWWWBBBBB",
		"BWWWBWWB....",
		"BWWBBWWB....",
		"BWB..BWWB...",
		"BB...BWWB...",
		"B.....BWWB..",
		"......BWWB..",
		".......BB..."
	};

	private static final CursorState CURSOR = new CursorState(0L);
	private static boolean cursorShown = true;
	private static Turn turn;
	private static String caption;
	private static long captionUntil;
	private static Screen lastScreen;
	private static Object lastLevel;
	private static long previousFrameNanos;

	private Overlay() {
	}

	public static void onFrame(Minecraft minecraft) {
		long now = System.nanoTime();
		if (minecraft.level != lastLevel) {
			lastLevel = minecraft.level;
			if (minecraft.level != null) {
				ChatCapture.markJoin(previousFrameNanos);
			}
		}

		previousFrameNanos = now;

		Screen screen = syncScreen(minecraft, now);
		Point cursor = CURSOR.sample(now);

		if (turn != null) {
			LocalPlayer player = minecraft.player;
			if (player == null) {
				turn = null;
			} else {
				double progress = turn.progress(now);
				double eased = Easing.inOut(progress);
				float yaw = (float) (turn.fromYaw + (turn.toYaw - turn.fromYaw) * eased);
				float pitch = Mth.clamp((float) (turn.fromPitch + (turn.toPitch - turn.fromPitch) * eased), -90.0F, 90.0F);
				float yawDelta = yaw - player.getYRot();
				float pitchDelta = pitch - player.getXRot();
				player.setYRot(yaw);
				player.setXRot(pitch);
				player.yRotO += yawDelta;
				player.xRotO += pitchDelta;
				if (progress >= 1.0) {
					turn = null;
				}
			}
		}

		if (screen != null) {
			Window window = minecraft.getWindow();
			double toScreen = window.getScreenWidth() / (double) window.getGuiScaledWidth();
			MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
			mouse.animuscamera$setXpos(cursor.x() * toScreen);
			mouse.animuscamera$setYpos(cursor.y() * toScreen);
		}

		minecraft.getFramerateLimitTracker().onInputReceived();
	}

	/** Tells the cursor about a screen change: a new screen keeps the last position, no screen hides it. */
	private static Screen syncScreen(Minecraft minecraft, long now) {
		Screen screen = minecraft.gui.screen();
		if (screen != lastScreen) {
			lastScreen = screen;
			CURSOR.sample(now);
			if (screen != null) {
				Window window = minecraft.getWindow();
				CURSOR.screenOpened(window.getGuiScaledWidth(), window.getGuiScaledHeight());
			} else {
				CURSOR.screenClosed();
			}
		}

		return screen;
	}

	/**
	 * Glides the cursor from where it is to a GUI position. Returns the glide time in milliseconds:
	 * the requested time when millis is above zero, otherwise a time scaled by the distance.
	 */
	public static long moveCursor(Minecraft minecraft, double x, double y, long millis) {
		long now = System.nanoTime();
		syncScreen(minecraft, now);
		return CURSOR.moveTo(x, y, now, millis);
	}

	/** Restarts the path randomness so a re-recorded script moves the same way. */
	public static void seedCursor(long seed) {
		CURSOR.reseed(seed);
	}

	public static void setCursorShown(boolean shown) {
		cursorShown = shown;
	}

	public static void turnTo(LocalPlayer player, float yaw, float pitch, long millis) {
		float fromYaw = player.getYRot();
		float toYaw = fromYaw + Mth.wrapDegrees(yaw - fromYaw);
		turn = new Turn(fromYaw, player.getXRot(), toYaw, Mth.clamp(pitch, -90.0F, 90.0F), System.nanoTime(), Math.max(1L, millis) * 1_000_000L);
	}

	public static void showCaption(String text, long millis) {
		caption = text;
		captionUntil = System.nanoTime() + millis * 1_000_000L;
	}

	public static void draw(GuiGraphicsExtractor graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean showCursor = cursorShown && CURSOR.visible() && minecraft.gui.screen() != null;
		boolean showCaption = caption != null && System.nanoTime() < captionUntil;
		if (!showCursor && !showCaption) {
			return;
		}

		graphics.nextStratum();
		float toPixels = 1.0F / minecraft.getWindow().getGuiScale();
		if (showCaption) {
			drawCaption(graphics, minecraft, toPixels);
		}

		if (showCursor) {
			graphics.pose().pushMatrix();
			Point cursor = CURSOR.position();
			graphics.pose().translate((float) cursor.x(), (float) cursor.y());
			graphics.pose().scale(toPixels * CURSOR_CELL_PIXELS, toPixels * CURSOR_CELL_PIXELS);
			for (int row = 0; row < ARROW.length; row++) {
				String line = ARROW[row];
				for (int column = 0; column < line.length(); column++) {
					char cell = line.charAt(column);
					if (cell != '.') {
						graphics.fill(column, row, column + 1, row + 1, cell == 'B' ? BLACK : WHITE);
					}
				}
			}

			graphics.pose().popMatrix();
		}
	}

	private static void drawCaption(GuiGraphicsExtractor graphics, Minecraft minecraft, float toPixels) {
		int screenHeight = minecraft.getWindow().getHeight();
		int textWidth = minecraft.font.width(caption);
		int boxWidth = textWidth + CAPTION_PADDING * 2;
		int boxHeight = minecraft.font.lineHeight + CAPTION_PADDING * 2 - 1;
		graphics.pose().pushMatrix();
		graphics.pose().scale(toPixels, toPixels);
		graphics.pose().translate(CAPTION_MARGIN_PIXELS, screenHeight - CAPTION_MARGIN_PIXELS - boxHeight * CAPTION_TEXT_SCALE);
		graphics.pose().scale(CAPTION_TEXT_SCALE, CAPTION_TEXT_SCALE);
		graphics.fill(0, 0, boxWidth, boxHeight, CAPTION_BACKGROUND);
		graphics.text(minecraft.font, caption, CAPTION_PADDING, CAPTION_PADDING, WHITE, false);
		graphics.pose().popMatrix();
	}

	private record Turn(float fromYaw, float fromPitch, float toYaw, float toPitch, long start, long duration) {
		double progress(long now) {
			return Math.min(1.0, (now - this.start) / (double) this.duration);
		}
	}
}
