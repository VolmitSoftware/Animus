package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player stood at their own region's last viewer turn. Other regions read it to find players near an NPC
 * without touching a player they do not own. Thread-safe.
 */
final class PublishedPlayers {
    private final Map<UUID, WorldPosition> at = new ConcurrentHashMap<>();

    /** On the player's region, once per viewer turn. */
    void at(UUID player, WorldPosition position) { at.put(player, position); }

    void left(UUID player) { at.remove(player); }

    /** Players last seen within {@code radius} of {@code center} in its world, at most {@code limit}. */
    List<UUID> near(WorldPosition center, double radius, int limit) {
        List<UUID> found = new ArrayList<>();
        for (Map.Entry<UUID, WorldPosition> player : at.entrySet()) {
            if (found.size() >= limit) break;
            WorldPosition position = player.getValue();
            if (position.world().equals(center.world()) && position.point().distance(center.point()) <= radius) found.add(player.getKey());
        }
        return found;
    }
}
