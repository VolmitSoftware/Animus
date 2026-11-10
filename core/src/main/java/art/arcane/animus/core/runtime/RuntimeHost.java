package art.arcane.animus.core.runtime;

import art.arcane.animus.core.control.RideEntry;
import art.arcane.animus.core.navigation.DoorPassage;
import art.arcane.animus.core.navigation.TerrainReader;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the single-owner NPC runtime needs from a server platform. Every method except {@link #ownerThread()} and
 * {@link #execute(Runnable)} runs on the owner thread. World keys are the platform's own stable world names, the same
 * strings that appear in {@link WorldPosition#world()}. Nothing here loads terrain.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public interface RuntimeHost<P, I> extends TerrainReader {
    /** Interaction kinds a platform may veto before the runtime accepts them. */
    enum Interaction { ATTACK, INTERACT }

    boolean ownerThread();
    /** Runs the work on the owner thread later. False when it cannot be accepted. */
    boolean execute(Runnable work);
    /** Runs the tick once per owner tick until the handle closes. */
    AutoCloseable everyTick(Runnable tick);
    void warn(String message);

    /** Binds the packet transport. Throwing leaves the runtime unavailable with the reason. */
    BodyTransport<P, I> openTransport() throws Exception;
    /** How delivered chunks are recognized, for status. */
    String chunkDelivery();

    Collection<? extends P> onlinePlayers();
    Optional<P> player(UUID id);
    UUID id(P player);
    String name(P player);
    boolean online(P player);
    boolean dead(P player);
    boolean sleeping(P player);
    boolean spectator(P player);
    /** The player holds the sneak key, as the server last heard it. */
    boolean sneaking(P player);
    /** The player sits in a real server vehicle. */
    boolean inVehicle(P player);
    int entityId(P player);
    WorldPosition position(P player);
    Vec3 eye(P player);
    /** The player's own signed skin, for NPCs that mirror their viewer; empty in offline mode or when it cannot be read. */
    default Optional<art.arcane.animus.core.presentation.BodySkin> skin(P player) { return Optional.empty(); }
    /** Copies of what the player holds and wears, for NPCs that mirror their viewer's equipment. */
    default java.util.Map<art.arcane.animus.core.npc.EquipmentSlot, I> equipment(P player) { return java.util.Map.of(); }
    /** Unit look direction. */
    Vec3 direction(P player);
    float yaw(P player);
    float pitch(P player);
    boolean permitted(P player, String node);
    void tell(P player, String message);
    /** An ordinary plugin teleport; false when it was refused. */
    boolean teleport(P player, WorldPosition destination, float yaw, float pitch);

    /** The world key a definition's world name or identifier refers to, when that world is loaded. */
    Optional<String> resolveWorld(String reference);
    /** The server has delivered this chunk to the player and is not still sending it. */
    boolean chunkSent(P player, int chunkX, int chunkZ);
    /** No collidable block lies between the eye and the point {@code distance} along the direction. Loaded chunks only. */
    boolean clearSight(P player, Vec3 eye, Vec3 direction, double distance);
    /** Opens and closes real doors for NPCs whose movement opens doors, behind the platform's protection. */
    DoorPassage.Doors doors();
    /** The region entry rule a ride asks for each new block, since a ride moves its rider with no movement event. */
    default RideEntry.Rule rideEntry() { return RideEntry.OPEN; }
    /** The block is loaded, inside the world's height and solid. */
    boolean solid(String world, int x, int y, int z);
    /** A registered server entity uses this UUID, for diagnostics. */
    boolean registeredEntity(UUID uuid);

    /** A server item stack for a definition item, or null after adding a field error. */
    I item(NpcItem item, String path, List<String> errors);
    I copy(I item);
    /** The stack an equipment packet sends to clear a slot, or empty where the platform has none. */
    default Optional<I> emptyItem() { return Optional.empty(); }

    /** False vetoes an interaction the runtime already validated, as a cancelled platform event. */
    boolean interactionAllowed(P player, UUID npc, Interaction action, double distance);
    /** An NPC reached a waypoint of a route. */
    void waypointReached(UUID npc, String id, UUID route, int waypoint, WorldPosition at);
    /** The NPC became active, or came back after dying, where its spawn trigger fires. Owner thread. */
    default void npcSpawned(UUID npc, String id, WorldPosition at) {}
    /** An active NPC was removed, despawned or replaced by another UUID. Owner thread. */
    default void npcDespawned(UUID npc, String id) {}
    /** The NPC's health reached zero; {@code killer} dealt the blow when known. Owner thread. */
    default void npcDied(UUID npc, String id, WorldPosition at, Optional<art.arcane.animus.core.behavior.trigger.TriggerEvent.Attacker> killer) {}
}
