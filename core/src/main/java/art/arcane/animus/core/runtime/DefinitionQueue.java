package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import java.util.*;

/** Incremental publication reconciliation. Idle ticks do not scan definitions. Owner-thread only. */
public final class DefinitionQueue {
    public record Change(String id, NpcDefinition definition) {}
    public record Batch(List<Change> changes, int examined, boolean pending) {}
    private Map<String, NpcDefinition> target = Map.of();
    private final Map<String, NpcDefinition> seen = new LinkedHashMap<>();
    private Iterator<Map.Entry<String, NpcDefinition>> removals = Collections.emptyIterator();
    private Iterator<Map.Entry<String, NpcDefinition>> updates = Collections.emptyIterator();
    private boolean forced;

    public void publish(Map<String, NpcDefinition> snapshot, boolean force) {
        Objects.requireNonNull(snapshot);
        if (snapshot == target && !force) return;
        target = snapshot; forced = force;
        removals = seen.entrySet().iterator(); updates = snapshot.entrySet().iterator();
    }
    public NpcDefinition current(String id) { return target.get(id); }
    public boolean current(Change change) { return target.get(change.id()) == change.definition(); }
    public Batch poll(int budget) {
        if (budget < 1 || budget > 256) throw new IllegalArgumentException("Invalid reconciliation budget");
        List<Change> changes = new ArrayList<>(); int work = 0;
        while (removals.hasNext() && work < budget) {
            var old = removals.next(); work++;
            if (!target.containsKey(old.getKey())) { changes.add(new Change(old.getKey(), null)); removals.remove(); }
        }
        while (!removals.hasNext() && updates.hasNext() && work < budget) {
            var entry = updates.next(); work++;
            NpcDefinition old = seen.put(entry.getKey(), entry.getValue());
            if (forced || old != entry.getValue()) changes.add(new Change(entry.getKey(), entry.getValue()));
        }
        return new Batch(List.copyOf(changes), work, removals.hasNext() || updates.hasNext());
    }
    public void clear() { target = Map.of(); seen.clear(); removals = Collections.emptyIterator(); updates = Collections.emptyIterator(); }
}
