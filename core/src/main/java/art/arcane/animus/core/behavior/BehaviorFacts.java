package art.arcane.animus.core.behavior;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only world facts for behavior expressions. Implementations answer from snapshots that the platform refreshes on
 * its own threads, so any thread may call them and a call never touches the world. Values are Boolean, Long, Double,
 * String, or a list of strings such as an NPC's behaviors.
 */
public interface BehaviorFacts {
    BehaviorFacts NONE = new BehaviorFacts() {
        @Override public Optional<Map<String, Object>> player(UUID player) { return Optional.empty(); }
        @Override public Optional<Map<String, Object>> npc(UUID npc) { return Optional.empty(); }
        @Override public Map<String, Object> server() { return Map.of(); }
    };

    /** The latest snapshot of an online player, empty when the player is offline or unknown. */
    Optional<Map<String, Object>> player(UUID player);

    /** The latest snapshot of an NPC, empty when the runtime does not know it. */
    Optional<Map<String, Object>> npc(UUID npc);

    /** The latest server snapshot. */
    Map<String, Object> server();

    /** The NPCs the runtime knows, spawned or not; empty where the platform lists none. */
    default java.util.List<UUID> npcs() { return java.util.List.of(); }

    /**
     * The form this platform's {@code *.world} facts give for a world name, dimension key or UUID: the name on Bukkit,
     * the dimension key on mod servers. Empty when no such world is loaded or the platform resolves no worlds.
     */
    default Optional<String> world(String reference) { return Optional.empty(); }
}
