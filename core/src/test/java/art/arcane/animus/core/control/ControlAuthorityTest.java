package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlAuthorityTest {
    private static final ControlInput FORWARD = new ControlInput(true,false,false,false,false,false,false);
    @Test void diagonalsNormalizeAndOppositesCancelWithoutJumpOrSprintSpeed() {
        Vec3 diagonal = new ControlInput(true,false,false,true,true,false,true).horizontal(0);
        assertEquals(1, diagonal.distance(Vec3.ZERO), 0.0000001);
        assertEquals(0, diagonal.y()); assertTrue(diagonal.x() < 0 && diagonal.z() > 0);
        assertEquals(Vec3.ZERO, new ControlInput(true,true,true,true,true,false,true).horizontal(0));
        assertEquals(-1, FORWARD.horizontal(90).x(), 0.0000001);
        assertThrows(IllegalArgumentException.class, () -> FORWARD.horizontal(Double.NaN));
    }
    @Test void heldInputPersistsThroughHeartbeatsWithoutFreshKeyPackets() {
        ControlInbox inbox = new ControlInbox(0); ControlLoop loop = new ControlLoop(Vec3.ZERO,0);
        inbox.offer(FORWARD,1); inbox.heartbeat(1_999_000_000L);
        assertEquals(ControlLoop.State.PROPOSED,loop.propose(2_000_000_000L,Vec3.ZERO,0,inbox.snapshot()).state());
        assertEquals(1,inbox.snapshot().latest().sequence());
        assertEquals("connection-stale",loop.propose(4_000_000_000L,Vec3.ZERO,0,inbox.snapshot()).reason());
    }
    @Test void oneInputCannotProduceCatchUpStepsOrExceedTheServerStepRate() {
        ControlInbox inbox=new ControlInbox(0); inbox.offer(FORWARD,0);
        ControlLoop loop=new ControlLoop(Vec3.ZERO,0); Vec3 position=Vec3.ZERO;
        for (int millis=0;millis<=1000;millis++) {
            inbox.heartbeat(millis*1_000_000L);
            var next=loop.propose(millis*1_000_000L,position,0,inbox.snapshot());
            if(next.state()==ControlLoop.State.PROPOSED)position=next.position();
        }
        assertEquals(20,loop.ticks()); assertEquals(2,position.z(),0.0000001);
        inbox.heartbeat(10_000_000_000L);
        assertEquals(ControlLoop.State.PROPOSED,loop.propose(10_000_000_000L,position,0,inbox.snapshot()).state());
        assertEquals(ControlLoop.State.THROTTLED,loop.propose(10_000_000_001L,position,0,inbox.snapshot()).state());
    }
    @Test void keyChangesCannotReplaceHeartbeatsAndHeartbeatsCannotAcquireInput() {
        ControlInbox inbox=new ControlInbox(0); ControlLoop loop=new ControlLoop(Vec3.ZERO,0);
        inbox.heartbeat(1_000_000_000L);
        assertEquals(ControlLoop.State.WAITING_INPUT,loop.propose(1_000_000_000L,Vec3.ZERO,0,inbox.snapshot()).state());
        inbox.offer(FORWARD,2_999_999_999L);
        assertEquals("connection-stale",loop.propose(3_000_000_000L,Vec3.ZERO,0,inbox.snapshot()).reason());
    }
    @Test void emptyAdmissionStopAndReplacementDoNotInheritLatchedInput() {
        ControlInbox first=new ControlInbox(0); first.offer(FORWARD,1); first.close("replaced");
        assertFalse(first.offer(FORWARD,2)); assertNull(first.snapshot().latest());
        ControlInbox second=new ControlInbox(3); ControlLoop loop=new ControlLoop(Vec3.ZERO,3);
        assertEquals(ControlLoop.State.WAITING_INPUT,loop.propose(100_000_000L,Vec3.ZERO,0,second.snapshot()).state());
        second.offer(new ControlInput(false,false,false,false,false,true,false),100_000_001L);
        assertEquals("shift",loop.propose(100_000_002L,Vec3.ZERO,0,second.snapshot()).reason());
        second.offer(FORWARD,200_000_000L);
        assertEquals(ControlLoop.State.STOPPED,loop.propose(200_000_000L,Vec3.ZERO,0,second.snapshot()).state());
    }
    @Test void floodClosesRatherThanDroppingTheFinalReleaseAndContinuingMovement() {
        ControlInbox inbox=new ControlInbox(0);
        for(int i=0;i<80;i++)assertTrue(inbox.offer(FORWARD,i));
        assertFalse(inbox.offer(ControlInput.EMPTY,81));
        assertFalse(inbox.snapshot().active()); assertEquals("input-rate",inbox.snapshot().reason()); assertNull(inbox.snapshot().latest());
    }
    @Test void leashAndLifetimeBoundTheControllerEvenWithContinuingHeartbeats() {
        ControlInbox inbox=new ControlInbox(0); inbox.offer(FORWARD,0); inbox.heartbeat(1_000_000_000L);
        ControlLoop loop=new ControlLoop(Vec3.ZERO,0);
        assertEquals(ControlLoop.State.LEASH,loop.propose(1_000_000_000L,new Vec3(0,0,8),0,inbox.snapshot()).state());
        inbox.heartbeat(ControlLoop.LIFETIME_NANOS);
        assertEquals("expired",loop.propose(ControlLoop.LIFETIME_NANOS,Vec3.ZERO,0,inbox.snapshot()).reason());
    }
}
