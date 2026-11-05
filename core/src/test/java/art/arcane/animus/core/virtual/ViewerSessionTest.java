package art.arcane.animus.core.virtual;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.virtual.ViewerSession.Rejection.*;

class ViewerSessionTest {
    private final UUID viewer = UUID.randomUUID(), sessionId = UUID.randomUUID();
    private final WorldPosition eye = new WorldPosition("world", new Vec3(0, 1.6, 0));
    private ViewerSession session(int maximumIds) { return new ViewerSession(viewer, sessionId, new ViewerSession.Limits(2, maximumIds, 100, 4)); }
    private VirtualNpcState npc(double z) {
        VirtualNpcState state = new VirtualNpcState(UUID.randomUUID(), new WorldPosition("world", new Vec3(0, 0, z)), 1, 2, 20);
        state.spawn(); return state;
    }
    private ViewerSession.Request request(int id, long time) { return new ViewerSession.Request(viewer, sessionId, id, eye, new Vec3(0, 0, 1), time); }

    @Test void validatesServerRayAndBoundsLineOfSight() {
        ViewerSession session = session(8); VirtualNpcState npc = npc(4); session.bind(7, npc.snapshot());
        var hit = session.validate(request(7, 10), (origin, point, distance) -> {
            assertEquals(eye, origin); assertEquals(new Vec3(0, 1.6, 3.5), point); assertEquals(3.5, distance); return true;
        });
        assertTrue(hit.accepted()); assertEquals(npc.snapshot(), hit.npc());
        assertEquals(OCCLUDED, session.validate(request(7, 110), (origin, point, distance) -> false).rejection());
    }
    @Test void rejectsForgedViewerSessionUnknownIdWorldAndDirection() {
        ViewerSession session = session(8); session.bind(7, npc(4).snapshot());
        AtomicInteger los = new AtomicInteger(); ViewerSession.LineOfSight clear = (a, b, c) -> { los.incrementAndGet(); return true; };
        assertEquals(WRONG_VIEWER, session.validate(new ViewerSession.Request(UUID.randomUUID(), sessionId, 7, eye, new Vec3(0, 0, 1), 0), clear).rejection());
        assertEquals(STALE_SESSION, session.validate(new ViewerSession.Request(viewer, UUID.randomUUID(), 7, eye, new Vec3(0, 0, 1), 0), clear).rejection());
        assertEquals(UNKNOWN_ENTITY, session.validate(request(999, 0), clear).rejection());
        assertEquals(WRONG_WORLD, session.validate(new ViewerSession.Request(viewer, sessionId, 7, new WorldPosition("other", eye.point()), new Vec3(0, 0, 1), 0), clear).rejection());
        assertEquals(INVALID_DIRECTION, session.validate(new ViewerSession.Request(viewer, sessionId, 7, eye, Vec3.ZERO, 0), clear).rejection());
        assertEquals(0, los.get());
    }
    @Test void reachMissesAndSpamDoNotExecuteWorldQueries() {
        ViewerSession session = session(8); session.bind(7, npc(6).snapshot());
        AtomicInteger calls = new AtomicInteger(); ViewerSession.LineOfSight clear = (a, b, c) -> { calls.incrementAndGet(); return true; };
        assertEquals(OUT_OF_REACH, session.validate(request(7, 100), clear).rejection());
        for (int i = 0; i < 1000; i++) assertEquals(RATE_LIMITED, session.validate(request(7, 101), clear).rejection());
        assertEquals(RATE_LIMITED, session.validate(request(7, 99), clear).rejection());
        assertEquals(0, calls.get());
        assertEquals(OUT_OF_REACH, session.validate(request(7, 200), clear).rejection());
    }
    @Test void retiredIdsCannotBeReboundOrResurrectedAndBudgetsStayBounded() {
        ViewerSession session = session(3); var snapshot = npc(3).snapshot();
        session.bind(1, snapshot); session.bind(2, snapshot);
        assertThrows(IllegalStateException.class, () -> session.bind(3, snapshot));
        session.unbind(1); session.bind(3, snapshot);
        session.clear();
        assertEquals(0, session.visibleCount()); assertEquals(3, session.issuedCount());
        assertThrows(IllegalArgumentException.class, () -> session.bind(1, snapshot));
        assertThrows(IllegalArgumentException.class, () -> session.updateSnapshot(1, snapshot));
        assertThrows(IllegalStateException.class, () -> session.bind(4, snapshot));
        assertEquals(UNKNOWN_ENTITY, session.validate(request(1, 0), (a, b, c) -> true).rejection());
        session.close(); assertEquals(0, session.issuedCount());
        assertEquals(CLOSED, session.validate(request(3, 0), (a, b, c) -> true).rejection());
        assertThrows(IllegalStateException.class, () -> session.bind(5, snapshot));
    }
    @Test void healthMovementWorldChangeAndDespawnInvalidateAuthority() {
        ViewerSession session = session(10); VirtualNpcState state = npc(3); session.bind(10, state.snapshot());
        var before = state.snapshot(); session.updateSnapshot(10, state.damage(3));
        assertEquals(17, session.validate(request(10, 0), (a, b, c) -> true).npc().health());
        assertThrows(IllegalArgumentException.class, () -> session.updateSnapshot(10, before));
        assertThrows(IllegalArgumentException.class, () -> session.updateSnapshot(10, npc(3).snapshot()));
        session.updateSnapshot(10, state.move(new WorldPosition("other", new Vec3(0, 0, 3))));
        assertEquals(UNKNOWN_ENTITY, session.validate(request(10, 100), (a, b, c) -> true).rejection());
        state.move(new WorldPosition("world", new Vec3(0, 0, 3))); session.bind(11, state.snapshot());
        session.updateSnapshot(11, state.damage(50));
        assertEquals(0, state.snapshot().health()); assertFalse(state.snapshot().active());
        assertEquals(UNKNOWN_ENTITY, session.validate(request(11, 200), (a, b, c) -> true).rejection());
        assertThrows(IllegalStateException.class, state::spawn);
        state.respawn(new WorldPosition("world", new Vec3(0, 0, 3))); session.bind(12, state.snapshot());
        session.despawn(state.snapshot().npcId()); assertEquals(0, session.visibleCount());
    }
    @Test void reentrantCancellationDuringLineOfSightCannotApproveAnOldSnapshot() {
        ViewerSession session = session(3); session.bind(1, npc(3).snapshot());
        assertEquals(STATE_CHANGED, session.validate(request(1, 0), (a, b, c) -> { session.clear(); return true; }).rejection());
    }

    @Test void hotloadedLimitsKeepVisibleBodiesAndGateOnlyNewBinds() {
        ViewerSession session = session(64);
        session.bind(1, npc(3).snapshot()); session.bind(2, npc(4).snapshot());
        assertThrows(IllegalStateException.class, () -> session.bind(3, npc(5).snapshot()));
        session.limits(new ViewerSession.Limits(4, 64, 100, 4));
        session.bind(3, npc(5).snapshot()); session.bind(4, npc(6).snapshot());
        assertEquals(4, session.visibleCount());
        session.limits(new ViewerSession.Limits(1, 64, 100, 4));
        assertEquals(4, session.visibleCount(), "lowering the limit retires nothing");
        assertEquals(1, session.limits().maxVisible());
        assertThrows(IllegalStateException.class, () -> session.bind(5, npc(7).snapshot()));
        session.unbind(1); session.unbind(2); session.unbind(3);
        assertThrows(IllegalStateException.class, () -> session.bind(5, npc(7).snapshot()), "still at the lowered limit");
        session.unbind(4);
        session.bind(5, npc(7).snapshot());
    }
}
