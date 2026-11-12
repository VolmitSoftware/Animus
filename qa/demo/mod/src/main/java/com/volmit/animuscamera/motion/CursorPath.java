package com.volmit.animuscamera.motion;

import java.util.Random;

/**
 * One glide of the virtual cursor: a cubic Bezier bent slightly to one side, eased in and out,
 * that passes long targets by a few pixels and settles back onto them.
 */
public final class CursorPath {
	public static final long MIN_MILLIS = 180L;
	public static final long MAX_MILLIS = 900L;
	/** Moves at least this long (GUI pixels) overshoot. */
	public static final double OVERSHOOT_DISTANCE = 150.0;
	public static final double MAX_OVERSHOOT = 6.0;
	private static final double BASE_MILLIS = 110.0;
	private static final double MILLIS_PER_BIT = 150.0;
	private static final double TARGET_WIDTH = 12.0;
	private static final double MAX_BEND = 28.0;
	private static final double SETTLE_SHARE = 0.18;

	private final Point from;
	private final Point control1;
	private final Point control2;
	private final Point end;
	private final Point to;
	private final long millis;
	private final boolean overshoots;

	private CursorPath(Point from, Point control1, Point control2, Point end, Point to, long millis, boolean overshoots) {
		this.from = from;
		this.control1 = control1;
		this.control2 = control2;
		this.end = end;
		this.to = to;
		this.millis = millis;
		this.overshoots = overshoots;
	}

	/** Fitts-like glide time for a distance in GUI pixels, clamped; zero when there is nothing to move. */
	public static long durationMillis(double distance) {
		if (distance < 0.5) {
			return 0L;
		}

		double millis = BASE_MILLIS + MILLIS_PER_BIT * (Math.log1p(distance / TARGET_WIDTH) / Math.log(2.0));
		return Math.max(MIN_MILLIS, Math.min(MAX_MILLIS, Math.round(millis)));
	}

	public static CursorPath plan(Point from, Point to, long millis, Random random) {
		double distance = from.distance(to);
		if (distance < 0.5) {
			return new CursorPath(from, from, to, to, to, Math.max(0L, millis), false);
		}

		double ux = (to.x() - from.x()) / distance;
		double uy = (to.y() - from.y()) / distance;
		double side = random.nextBoolean() ? 1.0 : -1.0;
		double bend = Math.min(MAX_BEND, distance * (0.04 + random.nextDouble() * 0.06)) * side;
		double tail = 0.5 + random.nextDouble() * 0.4;
		boolean overshoots = distance >= OVERSHOOT_DISTANCE;
		double overshoot = overshoots ? Math.min(MAX_OVERSHOOT, distance * 0.025) * (0.7 + random.nextDouble() * 0.3) : 0.0;
		Point end = new Point(to.x() + ux * overshoot, to.y() + uy * overshoot);
		Point control1 = new Point(from.x() + ux * distance * 0.3 - uy * bend, from.y() + uy * distance * 0.3 + ux * bend);
		Point control2 = new Point(from.x() + ux * distance * 0.7 - uy * bend * tail, from.y() + uy * distance * 0.7 + ux * bend * tail);
		return new CursorPath(from, control1, control2, end, to, Math.max(0L, millis), overshoots);
	}

	/** Position at a share of the glide time (0 to 1). */
	public Point at(double progress) {
		if (progress <= 0.0) {
			return this.from;
		}

		if (progress >= 1.0) {
			return this.to;
		}

		if (!this.overshoots) {
			return this.bezier(Easing.inOut(progress));
		}

		double glide = 1.0 - SETTLE_SHARE;
		if (progress < glide) {
			return this.bezier(Easing.inOut(progress / glide));
		}

		double settle = Easing.inOut((progress - glide) / SETTLE_SHARE);
		return new Point(this.end.x() + (this.to.x() - this.end.x()) * settle, this.end.y() + (this.to.y() - this.end.y()) * settle);
	}

	public long millis() {
		return this.millis;
	}

	public Point to() {
		return this.to;
	}

	private Point bezier(double t) {
		double u = 1.0 - t;
		double a = u * u * u;
		double b = 3.0 * u * u * t;
		double c = 3.0 * u * t * t;
		double d = t * t * t;
		return new Point(
			a * this.from.x() + b * this.control1.x() + c * this.control2.x() + d * this.end.x(),
			a * this.from.y() + b * this.control1.y() + c * this.control2.y() + d * this.end.y()
		);
	}
}
