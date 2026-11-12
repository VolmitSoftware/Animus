package com.volmit.animuscamera;

import com.google.gson.JsonObject;
import com.volmit.animuscamera.ScriptRunner.ScriptContext;
import com.volmit.animuscamera.mixin.AbstractContainerScreenAccessor;
import com.volmit.animuscamera.mixin.AnvilScreenAccessor;
import com.volmit.animuscamera.mixin.ChatScreenAccessor;
import com.volmit.animuscamera.mixin.KeyMappingAccessor;
import com.volmit.animuscamera.mixin.MinecraftAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;

/** The script step implementations. Runs on the script thread and hops to the client thread for game state. */
public final class ScriptSteps {
	private static final long CHAR_DELAY_MS = 50L;
	/** Pause after the last typed character, so the finished text is readable before it is sent. */
	private static final long TYPED_HOLD_MS = 1000L;
	private static final long DWELL_AFTER_CLICK_MS = 250L;
	private static final long HOVER_HOLD_MS = 1500L;
	private static final long TAP_MS = 150L;
	private static final long SNEAK_TICKS_MS = 150L;
	private static final long SUGGEST_VISIBLE_MS = 600L;
	private static final int KEY_ENTER = 257;
	private static final int KEY_TAB = 258;
	private static final int KEY_ESCAPE = 256;
	private static final int KEY_E = 69;

	private ScriptSteps() {
	}

