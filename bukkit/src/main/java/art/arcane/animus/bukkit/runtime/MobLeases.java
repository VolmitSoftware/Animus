package art.arcane.animus.bukkit.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which NPC controls which real mob on Folia. A mob has at most one controller and the whole server at most
 * {@code capacity}. A claim is released once, by identity, so a stale claim can never end the lease that replaced it.
 * Safe from any thread.
 */
final class MobLeases {
    record Claim(UUID mob, UUID npc, long id) {}
    private final int capacity;
    private final Map<UUID, Claim> claims = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong ids = new AtomicLong();

    MobLeases(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Mob control needs room for one mob");
        this.capacity = capacity;
    }

    /** A new claim, or null when the mob is already controlled or the limit is reached. */
    Claim claim(UUID mob, UUID npc) {
        if (size.incrementAndGet() > capacity) { size.decrementAndGet(); return null; }
        Claim claim = new Claim(mob, npc, ids.incrementAndGet());
        if (claims.putIfAbsent(mob, claim) != null) { size.decrementAndGet(); return null; }
        return claim;
    }
    boolean current(Claim claim) { return claims.get(claim.mob()) == claim; }
    boolean release(Claim claim) {
        if (!claims.remove(claim.mob(), claim)) return false;
        size.decrementAndGet();
        return true;
    }
    List<Claim> of(UUID npc) {
        List<Claim> found = new ArrayList<>();
        for (Claim claim : claims.values()) if (claim.npc().equals(npc)) found.add(claim);
        return found;
    }
    int size() { return size.get(); }
}
