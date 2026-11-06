package art.arcane.animus.bukkit.recording;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingPlayback;
import art.arcane.animus.core.recording.RecordingPose;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One appearance of a ghost: a viewer-private player body that plays a recording once at recorded speed, then
 * despawns. Each tick sends at most one move (a position sync and a head packet), plus the recording's swings and
 * equipment changes; a move still in flight is never stacked. Sneaking, swimming and gliding are the body's pose, so a
 * pose change respawns the body with a fresh identity. Call every method on the viewer's owner; {@code completions}
 * brings transport callbacks back there.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public final class GhostReplay<P, I> {
    /** Builds the bodies and items of one ghost. */
    public interface Bodies<I> {
        /** A fresh player body showing {@code pose}, with its own identity. */
        BodyTransport.Body create(NpcPose pose);
        /** The stack for a recorded item; empty clears the slot. */
        I item(Optional<NpcItem> item);
        /** The world key the transport expects in {@link BodyPose#world()}. */
        String world();
    }

    private final BodyTransport<P, I> transport;
    private final P viewer;
    private final RecordingPlayback playback;
    private final Bodies<I> bodies;
    private final Consumer<Runnable> completions;
    private final Map<EquipmentSlot, Optional<NpcItem>> worn = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Optional<NpcItem>> changed = new EnumMap<>(EquipmentSlot.class);
    private BodyTransport.Body body;
    private NpcPose pose;
    private RecordingPose sent;
    private boolean started, visible, moving, finished;

    public GhostReplay(BodyTransport<P, I> transport, P viewer, RecordingDocument document, Bodies<I> bodies, Consumer<Runnable> completions) {
        this.transport = Objects.requireNonNull(transport);
        this.viewer = Objects.requireNonNull(viewer);
        this.bodies = Objects.requireNonNull(bodies);
        this.completions = Objects.requireNonNull(completions);
        playback = new RecordingPlayback(document, Playback.ONCE, 0);
    }

    /** The first call shows the body at tick 0; each later call plays one more tick. The last tick despawns it. */
    public void tick() {
        if (finished) return;
        try {
            if (started) playback.advance();
            started = true;
            RecordingPose at = playback.pose();
            NpcPose wanted = pose(at);
            boolean swung = false;
            for (RecordingEvent event : playback.due()) {
                if (event instanceof RecordingEvent.Equipment equipment) { worn.put(equipment.slot(), equipment.item()); changed.put(equipment.slot(), equipment.item()); }
                else if (event instanceof RecordingEvent.Swing) swung = true;
            }
            if (body == null || wanted != pose) show(at, wanted);
            else if (visible) {
                if (!moving && !at.equals(sent)) move(at);
                if (swung) transport.swing(viewer, body);
                if (!changed.isEmpty()) dress(changed);
            }
            if (playback.finished()) close();
        } catch (IllegalStateException | IllegalArgumentException gone) { close(); }
    }

    /** True once the recording played out or the ghost was closed. */
    public boolean finished() { return finished; }

    /** Despawns the body now. */
    public void close() {
        if (finished) return;
        finished = true;
        BodyTransport.Body shown = body;
        body = null;
        visible = false;
        if (shown == null) return;
        try { transport.despawn(viewer, shown); }
        catch (RuntimeException closed) { /* A closed transport already removed every body of this viewer. */ }
    }

    private void show(RecordingPose at, NpcPose wanted) {
        if (body != null) transport.despawn(viewer, body);
        BodyTransport.Body created = bodies.create(wanted);
        body = created;
        pose = wanted;
        visible = false;
        moving = false;
        sent = at;
        changed.clear();
        observe(transport.spawn(viewer, created, bodyPose(at)), () -> {
            if (body != created) return;
            visible = true;
            Map<EquipmentSlot, Optional<NpcItem>> dressed = new EnumMap<>(EquipmentSlot.class);
            for (Map.Entry<EquipmentSlot, Optional<NpcItem>> slot : worn.entrySet()) if (slot.getValue().isPresent()) dressed.put(slot.getKey(), slot.getValue());
            if (!dressed.isEmpty()) dress(dressed);
        });
    }

    private void move(RecordingPose at) {
        BodyTransport.Body moved = body;
        moving = true;
        sent = at;
        observe(transport.move(viewer, moved, bodyPose(at)), () -> { if (body == moved) moving = false; });
    }

    private void dress(Map<EquipmentSlot, Optional<NpcItem>> slots) {
        if (!transport.equipmentAvailable()) { changed.clear(); return; }
        Map<EquipmentSlot, I> items = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<EquipmentSlot, Optional<NpcItem>> slot : slots.entrySet()) items.put(slot.getKey(), bodies.item(slot.getValue()));
        changed.clear();
        observe(transport.equipment(viewer, body, items), () -> {});
    }

    /** Runs {@code done} on the owner after the packets left; any failure ends the ghost, which is cosmetic. */
    private void observe(CompletionStage<Void> stage, Runnable done) {
        stage.whenComplete((Void nothing, Throwable failure) -> completions.accept(() -> {
            if (finished) return;
            if (failure != null) close();
            else done.run();
        }));
    }

    private BodyPose bodyPose(RecordingPose at) {
        return new BodyPose(bodies.world(), at.x(), at.y(), at.z(), (float) at.yaw(), (float) at.pitch(), at.onGround());
    }

    /** Player bodies have no gliding pose; a glide shows as the horizontal swimming pose. */
    static NpcPose pose(RecordingPose at) {
        if (at.swimming() || at.gliding()) return NpcPose.SWIMMING;
        return at.sneaking() ? NpcPose.SNEAKING : NpcPose.STANDING;
    }
}
