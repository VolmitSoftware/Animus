package art.arcane.animus.bukkit.recording;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * One player's recording: samples the player every {@code sampleTicks} on its owner, and replays a ghost behind it
 * every {@code ghostIntervalTicks}. Ended sessions wait for their save; a save refused for its size holds the session
 * until it is stopped again or cancelled. Owner thread only.
 */
final class RecordingSession {
    enum State { RECORDING, SAVING, HELD, DONE }

    final Player player;
    final CommandActor actor;
    final RecordingCapture capture;
    final UUID world;
    private final Map<EquipmentSlot, ItemStack> stacks = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
    State state = State.RECORDING;
    AutoCloseable task;
    GhostReplay<Player, ItemStack> ghost;
    RecordingCapture.End ended;

    RecordingSession(Player player, CommandActor actor, String id, RecordingSettings settings) {
        this.player = player;
        this.actor = actor;
        world = player.getWorld().getUID();
        capture = new RecordingCapture(id, player.getUniqueId(), settings.sampleTicks(), sample());
    }

    /**
     * Reads the player directly; equipment converts only when a slot's stack changed since the last sample. On-ground is
     * the client's own claim, deprecated for anti-cheat use, which is exactly what playback should repeat.
     */
    @SuppressWarnings("deprecation")
    RecordingCapture.Sample sample() {
        Location at = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        for (EquipmentSlot slot : RecordingEvent.SLOTS) {
            ItemStack stack = RecorderLook.stack(inventory, slot);
            ItemStack before = stacks.get(slot);
            if (items.containsKey(slot) && (stack == null ? before == null : stack.equals(before))) continue;
            stacks.put(slot, stack == null ? null : stack.clone());
            items.put(slot, RecorderLook.item(stack));
        }
        return new RecordingCapture.Sample(player.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                player.isOnGround(), player.isSneaking(), player.isSprinting(), player.isSwimming(), player.isGliding(), items);
    }

    /**
     * One tick while recording: the end the player's state calls for, if any. Quit, death and world change are checked
     * every tick; position, teleports and equipment every sample.
     */
    Optional<RecordingCapture.End> tick(int maximumTicks) {
        if (!player.isOnline()) return Optional.of(RecordingCapture.End.QUIT);
        if (player.isDead()) return Optional.of(RecordingCapture.End.DEATH);
        if (!player.getWorld().getUID().equals(world)) return Optional.of(RecordingCapture.End.WORLD);
        if (!capture.advance()) return Optional.empty();
        return capture.sample(sample(), maximumTicks);
    }

    boolean ghostRunning() { return ghost != null && !ghost.finished(); }

    Optional<RecordingDocument> ghostDue(RecordingSettings settings) {
        return capture.ghost(settings.ghostIntervalTicks(), settings.ghostStretchTicks(), ghostRunning());
    }

    String describe() {
        String status = state == State.HELD ? "held, not saved" : state.name().toLowerCase(Locale.ROOT);
        return capture.id() + " by " + player.getName() + ": " + status + ", " + capture.tick() + " ticks, " + capture.frames() + " frames, "
                + capture.events() + " events" + (capture.dropped() > 0 ? " (" + capture.dropped() + " dropped)" : "");
    }
}
