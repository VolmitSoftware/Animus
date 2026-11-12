package com.volmit.animuscamera.motion;

/** A position in GUI-scaled pixels. */
public record Point(double x, double y) {
	public double distance(Point other) {
		return Math.hypot(other.x - this.x, other.y - this.y);
	}
}
