package art.arcane.animus.core.rig;

import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BoneDisplay;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * One active NPC's rig: the document, the display each visible bone shows and its playback. Every runtime binds, ticks
 * and publishes rigs only here, so single-owner and regionized servers animate the same way. Owner only, except
 * {@link #snapshot()}, whose value is immutable and safe to hand to viewers on other threads.
 * <p>
 * Each socket adds an item display after the bone displays. It shows the NPC's item in that equipment slot on the
 * socket's bone, posed like a held or worn item, and the body no longer shows that slot.
 *
 * @param <I> the platform's item stack
 */
public final class NpcRig<I> {
    /** Displays one rig shows at most: every bone and every socket. */
    public static final int MAX_DISPLAYS = RigDocument.MAX_BONES + 8;
    /**
     * What viewers present: the displays, plus the newest planned segment (null before the first plan). The last
     * {@code sockets} displays are socket items; {@code socketRevision} changes whenever one of their items does.
     */
    public record Snapshot<I>(long identity, RigDocument rig, List<BoneDisplay<I>> displays, boolean hideBody, RigFrame frame, int sockets, long socketRevision) {
        public Snapshot { displays = List.copyOf(displays); }
        /** Display indexes of the sockets. */
        public int[] socketIndices() { return java.util.stream.IntStream.range(displays.size() - sockets, displays.size()).toArray(); }
        /** The socket displays, in {@link #socketIndices()} order. */
        public List<BoneDisplay<I>> socketDisplays() { return displays.subList(displays.size() - sockets, displays.size()); }
    }
    /** Turns a definition item into a server stack, or adds a problem and returns null. */
    @FunctionalInterface public interface Items<I> { I item(NpcItem item, String path, List<String> problems); }
    /** Receives animation events as they come due, on the NPC's owner. */
    @FunctionalInterface public interface Events { void fired(UUID npc, RigPlayback.Fired event); }
    /** One owner tick's results. */
    public record Step(List<RigPlayback.Finished> finished, List<RigPlayback.Fired> events) {
        public Step { finished = List.copyOf(finished); events = List.copyOf(events); }
    }

    private static final AtomicLong IDENTITIES = new AtomicLong();
    private final long identity = IDENTITIES.incrementAndGet();
    private final RigDocument rig;
    private final List<BoneDisplay<I>> displays;
    private final RigPlayback playback;
    /** Socket slots in display order, and the source stacks their displays were last built from. */
    private final EquipmentSlot[] slots;
    private final Object[] worn;
    private final I empty;
    private long generation, socketRevision;
    private volatile Snapshot<I> snapshot;

    private NpcRig(RigDocument rig, List<BoneDisplay<I>> displays, RigPlayback playback, long generation, EquipmentSlot[] slots, I empty) {
        this.rig = rig; this.displays = new ArrayList<>(displays); this.playback = playback; this.generation = generation;
        this.slots = slots; this.empty = empty; worn = new Object[slots.length];
        snapshot = snapshot(null);
    }

    /**
     * The definition's rig from the catalog; empty, with a problem, when it is missing or one of its items is unknown here.
     * {@code empty} is the platform's empty stack; without one the rig's sockets are left out.
     */
    public static <I> Optional<NpcRig<I>> bind(String rigId, RigCatalog catalog, SeatGeometry seat, Items<I> items, Optional<I> empty, List<String> problems) {
        return bind(rigId, catalog, seat, 1, items, empty, problems);
    }

    /** As above, for a body of the given {@code scale} attribute; the bones grow with the body. */
    public static <I> Optional<NpcRig<I>> bind(String rigId, RigCatalog catalog, SeatGeometry seat, double scale, Items<I> items, Optional<I> empty, List<String> problems) {
        Optional<RigDocument> found = catalog.rig(rigId);
        if (found.isEmpty()) { problems.add("$.rig.id: " + rigId + " is not a loaded rig"); return Optional.empty(); }
        RigDocument rig = found.get();
        List<BoneDisplay<I>> displays = new ArrayList<>();
        int before = problems.size();
        for (int index : rig.displayBones()) {
            RigDocument.Visual visual = rig.bones().get(index).visual();
            if (visual.type() == RigDocument.Type.ITEM) {
                I stack = items.item(visual.item().orElseThrow(), rig.id() + " bone " + rig.bones().get(index).id() + " item", problems);
                if (stack != null) displays.add(BoneDisplay.item(visual, stack));
            } else displays.add(BoneDisplay.of(visual));
        }
        if (problems.size() != before) return Optional.empty();
        RigDocument shown = empty.isPresent() ? rig : rig.withoutSockets();
        EquipmentSlot[] slots = shown.sockets().keySet().stream().map((String key) -> EquipmentSlot.fromKey(key).orElseThrow()).toArray(EquipmentSlot[]::new);
        for (EquipmentSlot slot : slots) displays.add(socket(slot, empty.orElseThrow()));
        return Optional.of(new NpcRig<>(shown, displays, new RigPlayback(shown, catalog.clips(), seat, scale), catalog.generation(), slots, empty.orElse(null)));
    }

    private static <I> BoneDisplay<I> socket(EquipmentSlot slot, I item) {
        String context = switch (slot) {
            case MAIN_HAND -> "thirdperson_righthand";
            case OFF_HAND -> "thirdperson_lefthand";
            case HEAD -> "head";
            default -> "fixed";
        };
        return new BoneDisplay<>(BoneDisplay.Kind.ITEM, item, BoneDisplay.itemContext(context), null, null, (byte) 0, 0, false, 0);
    }
    private Snapshot<I> snapshot(RigFrame frame) {
        return new Snapshot<>(identity, rig, displays, rig.body() == RigDocument.Body.HIDDEN, frame, slots.length, socketRevision);
    }

    public long identity() { return identity; }
    public RigDocument document() { return rig; }
    public Snapshot<I> snapshot() { return snapshot; }
    public String status() { return playback.status(); }

    /**
     * After a catalog reload: true when this rig stays (same document); its playback then reads the new animations.
     * False means the document changed or left, and the caller binds again and re-presents the body.
     */
    public boolean refresh(RigCatalog catalog, long now) {
        if (catalog.generation() == generation) return true;
        Optional<RigDocument> next = catalog.rig(rig.id());
        if (next.isEmpty() || next.get().revision() != rig.revision() || !next.get().source().equals(rig.source())) return false;
        generation = catalog.generation();
        playback.animations(catalog.clips(), now);
        return true;
    }

    /**
     * Shows the NPC's current equipment on the sockets; an empty or missing slot shows nothing. Cheap when nothing
     * changed, so owners may call it every tick. {@code copy} detaches the stacks handed to viewers.
     */
    public void equip(Map<EquipmentSlot, I> equipment, UnaryOperator<I> copy) {
        boolean changed = false;
        int first = displays.size() - slots.length;
        for (int i = 0; i < slots.length; i++) {
            I item = equipment.get(slots[i]);
            if (item == worn[i]) continue;
            worn[i] = item;
            displays.set(first + i, socket(slots[i], item == null ? empty : copy.apply(item)));
            changed = true;
        }
        if (!changed) return;
        socketRevision++;
        snapshot = snapshot(snapshot.frame());
    }
    /** The equipment the body itself still shows: every slot without a socket. */
    public Map<EquipmentSlot, I> bodyEquipment(Map<EquipmentSlot, I> equipment) {
        if (slots.length == 0) return equipment;
        Map<EquipmentSlot, I> body = new EnumMap<>(EquipmentSlot.class);
        body.putAll(equipment);
        for (EquipmentSlot slot : slots) body.remove(slot);
        return body;
    }

    public void play(String animation, double speed, Optional<AnimationDocument.Loop> loop, int layer, long now) { playback.play(animation, speed, loop, layer, now); }
    public void stop(OptionalInt layer, int fadeTicks, long now) { playback.stop(layer, fadeTicks, now); }

    /** True when a new segment is due; {@link #plan} makes it. */
    public boolean due(long now, float yaw) { return playback.due(now, yaw); }

    /** One owner tick: walking state, events that came due and clips that ended. Cheap; planning is separate so callers can bound it. */
    public Step tick(long now, boolean walking) {
        playback.moving(walking, now);
        List<RigPlayback.Fired> events = playback.events(now);
        return new Step(playback.drainFinished(now), events);
    }

    public void plan(long now, float yaw, int sampleTicks, long nanos) {
        RigFrame frame = playback.plan(now, yaw, sampleTicks, nanos);
        if (frame != null) snapshot = snapshot(frame);
    }
}
