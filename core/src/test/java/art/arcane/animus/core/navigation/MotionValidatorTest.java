package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.navigation.VoxelSnapshot.Cell.*;
import static art.arcane.animus.core.navigation.MotionValidator.*;

class MotionValidatorTest {
    private static final Vec3 START = new Vec3(.5, 1, .5);
    @Test void revalidatesNewObstructionInsteadOfTrustingPreviouslyClearPath() {
        Sampler clear = (x,y,z) -> y == 0 ? SOLID : AIR;
        Vec3 next = new Vec3(.75, 1, .5);
        assertTrue(check(START,next,.6,1.8,Medium.GROUND,true,128,clear).clear());
        assertEquals(Status.BLOCKED,check(START,next,.6,1.8,Medium.GROUND,true,128,(x,y,z) -> x == 1 && y == 1 ? SOLID : clear.sample(x,y,z)).status());
    }
    @Test void partialBlockAndPlantCannotProvideGroundOrClearance() {
        assertEquals(Status.UNSUPPORTED_GEOMETRY,check(START,START,.6,1.8,Medium.GROUND,true,128,(x,y,z)->y==0?OBSTACLE:AIR).status());
        assertEquals(Status.UNSUPPORTED_GEOMETRY,check(START,START,.6,1.8,Medium.AIR,false,128,(x,y,z)->OBSTACLE).status());
    }
    @Test void unavailableTerrainIsNotAirOrARequestToSampleFurtherCells() {
        AtomicInteger calls=new AtomicInteger();
        var result=check(START,new Vec3(.75,1,.5),.6,1.8,Medium.GROUND,true,128,(x,y,z)->{calls.incrementAndGet();return UNAVAILABLE;});
        assertEquals(Status.UNAVAILABLE_TERRAIN,result.status());assertEquals(1,calls.get());
    }
    @Test void volumeBudgetRejectsBeforeWorldReadsAndCountsActualReads() {
        AtomicInteger calls=new AtomicInteger();
        assertEquals(Status.BUDGET_EXHAUSTED,check(START,START,4,8,Medium.GROUND,true,8,(x,y,z)->{calls.incrementAndGet();return AIR;}).status());
        assertEquals(0,calls.get());
        var result=check(START,START,.6,1.8,Medium.GROUND,true,128,(x,y,z)->{calls.incrementAndGet();return y==0?SOLID:AIR;});
        assertTrue(result.clear());assertEquals(calls.get(),result.sampledCells());assertEquals(36,result.sampledCells());
    }
    @Test void swimmingNeedsFluidOrSupportWhileFlyingNeedsNeither() {
        Vec3 next=new Vec3(.5,1.25,.5);
        assertTrue(check(START,next,.6,1.8,Medium.WATER,false,128,(x,y,z)->WATER).clear());
        assertEquals(Status.BLOCKED,check(START,next,.6,1.8,Medium.WATER,false,128,(x,y,z)->AIR).status());
        assertTrue(check(START,next,.6,1.8,Medium.AIR,false,128,(x,y,z)->AIR).clear());
        assertEquals(Status.BLOCKED,check(START,next,.6,1.8,Medium.AIR,false,128,(x,y,z)->WATER).status());
    }
    @Test void sweptFootprintRejectsDiagonalGapsAndWideBodies() {
        Vec3 next=new Vec3(.7,1,.7);
        assertEquals(Status.BLOCKED,check(new Vec3(.8,1,.8),next,.6,1.8,Medium.GROUND,true,128,(x,y,z)->y==0?(x==1&&z==0?AIR:SOLID):AIR).status());
        assertEquals(Status.BLOCKED,check(START,START,1.4,1.8,Medium.AIR,false,128,(x,y,z)->x!=0?SOLID:AIR).status());
        assertEquals(Status.UNSUPPORTED_GEOMETRY,check(START,START,Double.NaN,1.8,Medium.AIR,false,128,(x,y,z)->AIR).status());
    }
    @Test void flightCannotClipTheOverhangingTopOfAnUnsupportedFence() {
        Vec3 from=new Vec3(.5,1.25,.5),to=new Vec3(.6,1.25,.5);
        assertEquals(Status.UNSUPPORTED_GEOMETRY,check(from,to,.6,1.8,Medium.AIR,false,128,(x,y,z)->y==0?OBSTACLE:AIR).status());
    }
}
