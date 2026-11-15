package art.arcane.animus.bukkit.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-wide seat leases on a regionized server: one seat per player and at most {@code [seats] maximum} seated players,
 * from any thread. A lease names the NPC's actor so player-side events (logout, death, teleport) can hand the end of a
 * seat to the NPC's owner; its generation is the seat's epoch, so a late request for an earlier seat never ends a newer one.
 *
 * @param <A> the NPC's actor
 */
final class RegionSeats<A> {
    record Lease<A>(UUID player, UUID npc, UUID generation, A actor) {}
    enum Rejection { ALREADY_SEATED, CAPACITY }
    record Admission<A>(Lease<A> lease, Rejection rejection) {}
    private final Map<UUID, Lease<A>> byPlayer = new HashMap<>();

    synchronized Admission<A> admit(UUID player, UUID npc, A actor, int maximum) {
        Objects.requireNonNull(player); Objects.requireNonNull(npc); Objects.requireNonNull(actor);
        if (byPlayer.containsKey(player)) return new Admission<>(null, Rejection.ALREADY_SEATED);
        if (byPlayer.size() >= maximum) return new Admission<>(null, Rejection.CAPACITY);
        Lease<A> lease = new Lease<>(player, npc, UUID.randomUUID(), actor);
        byPlayer.put(player, lease);
        return new Admission<>(lease, null);
    }
    /** Ends the lease once; false when it already ended. */
    synchronized boolean release(Lease<A> lease) { return byPlayer.remove(lease.player(), lease); }
    synchronized boolean current(Lease<A> lease) { return byPlayer.get(lease.player()) == lease; }
    synchronized Lease<A> of(UUID player) { return byPlayer.get(player); }
    synchronized int size() { return byPlayer.size(); }
    synchronized List<Lease<A>> leases() { return new ArrayList<>(byPlayer.values()); }
    synchronized void clear() { byPlayer.clear(); }
}
