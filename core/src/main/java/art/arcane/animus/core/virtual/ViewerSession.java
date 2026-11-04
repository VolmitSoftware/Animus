package art.arcane.animus.core.virtual;

import java.util.*;

/** Per-viewer presentation ownership and authoritative interaction gate. Never call platform world APIs on a network thread. */
public final class ViewerSession implements AutoCloseable {
    public record Limits(int maxVisible, int maxIssuedIds, long minimumActionIntervalNanos, double maximumReach) {
        public Limits {
            if (maxVisible < 1 || maxIssuedIds < maxVisible || maxIssuedIds > 1_000_000
                    || minimumActionIntervalNanos < 0 || !Double.isFinite(maximumReach) || maximumReach <= 0 || maximumReach > 128)
                throw new IllegalArgumentException("Invalid viewer session limits");
        }
    }
    public record Request(UUID viewerId, UUID sessionId, int entityId, WorldPosition eye, Vec3 direction, long nowNanos) {
        public Request {
            Objects.requireNonNull(viewerId, "viewerId"); Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(eye, "eye"); Objects.requireNonNull(direction, "direction");
        }
    }
    @FunctionalInterface
    public interface LineOfSight {
        /** Both endpoints come from the server ray. Distance never exceeds Limits.maximumReach. */
        boolean clear(WorldPosition eye, Vec3 hitPoint, double distance);
    }
    public enum Rejection { NONE, CLOSED, WRONG_VIEWER, STALE_SESSION, UNKNOWN_ENTITY, INACTIVE, WRONG_WORLD, RATE_LIMITED, INVALID_DIRECTION, OUT_OF_REACH, OCCLUDED, STATE_CHANGED }
    public record Validation(Rejection rejection, VirtualNpcState.Snapshot npc, Vec3 hitPoint, double distance) {
        public boolean accepted() { return rejection == Rejection.NONE; }
        private static Validation rejected(Rejection reason) { return new Validation(reason, null, null, 0); }
    }

    private final UUID viewerId;
    private final UUID sessionId;
    private final Limits limits;
    private final Map<Integer, VirtualNpcState.Snapshot> visible = new HashMap<>();
    private final Set<Integer> issued = new HashSet<>();
    private boolean closed;
    private boolean acted;
    private long lastAction;

    public ViewerSession(UUID viewerId, UUID sessionId, Limits limits) {
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.limits = Objects.requireNonNull(limits, "limits");
    }
    public UUID viewerId() { return viewerId; }
    public UUID sessionId() { return sessionId; }
    public Limits limits() { return limits; }
    public synchronized int visibleCount() { return visible.size(); }
    public synchronized int issuedCount() { return issued.size(); }

    /** IDs are never rebound, even after despawn or a world transition. Exhaustion requires a new session. */
    public synchronized void bind(int entityId, VirtualNpcState.Snapshot snapshot) {
        ensureOpen(); Objects.requireNonNull(snapshot, "snapshot");
        if (entityId <= 0 || issued.contains(entityId)) throw new IllegalArgumentException("Entity ID is invalid or already issued in this session");
        if (!snapshot.active()) throw new IllegalArgumentException("Cannot present an inactive NPC");
        if (visible.size() >= limits.maxVisible() || issued.size() >= limits.maxIssuedIds()) throw new IllegalStateException("Viewer presentation budget exhausted");
        issued.add(entityId); visible.put(entityId, snapshot);
    }

    /** Updates retain identity and reject old snapshots; they cannot resurrect a retired entity ID. */
    public synchronized void updateSnapshot(int entityId, VirtualNpcState.Snapshot snapshot) {
        ensureOpen(); Objects.requireNonNull(snapshot, "snapshot");
        VirtualNpcState.Snapshot previous = visible.get(entityId);
        if (previous == null) throw new IllegalArgumentException("Entity ID is not visible");
        if (!previous.npcId().equals(snapshot.npcId()) || snapshot.revision() < previous.revision()
                || (snapshot.revision() == previous.revision() && !snapshot.equals(previous)))
            throw new IllegalArgumentException("NPC identity or runtime revision changed unexpectedly");
        if (!snapshot.active() || !snapshot.position().world().equals(previous.position().world())) visible.remove(entityId);
        else visible.put(entityId, snapshot);
    }

    public synchronized Optional<VirtualNpcState.Snapshot> unbind(int entityId) { return Optional.ofNullable(visible.remove(entityId)); }
    public synchronized void despawn(UUID npcId) { visible.values().removeIf(npc -> npc.npcId().equals(npcId)); }
    /** Use on teleport, world change, respawn, chunk reset; issued IDs remain retired until session closure. */
    public synchronized void clear() { visible.clear(); }

    public synchronized Validation validate(Request request, LineOfSight lineOfSight) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(lineOfSight, "lineOfSight");
        if (closed) return Validation.rejected(Rejection.CLOSED);
        if (!viewerId.equals(request.viewerId())) return Validation.rejected(Rejection.WRONG_VIEWER);
        if (!sessionId.equals(request.sessionId())) return Validation.rejected(Rejection.STALE_SESSION);
        VirtualNpcState.Snapshot npc = visible.get(request.entityId());
        if (npc == null) return Validation.rejected(Rejection.UNKNOWN_ENTITY);
        if (!npc.active() || npc.health() == 0) return Validation.rejected(Rejection.INACTIVE);
        if (!npc.position().world().equals(request.eye().world())) return Validation.rejected(Rejection.WRONG_WORLD);
        // Consume the budget before ray/LOS work, including unsuccessful visible-NPC attempts.
        if (acted && request.nowNanos() - lastAction < limits.minimumActionIntervalNanos()) return Validation.rejected(Rejection.RATE_LIMITED);
        acted = true; lastAction = request.nowNanos();
        Vec3 direction;
        try { direction = request.direction().normalized(); }
        catch (IllegalArgumentException e) { return Validation.rejected(Rejection.INVALID_DIRECTION); }
        OptionalDouble hit = npc.bounds().rayDistance(request.eye().point(), direction, limits.maximumReach());
        if (hit.isEmpty()) return Validation.rejected(Rejection.OUT_OF_REACH);
        double distance = hit.getAsDouble();
        Vec3 point = request.eye().point().add(direction.multiply(distance));
        if (!lineOfSight.clear(request.eye(), point, distance)) return Validation.rejected(Rejection.OCCLUDED);
        if (closed || visible.get(request.entityId()) != npc) return Validation.rejected(Rejection.STATE_CHANGED);
        return new Validation(Rejection.NONE, npc, point, distance);
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Viewer session is closed"); }
    @Override public synchronized void close() { closed = true; visible.clear(); issued.clear(); }
}
