package com.volmit.animuscamera.motion;

/** Sine ease in and out: starts and ends at rest, peaks at about 1.6 times the mean speed. */
public final class Easing {
	private Easing() {
	}

	public static double inOut(double t) {
		double clamped = Math.max(0.0, Math.min(1.0, t));
		return (1.0 - Math.cos(Math.PI * clamped)) / 2.0;
	}
}
