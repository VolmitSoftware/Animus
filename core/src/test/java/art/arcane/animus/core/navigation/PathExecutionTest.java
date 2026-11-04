package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.navigation.VoxelSnapshot.Cell.*;

class PathExecutionTest {
    private static PathPlanner.Step step(int x,int y,PathPlanner.Motion motion){return new PathPlanner.Step(new BlockPos(x,y,0),motion);}
    @Test void walkingCommitsOnlyValidatedBoundedProposalsAndFinishesAtExactGoal() {
        var execution=new PathExecution(new Vec3(.5,1,.5),List.of(step(0,1,PathPlanner.Motion.START),step(1,1,PathPlanner.Motion.WALK)),new Vec3(1.75,1,.5),MovementProfile.Mode.WALK);
        run(execution,(x,y,z)->y==0?SOLID:AIR);
        assertEquals(new Vec3(1.75,1,.5),execution.position());
    }
    @Test void noOpDestinationDoesNotWiggleThroughTheVoxelCenter() {
        Vec3 position=new Vec3(.1,1,.2);
        var execution=new PathExecution(position,List.of(step(0,1,PathPlanner.Motion.START)),position,MovementProfile.Mode.WALK);
        assertTrue(execution.finished());assertTrue(execution.next(.2).isEmpty());assertEquals(position,execution.position());
    }
    @Test void stepAndJumpRiseBeforeCrossingFullBlockObstacles() {
        for(var motion:List.of(PathPlanner.Motion.STEP,PathPlanner.Motion.JUMP)) {
            int targetY=motion==PathPlanner.Motion.STEP?2:3;
            var execution=new PathExecution(new Vec3(.5,1,.5),List.of(step(0,1,PathPlanner.Motion.START),step(1,targetY,motion)),new Vec3(1.5,targetY,.5),MovementProfile.Mode.WALK);
            run(execution,(x,y,z)->y<=0||(x==1&&y<targetY)?SOLID:AIR);
            assertEquals(new Vec3(1.5,targetY,.5),execution.position());
        }
    }
    @Test void fallingLeavesLedgeBeforeDescendingAndChecksTheLanding() {
        var execution=new PathExecution(new Vec3(.5,3,.5),List.of(step(0,3,PathPlanner.Motion.START),step(1,1,PathPlanner.Motion.FALL)),new Vec3(1.5,1,.5),MovementProfile.Mode.WALK);
        run(execution,(x,y,z)->y==0||(x==0&&y<3)?SOLID:AIR);
        assertEquals(new Vec3(1.5,1,.5),execution.position());
    }
    @Test void cancelledOrStaleProposalsCannotMoveTheAuthority() {
        var execution=new PathExecution(new Vec3(.5,1,.5),List.of(step(0,1,PathPlanner.Motion.START),step(1,1,PathPlanner.Motion.WALK)),new Vec3(1.5,1,.5),MovementProfile.Mode.WALK);
        var first=execution.next(.1).orElseThrow();execution.commit(first);
        assertThrows(IllegalStateException.class,()->execution.commit(first));
        var next=execution.next(.1).orElseThrow();Vec3 stopped=execution.position();execution.cancel();
        assertTrue(execution.next(.1).isEmpty());assertThrows(IllegalStateException.class,()->execution.commit(next));assertEquals(stopped,execution.position());
    }
    @Test void blockedLiveProbeDoesNotAdvanceThePendingStep() {
        var execution=new PathExecution(new Vec3(.5,1,.5),List.of(step(0,1,PathPlanner.Motion.START),step(1,1,PathPlanner.Motion.WALK)),new Vec3(1.5,1,.5),MovementProfile.Mode.WALK);
        var proposal=execution.next(.25).orElseThrow();
        assertFalse(MotionValidator.check(proposal.from(),proposal.to(),.6,1.8,proposal.medium(),proposal.requireSupport(),128,(x,y,z)->SOLID).clear());
        assertEquals(new Vec3(.5,1,.5),execution.position());assertFalse(execution.finished());
    }
    @Test void swimmingAndFlyingExecuteVerticalRoutesWithExplicitMedia() {
        for(var mode:List.of(MovementProfile.Mode.SWIM,MovementProfile.Mode.FLY)) {
            var motion=mode==MovementProfile.Mode.SWIM?PathPlanner.Motion.SWIM:PathPlanner.Motion.FLY;
            var execution=new PathExecution(new Vec3(.5,1,.5),List.of(step(0,1,PathPlanner.Motion.START),step(0,2,motion)),new Vec3(.5,2.25,.5),mode);
            run(execution,(x,y,z)->mode==MovementProfile.Mode.SWIM?WATER:AIR);
            assertEquals(new Vec3(.5,2.25,.5),execution.position());
        }
    }
    private static void run(PathExecution execution,MotionValidator.Sampler terrain) {
        for(int i=0;i<200&&!execution.finished();i++) {
            var step=execution.next(.2).orElseThrow();assertTrue(step.from().distance(step.to())<=.200001);
            assertTrue(MotionValidator.check(step.from(),step.to(),.6,1.8,step.medium(),step.requireSupport(),128,terrain).clear(),step.toString());
            if(step.landing())assertTrue(MotionValidator.check(step.to(),step.to(),.6,1.8,MotionValidator.Medium.GROUND,true,128,terrain).clear());
            execution.commit(step);
        }
        assertTrue(execution.finished());
    }
}
