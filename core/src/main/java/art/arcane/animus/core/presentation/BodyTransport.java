package art.arcane.animus.core.presentation;

import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    /** An empty stack clears its slot. */
    CompletionStage<Void> equipment(P viewer, Body body, Map<EquipmentSlot, I> items);
    /** Authoritative: bypasses the ordinary operation limit. */
    CompletionStage<Void> despawn(P viewer, Body body);
    void forget(P viewer);
    ControlSubscription subscribeControl(P viewer);
    /** Moves the real rider's server position without a client teleport. */
    void placeRider(P rider, double x, double y, double z);
    @Override void close();
}
