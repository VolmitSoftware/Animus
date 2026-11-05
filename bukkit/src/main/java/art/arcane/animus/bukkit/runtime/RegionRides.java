package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.control.RideLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-wide ride leases on a regionized server: one NPC per player, one real rider per NPC and at most
 * {@link RideLedger#MAX_RIDERS} riders, from any thread. A lease names the NPC's actor so player-side events (logout,
 * death, teleport) can hand the end of a ride to the NPC's owner; its generation is the ride's epoch, so a late request
 * for an earlier ride never ends a newer one.
 *
 * @param <A> the NPC's actor
 */
final class RegionRides<A> {
    record Lease<A>(UUID player, UUID npc, UUID generation, A actor) {}
    record Admission<A>(Lease<A> lease, RideLedger.Rejection rejection) {}
    private final Map<UUID, Lease<A>> byPlayer = new HashMap<>(), byNpc = new HashMap<>();

    synchronized Admission<A> admit(UUID player, UUID npc, A actor) {
        Objects.requireNonNull(player); Objects.requireNonNull(npc); Objects.requireNonNull(actor);
        if (byPlayer.containsKey(player)) return new Admission<>(null, RideLedger.Rejection.ALREADY_RIDING);
        if (byNpc.containsKey(npc)) return new Admission<>(null, RideLedger.Rejection.OCCUPIED);
        if (byPlayer.size() >= RideLedger.MAX_RIDERS) return new Admission<>(null, RideLedger.Rejection.CAPACITY);
        Lease<A> lease = new Lease<>(player, npc, UUID.randomUUID(), actor);
        byPlayer.put(player, lease); byNpc.put(npc, lease);
        return new Admission<>(lease, null);
    }
    /** Ends the lease once; false when it already ended. */
    synchronized boolean release(Lease<A> lease) {
        boolean removed = byPlayer.remove(lease.player(), lease);
        byNpc.remove(lease.npc(), lease);
        return removed;
    }
    synchronized boolean current(Lease<A> lease) { return byPlayer.get(lease.player()) == lease; }
    synchronized Lease<A> ofRider(UUID player) { return byPlayer.get(player); }
    synchronized Lease<A> ofNpc(UUID npc) { return byNpc.get(npc); }
    synchronized int size() { return byPlayer.size(); }
    synchronized List<Lease<A>> leases() { return new ArrayList<>(byPlayer.values()); }
    synchronized void clear() { byPlayer.clear(); byNpc.clear(); }
}
