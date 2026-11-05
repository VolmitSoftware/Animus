package art.arcane.animus.core.rig;

import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BoneDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One active NPC's rig: the document, the display each visible bone shows and its playback. Every runtime binds, ticks
 * and publishes rigs only here, so single-owner and regionized servers animate the same way. Owner only, except
 * {@link #snapshot()}, whose value is immutable and safe to hand to viewers on other threads.
 *
 * @param <I> the platform's item stack
 */
public final class NpcRig<I> {
    /** What viewers present: fixed displays, plus the newest planned segment (null before the first plan). */
    public record Snapshot<I>(long identity, RigDocument rig, List<BoneDisplay<I>> displays, boolean hideBody, RigFrame frame) {
        public Snapshot { displays = List.copyOf(displays); }
    }
    /** Turns a definition item into a server stack, or adds a problem and returns null. */
    @FunctionalInterface public interface Items<I> { I item(NpcItem item, String path, List<String> problems); }

    private static final AtomicLong IDENTITIES = new AtomicLong();
    private final long identity = IDENTITIES.incrementAndGet();
    private final RigDocument rig;
    private final List<BoneDisplay<I>> displays;
    private final RigPlayback playback;
    private long generation;
    private volatile Snapshot<I> snapshot;

    private NpcRig(RigDocument rig, List<BoneDisplay<I>> displays, RigPlayback playback, long generation) {
        this.rig = rig; this.displays = List.copyOf(displays); this.playback = playback; this.generation = generation;
        snapshot = new Snapshot<>(identity, rig, displays, rig.body() == RigDocument.Body.HIDDEN, null);
    }

    /** The definition's rig from the catalog; empty, with a problem, when it is missing or one of its items is unknown here. */
    public static <I> Optional<NpcRig<I>> bind(String rigId, RigCatalog catalog, SeatGeometry seat, Items<I> items, List<String> problems) {
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
        return Optional.of(new NpcRig<>(rig, displays, new RigPlayback(rig, catalog.clips(), seat), catalog.generation()));
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

    public void play(String animation, double speed, Optional<AnimationDocument.Loop> loop, int layer, long now) { playback.play(animation, speed, loop, layer, now); }
    public void stop(OptionalInt layer, int fadeTicks, long now) { playback.stop(layer, fadeTicks, now); }

    /** True when a new segment is due; {@link #plan} makes it. */
    public boolean due(long now, float yaw) { return playback.due(now, yaw); }

    /** One owner tick: walking state and clips that ended. Cheap; planning is separate so callers can bound it. */
    public List<RigPlayback.Finished> tick(long now, boolean walking) {
        playback.moving(walking, now);
        return playback.drainFinished(now);
    }

    public void plan(long now, float yaw, int sampleTicks, long nanos) {
        RigFrame frame = playback.plan(now, yaw, sampleTicks, nanos);
        if (frame != null) snapshot = new Snapshot<>(identity, rig, displays, rig.body() == RigDocument.Body.HIDDEN, frame);
    }
}
