package art.arcane.animus.core.toolkit;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The NPCs each player has picked with the wand, in the order picked. The runtime's own single selection stays the
 * primary NPC that {@code @selected} names; this set is what the toolkit screens act on. Forgotten on quit.
 */
public final class ToolkitSelection {
    public static final int MAXIMUM = 256;
    private final Map<UUID, LinkedHashSet<String>> chosen = new HashMap<>();

    public synchronized List<String> of(UUID player) {
        LinkedHashSet<String> ids = chosen.get(player);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /** Makes the NPC the only one selected. */
    public synchronized void only(UUID player, String id) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(id);
        chosen.put(player, ids);
    }

    /** Replaces the selection with these NPCs in order, keeping the first {@link #MAXIMUM}; returns how many are selected. */
    public synchronized int set(UUID player, List<String> ids) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        for (String id : ids) if (next.size() < MAXIMUM) next.add(id);
        if (next.isEmpty()) chosen.remove(player); else chosen.put(player, next);
        return next.size();
    }

    /** Adds the NPC, or removes it when it is already selected. True when it is selected afterwards. */
    public synchronized boolean toggle(UUID player, String id) {
        LinkedHashSet<String> ids = chosen.computeIfAbsent(player, (UUID ignored) -> new LinkedHashSet<>());
        if (ids.remove(id)) {
            if (ids.isEmpty()) chosen.remove(player);
            return false;
        }
        if (ids.size() >= MAXIMUM) throw new IllegalStateException("Selection is full at " + MAXIMUM + " NPCs");
        ids.add(id);
        return true;
    }

    public synchronized void clear(UUID player) { chosen.remove(player); }

    /** Drops NPCs that no longer exist. */
    public synchronized void retain(UUID player, Predicate<String> exists) {
        LinkedHashSet<String> ids = chosen.get(player);
        if (ids == null) return;
        ids.removeIf(exists.negate());
        if (ids.isEmpty()) chosen.remove(player);
    }

    /** Players with at least one NPC selected. */
    public synchronized Set<UUID> players() { return Set.copyOf(chosen.keySet()); }
}
