package com.volmit.animuscamera;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

/** Watches the queue folder, runs scripts one at a time and reports progress in status.json. */
public final class ScriptRunner {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path DEFAULT_FFMPEG = Path.of("ffmpeg");
	private final Path root;
	private final Path queue;
	private final Path done;
	private final Path statusFile;
	private final ScheduledExecutorService statusWriter = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "AnimusCamera-Status");
		thread.setDaemon(true);
		return thread;
	});
	private volatile String state = "starting";
	private volatile String scriptName = "";
	private volatile int stepIndex;
	private volatile int stepCount;
	private volatile String stepText = "";
	private volatile String lastScript = "";
	private volatile String lastResult = "";
	private volatile String lastLog = "";
	private volatile String lastClip = "";

	public ScriptRunner(Path root) {
		this.root = root;
		this.queue = root.resolve("queue");
		this.done = root.resolve("done");
		this.statusFile = root.resolve("status.json");
	}

	public void start() {
		try {
			Files.createDirectories(this.queue);
			Files.createDirectories(this.done);
		} catch (IOException e) {
			AnimusCamera.LOGGER.error("Could not create {}", this.root, e);
		}

		this.loadSettings();
		this.statusWriter.scheduleAtFixedRate(this::writeStatus, 0L, 1L, TimeUnit.SECONDS);
		Thread thread = new Thread(this::loop, "AnimusCamera-Script");
		thread.setDaemon(true);
		thread.start();
	}

	private void loop() {
		while (true) {
			try {
				Thread.sleep(250L);
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft == null || !Boolean.TRUE.equals(ClientThread.call(() -> minecraft.isGameLoadFinished() && minecraft.gui.overlay() == null))) {
					this.state = "loading";
					continue;
				}

				Path next = this.nextScript();
				if (next == null) {
					this.state = "idle";
					continue;
				}

				this.run(minecraft, next);
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				AnimusCamera.LOGGER.error("Script loop error", t);
			}
		}
	}

	private Path nextScript() throws IOException {
		try (Stream<Path> files = Files.list(this.queue)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".json")).min(Comparator.comparing(path -> path.getFileName().toString())).orElse(null);
		}
	}

	private void run(Minecraft minecraft, Path file) throws IOException {
		String name = file.getFileName().toString();
		String baseName = name.substring(0, name.length() - ".json".length());
		ScriptContext context = new ScriptContext(minecraft, this.loadSettings());
		boolean inWorld = ClientThread.call(() -> minecraft.level != null);
		context.setChatCursor(inWorld ? ChatCapture.lastJoinNanos() : System.nanoTime());
		this.scriptName = name;
		this.state = "running";
		this.stepIndex = 0;
		this.stepText = "";
		long seed = baseName.hashCode();
		ClientThread.run(() -> Overlay.seedCursor(seed));
		context.log("Script " + name + " started");
		JsonArray steps = null;
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonArray()) {
				throw new IllegalArgumentException("Script must be a JSON array of steps");
			}

			steps = parsed.getAsJsonArray();
		} catch (Exception e) {
			context.fail("Could not read script: " + e.getMessage());
		}

		if (steps != null) {
			this.stepCount = steps.size();
			for (int i = 0; i < steps.size() && !context.quitRequested(); i++) {
				JsonElement element = steps.get(i);
				this.stepIndex = i + 1;
				this.stepText = element.toString();
				context.log("Step " + (i + 1) + "/" + steps.size() + " " + element);
				try {
					if (!element.isJsonObject()) {
						throw new StepFailure("Step is not a JSON object");
					}

					ScriptSteps.run(element.getAsJsonObject(), context);
				} catch (StepFailure e) {
					context.fail("Step " + (i + 1) + " failed: " + e.getMessage());
				} catch (Throwable t) {
					AnimusCamera.LOGGER.warn("Step error", t);
					context.fail("Step " + (i + 1) + " failed: " + t);
				}
			}
		}

		if (AnimusCamera.recorder() != null) {
			context.log("Recording still running at script end, stopping it");
			ScriptSteps.stopRecording(context);
		}

		this.state = "finishing";
		for (CompletableFuture<String> clip : context.clips()) {
			try {
				String summary = clip.get(30L, TimeUnit.MINUTES);
				context.log("Clip written " + summary);
				this.lastClip = summary;
			} catch (Exception e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				context.fail("Clip failed: " + cause.getMessage());
			}
		}

		String result = context.failed() ? "failed" : "ok";
		context.log("Script " + name + " finished: " + result);
		Path logFile = this.done.resolve(baseName + ".log");
		Files.writeString(logFile, String.join("\n", context.lines()) + "\n", StandardCharsets.UTF_8);
		Files.move(file, this.done.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		this.lastScript = name;
		this.lastResult = result;
		this.lastLog = logFile.toString();
		this.scriptName = "";
		this.stepText = "";
		this.state = context.quitRequested() ? "quitting" : "idle";
		this.writeStatus();
		if (context.quitRequested()) {
			ClientThread.run(minecraft::stop);
		}
	}

	/** Reads settings.json, applies the toast setting and returns the ffmpeg path. */
	private Path loadSettings() {
		Path settings = this.root.resolve("settings.json");
		Path ffmpeg = DEFAULT_FFMPEG;
		boolean toasts = false;
		if (Files.isRegularFile(settings)) {
			try {
				JsonObject object = JsonParser.parseString(Files.readString(settings, StandardCharsets.UTF_8)).getAsJsonObject();
				if (object.has("ffmpeg")) {
					ffmpeg = Path.of(object.get("ffmpeg").getAsString());
				}

				if (object.has("toasts")) {
					toasts = object.get("toasts").getAsBoolean();
				}
			} catch (Exception e) {
				AnimusCamera.LOGGER.warn("Could not read {}", settings, e);
			}
		}

		AnimusCamera.setToastsEnabled(toasts);
		return ffmpeg;
	}

	private synchronized void writeStatus() {
		try {
			FrameRecorder recorder = AnimusCamera.recorder();
			Map<String, Object> status = new LinkedHashMap<>();
			status.put("state", this.state);
			status.put("script", this.scriptName);
			status.put("step", this.stepIndex);
			status.put("steps", this.stepCount);
			status.put("stepText", this.stepText);
			status.put("recording", recorder != null);
			status.put("recordingFile", recorder != null ? recorder.output().toString() : "");
			status.put("frames", recorder != null ? recorder.framesWritten() : 0L);
			status.put("lastScript", this.lastScript);
			status.put("lastResult", this.lastResult);
			status.put("lastLog", this.lastLog);
			status.put("lastClip", this.lastClip);
			status.put("updated", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
			Path temporary = this.statusFile.resolveSibling("status.json.tmp");
			Files.writeString(temporary, GSON.toJson(status), StandardCharsets.UTF_8);
			Files.move(temporary, this.statusFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable t) {
			AnimusCamera.LOGGER.warn("Could not write status", t);
		}
	}

	/** Per script state shared with the steps. */
	public static final class ScriptContext {
		private static final long DEFAULT_CLICK_REST_MS = 400L;
		private final Minecraft minecraft;
		private final Path ffmpeg;
		private final long startNanos = System.nanoTime();
		private final List<String> lines = new ArrayList<>();
		private final List<CompletableFuture<String>> clips = new ArrayList<>();
		private boolean failed;
		private boolean quitRequested;
		private long chatCursor;
		private int lastEntityId = -1;
		private long clickRest = DEFAULT_CLICK_REST_MS;

		ScriptContext(Minecraft minecraft, Path ffmpeg) {
			this.minecraft = minecraft;
			this.ffmpeg = ffmpeg;
		}

		public Minecraft minecraft() {
			return this.minecraft;
		}

		public Path ffmpeg() {
			return this.ffmpeg;
		}

		public void log(String message) {
			String line = String.format("[%8.3f] %s", (System.nanoTime() - this.startNanos) / 1.0E9, message);
			this.lines.add(line);
			AnimusCamera.LOGGER.info(line);
		}

		public void fail(String message) {
			this.failed = true;
			this.log("ERROR " + message);
		}

		public boolean failed() {
			return this.failed;
		}

		public void requestQuit() {
			this.quitRequested = true;
		}

		public boolean quitRequested() {
			return this.quitRequested;
		}

		/** Chat lines at or after this time are visible to the next waitForChat. */
		public long chatCursor() {
			return this.chatCursor;
		}

		public void setChatCursor(long nanos) {
			this.chatCursor = nanos;
		}

		/** The entity the last lookAtEntity step turned to, or -1 after any other turn. */
		public int lastEntityId() {
			return this.lastEntityId;
		}

		public void setLastEntityId(int id) {
			this.lastEntityId = id;
		}

		/** How long the cursor rests on a slot, button or chat text before clicking it. */
		public long clickRest() {
			return this.clickRest;
		}

		public void setClickRest(long millis) {
			this.clickRest = Math.max(0L, millis);
		}

		public void addClip(CompletableFuture<String> clip) {
			this.clips.add(clip);
		}

		List<CompletableFuture<String>> clips() {
			return this.clips;
		}

		List<String> lines() {
			return this.lines;
		}
	}
}
