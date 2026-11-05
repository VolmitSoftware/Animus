package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.UUID;

/** The NPCs that build, by NPC id, on the owner thread. A member that is not active is simply absent. */
public interface BuildCrew {
    record Member(String id, UUID uuid, WorldPosition position, double width, double height) {}

    Optional<Member> member(String npc);
    /** The NPC is following a route right now. */
    boolean routing(String npc);
    /** Starts walking; throws {@link IllegalArgumentException} or {@link IllegalStateException} when refused. */
    void walk(String npc, WorldPosition goal, double speed);
    void stop(String npc);
    /** Moves the NPC at once onto clear, supported, loaded ground; false leaves it where it is. */
    boolean relocate(String npc, WorldPosition to);
    void face(String npc, WorldPosition at);
    void swing(String npc);
    /** Shows the item in the main hand, or clears it. */
    void hold(String npc, Optional<String> item);
    /** A build changed this block; NPCs standing on it check their footing and fall when nothing holds them up. */
    void terrainChanged(String world, BlockPos at);
}
