package art.arcane.animus.core.runtime;

import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The persisted NPC runtime as commands and behaviors see it. {@link PersistedNpcService} owns every NPC on one owner
 * thread; a regionized platform gives each NPC to the region that owns its chunk.
 * <p>
 * Status reads ({@link #status}, {@link #inspect}, {@link #navigationStatus}, {@link #active}, selections) run on the
 * calling command or owner thread. Every other NPC method runs on the NPC's owner: call it inside work given to
 * {@link #onOwner}.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public interface NpcRuntime<P, I> extends AutoCloseable {
    /** Published view of an active NPC body. */
    record ActiveNpc(String id, String name, VirtualNpcState.Snapshot state) {}
    /** Player reads and delivery for behaviors, respecting player ownership. */
    interface PlayerAccess<P> {
        /** The player is online, wherever its owner is. */
        boolean online(UUID player);
        /** The online player, when the calling thread may read it now. */
        Optional<P> owned(UUID player);
        /** Online players the calling thread may read, within the radius, at most {@code limit}. */
        List<P> ownedNear(WorldPosition at, double radius, int limit);
        /** Runs on the player's owner, now or later. False when the player is offline or refused the work. */
        boolean deliver(UUID player, Consumer<P> action);
        /** Runs on the console's owner. False when refused. */
        boolean console(Runnable action);
    }

    /** A validated click on an active NPC, on the player's owner. True when the click was used, which skips riding and selection. */
    @FunctionalInterface interface InteractionListener<P> { boolean interacted(P player, UUID npc, boolean attack, boolean sneaking); }

    /** Engine-owned observer of every accepted click, called on the player's owner; quick, nonblocking and thread-safe. */
    @FunctionalInterface interface InteractionObserver { void accepted(UUID npc, UUID viewer, boolean attack, double distance, boolean preview); }

    boolean available();
    /** Whether {@code npcs.enabled} turns this runtime on and off in place; false where that needs a restart (Folia). */
    default boolean switchesLive() { return true; }
    /** Connect during startup. A later call replaces the listener. */
    void interactions(InteractionListener<P> listener);
    /** Connect during startup. A later call replaces the observer. */
    void observeInteractions(InteractionObserver observer);
    String status();
    /**
     * On the caller's owner: cosmetic updates such as hologram text animation may send now. False while NPC moves and
     * despawns are waiting for packet budget, and on most ticks while another budget runs short; see {@link PresentationPressure}.
     */
    boolean cosmeticAllowed();
    /** Connect during startup, before the first NPC can activate. */
    void bindTriggers(TriggerDispatcher dispatcher);
    /** Connect during startup: where rig animation events run. Runtimes without rigs ignore it. */
    default void animationEvents(art.arcane.animus.core.rig.NpcRig.Events events) {}
    void refresh();
    List<String> validateActivation(NpcDefinition definition);
    String inspect(String id);
    String rideStatus();
    boolean stopRider(String playerName);
    Optional<String> selection(UUID viewer);
    void select(UUID viewer, String id);
    Optional<ActiveNpc> active(UUID npc);
    /** The NPC's last published state, from any thread, for behavior facts. Empty when it is not active. */
    default Optional<VirtualNpcState.Snapshot> state(UUID npc) { return active(npc).map(ActiveNpc::state); }
    /** Also finds a combat NPC that has died while its definition stays spawned; without combat it is {@link #active}. */
    default Optional<ActiveNpc> combatant(UUID npc) { return active(npc); }
    /** Who may still speak, sound and show particles: an active NPC, or a combat NPC whose death routine is running. */
    default Optional<ActiveNpc> voice(UUID npc) { return active(npc).or(() -> combatant(npc)); }
    String navigationStatus(String id);
    PlayerAccess<P> players();
    /** Viewer-private previews this runtime presents; inert packet bodies shown to one player only. */
    ViewerPreviews previews();

    /**
     * Runs work on the NPC's owner, now when the caller already owns it. False when the NPC is not active anywhere.
     * Accepted work runs exactly once, or {@code refused} receives a reason when the owner drops it.
     */
    boolean onOwner(UUID npc, Runnable work, Consumer<String> refused);
    boolean onOwner(String id, Runnable work, Consumer<String> refused);

    WorldPosition navigationOrigin(String id);
    UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond);
    boolean stopNavigation(String id);
    void lookAt(String id, WorldPosition target);
    /** Turns the NPC to one of its definition's named poses. @throws IllegalArgumentException for a pose it does not declare */
    void pose(String id, String pose);
    void followRoute(String id, String route);
    CompletableFuture<Void> awaitRoute(String id);
    /**
     * Replaces the current route with a recording the NPC plays from its first frame after walking there. Its read and
     * any read failure are reported in navigation status; {@link #awaitRoute} completes when a {@code once} playback ends.
     * An empty playback plays the recording's own, or loop.
     */
    void playRecording(String id, String recording, Optional<art.arcane.animus.core.recording.Playback> playback);
    /**
     * Restarts the definition's own route or recording when nothing else moves the NPC. A recording stopped by combat, a
     * manual route or {@link #stopNavigation} continues from where it stopped. False when the NPC declares nothing to resume.
     */
    boolean resumePatrol(String id);
    void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset);
    void setEquipment(String id, EquipmentSlot slot, I item);
    /**
     * Sets one vanilla attribute for the running NPC, or with an empty value returns it to the definition's value. The
     * change lasts until the definition is applied again; see {@link RuntimeAttributes}.
     */
    void setAttribute(String id, String attribute, java.util.OptionalDouble value);
    /**
     * Plays an animation of the NPC's rig on a layer (0-3) at a speed (0.25-4), optionally overriding its loop mode
     * ({@code once}, {@code loop}, {@code pingpong} or {@code hold}). Throws {@link IllegalStateException} when the NPC
     * has no rig and {@link IllegalArgumentException} for an unknown animation.
     */
    default void playAnimation(String id, String animation, double speed, java.util.Optional<String> loop, int layer) {
        throw new IllegalStateException("Rig animation is not available on this runtime");
    }
    /** Stops one layer or every played layer, fading over 0-40 ticks; layer 0 returns to the rig's idle animation. */
    default void stopAnimation(String id, java.util.OptionalInt layer, int fadeTicks) {
        throw new IllegalStateException("Rig animation is not available on this runtime");
    }

    @Override void close();
}
