package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The persisted NPC runtime as commands and behaviors see it. {@link PersistedNpcService} owns every NPC on the
 * Bukkit main thread; {@link RegionNpcRuntime} gives each NPC to the Folia region that owns its chunk.
 * <p>
 * Status reads ({@link #status}, {@link #inspect}, {@link #navigationStatus}, {@link #active}, selections) run on the
 * calling command or owner thread. Every other NPC method runs on the NPC's owner: call it inside work given to
 * {@link #onOwner}.
 */
public interface NpcRuntime extends AutoCloseable {
    /** Published view of an active NPC body. */
    record ActiveNpc(String id, String name, VirtualNpcState.Snapshot state) {}
    /** Player reads and delivery for behaviors, respecting player ownership. */
    interface PlayerAccess {
        /** The player is online, wherever its owner is. */
        boolean online(UUID player);
        /** The online player, when the calling thread may read it now. */
        Optional<Player> owned(UUID player);
        /** Online players the calling thread may read, within the radius, at most {@code limit}. */
        List<Player> ownedNear(WorldPosition at, double radius, int limit);
        /** Runs on the player's owner, now or later. False when the player is offline or refused the work. */
        boolean deliver(UUID player, Consumer<Player> action);
        /** Runs on the console's owner. False when refused. */
        boolean console(Runnable action);
    }

    boolean available();
    String status();
    /** Connect during plugin enable, before the first NPC can activate. */
    void bindTriggers(TriggerDispatcher dispatcher);
    void refresh();
    List<String> validateActivation(NpcDefinition definition);
    String inspect(String id);
    String rideStatus();
    boolean stopRider(String playerName);
    Optional<String> selection(UUID viewer);
    void select(UUID viewer, String id);
    Optional<ActiveNpc> active(UUID npc);
    /** Also finds a combat NPC that has died while its definition stays spawned; without combat it is {@link #active}. */
    default Optional<ActiveNpc> combatant(UUID npc) { return active(npc); }
    String navigationStatus(String id);
    PlayerAccess players();

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
    void followRoute(String id, String route);
    CompletableFuture<Void> awaitRoute(String id);
    void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset);
    void setEquipment(String id, EquipmentSlot slot, ItemStack item);

    @Override void close();
}