	public static void run(JsonObject step, ScriptContext context) {
		Minecraft minecraft = context.minecraft();
		if (step.has("wait")) {
			sleep(step.get("wait").getAsLong());
		} else if (step.has("record")) {
			record(step, context);
		} else if (step.has("chat")) {
			String text = step.get("chat").getAsString();
			chat(minecraft, text, bool(step, "typing", true), bool(step, "send", true), number(step, "hold", 0L), bool(step, "tab", false));
			if (text.startsWith("/tp ") || text.startsWith("/teleport ")) {
				sleep(300L);
				EntitySteps.logPose(context);
			}
		} else if (step.has("key")) {
			key(minecraft, step.get("key").getAsString(), number(step, "hold", TAP_MS), bool(step, "release", true));
		} else if (step.has("look")) {
			JsonObject look = step.getAsJsonObject("look");
			EntitySteps.turn(context, look.get("yaw").getAsFloat(), look.get("pitch").getAsFloat(), number(look, "ms", 600L), -1);
		} else if (step.has("lookAt")) {
			JsonObject target = step.getAsJsonObject("lookAt");
			EntitySteps.lookAt(context, new Vec3(target.get("x").getAsDouble(), target.get("y").getAsDouble(), target.get("z").getAsDouble()), number(target, "ms", 600L));
		} else if (step.has("lookAtEntity")) {
			EntitySteps.lookAtEntity(context, step.get("lookAtEntity").getAsString(), number(step, "ms", 600L));
		} else if (step.has("waitForWorld")) {
			EntitySteps.waitForWorld(context, number(step, "timeout", 120000L));
		} else if (step.has("move")) {
			move(minecraft, step.get("move").getAsString(), number(step, "ms", 800L));
		} else if (step.has("sprint")) {
			boolean sprint = step.get("sprint").getAsBoolean();
			ClientThread.run(() -> minecraft.options.keySprint.setDown(sprint));
		} else if (step.has("sneakHold")) {
			boolean sneak = step.get("sneakHold").getAsBoolean();
			ClientThread.run(() -> minecraft.options.keyShift.setDown(sneak));
		} else if (step.has("hotbar")) {
			hotbar(minecraft, step.get("hotbar").getAsInt());
		} else if (step.has("useItem")) {
			clickWorld(context, false, bool(step, "sneak", false));
		} else if (step.has("attackTarget")) {
			clickWorld(context, true, bool(step, "sneak", false));
		} else if (step.has("clickSlot")) {
			int index = step.get("clickSlot").getAsInt();
			clickSlot(minecraft, context, step, screen -> slotByIndex(screen, index));
		} else if (step.has("clickSlotNamed")) {
			String name = step.get("clickSlotNamed").getAsString();
			clickSlot(minecraft, context, step, screen -> slotByName(screen, name));
		} else if (step.has("hoverSlotNamed")) {
			String name = step.get("hoverSlotNamed").getAsString();
			hoverSlot(minecraft, context, step, screen -> slotByName(screen, name));
		} else if (step.has("hoverSlot")) {
			int index = step.get("hoverSlot").getAsInt();
			hoverSlot(minecraft, context, step, screen -> slotByIndex(screen, index));
		} else if (step.has("anvilText")) {
			anvilText(minecraft, context, step.get("anvilText").getAsString());
		} else if (step.has("clickChat")) {
			chatComponent(minecraft, context, step.get("clickChat").getAsString(), number(step, "glide", 0L), number(step, "rest", context.clickRest()), true);
		} else if (step.has("hoverChat")) {
			chatComponent(minecraft, context, step.get("hoverChat").getAsString(), number(step, "glide", 0L), number(step, "ms", HOVER_HOLD_MS), false);
		} else if (step.has("clickButton")) {
			clickButton(minecraft, context, step.get("clickButton").getAsString(), number(step, "glide", 0L), number(step, "rest", context.clickRest()));
		} else if (step.has("clearChat")) {
			ClientThread.run(() -> minecraft.gui.hud.getChat().clearMessages(true));
		} else if (step.has("closeScreen")) {
			closeScreen(minecraft);
		} else if (step.has("perspective")) {
			perspective(minecraft, step.get("perspective").getAsString());
		} else if (step.has("caption")) {
			String text = step.get("caption").getAsString();
			long millis = number(step, "ms", 2000L);
			ClientThread.run(() -> Overlay.showCaption(text, millis));
		} else if (step.has("cursor")) {
			cursor(step.get("cursor").getAsString());
		} else if (step.has("settings")) {
			settings(step.getAsJsonObject("settings"), context);
		} else if (step.has("log")) {
			context.log("Note: " + step.get("log").getAsString());
		} else if (step.has("waitForScreen")) {
			waitForScreen(minecraft, context, step.get("waitForScreen").getAsString(), number(step, "timeout", 8000L));
		} else if (step.has("waitForChat")) {
			waitForChat(context, step.get("waitForChat").getAsString(), number(step, "timeout", 8000L));
		} else if (step.has("join")) {
			String address = step.get("join").getAsString();
			ClientThread.run(() -> ConnectScreen.startConnecting(
				minecraft.gui.screen(), minecraft, ServerAddress.parseString(address), new ServerData("Animus stage", address, ServerData.Type.OTHER), false, null));
		} else if (step.has("disconnect")) {
			disconnect(minecraft);
		} else if (step.has("quit")) {
			if (AnimusCamera.recorder() != null) {
				stopRecording(context);
			}

			context.requestQuit();
		} else {
			throw new StepFailure("Unknown step " + step);
		}
	}

