package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.UUID;

/**
 * The NPC runtime as builds use it. A single-owner runtime answers every call on its one owner thread. A regionized
 * runtime answers {@link #active} from published state on any thread, and moves an NPC only on that NPC's own region:
 * {@link #here} says whether the calling thread is that region now.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public interface BuildNpcs<P, I> {
    boolean available();
    String status();
    NpcRuntime.PlayerAccess<P> players();
    Optional<NpcRuntime.ActiveNpc> active(UUID npc);
    /** The calling thread may move and change this NPC now. Always true on a single-owner runtime. */
    default boolean here(String id) { return true; }
    boolean routing(String id);
    UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond);
    boolean stopNavigation(String id);
    boolean relocate(String id, WorldPosition destination);
    void lookAt(String id, WorldPosition target);
    void swing(String id);
    void setEquipment(String id, EquipmentSlot slot, I item);
    void terrainChanged(String world, BlockPos at);
}
