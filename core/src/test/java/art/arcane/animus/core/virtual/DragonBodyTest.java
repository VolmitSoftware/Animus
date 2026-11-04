package art.arcane.animus.core.virtual;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DragonBodyTest {
    private static final Vec3 FEET = new Vec3(100, 70, -40);
    private static Vec3 center(Hitbox box) { return new Vec3((box.min().x() + box.max().x()) / 2, box.min().y(), (box.min().z() + box.max().z()) / 2); }
    private static VirtualNpcState.Snapshot dragon() { return new VirtualNpcState(UUID.randomUUID(), new WorldPosition("w", FEET), 16, 8, 200).spawn(); }

    @Test void nativeDragonModelFacesOppositeItsWireRotationSoFacingIsMirrored() {
        assertEquals(180, Math.abs(DragonBody.wireYaw(0)), 1e-6);
        assertEquals(-90, DragonBody.wireYaw(90), 1e-6);
        assertEquals(90, DragonBody.wireYaw(-90), 1e-6);
        assertEquals(0, DragonBody.wireYaw(180), 1e-6);
        for (float yaw = -720; yaw <= 720; yaw += 7.5f) {
            float wire = DragonBody.wireYaw(yaw);
            assertTrue(wire >= -180 && wire <= 180, "wrapped: " + wire);
        }
    }
    @Test void partLayoutMatchesTheNativeOffsetsForAFacingDirection() {
        List<Hitbox> parts = DragonBody.parts(FEET, 0);
        assertEquals(DragonBody.PART_COUNT, parts.size());
        // Facing +Z, the head extends forward and the tail tip trails 7.5 blocks behind.
        assertEquals(100, center(parts.get(0)).x(), 1e-5);
        assertEquals(-40 + 6.5, center(parts.get(0)).z(), 1e-5);
        assertEquals(1, parts.get(0).max().x() - parts.get(0).min().x(), 1e-6);
        assertEquals(-40 + 5.5, center(parts.get(1)).z(), 1e-5);
        assertEquals(-40 + 0.5, center(parts.get(2)).z(), 1e-5);
        assertEquals(5, parts.get(2).max().x() - parts.get(2).min().x(), 1e-6);
        assertEquals(-40 - 3.5, center(parts.get(3)).z(), 1e-5);
        assertEquals(-40 - 5.5, center(parts.get(4)).z(), 1e-5);
        assertEquals(-40 - 7.5, center(parts.get(5)).z(), 1e-5);
        assertEquals(71.5, parts.get(5).min().y(), 1e-5);
        assertEquals(72, parts.get(6).min().y(), 1e-5);
        assertEquals(4.5, Math.abs(center(parts.get(6)).x() - 100), 1e-5);
        assertEquals(4.5, Math.abs(center(parts.get(7)).x() - 100), 1e-5);
        assertNotEquals(center(parts.get(6)).x(), center(parts.get(7)).x());
    }
    @Test void hitShapeUsesServerGeometryNotTheClientsClaimedPart() {
        ViewerSession.HitShape shape = DragonBody.hitShape(0);
        VirtualNpcState.Snapshot snapshot = dragon();
        // Aiming at the head from in front connects.
        OptionalDouble head = shape.rayDistance(snapshot, new Vec3(100, 70.5, -30), new Vec3(0, 0, -1), 4.5);
        assertTrue(head.isPresent());
        // Beyond the wing tip but inside the broad 16-wide bounds nothing is really there, so it cannot be claimed.
        Vec3 aside = new Vec3(107.5, 70.5, -36), along = new Vec3(0, 0, -1);
        assertTrue(snapshot.bounds().rayDistance(aside, along, 4.5).isPresent());
        assertTrue(shape.rayDistance(snapshot, aside, along, 4.5).isEmpty());
    }
    @Test void tailTipOutsideTheBroadBoundsStaysHittable() {
        VirtualNpcState.Snapshot snapshot = dragon();
        Vec3 eye = new Vec3(100, 72, -50.5), ahead = new Vec3(0, 0, 1);
        assertTrue(snapshot.bounds().rayDistance(eye, ahead, 1.5).isEmpty(), "outside the 16-wide box");
        assertTrue(DragonBody.hitShape(0).rayDistance(snapshot, eye, ahead, 1.5).isPresent());
    }
    @Test void rotatedDragonTurnsItsHitAreaWithIt() {
        VirtualNpcState.Snapshot snapshot = dragon();
        // Facing -X (yaw 90): the head is 6.5 blocks toward -X and the same ray misses when facing +X.
        Vec3 eye = new Vec3(90, 69.5, -40), east = new Vec3(1, 0, 0);
        assertTrue(DragonBody.hitShape(90).rayDistance(snapshot, eye, east, 4.5).isPresent());
        assertTrue(DragonBody.hitShape(-90).rayDistance(snapshot, eye, east, 2.0).isEmpty());
    }
    @Test void viewerSessionValidatesThroughTheSuppliedShapeUsingOnlyAuthoritativeState() {
        UUID viewer = UUID.randomUUID(), session = UUID.randomUUID();
        VirtualNpcState.Snapshot snapshot = dragon();
        try (ViewerSession guard = new ViewerSession(viewer, session, new ViewerSession.Limits(4, 16, 0, 4.5))) {
            guard.bind(50, snapshot);
            ViewerSession.Request hit = new ViewerSession.Request(viewer, session, 50, new WorldPosition("w", new Vec3(100, 70.5, -30)), new Vec3(0, 0, -1), 0);
            assertTrue(guard.validate(hit, (eye, point, distance) -> true, DragonBody.hitShape(0)).accepted());
            ViewerSession.Request miss = new ViewerSession.Request(viewer, session, 50, new WorldPosition("w", new Vec3(107.5, 70.5, -36)), new Vec3(0, 0, -1), 1);
            assertEquals(ViewerSession.Rejection.OUT_OF_REACH, guard.validate(miss, (eye, point, distance) -> true, DragonBody.hitShape(0)).rejection());
            assertTrue(guard.validate(miss, (eye, point, distance) -> true).accepted(), "the default shape stays the broad bounds");
        }
    }
    @Test void phaseFollowsTheSemanticMotionState() {
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, DragonBody.phase(true, true));
        assertEquals(DragonBody.PHASE_HOLDING_PATTERN, DragonBody.phase(true, false));
        assertEquals(DragonBody.PHASE_SITTING_SCANNING, DragonBody.phase(false, true));
        assertEquals(DragonBody.PHASE_HOVERING, DragonBody.phase(false, false));
    }
    @Test void reservedIdsCoverTheRootAndEveryPart() { assertEquals(DragonBody.PART_COUNT + 1, DragonBody.RESERVED_IDS); }
}
