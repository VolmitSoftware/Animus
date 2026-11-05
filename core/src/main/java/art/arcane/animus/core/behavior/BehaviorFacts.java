package art.arcane.animus.core.behavior;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only world facts for behavior expressions. Implementations answer from snapshots that the platform refreshes on
 * its own threads, so any thread may call them and a call never touches the world. Values are Boolean, Long, Double or
 * String.
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
}
