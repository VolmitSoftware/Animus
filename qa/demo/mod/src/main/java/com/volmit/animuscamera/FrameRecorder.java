package com.volmit.animuscamera;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

/**
 * Captures the main render target on a 30 fps wall clock.
 * Frames are read back from the GPU, flipped, JPEG encoded on worker threads and appended to an MJPEG
 * stream on disk. When recording stops, ffmpeg turns that stream into the final VP9 webm.
 */
public final class FrameRecorder {
	public static final int FPS = 30;
	private static final long SLOT_NANOS = 1_000_000_000L / FPS;
	private static final long CAPTURE_SPACING_NANOS = SLOT_NANOS * 8 / 10;
	private static final long WRITER_DELAY_NANOS = 400_000_000L;
	private static final int GPU_RING_SIZE = 3;
	private static final int PIXEL_POOL_SIZE = 8;
	private static final int ENCODER_THREADS = 3;
	private static final int JPEG_QUALITY = 92;
	private static final int TARGET_WIDTH = 1920;
	private static final int TARGET_HEIGHT = 1080;

	private final Path output;
	private final Path mjpegFile;
	private final Path ffmpegLog;
	private final int width;
	private final int height;
	private final int stride;
	private final GpuBuffer[] ring = new GpuBuffer[GPU_RING_SIZE];
	private final boolean[] ringBusy = new boolean[GPU_RING_SIZE];
	private final List<ByteBuffer> allPixels = new ArrayList<>();
	private final ArrayBlockingQueue<ByteBuffer> freePixels = new ArrayBlockingQueue<>(PIXEL_POOL_SIZE);
	private final ConcurrentSkipListMap<Long, byte[]> encoded = new ConcurrentSkipListMap<>();
	private final ExecutorService encoders;
	private final AtomicInteger pendingFrames = new AtomicInteger();
	private final AtomicLong framesWritten = new AtomicLong();
	private final AtomicLong framesCaptured = new AtomicLong();
	private final AtomicLong framesDropped = new AtomicLong();
	private final CompletableFuture<String> finished = new CompletableFuture<>();
	private final OutputStream mjpegOut;
	private final Thread writer;
	private final long startNanos;
	private volatile long stopNanos = Long.MAX_VALUE;
	private volatile boolean capturing = true;
	private long lastCaptureNanos;

