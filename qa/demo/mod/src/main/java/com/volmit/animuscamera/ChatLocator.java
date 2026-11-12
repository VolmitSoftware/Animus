package com.volmit.animuscamera;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** Finds where a piece of chat text sits on screen while the chat screen is open. */
public final class ChatLocator {
	private ChatLocator() {
	}

	/** Returns the GUI position of the middle of the newest visible chat text containing the needle, or null. */
	public static double[] find(Minecraft minecraft, String needle) {
		Collector collector = new Collector(minecraft.font, needle.toLowerCase(Locale.ROOT));
		minecraft.gui.hud.getChat().captureClickableText(
			collector, minecraft.getWindow().getGuiScaledHeight(), minecraft.gui.hud.getGuiTicks(), ChatComponent.DisplayMode.FOREGROUND
		);
		return collector.match;
	}

	private static final class Collector implements ActiveTextCollector {
		private final Font font;
		private final String needle;
		private ActiveTextCollector.Parameters parameters = new ActiveTextCollector.Parameters(new Matrix3x2f());
		private double[] match;

		Collector(Font font, String needle) {
			this.font = font;
			this.needle = needle;
		}

		@Override
		public ActiveTextCollector.Parameters defaultParameters() {
			return this.parameters;
		}

		@Override
		public void defaultParameters(ActiveTextCollector.Parameters newParameters) {
			this.parameters = newParameters;
		}

		@Override
		public void accept(TextAlignment alignment, int anchorX, int y, ActiveTextCollector.Parameters lineParameters, FormattedCharSequence text) {
			StringBuilder plain = new StringBuilder();
			text.accept((position, style, codepoint) -> {
				plain.appendCodePoint(codepoint);
				return true;
			});
			String visible = plain.toString();
			int index = visible.toLowerCase(Locale.ROOT).indexOf(this.needle);
			if (index < 0) {
				return;
			}

			int startPoints = visible.codePointCount(0, index);
			int endPoints = startPoints + visible.codePointCount(index, index + this.needle.length());
			int startWidth = this.font.width(prefix(text, startPoints));
			int endWidth = this.font.width(prefix(text, endPoints));
			int left = alignment.calculateLeft(anchorX, this.font.width(text));
			float localX = left + (startWidth + endWidth) / 2.0F;
			float localY = y + this.font.lineHeight / 2.0F;
			Matrix3x2fc pose = lineParameters.pose();
			double guiX = pose.m00() * localX + pose.m10() * localY + pose.m20();
			double guiY = pose.m01() * localX + pose.m11() * localY + pose.m21();
			this.match = new double[]{guiX, guiY};
		}

		@Override
		public void acceptScrolling(Component message, int centerX, int left, int right, int top, int bottom, ActiveTextCollector.Parameters scrollParameters) {
		}

		private static FormattedCharSequence prefix(FormattedCharSequence text, int codepoints) {
			return sink -> {
				int[] seen = new int[1];
				return text.accept((position, style, codepoint) -> seen[0]++ < codepoints && sink.accept(position, style, codepoint));
			};
		}
	}
}
