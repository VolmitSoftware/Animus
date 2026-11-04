package art.arcane.animus.core.virtual;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SpatialNpcIndexTest {
    static VirtualNpcState npc(int id, String world, double x, double z) {
        VirtualNpcState state = new VirtualNpcState(new UUID(0, id), new WorldPosition(world, new Vec3(x, 64, z)), 0.6, 1.8, 20);
        state.spawn(); return state;
    }
    @Test void skipsOtherWorldsAndDistantCellsWithoutScanningTheirBodies() {
        SpatialNpcIndex index = new SpatialNpcIndex(5000);
        index.upsert(npc(1, "world", 8, 8).snapshot());
        for (int i = 2; i <= 4999; i++) index.upsert(npc(i, "world", 1600 + i, 8).snapshot());
        index.upsert(npc(5000, "other", 8, 8).snapshot());
        AtomicInteger chunkReads = new AtomicInteger();
        var scan = index.scan(new SpatialNpcIndex.Query(new WorldPosition("world", new Vec3(8, 64, 8)), 7), null,
                new SpatialNpcIndex.Budget(1, 1), c -> { chunkReads.incrementAndGet(); return true; });
        assertEquals(1, scan.candidates().size()); assertEquals(new UUID(0, 1), scan.candidates().getFirst().npcId());
        assertEquals(1, scan.candidatesVisited()); assertEquals(1, scan.cellsVisited()); assertEquals(1, chunkReads.get()); assertTrue(scan.complete());
    }
    @Test void resumesDenseCellsWithoutDuplicatingCandidates() {
        SpatialNpcIndex index = new SpatialNpcIndex(20);
        for (int i = 1; i <= 20; i++) index.upsert(npc(i, "world", 8, 8).snapshot());
        var query = new SpatialNpcIndex.Query(new WorldPosition("world", new Vec3(8, 64, 8)), 1);
        SpatialNpcIndex.Cursor cursor = null; Set<UUID> seen = new HashSet<>(); int calls = 0;
        do {
            var scan = index.scan(query, cursor, new SpatialNpcIndex.Budget(1, 3), c -> true);
            assertTrue(scan.candidatesVisited() <= 3); assertEquals(1, scan.cellsVisited());
            for (var candidate : scan.candidates()) assertTrue(seen.add(candidate.npcId()));
            cursor = scan.next().orElse(null); calls++;
        } while (cursor != null && calls < 20);
        assertEquals(20, seen.size()); assertEquals(7, calls); assertNull(cursor);
    }
    @Test void boundsEmptyWorldCellWorkAndHonorsChunkAvailability() {
        SpatialNpcIndex index = new SpatialNpcIndex(10); index.upsert(npc(1, "world", 8, 8).snapshot());
        var query = new SpatialNpcIndex.Query(new WorldPosition("world", new Vec3(8, 64, 8)), 64);
        var scan = index.scan(query, null, new SpatialNpcIndex.Budget(2, 10), c -> true);
        assertEquals(2, scan.cellsVisited()); assertFalse(scan.complete());
        var unavailable = index.scan(new SpatialNpcIndex.Query(query.center(), 1), null, new SpatialNpcIndex.Budget(1, 10), c -> false);
        assertTrue(unavailable.candidates().isEmpty()); assertEquals(0, unavailable.candidatesVisited()); assertTrue(unavailable.complete());
    }
    @Test void reindexesMovesAndRejectsStaleUpdatesAndCapacityOverflow() {
        SpatialNpcIndex index = new SpatialNpcIndex(1); var state = npc(1, "world", 8, 8); var initial = state.snapshot(); index.upsert(initial);
        assertThrows(IllegalStateException.class, () -> index.upsert(npc(2, "world", 8, 8).snapshot()));
        index.upsert(state.move(new WorldPosition("other", new Vec3(8, 64, 8))));
        assertThrows(IllegalArgumentException.class, () -> index.upsert(initial));
        var budget = new SpatialNpcIndex.Budget(1, 1);
        assertTrue(index.scan(new SpatialNpcIndex.Query(initial.position(), 1), null, budget, c -> true).candidates().isEmpty());
        assertEquals(1, index.scan(new SpatialNpcIndex.Query(state.snapshot().position(), 1), null, budget, c -> true).candidates().size());
        index.upsert(state.despawn()); assertEquals(0, index.size()); assertTrue(index.find(initial.npcId()).isEmpty());
    }
    @Test void cursorFromAnotherWorldCannotLeakEntities() {
        SpatialNpcIndex index = new SpatialNpcIndex(3);
        index.upsert(npc(1, "world", 8, 8).snapshot()); index.upsert(npc(2, "world", 8, 8).snapshot()); index.upsert(npc(3, "other", 8, 8).snapshot());
        var budget = new SpatialNpcIndex.Budget(1, 1);
        var first = index.scan(new SpatialNpcIndex.Query(new WorldPosition("world", new Vec3(8, 64, 8)), 1), null, budget, c -> true);
        var next = index.scan(new SpatialNpcIndex.Query(new WorldPosition("other", new Vec3(8, 64, 8)), 1), first.next().orElseThrow(), budget, c -> true);
        assertEquals(new UUID(0, 3), next.candidates().getFirst().npcId()); assertTrue(next.complete());
    }
}