	private FrameRecorder(Path output, int width, int height) throws IOException {
		this.output = output;
		this.width = width;
		this.height = height;
		this.stride = width * 4;
		this.mjpegFile = output.resolveSibling(output.getFileName() + ".mjpeg");
		this.ffmpegLog = output.resolveSibling(output.getFileName() + ".ffmpeg.log");
		Files.createDirectories(output.toAbsolutePath().getParent());
		this.mjpegOut = new BufferedOutputStream(Files.newOutputStream(this.mjpegFile), 1 << 20);
		for (int i = 0; i < GPU_RING_SIZE; i++) {
			this.ring[i] = RenderSystem.getDevice().createBuffer(() -> "Animus camera readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) this.stride * height);
		}

		for (int i = 0; i < PIXEL_POOL_SIZE; i++) {
			ByteBuffer pixels = MemoryUtil.memAlloc(this.stride * height);
			this.allPixels.add(pixels);
			this.freePixels.add(pixels);
		}

		this.encoders = Executors.newFixedThreadPool(ENCODER_THREADS, runnable -> {
			Thread thread = new Thread(runnable, "AnimusCamera-Encoder");
			thread.setDaemon(true);
			return thread;
		});
		this.startNanos = System.nanoTime();
		this.writer = new Thread(this::writeLoop, "AnimusCamera-Writer");
		this.writer.setDaemon(true);
		this.writer.start();
	}

	/** Starts a recording. Must run on the render thread. */
	public static FrameRecorder start(Minecraft minecraft, Path output) throws IOException {
		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		return new FrameRecorder(output, target.width, target.height);
	}

	public Path output() {
		return this.output;
	}

	public long framesWritten() {
		return this.framesWritten.get();
	}

	public long framesDropped() {
		return this.framesDropped.get();
	}

	public long framesCaptured() {
		return this.framesCaptured.get();
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	/** Called on the render thread after each frame has been rendered. */
	public void onFrameRendered(Minecraft minecraft) {
		if (!this.capturing) {
			return;
		}

		long now = System.nanoTime();
		if (now - this.lastCaptureNanos < CAPTURE_SPACING_NANOS) {
			return;
		}

		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture texture = target.getColorTexture();
		if (texture == null || target.width != this.width || target.height != this.height) {
			this.framesDropped.incrementAndGet();
			return;
		}

		int slot = -1;
		for (int i = 0; i < GPU_RING_SIZE; i++) {
			if (!this.ringBusy[i]) {
				slot = i;
				break;
			}
		}

		ByteBuffer pixels = slot < 0 ? null : this.freePixels.poll();
		if (pixels == null) {
			this.framesDropped.incrementAndGet();
			return;
		}

		this.lastCaptureNanos = now;
		this.ringBusy[slot] = true;
		this.pendingFrames.incrementAndGet();
		int ringIndex = slot;
		GpuBuffer buffer = this.ring[ringIndex];
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
			try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
				long source = MemoryUtil.memAddress(view.data());
				long destination = MemoryUtil.memAddress(pixels);
				for (int y = 0; y < this.height; y++) {
					MemoryUtil.memCopy(source + (long) (this.height - 1 - y) * this.stride, destination + (long) y * this.stride, this.stride);
				}
			} catch (Throwable t) {
				AnimusCamera.LOGGER.warn("Frame readback failed", t);
				this.ringBusy[ringIndex] = false;
				this.freePixels.offer(pixels);
				this.pendingFrames.decrementAndGet();
				return;
			}

			this.ringBusy[ringIndex] = false;
			this.encoders.execute(() -> this.encode(now, pixels));
		}, 0);
	}

	private void encode(long capturedNanos, ByteBuffer pixels) {
		try {
			byte[] jpeg = JpegEncoder.encode(pixels, this.width, this.height);
			if (jpeg != null) {
				this.encoded.put(capturedNanos, jpeg);
				this.framesCaptured.incrementAndGet();
			} else {
				this.framesDropped.incrementAndGet();
			}
		} finally {
			this.freePixels.offer(pixels);
			this.pendingFrames.decrementAndGet();
		}
	}

	private void writeLoop() {
		long slotIndex = 0L;
		byte[] last = null;
		try {
			while (true) {
				long slotTime = this.startNanos + slotIndex * SLOT_NANOS;
				if (slotTime >= this.stopNanos) {
					break;
				}

				while (System.nanoTime() < slotTime + WRITER_DELAY_NANOS && !(this.stopNanos != Long.MAX_VALUE && this.pendingFrames.get() == 0)) {
					Thread.sleep(5L);
				}

				Map.Entry<Long, byte[]> entry = this.encoded.floorEntry(slotTime);
				if (entry == null) {
					entry = this.encoded.firstEntry();
				}

				byte[] frame = entry != null ? entry.getValue() : last;
				if (frame == null) {
					if (this.stopNanos != Long.MAX_VALUE && this.pendingFrames.get() == 0) {
						break;
					}

					Thread.sleep(5L);
					continue;
				}

				if (entry != null) {
					this.encoded.headMap(entry.getKey()).clear();
				}

				this.mjpegOut.write(frame);
				last = frame;
				slotIndex++;
				this.framesWritten.set(slotIndex);
			}
		} catch (Throwable t) {
			AnimusCamera.LOGGER.error("Frame writer failed", t);
		}
	}

	/** Stops capturing and transcodes in the background. The future completes with a summary or fails. */
	public CompletableFuture<String> stop(Minecraft minecraft, Path ffmpeg) {
		if (!this.capturing) {
			return this.finished;
		}

		this.capturing = false;
		this.stopNanos = System.nanoTime();
		Thread finisher = new Thread(() -> this.finish(minecraft, ffmpeg), "AnimusCamera-Finisher");
		finisher.setDaemon(false);
		finisher.start();
		return this.finished;
	}

	public CompletableFuture<String> finished() {
		return this.finished;
	}

	private void finish(Minecraft minecraft, Path ffmpeg) {
		try {
			this.writer.join();
			long pendingDeadline = System.nanoTime() + 10_000_000_000L;
			while (this.pendingFrames.get() > 0 && System.nanoTime() < pendingDeadline) {
				Thread.sleep(10L);
			}

			this.encoders.shutdown();
			this.encoders.awaitTermination(30L, TimeUnit.SECONDS);
			this.mjpegOut.close();
			minecraft.execute(this::releaseNativeResources);
			long frames = this.framesWritten.get();
			if (frames == 0L) {
				throw new IOException("No frames were captured");
			}

			List<String> command = new ArrayList<>(List.of(
				ffmpeg.toString(), "-y", "-hide_banner", "-loglevel", "warning",
				"-f", "image2pipe", "-framerate", Integer.toString(FPS), "-c:v", "mjpeg", "-i", this.mjpegFile.toString()
			));
			if (this.width != TARGET_WIDTH || this.height != TARGET_HEIGHT) {
				command.addAll(List.of("-vf", "scale=" + TARGET_WIDTH + ":" + TARGET_HEIGHT + ":flags=lanczos"));
			}

			command.addAll(List.of(
				"-pix_fmt", "yuv420p", "-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "30", "-row-mt", "1", "-threads", "4", "-an",
				this.output.toString()
			));
			Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(this.ffmpegLog.toFile())
				.start();
			int exit = process.waitFor();
			if (exit != 0) {
				throw new IOException("ffmpeg exited with " + exit + ", see " + this.ffmpegLog);
			}

			Files.deleteIfExists(this.mjpegFile);
			if (Files.size(this.ffmpegLog) == 0L) {
				Files.deleteIfExists(this.ffmpegLog);
			}

			String summary = String.format(
				"%s: %d frames (%.2f s) from %dx%d, %d captured, %d dropped",
				this.output, frames, frames / (double) FPS, this.width, this.height, this.framesCaptured.get(), this.framesDropped.get()
			);
			this.finished.complete(summary);
		} catch (Throwable t) {
			this.finished.completeExceptionally(t);
		}
	}

	private void releaseNativeResources() {
		for (GpuBuffer buffer : this.ring) {
			if (buffer != null && !buffer.isClosed()) {
				buffer.close();
			}
		}

		for (ByteBuffer pixels : this.allPixels) {
			MemoryUtil.memFree(pixels);
		}

		this.allPixels.clear();
		this.freePixels.clear();
	}

	/** JPEG encoding through stb with one native callback per encoder thread. */
	private static final class JpegEncoder {
		private static final ThreadLocal<JpegEncoder> LOCAL = ThreadLocal.withInitial(JpegEncoder::new);
		private final STBIWriteCallback callback;
		private byte[] buffer = new byte[1 << 20];
		private int size;

		private JpegEncoder() {
			this.callback = STBIWriteCallback.create((context, data, length) -> this.append(data, length));
		}

		static byte[] encode(ByteBuffer pixels, int width, int height) {
			JpegEncoder encoder = LOCAL.get();
			encoder.size = 0;
			int ok = STBImageWrite.stbi_write_jpg_to_func(encoder.callback, 0L, width, height, 4, pixels, JPEG_QUALITY);
			if (ok == 0) {
				return null;
			}

			byte[] result = new byte[encoder.size];
			System.arraycopy(encoder.buffer, 0, result, 0, encoder.size);
			return result;
		}

		private void append(long data, int length) {
			if (this.size + length > this.buffer.length) {
				byte[] grown = new byte[Math.max(this.buffer.length * 2, this.size + length)];
				System.arraycopy(this.buffer, 0, grown, 0, this.size);
				this.buffer = grown;
			}

			MemoryUtil.memByteBuffer(data, length).get(this.buffer, this.size, length);
			this.size += length;
		}
	}
}