	private static void record(JsonObject step, ScriptContext context) {
		String action = step.get("record").getAsString();
		Minecraft minecraft = context.minecraft();
		if (action.equals("start")) {
			if (AnimusCamera.recorder() != null) {
				throw new StepFailure("Already recording to " + AnimusCamera.recorder().output());
			}

			if (!step.has("file")) {
				throw new StepFailure("record start needs a file");
			}

			Path file = Path.of(step.get("file").getAsString());
			if (!file.isAbsolute()) {
				throw new StepFailure("record file must be an absolute path");
			}

			FrameRecorder recorder = ClientThread.call(() -> {
				try {
					FrameRecorder started = FrameRecorder.start(minecraft, file);
					AnimusCamera.setRecorder(started);
					return started;
				} catch (IOException e) {
					throw new StepFailure("Could not start recording: " + e.getMessage());
				}
			});
			context.log("Recording " + recorder.width() + "x" + recorder.height() + " to " + file);
		} else if (action.equals("stop")) {
			if (AnimusCamera.recorder() == null) {
				throw new StepFailure("Not recording");
			}

			CompletableFuture<String> clip = stopRecording(context);
			if (bool(step, "wait", false)) {
				try {
					context.log("Clip written " + clip.get(30L, TimeUnit.MINUTES));
				} catch (Exception e) {
					throw new StepFailure("Clip failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
				}
			}
		} else {
			throw new StepFailure("record must be start or stop");
		}
	}

	public static CompletableFuture<String> stopRecording(ScriptContext context) {
		Minecraft minecraft = context.minecraft();
		FrameRecorder recorder = ClientThread.call(() -> {
			FrameRecorder active = AnimusCamera.recorder();
			AnimusCamera.setRecorder(null);
			return active;
		});
		CompletableFuture<String> clip = recorder.stop(minecraft, context.ffmpeg());
		context.addClip(clip);
		context.log("Recording stopped after " + recorder.framesWritten() + " frames, encoding " + recorder.output());
		return clip;
	}

	private static void chat(Minecraft minecraft, String text, boolean typing, boolean submit, long hold, boolean tab) {
		requirePlayer(minecraft);
		if (!typing) {
			ClientThread.run(() -> send(minecraft, text));
			return;
		}

		ChatScreen screen = ClientThread.call(() -> {
			minecraft.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
			if (minecraft.gui.screen() instanceof ChatScreen chatScreen) {
				return chatScreen;
			}

			throw new StepFailure("Chat screen did not open");
		});
		sleep(150L);
		text.codePoints().forEach(codepoint -> {
			ClientThread.run(() -> {
				if (minecraft.gui.screen() != screen) {
					throw new StepFailure("Chat screen closed while typing");
				}

				screen.charTyped(new CharacterEvent(codepoint));
			});
			sleep(CHAR_DELAY_MS);
		});
		if (tab) {
			sleep(200L);
			ClientThread.run(() -> screen.keyPressed(new KeyEvent(KEY_TAB, 0, 0)));
		}
		if (!submit) {
			sleep(hold);
			ClientThread.run(() -> {
				if (minecraft.gui.screen() == screen) {
					((ChatScreenAccessor) screen).animuscamera$getInput().setValue("");
					minecraft.gui.setScreen(null);
				}
			});
			return;
		}
		sleep(TYPED_HOLD_MS);
		submitChat(minecraft, screen);
	}

	private static void submitChat(Minecraft minecraft, ChatScreen screen) {
		ClientThread.run(() -> {
			screen.keyPressed(new KeyEvent(KEY_ENTER, 0, 0));
			if (minecraft.gui.screen() == screen) {
				String value = ((ChatScreenAccessor) screen).animuscamera$getInput().getValue();
				if (!value.isEmpty()) {
					screen.handleChatInput(value, true);
					minecraft.gui.setScreen(null);
				}
			}
		});
	}

	private static void send(Minecraft minecraft, String text) {
		if (text.startsWith("/")) {
			minecraft.player.connection.sendCommand(text.substring(1));
		} else {
			minecraft.player.connection.sendChat(text);
		}
	}

	/** Left or right click into the world, optionally with sneak held from two ticks before to two ticks after. */
	private static void clickWorld(ScriptContext context, boolean attack, boolean sneak) {
		Minecraft minecraft = context.minecraft();
		requirePlayer(minecraft);
		KeyMapping sneakKey = minecraft.options.keyShift;
		if (sneak) {
			ClientThread.run(() -> sneakKey.setDown(true));
			sleep(SNEAK_TICKS_MS);
		}

		try {
			if (!EntitySteps.actOnLastEntity(context, attack)) {
				tap(minecraft, attack ? minecraft.options.keyAttack : minecraft.options.keyUse, TAP_MS);
			}

			if (sneak) {
				sleep(SNEAK_TICKS_MS);
			}
		} finally {
			if (sneak) {
				ClientThread.run(() -> sneakKey.setDown(false));
				context.log("sneak held through click");
			}
		}
	}

	private static void key(Minecraft minecraft, String name, long hold, boolean release) {
		Options options = minecraft.options;
		if (!release) {
			if (!name.equals("sneak")) {
				throw new StepFailure("\"release\": false works only for sneak; release it with {\"sneakHold\": false}");
			}

			ClientThread.run(() -> options.keyShift.setDown(true));
			sleep(hold);
			return;
		}

		switch (name) {
			case "sneak" -> tap(minecraft, options.keyShift, hold);
			case "jump" -> tap(minecraft, options.keyJump, hold);
			case "attack" -> tap(minecraft, options.keyAttack, hold);
			case "use" -> tap(minecraft, options.keyUse, hold);
			case "drop" -> tap(minecraft, options.keyDrop, hold);
			case "swap" -> tap(minecraft, options.keySwapOffhand, hold);
			case "f5" -> tap(minecraft, options.keyTogglePerspective, hold);
			case "inventory" -> {
				boolean handled = ClientThread.call(() -> {
					Screen screen = minecraft.gui.screen();
					if (screen != null) {
						screen.keyPressed(new KeyEvent(KEY_E, 0, 0));
						return true;
					}

					return false;
				});
				if (!handled) {
					tap(minecraft, options.keyInventory, hold);
				}
			}
			case "escape" -> ClientThread.run(() -> {
				Screen screen = minecraft.gui.screen();
				if (screen != null) {
					screen.keyPressed(new KeyEvent(KEY_ESCAPE, 0, 0));
				} else if (minecraft.level != null) {
					minecraft.pauseGame(false);
				}
			});
			default -> throw new StepFailure("Unknown key " + name);
		}
	}

	static void tap(Minecraft minecraft, KeyMapping mapping, long hold) {
		ClientThread.run(() -> {
			if (mapping == minecraft.options.keyAttack) {
				((MinecraftAccessor) minecraft).animuscamera$setMissTime(0);
			}

			mapping.setDown(true);
			KeyMappingAccessor clicks = (KeyMappingAccessor) mapping;
			clicks.animuscamera$setClickCount(clicks.animuscamera$getClickCount() + 1);
		});
		sleep(Math.max(60L, hold));
		ClientThread.run(() -> mapping.setDown(false));
	}

	private static void move(Minecraft minecraft, String direction, long millis) {
		Options options = minecraft.options;
		KeyMapping mapping = switch (direction) {
			case "forward" -> options.keyUp;
			case "back" -> options.keyDown;
			case "left" -> options.keyLeft;
			case "right" -> options.keyRight;
			default -> throw new StepFailure("Unknown move direction " + direction);
		};
		requirePlayer(minecraft);
		ClientThread.run(() -> mapping.setDown(true));
		try {
			sleep(millis);
		} finally {
			ClientThread.run(() -> mapping.setDown(false));
		}
	}

	private static void hotbar(Minecraft minecraft, int slot) {
		if (slot < 0 || slot > 8) {
			throw new StepFailure("hotbar must be 0 to 8");
		}

		requirePlayer(minecraft);
		boolean screenOpen = ClientThread.call(() -> minecraft.gui.screen() != null);
		if (screenOpen) {
			ClientThread.run(() -> minecraft.player.getInventory().setSelectedSlot(slot));
		} else {
			tap(minecraft, minecraft.options.keyHotbarSlots[slot], TAP_MS);
		}
	}

	private interface SlotFinder {
		Slot find(AbstractContainerScreen<?> screen);
	}

	private static Slot slotByIndex(AbstractContainerScreen<?> screen, int index) {
		if (index < 0 || index >= screen.getMenu().slots.size()) {
			throw new StepFailure("Slot " + index + " does not exist, the menu has " + screen.getMenu().slots.size() + " slots");
		}

		return screen.getMenu().getSlot(index);
	}

	private static Slot slotByName(AbstractContainerScreen<?> screen, String name) {
		String needle = name.toLowerCase(Locale.ROOT);
		for (Slot slot : screen.getMenu().slots) {
			if (slot.hasItem()) {
				String itemName = ChatFormatting.stripFormatting(slot.getItem().getHoverName().getString());
				if (itemName != null && itemName.toLowerCase(Locale.ROOT).contains(needle)) {
					return slot;
				}
			}
		}

		throw new StepFailure("No slot holds an item named like '" + name + "'");
	}

	private static void clickSlot(Minecraft minecraft, ScriptContext context, JsonObject step, SlotFinder finder) {
		String button = step.has("button") ? step.get("button").getAsString() : "left";
		if (!button.equals("left") && !button.equals("right")) {
			throw new StepFailure("button must be left or right");
		}

		int buttonNumber = button.equals("left") ? 0 : 1;
		ContainerInput input = bool(step, "sneak", false) ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
		clickSlotWith(minecraft, context, finder, buttonNumber, input, number(step, "glide", 0L), number(step, "rest", context.clickRest()));
	}

	/** Glides to a slot and holds still for "ms" so its tooltip and lore render. "glide" forces the glide time. */
	private static void hoverSlot(Minecraft minecraft, ScriptContext context, JsonObject step, SlotFinder finder) {
		long glide = number(step, "glide", 0L);
		long hold = number(step, "ms", HOVER_HOLD_MS);
		Object[] target = ClientThread.call(() -> {
			if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
				throw new StepFailure("No container screen is open");
			}

			Slot slot = finder.find(screen);
			AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
			long millis = Overlay.moveCursor(minecraft, accessor.animuscamera$getLeftPos() + slot.x + 8.0, accessor.animuscamera$getTopPos() + slot.y + 8.0, glide);
			return new Object[]{slot, slot.hasItem() ? slot.getItem().getHoverName().getString() : "empty", millis};
		});
		context.log("Hovering slot " + ((Slot) target[0]).index + " (" + target[1] + ") for " + hold + " ms");
		sleep((Long) target[2] + hold);
	}

