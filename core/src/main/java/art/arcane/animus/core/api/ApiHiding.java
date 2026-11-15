package art.arcane.animus.core.api;

import art.arcane.animus.core.behavior.trigger.HiddenNpcs;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NPCs plugins hid from single players. An NPC stays hidden while any owner hides it. Thread-safe and cheap to ask. */
final class ApiHiding implements HiddenNpcs {
    private final Map<UUID, Map<UUID, Set<String>>> byViewer = new ConcurrentHashMap<>();

    void set(String owner, UUID viewer, UUID npc, boolean hidden) {
        if (hidden) {
            byViewer.computeIfAbsent(viewer, (UUID ignored) -> new ConcurrentHashMap<>()).computeIfAbsent(npc, (UUID ignored) -> ConcurrentHashMap.newKeySet()).add(owner);
            return;
        }
        byViewer.computeIfPresent(viewer, (UUID ignored, Map<UUID, Set<String>> npcs) -> {
            npcs.computeIfPresent(npc, (UUID key, Set<String> owners) -> { owners.remove(owner); return owners.isEmpty() ? null : owners; });
            return npcs.isEmpty() ? null : npcs;
        });
    }

    void release(String owner) {
        for (UUID viewer : Set.copyOf(byViewer.keySet()))
            byViewer.computeIfPresent(viewer, (UUID ignored, Map<UUID, Set<String>> npcs) -> {
                npcs.values().forEach((Set<String> owners) -> owners.remove(owner));
                npcs.values().removeIf(Set::isEmpty);
                return npcs.isEmpty() ? null : npcs;
            });
    }

    void left(UUID viewer) { byViewer.remove(viewer); }

    @Override public boolean hides(UUID viewer, String id, UUID npc) {
        if (byViewer.isEmpty()) return false;
        Map<UUID, Set<String>> npcs = byViewer.get(viewer);
        return npcs != null && npcs.containsKey(npc);
    }
}
