package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded owner-thread registry: one NPC per rider, one real rider per NPC, at most {@link #MAX_RIDERS} leases. */
public final class RideLedger {
    public enum Rejection { ALREADY_RIDING, OCCUPIED, CAPACITY }
    public record Admission(RideSession session, Rejection rejection) {}
    public static final int MAX_RIDERS = 8;
    private final Map<UUID, RideSession> riders = new HashMap<>(), npcs = new HashMap<>();

    public Admission admit(UUID rider, UUID npc, Vec3 anchor, RideSettings settings, boolean flying, long now) {
        if (riders.containsKey(rider)) return new Admission(null, Rejection.ALREADY_RIDING);
        if (npcs.containsKey(npc)) return new Admission(null, Rejection.OCCUPIED);
        if (riders.size() >= MAX_RIDERS) return new Admission(null, Rejection.CAPACITY);
        RideSession session = new RideSession(rider, npc, anchor, settings, flying, now);
        riders.put(rider, session); npcs.put(npc, session);
        return new Admission(session, null);
    }
    public boolean remove(RideSession session) {
        boolean removed = riders.remove(session.rider(), session);
        npcs.remove(session.npc(), session);
        return removed;
    }
    public RideSession ofRider(UUID rider) { return riders.get(rider); }
    public RideSession ofNpc(UUID npc) { return npcs.get(npc); }
    public int size() { return riders.size(); }
}
