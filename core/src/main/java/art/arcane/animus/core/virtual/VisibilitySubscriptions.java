package art.arcane.animus.core.virtual;

import java.util.*;
import java.util.function.Predicate;

/** Bounded per-viewer presentation deltas. Despawns precede coalesced movement/state updates and new spawns. */
public final class VisibilitySubscriptions implements AutoCloseable {
    public record Limits(int maxViewers, int maxVisiblePerViewer, int maxChangesPerUpdate, SpatialNpcIndex.Budget queryBudget) {
        public Limits {
            Objects.requireNonNull(queryBudget, "queryBudget");
            if (maxViewers < 1 || maxViewers > 100000 || maxVisiblePerViewer < 1 || maxVisiblePerViewer > 65536
                    || maxChangesPerUpdate < 1 || maxChangesPerUpdate > 65536)
                throw new IllegalArgumentException("Invalid visibility limits");
        }
    }
    public record Delta(List<VirtualNpcState.Snapshot> spawned, List<UUID> despawned, List<VirtualNpcState.Snapshot> updated,
                        boolean scanComplete, boolean saturated, int cellsVisited, int candidatesVisited) {
        public Delta { spawned = List.copyOf(spawned); despawned = List.copyOf(despawned); updated = List.copyOf(updated); }
        public int changes() { return spawned.size() + despawned.size() + updated.size(); }
    }
    private static final class Viewer {
        private final Map<UUID, VirtualNpcState.Snapshot> sent = new LinkedHashMap<>();
        private final Set<UUID> despawns = new LinkedHashSet<>();
        private SpatialNpcIndex.Cursor cursor;
        private int updateOffset;
    }
    private final SpatialNpcIndex index;
    private final Limits limits;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private boolean closed;

    public VisibilitySubscriptions(SpatialNpcIndex index, Limits limits) {
        this.index = Objects.requireNonNull(index, "index"); this.limits = Objects.requireNonNull(limits, "limits");
    }
    public synchronized int viewerCount() { return viewers.size(); }
    public synchronized Set<UUID> visible(UUID viewerId) {
        Viewer viewer = viewers.get(viewerId);
        return viewer == null ? Set.of() : Set.copyOf(viewer.sent.keySet());
    }
    public synchronized int pendingDespawns(UUID viewerId) {
        Viewer viewer = viewers.get(viewerId);
        return viewer == null ? 0 : viewer.despawns.size();
    }

    /** Predicates must use immutable platform snapshots. Existing-body work is bounded by two maxVisible passes, followed by queryBudget. */
    public synchronized Delta update(UUID viewerId, WorldPosition position, double range,
                                     Predicate<ChunkPosition> chunkVisible, Predicate<VirtualNpcState.Snapshot> visibleRule) {
        ensureOpen(); Objects.requireNonNull(viewerId, "viewerId"); Objects.requireNonNull(chunkVisible, "chunkVisible"); Objects.requireNonNull(visibleRule, "visibleRule");
        synchronized (index) {
            SpatialNpcIndex.Query query = new SpatialNpcIndex.Query(position, range);
            Viewer viewer = viewers.get(viewerId);
            if (viewer == null) {
                if (viewers.size() >= limits.maxViewers()) throw new IllegalStateException("Viewer capacity exhausted");
                viewer = new Viewer(); viewers.put(viewerId, viewer);
            }
            // Retire invalid bodies immediately; bounded packet delivery can finish over later updates.
            Iterator<Map.Entry<UUID, VirtualNpcState.Snapshot>> sent = viewer.sent.entrySet().iterator();
            while (sent.hasNext()) {
                Map.Entry<UUID, VirtualNpcState.Snapshot> entry = sent.next();
                VirtualNpcState.Snapshot current = index.find(entry.getKey()).orElse(null);
                if (!eligible(current, position, range, chunkVisible, visibleRule)) {
                    sent.remove(); viewer.despawns.add(entry.getKey());
                }
            }
            List<UUID> despawned = new ArrayList<>();
            Iterator<UUID> pending = viewer.despawns.iterator();
            while (pending.hasNext() && despawned.size() < limits.maxChangesPerUpdate()) { despawned.add(pending.next()); pending.remove(); }
            int remaining = limits.maxChangesPerUpdate() - despawned.size();
            List<VirtualNpcState.Snapshot> updated = new ArrayList<>();
            List<UUID> roundRobin = new ArrayList<>(viewer.sent.keySet());
            int examined = 0;
            while (examined < roundRobin.size() && remaining > 0) {
                int offset = Math.floorMod(viewer.updateOffset, roundRobin.size());
                UUID id = roundRobin.get(offset);
                viewer.updateOffset = offset + 1; examined++;
                VirtualNpcState.Snapshot current = index.find(id).orElse(null);
                if (current != null && !current.equals(viewer.sent.get(id))) {
                    viewer.sent.put(id, current); updated.add(current); remaining--;
                }
            }
            List<VirtualNpcState.Snapshot> spawned = new ArrayList<>();
            int capacity = limits.maxVisiblePerViewer() - viewer.sent.size();
            boolean complete = false;
            int cells = 0, candidates = 0;
            if (remaining > 0 && capacity > 0 && viewer.despawns.isEmpty()) {
                // Limit candidates to output capacity; no unbounded intermediate spawn queue can build up.
                SpatialNpcIndex.Budget budget = new SpatialNpcIndex.Budget(limits.queryBudget().maxCells(),
                        Math.min(limits.queryBudget().maxCandidates(), Math.min(remaining, capacity)));
                SpatialNpcIndex.Scan scan = index.scan(query, viewer.cursor, budget, chunkVisible);
                viewer.cursor = scan.next().orElse(null);
                complete = scan.complete(); cells = scan.cellsVisited(); candidates = scan.candidatesVisited();
                for (VirtualNpcState.Snapshot candidate : scan.candidates()) {
                    if (!viewer.sent.containsKey(candidate.npcId()) && visibleRule.test(candidate)) {
                        viewer.sent.put(candidate.npcId(), candidate); spawned.add(candidate);
                    }
                }
            }
            boolean saturated = !viewer.despawns.isEmpty() || viewer.sent.size() == limits.maxVisiblePerViewer()
                    || despawned.size() + updated.size() + spawned.size() == limits.maxChangesPerUpdate();
            return new Delta(spawned, despawned, updated, complete, saturated, cells, candidates);
        }
    }

    /** Retires this NPC in every viewer immediately; each later update emits its bounded despawn batch. */
    public synchronized void despawn(UUID npcId) {
        index.remove(npcId);
        for (Viewer viewer : viewers.values()) if (viewer.sent.remove(npcId) != null) viewer.despawns.add(npcId);
    }

    /** Connection teardown needs no outgoing queue; return known bodies for callers that still have a live connection. */
    public synchronized Set<UUID> disconnect(UUID viewerId) {
        Viewer viewer = viewers.remove(viewerId);
        if (viewer == null) return Set.of();
        Set<UUID> retired = new LinkedHashSet<>(viewer.sent.keySet()); retired.addAll(viewer.despawns);
        return Set.copyOf(retired);
    }

    private static boolean eligible(VirtualNpcState.Snapshot npc, WorldPosition position, double range,
                                    Predicate<ChunkPosition> chunkVisible, Predicate<VirtualNpcState.Snapshot> rule) {
        return npc != null && npc.active() && npc.position().world().equals(position.world())
                && npc.position().point().distance(position.point()) <= range && chunkVisible.test(npc.position().chunk()) && rule.test(npc);
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Visibility subscriptions are closed"); }
    @Override public synchronized void close() { closed = true; viewers.clear(); }
}
