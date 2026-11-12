package com.volmit.animuscamera;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Runs work on the client thread and waits for the result. */
public final class ClientThread {
	private static final long TIMEOUT_SECONDS = 20L;

	private ClientThread() {
	}

	public static <T> T call(Supplier<T> supplier) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			return supplier.get();
		}

		try {
			return minecraft.submit(supplier).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}

			throw new StepFailure(String.valueOf(cause));
		} catch (TimeoutException e) {
			throw new StepFailure("Client thread did not respond within " + TIMEOUT_SECONDS + " s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StepFailure("Interrupted");
		}
	}

	public static void run(Runnable runnable) {
		call(() -> {
			runnable.run();
			return Boolean.TRUE;
		});
	}
}
