package com.volmit.animuscamera;

/** A script step that could not complete. The script logs it and moves on. */
public final class StepFailure extends RuntimeException {
	public StepFailure(String message) {
		super(message);
	}
}
