package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RideSessionTest {
    private static final ControlInput FORWARD = new ControlInput(true, false, false, false, false, false, false);
    private static final long SECOND = 1_000_000_000L, STEP = ControlLoop.STEP_NANOS;
    private static final RideSteering.Probe CLEAR = (from, to) -> new RideSteering.Result(RideSteering.Outcome.MOVED, to, true, "clear");
    private static final RideSettings SETTINGS = new RideSettings("animus.ride", 4, 16, 120);
    private final Vec3 anchor = new Vec3(0, 64, 0);

    private RideSession riding(long now) {
        RideSession session = new RideSession(UUID.randomUUID(), UUID.randomUUID(), anchor, SETTINGS, false, now);
        assertTrue(session.attached());
        return session;
    }

    @Test void lostConnectionEndsTheRideWithoutMovingTheRider() {
        RideSession closed = riding(0);
        ControlInbox inbox = new ControlInbox(0);
        inbox.close(ControlInbox.CONNECTION_CLOSED);
        RideSession.Step step = closed.tick(STEP, anchor, 0, 0, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.STOP, step.action());
        assertTrue(RideSession.leavesRiderInPlace(step.reason()), step.reason());

        RideSession stale = riding(0);
        ControlInbox silent = new ControlInbox(0);
        RideSession.Step staleStep = stale.tick(ControlLoop.LIVENESS_NANOS + STEP, anchor, 0, 0, 0, silent.snapshot(), CLEAR);
        assertEquals(RideSession.Action.STOP, staleStep.action());
        assertTrue(RideSession.leavesRiderInPlace(staleStep.reason()), staleStep.reason());

        for (String placed : new String[] {"session-retired", "shift", "expired", "terrain-unavailable", "attach-timeout"})
            assertFalse(RideSession.leavesRiderInPlace(placed), placed);
    }
    @Test void beginsAttachingAndCannotSteerUntilTheAttachPacketCompleted() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession session = new RideSession(UUID.randomUUID(), UUID.randomUUID(), anchor, SETTINGS, false, 0);
        assertEquals(RideSession.State.ATTACHING, session.state());
        RideSession.Step step = session.tick(STEP, anchor, 0, 0, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.WAIT, step.action());
        assertEquals(anchor, step.position());
        assertTrue(session.attached());
        assertFalse(session.attached(), "attach completes once");
        assertEquals(RideSession.State.RIDING, session.state());
    }
    @Test void attachThatNeverCompletesIsAbandonedRatherThanLeftHanging() {
        ControlInbox inbox = new ControlInbox(0);
        RideSession session = new RideSession(UUID.randomUUID(), UUID.randomUUID(), anchor, SETTINGS, false, 0);
        inbox.heartbeat(6 * SECOND);
        RideSession.Step step = session.tick(6 * SECOND, anchor, 0, 0, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.STOP, step.action());
        assertEquals("attach-timeout", step.reason());
        assertEquals(RideSession.State.CLOSED, session.state());
    }
    @Test void heldForwardMovesOneBoundedStepAlongTheRidersYaw() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession session = riding(0);
        RideSession.Step step = session.tick(STEP, anchor, 0, 90, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.MOVE, step.action());
        assertEquals(-0.2, step.position().x(), 1e-9, "speed 4 blocks/second is 0.2 per step");
        assertEquals(90, step.yaw());
        assertEquals(1, session.steps());
    }
    @Test void noInputCannotMoveAndFloodingOrKeyChangesNeverIncreaseSpeed() {
        ControlInbox inbox = new ControlInbox(0);
        RideSession session = riding(0);
        assertEquals(RideSession.Action.WAIT, session.tick(STEP, anchor, 0, 0, 0, inbox.snapshot(), CLEAR).action());
        Vec3 position = anchor;
        for (long tick = 2; tick <= 21; tick++) {
            long now = tick * STEP;
            inbox.heartbeat(now);
            inbox.offer(FORWARD, now);
            inbox.offer(ControlInput.EMPTY, now);
            inbox.offer(FORWARD, now);
            position = session.tick(now, position, 0, 0, 0, inbox.snapshot(), CLEAR).position();
        }
        assertEquals(4, position.z(), 1e-9, "20 steps at 0.2 blocks regardless of packet count");
    }
    @Test void turningWithoutMovingUpdatesYawOnlyPastTheThreshold() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(ControlInput.EMPTY, 0);
        RideSession session = riding(0);
        RideSession.Step turn = session.tick(STEP, anchor, 0, 45, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.TURN, turn.action());
        assertEquals(45, turn.yaw()); assertEquals(anchor, turn.position());
        assertEquals(RideSession.Action.WAIT, session.tick(STEP * 2, anchor, 45, 45.2f, 0, inbox.snapshot(), CLEAR).action());
    }
    @Test void shiftExcessInputStaleHeartbeatLifetimeAndTerrainEachEndTheRideWithTheirReason() {
        ControlInbox shift = new ControlInbox(0); shift.offer(new ControlInput(false, false, false, false, false, true, false), 0);
        assertEquals("shift", riding(0).tick(STEP, anchor, 0, 0, 0, shift.snapshot(), CLEAR).reason());

        ControlInbox flood = new ControlInbox(0);
        for (int i = 0; i <= ControlInbox.MAX_INPUTS_PER_SECOND; i++) flood.offer(FORWARD, 0);
        assertEquals("input-rate", riding(0).tick(STEP, anchor, 0, 0, 0, flood.snapshot(), CLEAR).reason());

        ControlInbox stale = new ControlInbox(0);
        assertEquals("connection-stale", riding(0).tick(ControlLoop.LIVENESS_NANOS, anchor, 0, 0, 0, stale.snapshot(), CLEAR).reason());

        ControlInbox alive = new ControlInbox(0);
        alive.heartbeat(120 * SECOND);
        assertEquals("expired", riding(0).tick(120 * SECOND, anchor, 0, 0, 0, alive.snapshot(), CLEAR).reason());

        ControlInbox moving = new ControlInbox(0); moving.offer(FORWARD, 0);
        RideSteering.Probe missing = (from, to) -> new RideSteering.Result(RideSteering.Outcome.TERRAIN_UNAVAILABLE, from, true, "unloaded");
        RideSession terrain = riding(0);
        assertEquals("terrain-unavailable", terrain.tick(STEP, anchor, 0, 0, 0, moving.snapshot(), missing).reason());
        assertEquals(RideSession.State.CLOSED, terrain.state());
    }
    @Test void blockedTerrainKeepsTheRideAndTheAuthoritativePosition() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSteering.Probe wall = (from, to) -> new RideSteering.Result(RideSteering.Outcome.BLOCKED, from, true, "wall");
        RideSession session = riding(0);
        RideSession.Step step = session.tick(STEP, anchor, 0, 0, 0, inbox.snapshot(), wall);
        assertEquals(RideSession.Action.BLOCKED, step.action());
        assertEquals(anchor, step.position());
        assertEquals(RideSession.State.RIDING, session.state());
        assertEquals(0, session.steps());
    }
    @Test void leashHoldsTheBodyAtTheBoundaryWithoutEndingTheRide() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession session = riding(0);
        Vec3 edge = new Vec3(0, 70, 15.9);
        RideSession.Step step = session.tick(STEP, edge, 0, 0, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.LEASH, step.action());
        assertEquals(edge, step.position());
        assertEquals(RideSession.State.RIDING, session.state());
    }
    @Test void verticalTravelFromSteppingDoesNotCountAgainstTheHorizontalLeash() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession session = riding(0);
        assertEquals(RideSession.Action.MOVE, session.tick(STEP, new Vec3(0, 90, 0), 0, 0, 0, inbox.snapshot(), CLEAR).action());
    }
    @Test void closingIsIdempotentKeepsTheFirstReasonAndLateCompletionCannotRestart() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession session = new RideSession(UUID.randomUUID(), UUID.randomUUID(), anchor, SETTINGS, false, 0);
        assertTrue(session.close("death"));
        assertFalse(session.close("teleport"));
        assertEquals("death", session.reason());
        assertFalse(session.attached());
        assertEquals(RideSession.State.CLOSED, session.state());
        RideSession.Step step = session.tick(STEP, anchor, 0, 0, 0, inbox.snapshot(), CLEAR);
        assertEquals(RideSession.Action.STOP, step.action());
        assertEquals(anchor, step.position());
    }
    @Test void aFlyingBodyFollowsTheLookPitchAndReportsItIsOffTheGround() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession session = new RideSession(UUID.randomUUID(), UUID.randomUUID(), anchor, SETTINGS, true, 0);
        assertTrue(session.attached());
        RideSteering.Probe air = (from, to) -> new RideSteering.Result(RideSteering.Outcome.MOVED, to, false, "flying");
        RideSession.Step step = session.tick(STEP, anchor, 0, 0, 90, inbox.snapshot(), air);
        assertEquals(RideSession.Action.MOVE, step.action());
        assertEquals(63.8, step.position().y(), 1e-9);
        assertFalse(step.grounded());
    }
    @Test void aWalkingBodyIgnoresTheLookPitch() {
        ControlInbox inbox = new ControlInbox(0); inbox.offer(FORWARD, 0);
        RideSession.Step step = riding(0).tick(STEP, anchor, 0, 0, 90, inbox.snapshot(), CLEAR);
        assertEquals(64, step.position().y(), 1e-9);
        assertEquals(0.2, step.position().z(), 1e-9);
        assertTrue(step.grounded());
    }
}
