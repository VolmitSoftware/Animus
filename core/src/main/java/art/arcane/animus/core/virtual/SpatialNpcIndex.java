package art.arcane.animus.core.virtual;

import java.util.*;
import java.util.function.Predicate;

/** Chunk-indexed active NPC snapshots. Queries visit bounded cells/candidates, never the entire NPC registry. */
public final class SpatialNpcIndex {
    public record Query(WorldPosition center, double radius) {
        public Query {
            Objects.requireNonNull(center, "center");
            if (!Double.isFinite(radius) || radius < 0 || radius > 4096)
                throw new IllegalArgumentException("Query radius must be within [0, 4096]");
        }
    }
    public record Budget(int maxCells, int maxCandidates) {
        public Budget {
            if (maxCells < 1 || maxCells > 65536 || maxCandidates < 1 || maxCandidates > 65536)
                throw new IllegalArgumentException("Scan budgets must be within 1..65536");
        }
    }
    private record Window(String world, int minX, int maxX, int minZ, int maxZ) {
        int width() { return maxX - minX + 1; }
        int size() { return width() * (maxZ - minZ + 1); }
        ChunkPosition cell(int offset) { return new ChunkPosition(world, minX + offset % width(), minZ + offset / width()); }
    }
    /** Opaque resume position. Movement that changes the queried chunk window safely restarts scanning. */
    public static final class Cursor {
        private final Window window;
        private final int cell;
        private final UUID after;
        private Cursor(Window window, int cell, UUID after) { this.window = window; this.cell = cell; this.after = after; }
    }
    public record Scan(List<VirtualNpcState.Snapshot> candidates, Optional<Cursor> next, int cellsVisited, int candidatesVisited) {
        public Scan { candidates = List.copyOf(candidates); Objects.requireNonNull(next, "next"); }
        public boolean complete() { return next.isEmpty(); }
    }

    private final int maximumEntries;
    private final Map<UUID, VirtualNpcState.Snapshot> entries = new HashMap<>();
    private final Map<ChunkPosition, NavigableMap<UUID, VirtualNpcState.Snapshot>> chunks = new HashMap<>();

    public SpatialNpcIndex(int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > 1_000_000) throw new IllegalArgumentException("Invalid index capacity");
        this.maximumEntries = maximumEntries;
    }
    public synchronized int size() { return entries.size(); }
    public synchronized Optional<VirtualNpcState.Snapshot> find(UUID id) { return Optional.ofNullable(entries.get(id)); }

    public synchronized void upsert(VirtualNpcState.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        VirtualNpcState.Snapshot previous = entries.get(snapshot.npcId());
        if (previous != null && (snapshot.revision() < previous.revision()
                || (snapshot.revision() == previous.revision() && !snapshot.equals(previous))))
            throw new IllegalArgumentException("Stale or conflicting runtime snapshot");
        if (!snapshot.active()) { remove(snapshot.npcId()); return; }
        if (previous == null && entries.size() >= maximumEntries) throw new IllegalStateException("NPC index capacity exhausted");
        if (previous != null) remove(snapshot.npcId());
        entries.put(snapshot.npcId(), snapshot);
        chunks.computeIfAbsent(snapshot.position().chunk(), ignored -> new TreeMap<>()).put(snapshot.npcId(), snapshot);
    }

    public synchronized Optional<VirtualNpcState.Snapshot> remove(UUID npcId) {
        VirtualNpcState.Snapshot removed = entries.remove(npcId);
        if (removed != null) {
            NavigableMap<UUID, VirtualNpcState.Snapshot> bucket = chunks.get(removed.position().chunk());
            bucket.remove(npcId);
            if (bucket.isEmpty()) chunks.remove(removed.position().chunk());
        }
        return Optional.ofNullable(removed);
    }

    /** chunkVisible must read an immutable platform snapshot; this method never loads terrain. */
    public synchronized Scan scan(Query query, Cursor cursor, Budget budget, Predicate<ChunkPosition> chunkVisible) {
        Objects.requireNonNull(query, "query"); Objects.requireNonNull(budget, "budget"); Objects.requireNonNull(chunkVisible, "chunkVisible");
        Vec3 center = query.center().point();
        Window window = new Window(query.center().world(), chunk(center.x() - query.radius()), chunk(center.x() + query.radius()),
                chunk(center.z() - query.radius()), chunk(center.z() + query.radius()));
        int cell = cursor != null && cursor.window.equals(window) ? cursor.cell : 0;
        UUID after = cursor != null && cursor.window.equals(window) ? cursor.after : null;
        int cellsVisited = 0, candidatesVisited = 0;
        List<VirtualNpcState.Snapshot> candidates = new ArrayList<>();
        while (cell < window.size() && cellsVisited < budget.maxCells() && candidatesVisited < budget.maxCandidates()) {
            ChunkPosition chunk = window.cell(cell);
            cellsVisited++;
            NavigableMap<UUID, VirtualNpcState.Snapshot> bucket = chunks.get(chunk);
            if (bucket == null || !chunkVisible.test(chunk)) { cell++; after = null; continue; }
            Iterator<VirtualNpcState.Snapshot> iterator = (after == null ? bucket : bucket.tailMap(after, false)).values().iterator();
            while (iterator.hasNext() && candidatesVisited < budget.maxCandidates()) {
                VirtualNpcState.Snapshot candidate = iterator.next();
                after = candidate.npcId();
                candidatesVisited++;
                if (center.distance(candidate.position().point()) <= query.radius()) candidates.add(candidate);
            }
            if (iterator.hasNext()) break;
            cell++; after = null;
        }
        Optional<Cursor> next = cell < window.size() ? Optional.of(new Cursor(window, cell, after)) : Optional.empty();
        return new Scan(candidates, next, cellsVisited, candidatesVisited);
    }

    private static int chunk(double coordinate) { return (int) Math.floor(coordinate / 16); }
}
