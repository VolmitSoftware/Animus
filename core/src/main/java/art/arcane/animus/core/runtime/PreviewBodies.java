package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One viewer session's preview bodies: the staged candidates of {@link ViewerPreviews}, presented to this viewer only,
 * standing still at their definition position. Belongs to the runtime's viewer object and lives exactly as long as its
 * transport watch; a new session starts again from the staged preview. Call every method on the viewer's owner.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public final class PreviewBodies<P, I> {
    private final ViewerPreviews previews;
    private final UUID viewer;
    private final List<Shown> bodies = new ArrayList<>();
    private long generation;
    private int reservedIds;

    public PreviewBodies(ViewerPreviews previews, UUID viewer) {
        this.previews = Objects.requireNonNull(previews);
        this.viewer = Objects.requireNonNull(viewer);
    }

    /**
     * Picks up a new, replaced or ended preview and spawns or removes bodies as the viewer comes in and out of range.
     * {@code otherIds} are the native IDs the live presentation already holds; {@code completions} runs packet callbacks
     * on the viewer's owner. Returns the packets sent.
     */
    public int update(P player, BodyTransport<P, I> packets, WorldPosition at, double viewDistance, Predicate<ChunkPosition> delivered,
                      Function<NpcDefinition, DefinitionSpec<I>> parse, Consumer<Runnable> completions, int otherIds, int budget) {
        int sent = 0;
        ViewerPreviews.Staged staged = previews.staged(viewer);
        long wanted = staged == null ? 0 : staged.generation();
        if (wanted != generation) {
            for (Shown shown : bodies) {
                shown.retired = true;
                if (shown.present) { despawn(player, packets, shown); sent++; }
            }
            bodies.clear();
            generation = wanted;
            if (staged != null) {
                try {
                    for (ViewerPreviews.Candidate candidate : staged.candidates()) {
                        DefinitionSpec<I> spec = parse.apply(candidate.definition());
                        // A fresh identity: the live body of the same NPC may still be leaving this viewer's client.
                        BodyTransport.Body body = packets.createBody(UUID.randomUUID(), spec.type(), spec.name(), spec.appearance(), candidate.skin());
                        bodies.add(new Shown(spec, body));
                    }
                    staged.accepted().complete(null);
                } catch (IllegalArgumentException invalid) {
                    bodies.clear();
                    previews.refuse(staged, invalid.getMessage());
                }
            }
        }
        for (Shown shown : bodies) {
            if (sent >= budget) break;
            boolean eligible = eligible(shown.spec.position(), at, viewDistance, delivered);
            if (eligible && !shown.present && !shown.failed && otherIds + reservedIds + shown.body.entityIds().length <= BodyRules.MAX_BODIES_PER_VIEWER) {
                spawn(player, packets, shown, completions); sent++;
            } else if (!eligible && shown.present && !shown.pending) { despawn(player, packets, shown); sent++; }
        }
        return sent;
    }

    private static boolean eligible(WorldPosition position, WorldPosition at, double viewDistance, Predicate<ChunkPosition> delivered) {
        return position.world().equals(at.world()) && position.point().distance(at.point()) <= viewDistance && delivered.test(position.chunk());
    }

    private void spawn(P player, BodyTransport<P, I> packets, Shown shown, Consumer<Runnable> completions) {
        shown.present = true; shown.pending = true; reservedIds += shown.body.entityIds().length;
        Vec3 point = shown.spec.position().point();
        BodyPose pose = new BodyPose(shown.spec.position().world(), point.x(), point.y(), point.z(), shown.spec.yaw(), shown.spec.pitch(), true);
        try {
            packets.spawn(player, shown.body, pose).whenComplete((Void nothing, Throwable failure) -> completions.accept(() -> {
                shown.pending = false;
                if (shown.retired) return;
                // Cosmetic: a failed preview body is not retried and never costs the viewer the live presentation.
                if (failure != null) { shown.failed = true; release(shown); return; }
                if (!shown.spec.equipment().isEmpty() && packets.equipmentAvailable()) packets.equipment(player, shown.body, shown.spec.equipment());
            }));
        } catch (RuntimeException failure) { shown.pending = false; shown.failed = true; release(shown); }
    }

    private void despawn(P player, BodyTransport<P, I> packets, Shown shown) {
        release(shown);
        try { packets.despawn(player, shown.body); }
        catch (RuntimeException ignored) { /* A closed transport already removed every body of this viewer. */ }
    }

    private void release(Shown shown) {
        if (!shown.present) return;
        shown.present = false; reservedIds -= shown.body.entityIds().length;
    }

    /** A body of this viewer's preview: interactions with it are dropped. */
    public boolean owns(UUID body) {
        for (Shown shown : bodies) if (shown.body.uuid().equals(body)) return true;
        return false;
    }

    /** Native IDs the preview bodies hold on this viewer's client. */
    public int reservedIds() { return reservedIds; }

    public int shown() {
        int count = 0;
        for (Shown shown : bodies) if (shown.present) count++;
        return count;
    }

    private final class Shown {
        final DefinitionSpec<I> spec; final BodyTransport.Body body;
        boolean present, pending, failed, retired;
        Shown(DefinitionSpec<I> spec, BodyTransport.Body body) { this.spec = spec; this.body = body; }
    }
}
