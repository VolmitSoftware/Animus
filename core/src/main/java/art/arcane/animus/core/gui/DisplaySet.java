package art.arcane.animus.core.gui;

import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.presentation.DisplayTransport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The displays one viewer sees of one hologram or menu, by slot name. {@link #apply} sends only what changed: new
 * slots spawn, changed slots update, missing slots are removed. A slot keeps its entity ID while its kind of content
 * stays the same. Used only on the viewer's owner.
 */
final class DisplaySet<P> {
    private record Shown(int id, UUID uuid, DisplayEntity entity) {}
    private final DisplayTransport<P> transport;
    private final Map<String, Shown> shown = new LinkedHashMap<>();
    private final Map<Integer, String> slots = new HashMap<>();

    DisplaySet(DisplayTransport<P> transport) { this.transport = transport; }

    /** {@code desired} entities carry any id and uuid; the set assigns its own. */
    void apply(P viewer, Map<String, DisplayEntity> desired) {
        List<DisplayEntity> spawn = new ArrayList<>(), update = new ArrayList<>();
        List<Integer> remove = new ArrayList<>();
        Iterator<Map.Entry<String, Shown>> current = shown.entrySet().iterator();
        while (current.hasNext()) {
            Map.Entry<String, Shown> entry = current.next();
            DisplayEntity wanted = desired.get(entry.getKey());
            if (wanted == null || !sameKind(wanted, entry.getValue().entity())) {
                remove.add(entry.getValue().id());
                slots.remove(entry.getValue().id());
                current.remove();
            }
        }
        for (Map.Entry<String, DisplayEntity> entry : desired.entrySet()) {
            Shown previous = shown.get(entry.getKey());
            DisplayEntity wanted = entry.getValue();
            if (previous == null) {
                int id = transport.reserve();
                DisplayEntity placed = new DisplayEntity(id, UUID.randomUUID(), wanted.content(), wanted.x(), wanted.y(), wanted.z(), wanted.yaw(), wanted.pitch(), wanted.style());
                shown.put(entry.getKey(), new Shown(id, placed.uuid(), placed));
                slots.put(id, entry.getKey());
                spawn.add(placed);
            } else {
                DisplayEntity placed = new DisplayEntity(previous.id(), previous.uuid(), wanted.content(), wanted.x(), wanted.y(), wanted.z(), wanted.yaw(), wanted.pitch(), wanted.style());
                if (!placed.equals(previous.entity())) { shown.put(entry.getKey(), new Shown(previous.id(), previous.uuid(), placed)); update.add(placed); }
            }
        }
        if (!remove.isEmpty()) transport.remove(viewer, remove.stream().mapToInt(Integer::intValue).toArray());
        if (!spawn.isEmpty()) transport.spawn(viewer, spawn);
        if (!update.isEmpty()) transport.update(viewer, update);
    }

    void clear(P viewer) {
        if (!shown.isEmpty()) transport.remove(viewer, shown.values().stream().mapToInt(Shown::id).toArray());
        shown.clear();
        slots.clear();
    }

    /** Forgets without sending, for a viewer whose client already dropped everything. */
    void forget() { shown.clear(); slots.clear(); }

    Optional<String> slot(int entityId) { return Optional.ofNullable(slots.get(entityId)); }
    boolean empty() { return shown.isEmpty(); }
    int size() { return shown.size(); }

    private static boolean sameKind(DisplayEntity wanted, DisplayEntity shown) {
        if (wanted.content().getClass() != shown.content().getClass()) return false;
        // A still entity's type is fixed when it spawns.
        return !(wanted.content() instanceof DisplayEntity.Mob mob) || mob.equals(shown.content());
    }
}
