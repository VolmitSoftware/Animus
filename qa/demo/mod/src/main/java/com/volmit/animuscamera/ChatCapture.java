package com.volmit.animuscamera;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Remembers recent chat lines, system messages included, so scripts can wait for server replies. */
public final class ChatCapture {
	private static final int MAX_LINES = 500;
	private static final Deque<Line> LINES = new ArrayDeque<>();
	private static volatile long lastJoinNanos;

	private ChatCapture() {
	}

	public static synchronized void record(String text) {
		LINES.addLast(new Line(System.nanoTime(), text));
		while (LINES.size() > MAX_LINES) {
			LINES.removeFirst();
		}
	}

	/** Marks the moment the client entered a world, so the join line counts for the next wait. */
	public static void markJoin(long beforeJoinNanos) {
		lastJoinNanos = beforeJoinNanos;
	}

	public static long lastJoinNanos() {
		return lastJoinNanos;
	}

	/** Returns the first line at or after the given time that contains the needle, or null. */
	public static synchronized Line findSince(long sinceNanos, String needle) {
		String lowerNeedle = needle.toLowerCase(Locale.ROOT);
		for (Line line : LINES) {
			if (line.nanos() >= sinceNanos && line.text().toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
				return line;
			}
		}

		return null;
	}

	public record Line(long nanos, String text) {
	}
}
