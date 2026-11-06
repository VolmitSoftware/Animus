package art.arcane.animus.core.presentation;

import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Per-viewer packet presentation of virtual bodies. Nothing here creates or registers a server entity. Call every
 * method on the viewer's owner. Inbound interactions are only ownership and rate filtered: the consumer must validate
 * reach, visibility, line of sight and current NPC state before acting on one.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public interface BodyTransport<P, I> extends AutoCloseable {
    enum Action { ATTACK, INTERACT }

    /** One presented body. Its identity and native IDs are fixed when it is created. */
    interface Body {
        int entityId();
        UUID uuid();
        String typeKey();
        double width();
        double height();
        SeatGeometry seat();
        int[] entityIds();
    }

    record Interaction(Body body, Action action, String hand, boolean secondaryAction, long receivedAtNanos) {}

    /**
     * The viewer has no current watch: its connection closed, a newer watch replaced it, it was forgotten, or it was
     * never installed. Work for that viewer is dropped, never sent; this is a lifecycle event, not a runtime error.
     */
    final class ViewerUnwatched extends CancellationException {
        public ViewerUnwatched(String message) { super(message); }
    }

    /** Latest steering input of one rider. Poll on the owner. */
    interface ControlSubscription extends AutoCloseable {
        ControlInbox.Snapshot latest();
        @Override void close();
    }

    Set<String> entityTypes();
    /** Null when a real player can ride on this server; otherwise why the capability is unavailable. */
    String riderUnavailable();
    /** False when poses, text and long names cannot be presented; bodies then show their plain look. */
    boolean presentationAvailable();
    boolean equipmentAvailable();

    /** A plain body: the profile name is its nameplate and nothing else is presented. */
    Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin);
    /** A body showing its definition's appearance. */
    Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin);
    /** Extra native IDs a body with this appearance reserves beyond its type's own. */
    int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance);

    /** Installing a new watch invalidates the previous one for the same player. */
    CompletionStage<Void> watch(P viewer, Consumer<Interaction> interactions);
    CompletionStage<Void> spawn(P viewer, Body body, BodyPose pose);
    CompletionStage<Void> move(P viewer, Body body, BodyPose pose);
    /** Viewer-local passenger list; the client replaces the whole roster. {@code realRider} may be null. */
    CompletionStage<Void> passengers(P viewer, Body vehicle, List<? extends Body> passengers, P realRider, boolean authoritative);
    CompletionStage<Void> dragonPhase(P viewer, Body dragon, int phase);
    /** Swings the body's main arm once, as a player placing or breaking a block does. */
    default CompletionStage<Void> swing(P viewer, Body body) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    /**
     * Updates the pose and motion flags of a visible body from {@code look} without respawning it, as a recording
     * playback does when the recorder sneaks, swims, sprints or glides. A transport without metadata updates ignores it.
     */
    default CompletionStage<Void> look(P viewer, Body body, BodyLook look) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    /**
     * Sets the attributes of a visible body, as a runtime change does; a body spawns with the attributes of its look. Only
     * attributes the body's type carries are sent. A transport without attribute packets ignores it.
     */
    default CompletionStage<Void> attributes(P viewer, Body body, art.arcane.animus.core.npc.NpcAttributes attributes) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    /** An empty stack clears its slot. */
    CompletionStage<Void> equipment(P viewer, Body body, Map<EquipmentSlot, I> items);
    /** Authoritative: bypasses the ordinary operation limit. */
    CompletionStage<Void> despawn(P viewer, Body body);
    void forget(P viewer);
    ControlSubscription subscribeControl(P viewer);
    /** Moves the real rider's server position without a client teleport. */
    void placeRider(P rider, double x, double y, double z);

    /** A rig's display bones for one body. Their native IDs are fixed when they are created; they never join the body's own IDs. */
    interface Bones {
        int count();
        int[] entityIds();
    }
    /** Whether this transport can present rig bones; false leaves rigged NPCs on their plain body. */
    default boolean bonesAvailable() { return false; }
    /** Reserves display entity IDs for a rig's visible bones on this body, in {@code displays} order. Validates block states. */
    default Bones createBones(Body body, List<BoneDisplay<I>> displays) { throw new IllegalStateException("Rig display bones are unavailable on this server"); }
    /**
     * Spawns the bones riding the visible body, at their first transforms with no interpolation, and adds them to the
     * body's passenger roster; every later roster of the body keeps them. {@code hideBody} makes the body itself invisible.
     */
    default CompletionStage<Void> showBones(P viewer, Body body, Bones bones, BodyPose pose, List<art.arcane.animus.core.rig.DisplayTransform> transforms, boolean hideBody) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Rig display bones are unavailable on this server"));
    }
    /** One metadata packet per listed bone: the new transform, interpolated over that bone's ticks from the next client tick. */
    default CompletionStage<Void> moveBones(P viewer, Bones bones, int[] indices, art.arcane.animus.core.rig.DisplayTransform[] transforms, int[] durations) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Rig display bones are unavailable on this server"));
    }
    /** One metadata packet per listed bone: its new content, such as a socket's item. The kind of each bone stays as created. */
    default CompletionStage<Void> restyleBones(P viewer, Bones bones, int[] indices, List<BoneDisplay<I>> displays) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Rig display bones are unavailable on this server"));
    }
    /** Removes the bones from the viewer and the roster. Despawning the body, forgetting the viewer and closing remove them as well. */
    default CompletionStage<Void> hideBones(P viewer, Bones bones) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    @Override void close();
}
