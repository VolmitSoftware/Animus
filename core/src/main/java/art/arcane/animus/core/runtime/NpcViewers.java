package art.arcane.animus.core.runtime;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which viewers show each NPC: the reverse of every viewer's visible bodies, so work about one NPC (a hit, a swing, a
 * respawn of its body) visits the viewers that see it instead of every viewer. Owner thread only.
 *
 * @param <V> the runtime's viewer
 */
public final class NpcViewers<V> {
    private final Map<UUID, Set<V>> byNpc = new HashMap<>();

    public void shown(UUID npc, V viewer) { byNpc.computeIfAbsent(npc, (UUID ignored) -> new LinkedHashSet<>()).add(viewer); }

    public void hidden(UUID npc, V viewer) {
        Set<V> showing = byNpc.get(npc);
        if (showing != null && showing.remove(viewer) && showing.isEmpty()) byNpc.remove(npc);
    }

    /** A copy, so callers may retire bodies while they walk it. */
    public List<V> of(UUID npc) {
        Set<V> showing = byNpc.get(npc);
        return showing == null ? List.of() : List.copyOf(showing);
    }

    public int count(UUID npc) {
        Set<V> showing = byNpc.get(npc);
        return showing == null ? 0 : showing.size();
    }

    /** The viewer left: it shows none of these NPCs any more. */
    public void forget(V viewer, Collection<UUID> npcs) { for (UUID npc : npcs) hidden(npc, viewer); }

    public void clear() { byNpc.clear(); }
}