	/** Glides to a slot, rests on it for the hover time so its tooltip shows, then clicks. The cursor stays on the slot. */
	private static void clickSlotWith(
		Minecraft minecraft, ScriptContext context, SlotFinder finder, int buttonNumber, ContainerInput input, long glide, long hover
	) {
		Object[] target = ClientThread.call(() -> {
			if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
				throw new StepFailure("No container screen is open");
			}

			Slot slot = finder.find(screen);
			AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
			double x = accessor.animuscamera$getLeftPos() + slot.x + 8.0;
			double y = accessor.animuscamera$getTopPos() + slot.y + 8.0;
			long millis = Overlay.moveCursor(minecraft, x, y, glide);
			String itemName = slot.hasItem() ? slot.getItem().getHoverName().getString() : "empty";
			return new Object[]{screen, slot, itemName, millis};
		});
		Slot slot = (Slot) target[1];
		context.log("Moving to slot " + slot.index + " (" + target[2] + ")");
		sleep((Long) target[3] + hover);
		ClientThread.run(() -> {
			if (minecraft.gui.screen() != target[0]) {
				throw new StepFailure("The container screen changed before the click");
			}

			((AbstractContainerScreenAccessor) target[0]).animuscamera$slotClicked(slot, slot.index, buttonNumber, input);
		});
		sleep(DWELL_AFTER_CLICK_MS);
	}

	private static void anvilText(Minecraft minecraft, ScriptContext context, String text) {
		AnvilScreen screen = ClientThread.call(() -> {
			if (!(minecraft.gui.screen() instanceof AnvilScreen anvil)) {
				throw new StepFailure("No anvil screen is open");
			}

			if (!anvil.getMenu().getSlot(0).hasItem()) {
				throw new StepFailure("The anvil has no item in its first slot");
			}

			EditBox name = ((AnvilScreenAccessor) anvil).animuscamera$getName();
			name.setValue("");
			return anvil;
		});
		sleep(200L);
		text.codePoints().forEach(codepoint -> {
			ClientThread.run(() -> {
				if (minecraft.gui.screen() != screen) {
					throw new StepFailure("Anvil screen closed while typing");
				}

				((AnvilScreenAccessor) screen).animuscamera$getName().insertText(Character.toString(codepoint));
			});
			sleep(CHAR_DELAY_MS);
		});
		ClientThread.run(() -> {
			String value = ((AnvilScreenAccessor) screen).animuscamera$getName().getValue();
			screen.getMenu().setItemName(value);
			minecraft.player.connection.send(new ServerboundRenameItemPacket(value));
		});
		context.log("Anvil name set to '" + text + "'");
		sleep(TYPED_HOLD_MS);
		clickSlotWith(minecraft, context, anvil -> anvil.getMenu().getSlot(2), 0, ContainerInput.PICKUP, 0L, context.clickRest());
	}

	/** Glides to chat text. A click rests on it for "rest" first; a hover holds still for "ms". */
	private static void chatComponent(Minecraft minecraft, ScriptContext context, String needle, long glide, long dwell, boolean click) {
		requirePlayer(minecraft);
		ChatScreen screen = ClientThread.call(() -> {
			if (!(minecraft.gui.screen() instanceof ChatScreen)) {
				if (minecraft.gui.screen() != null) {
					throw new StepFailure("Another screen is open: " + minecraft.gui.screen().getTitle().getString());
				}

				minecraft.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
			}

			if (minecraft.gui.screen() instanceof ChatScreen chatScreen) {
				return chatScreen;
			}

			throw new StepFailure("Chat screen did not open");
		});
		sleep(200L);
		double[] point = ClientThread.call(() -> ChatLocator.find(minecraft, needle));
		if (point == null) {
			throw new StepFailure("No visible chat line contains '" + needle + "'");
		}

		long moveMillis = ClientThread.call(() -> Overlay.moveCursor(minecraft, point[0], point[1], glide));
		context.log(String.format(Locale.ROOT, "Chat text '%s' at %.0f,%.0f", needle, point[0], point[1]));
		sleep(moveMillis + dwell);
		if (click) {
			ClientThread.run(() -> {
				if (minecraft.gui.screen() != screen) {
					throw new StepFailure("The chat screen closed before the click");
				}

				screen.mouseClicked(new MouseButtonEvent(point[0], point[1], new MouseButtonInfo(0, 0)), false);
			});
			sleep(100L);
			String suggested = ClientThread.call(() -> minecraft.gui.screen() == screen ? ((ChatScreenAccessor) screen).animuscamera$getInput().getValue() : "");
			if (!suggested.isEmpty()) {
				context.log("Chat box now holds '" + suggested + "', sending it");
				sleep(SUGGEST_VISIBLE_MS);
				submitChat(minecraft, screen);
			}

			sleep(DWELL_AFTER_CLICK_MS);
		}
	}

	private static void clickButton(Minecraft minecraft, ScriptContext context, String label, long glide, long hover) {
		String needle = label.toLowerCase(Locale.ROOT);
		Object[] target = ClientThread.call(() -> {
			Screen screen = minecraft.gui.screen();
			if (screen == null) {
				throw new StepFailure("No screen is open");
			}

			AbstractWidget widget = findWidget(screen, needle);
			if (widget == null) {
				throw new StepFailure("No visible button labelled like '" + label + "' on " + screen.getTitle().getString());
			}

			double x = widget.getX() + widget.getWidth() / 2.0;
			double y = widget.getY() + widget.getHeight() / 2.0;
			long millis = Overlay.moveCursor(minecraft, x, y, glide);
			return new Object[]{screen, x, y, widget.getMessage().getString(), millis};
		});
		context.log("Moving to button '" + target[3] + "'");
		sleep((Long) target[4] + hover);
		ClientThread.run(() -> {
			Screen screen = (Screen) target[0];
			if (minecraft.gui.screen() != screen) {
				throw new StepFailure("The screen changed before the click");
			}

			MouseButtonEvent event = new MouseButtonEvent((Double) target[1], (Double) target[2], new MouseButtonInfo(0, 0));
			screen.mouseClicked(event, false);
			if (minecraft.gui.screen() == screen) {
				screen.mouseReleased(event);
			}
		});
		sleep(DWELL_AFTER_CLICK_MS);
	}

	private static AbstractWidget findWidget(ContainerEventHandler parent, String needle) {
		for (GuiEventListener child : parent.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isActive()
				&& widget.getMessage().getString().toLowerCase(Locale.ROOT).contains(needle)) {
				return widget;
			}

			if (child instanceof ContainerEventHandler container) {
				AbstractWidget nested = findWidget(container, needle);
				if (nested != null) {
					return nested;
				}
			}
		}

		return null;
	}

	private static void closeScreen(Minecraft minecraft) {
		ClientThread.run(() -> {
			Screen screen = minecraft.gui.screen();
			if (screen == null) {
				return;
			}

			if (screen instanceof AbstractContainerScreen<?> && minecraft.player != null) {
				minecraft.player.closeContainer();
			} else {
				screen.onClose();
			}
		});
	}

	private static void perspective(Minecraft minecraft, String name) {
		CameraType type = switch (name) {
			case "first" -> CameraType.FIRST_PERSON;
			case "third-back" -> CameraType.THIRD_PERSON_BACK;
			case "third-front" -> CameraType.THIRD_PERSON_FRONT;
			default -> throw new StepFailure("perspective must be first, third-back or third-front");
		};
		ClientThread.run(() -> minecraft.options.setCameraType(type));
	}

	private static void cursor(String mode) {
		switch (mode) {
			case "show" -> ClientThread.run(() -> Overlay.setCursorShown(true));
			case "hide" -> ClientThread.run(() -> Overlay.setCursorShown(false));
			default -> throw new StepFailure("cursor must be show or hide");
		}
	}

	/** Script settings: "seed" restarts the cursor path randomness, "clickRest" sets the rest before each click. */
	private static void settings(JsonObject settings, ScriptContext context) {
		if (settings.has("seed")) {
			long seed = settings.get("seed").getAsLong();
			ClientThread.run(() -> Overlay.seedCursor(seed));
		}

		if (settings.has("clickRest")) {
			context.setClickRest(settings.get("clickRest").getAsLong());
		}
	}

	private static void waitForScreen(Minecraft minecraft, ScriptContext context, String needle, long timeout) {
		String lowerNeedle = needle.toLowerCase(Locale.ROOT);
		String found = waitFor(timeout, () -> {
			Screen screen = minecraft.gui.screen();
			if (screen == null) {
				return null;
			}

			String title = screen.getTitle().getString();
			String className = screen.getClass().getSimpleName();
			return title.toLowerCase(Locale.ROOT).contains(lowerNeedle) || className.toLowerCase(Locale.ROOT).contains(lowerNeedle) ? title + " (" + className + ")" : null;
		}, true);
		if (found == null) {
			String current = ClientThread.call(() -> minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getTitle().getString() + " (" + minecraft.gui.screen().getClass().getSimpleName() + ")");
			throw new StepFailure("Screen '" + needle + "' did not open within " + timeout + " ms, current screen: " + current);
		}

		context.log("Screen open: " + found);
	}

	private static void waitForChat(ScriptContext context, String needle, long timeout) {
		long since = context.chatCursor();
		ChatCapture.Line[] found = new ChatCapture.Line[1];
		waitFor(timeout, () -> {
			found[0] = ChatCapture.findSince(since, needle);
			return found[0] == null ? null : found[0].text();
		}, false);
		if (found[0] == null) {
			throw new StepFailure("Chat '" + needle + "' did not arrive within " + timeout + " ms");
		}

		context.setChatCursor(found[0].nanos() + 1L);
		context.log("Chat seen: " + found[0].text());
	}

	static String waitFor(long timeout, Supplier<String> check, boolean onClient) {
		long deadline = System.nanoTime() + timeout * 1_000_000L;
		while (true) {
			String result = onClient ? ClientThread.call(check) : check.get();
			if (result != null) {
				return result;
			}

			if (System.nanoTime() > deadline) {
				return null;
			}

			sleep(50L);
		}
	}

	private static void disconnect(Minecraft minecraft) {
		boolean connected = ClientThread.call(() -> {
			if (minecraft.level == null) {
				return false;
			}

			minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			return true;
		});
		if (!connected) {
			throw new StepFailure("Not connected to a world");
		}

		waitFor(10_000L, () -> minecraft.level == null ? "disconnected" : null, true);
	}

	static void requirePlayer(Minecraft minecraft) {
		if (!ClientThread.call(() -> minecraft.player != null && minecraft.level != null)) {
			throw new StepFailure("Not in a world");
		}
	}

	private static long number(JsonObject object, String key, long fallback) {
		return object.has(key) ? object.get(key).getAsLong() : fallback;
	}

	private static boolean bool(JsonObject object, String key, boolean fallback) {
		return object.has(key) ? object.get(key).getAsBoolean() : fallback;
	}

	static void sleep(long millis) {
		try {
			Thread.sleep(Math.max(0L, millis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StepFailure("Interrupted");
		}
	}
}
