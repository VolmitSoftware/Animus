package com.volmit.animuscamera.motion;

import java.util.Random;

/**
 * Where the virtual cursor is. It never parks: every glide starts at the current position, a new screen
 * keeps the last position, and the cursor is only hidden while no screen is open. Paths come from a seeded
 * random source so a re-record of the same script moves the same way.
 */
public final class CursorState {
	private static final long NANOS_PER_MILLI = 1_000_000L;
	private Random random;
	private Point position;
	private boolean visible;
	private CursorPath path;
	private long pathStart;

	public CursorState(long seed) {
		this.random = new Random(seed);
	}

	public void reseed(long seed) {
		this.random = new Random(seed);
	}

	public void screenOpened(double width, double height) {
		if (this.position == null) {
			this.position = new Point(width / 2.0, height / 2.0);
		} else {
			this.position = new Point(clamp(this.position.x(), width), clamp(this.position.y(), height));
		}

		this.path = null;
		this.visible = true;
	}

	public void screenClosed() {
		this.path = null;
		this.visible = false;
	}

	public boolean visible() {
		return this.visible;
	}

	/** Starts a glide from the current position. Returns its length in milliseconds; millis above zero force the length. */
	public long moveTo(double x, double y, long nowNanos, long millis) {
		Point from = this.sample(nowNanos);
		Point target = new Point(x, y);
		long length = millis > 0L ? millis : CursorPath.durationMillis(from.distance(target));
		this.path = CursorPath.plan(from, target, length, this.random);
		this.pathStart = nowNanos;
		return length;
	}

	/** Advances the glide to the given time and returns the position. */
	public Point sample(long nowNanos) {
		if (this.path != null) {
			long length = this.path.millis() * NANOS_PER_MILLI;
			double progress = length <= 0L ? 1.0 : (nowNanos - this.pathStart) / (double) length;
			this.position = this.path.at(progress);
			if (progress >= 1.0) {
				this.path = null;
			}
		}

		return this.position();
	}

	public Point position() {
		return this.position == null ? new Point(0.0, 0.0) : this.position;
	}

	public boolean moving(long nowNanos) {
		return this.path != null && nowNanos < this.pathStart + this.path.millis() * NANOS_PER_MILLI;
	}

	private static double clamp(double value, double size) {
		return Math.max(0.0, Math.min(size - 1.0, value));
	}
}
