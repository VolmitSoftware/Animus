package art.arcane.animus.core.virtual;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.virtual.SpatialNpcIndexTest.npc;

class VisibilitySubscriptionsTest {
    private static final WorldPosition CENTER = new WorldPosition("world", new Vec3(8, 64, 8));
    private VisibilitySubscriptions subscriptions(SpatialNpcIndex index, int viewers, int visible, int changes) {
        return new VisibilitySubscriptions(index, new VisibilitySubscriptions.Limits(viewers, visible, changes, new SpatialNpcIndex.Budget(4, 20)));
    }
    private void fill(VisibilitySubscriptions subscriptions, UUID viewer, int expected) {
        for (int i = 0; i < 100 && subscriptions.visible(viewer).size() < expected; i++) subscriptions.update(viewer, CENTER, 4, c -> true, n -> true);
        assertEquals(expected, subscriptions.visible(viewer).size());
    }
    @Test void worldChangesRetireImmediatelyAndDrainDespawnsBeforeNewSpawns() {
        SpatialNpcIndex index = new SpatialNpcIndex(5);
        for (int i = 1; i <= 4; i++) index.upsert(npc(i, "world", 8, 8).snapshot());
        index.upsert(npc(5, "other", 8, 8).snapshot());
        var subscriptions = subscriptions(index, 1, 4, 2); UUID viewer = UUID.randomUUID(); fill(subscriptions, viewer, 4);
        WorldPosition other = new WorldPosition("other", CENTER.point());
        var first = subscriptions.update(viewer, other, 4, c -> true, n -> true);
        assertEquals(2, first.despawned().size()); assertTrue(first.spawned().isEmpty());
        assertTrue(subscriptions.visible(viewer).isEmpty()); assertEquals(2, subscriptions.pendingDespawns(viewer));
        var second = subscriptions.update(viewer, other, 4, c -> true, n -> true);
        assertEquals(2, second.despawned().size()); assertTrue(second.spawned().isEmpty());
        var third = subscriptions.update(viewer, other, 4, c -> true, n -> true);
        assertEquals(new UUID(0, 5), third.spawned().getFirst().npcId());
    }
    @Test void unloadedChunksRangeRulesDespawnAndDisconnectCleanUp() {
        SpatialNpcIndex index = new SpatialNpcIndex(2); var first = npc(1, "world", 8, 8); index.upsert(first.snapshot());
        var subscriptions = subscriptions(index, 1, 2, 2); UUID viewer = UUID.randomUUID(); fill(subscriptions, viewer, 1);
        assertEquals(1, subscriptions.update(viewer, CENTER, 4, c -> false, n -> true).despawned().size());
        assertTrue(subscriptions.visible(viewer).isEmpty());
        assertTrue(subscriptions.update(viewer, CENTER, 4, c -> true, n -> false).spawned().isEmpty());
        fill(subscriptions, viewer, 1);
        assertEquals(1, subscriptions.update(viewer, new WorldPosition("world", new Vec3(100, 64, 100)), 4, c -> true, n -> true).despawned().size());
        fill(subscriptions, viewer, 1);
        subscriptions.despawn(first.snapshot().npcId());
        assertEquals(0, index.size()); assertTrue(subscriptions.visible(viewer).isEmpty()); assertEquals(1, subscriptions.pendingDespawns(viewer));
        assertEquals(Set.of(first.snapshot().npcId()), subscriptions.disconnect(viewer));
        assertEquals(0, subscriptions.viewerCount()); assertEquals(0, subscriptions.pendingDespawns(viewer));
        assertTrue(subscriptions.disconnect(viewer).isEmpty());
    }
    @Test void coalescesLatestSnapshotsAndRotatesMovingUpdatesFairly() {
        SpatialNpcIndex index = new SpatialNpcIndex(3); List<VirtualNpcState> states = new ArrayList<>();
        for (int i = 1; i <= 3; i++) { var state = npc(i, "world", 8, 8); states.add(state); index.upsert(state.snapshot()); }
        var subscriptions = subscriptions(index, 1, 3, 1); UUID viewer = UUID.randomUUID(); fill(subscriptions, viewer, 3);
        Set<UUID> updated = new HashSet<>();
        for (int tick = 1; tick <= 3; tick++) {
            for (var state : states) {
                state.move(new WorldPosition("world", new Vec3(8 + tick * 0.1, 64, 8)));
                index.upsert(state.move(new WorldPosition("world", new Vec3(8 + tick * 0.2, 64, 8))));
            }
            var delta = subscriptions.update(viewer, CENTER, 4, c -> true, n -> true);
            assertEquals(1, delta.changes()); assertEquals(1, delta.updated().size());
            assertEquals(8 + tick * 0.2, delta.updated().getFirst().position().point().x());
            updated.add(delta.updated().getFirst().npcId());
        }
        assertEquals(3, updated.size());
    }
    @Test void despawnsTakePriorityOverMovingBodiesAndNewSpawns() {
        SpatialNpcIndex index = new SpatialNpcIndex(4); var first = npc(1, "world", 8, 8); var second = npc(2, "world", 8, 8);
        index.upsert(first.snapshot()); index.upsert(second.snapshot());
        var subscriptions = subscriptions(index, 1, 3, 1); UUID viewer = UUID.randomUUID(); fill(subscriptions, viewer, 2);
        subscriptions.despawn(first.snapshot().npcId()); index.upsert(second.move(new WorldPosition("world", new Vec3(8.5, 64, 8))));
        index.upsert(npc(3, "world", 8, 8).snapshot());
        var delta = subscriptions.update(viewer, CENTER, 4, c -> true, n -> true);
        assertEquals(List.of(first.snapshot().npcId()), delta.despawned()); assertTrue(delta.updated().isEmpty()); assertTrue(delta.spawned().isEmpty());
        var next = subscriptions.update(viewer, CENTER, 4, c -> true, n -> true);
        assertEquals(List.of(second.snapshot()), next.updated()); assertTrue(next.spawned().isEmpty());
    }
    @Test void viewerVisibilityAndPerUpdateWorkStayWithinBudgetsInDenseCrowds() {
        SpatialNpcIndex index = new SpatialNpcIndex(100);
        for (int i = 1; i <= 100; i++) index.upsert(npc(i, "world", 8, 8).snapshot());
        var subscriptions = subscriptions(index, 1, 2, 1); UUID viewer = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            var delta = subscriptions.update(viewer, CENTER, 4, c -> true, n -> true);
            assertTrue(delta.changes() <= 1); assertTrue(delta.candidatesVisited() <= 1); assertTrue(delta.cellsVisited() <= 4);
            assertTrue(subscriptions.visible(viewer).size() <= 2); assertTrue(subscriptions.pendingDespawns(viewer) <= 2);
        }
        assertEquals(2, subscriptions.visible(viewer).size());
        assertThrows(IllegalStateException.class, () -> subscriptions.update(UUID.randomUUID(), CENTER, 4, c -> true, n -> true));
        subscriptions.close(); assertEquals(0, subscriptions.viewerCount());
        assertThrows(IllegalStateException.class, () -> subscriptions.update(viewer, CENTER, 4, c -> true, n -> true));
    }
}
