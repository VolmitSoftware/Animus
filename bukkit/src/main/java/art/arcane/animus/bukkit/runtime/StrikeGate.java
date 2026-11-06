package art.arcane.animus.bukkit.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manual attacks on Folia: each NPC has at most one hit on its way to the target's region, and the next waits for the
 * cooldown after the previous began. Every change is one atomic map update, so racing commands on different region
 * threads admit exactly one hit. Holds at most {@code capacity} NPCs; one settled past its cooldown gives its place up.
 */
final class StrikeGate {
    enum Admission { ADMITTED, IN_FLIGHT, COOLDOWN, FULL }
    private record Entry(boolean inFlight, long ready) {}
    private final int capacity;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    StrikeGate(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("A strike gate needs room for one NPC");
        this.capacity = capacity;
    }

    /** Reserves the NPC's next hit at {@code now}; the one after it is ready {@code cooldownNanos} later. */
    Admission reserve(UUID npc, long now, long cooldownNanos) {
        if (!entries.containsKey(npc) && entries.size() >= capacity) {
            entries.entrySet().removeIf((Map.Entry<UUID, Entry> entry) -> !entry.getValue().inFlight() && now - entry.getValue().ready() >= 0);
            if (entries.size() >= capacity) return Admission.FULL;
        }
        Admission[] result = {Admission.ADMITTED};
        entries.compute(npc, (UUID key, Entry entry) -> {
            if (entry != null && entry.inFlight()) { result[0] = Admission.IN_FLIGHT; return entry; }
            if (entry != null && now - entry.ready() < 0) { result[0] = Admission.COOLDOWN; return entry; }
            return new Entry(true, now + cooldownNanos);
        });
        return result[0];
    }
    /** The hit came back from the target's region, applied or not. */
    void release(UUID npc) { entries.computeIfPresent(npc, (UUID key, Entry entry) -> new Entry(false, entry.ready())); }
    void clear() { entries.clear(); }
}
