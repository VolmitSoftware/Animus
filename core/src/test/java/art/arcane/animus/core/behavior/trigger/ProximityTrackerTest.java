package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.virtual.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProximityTrackerTest {
    private static final ProximityTracker.Limits LIMITS=new ProximityTracker.Limits(1,16,32,8);
    private static final ProximityTracker.Binding ENTER=new ProximityTracker.Binding("b#enter",TriggerBinding.Phase.ENTER,8);
    private static final ProximityTracker.Binding LEAVE=new ProximityTracker.Binding("b#leave",TriggerBinding.Phase.LEAVE,8);
    private final SpatialNpcIndex index=new SpatialNpcIndex(64);
    private final UUID npc=UUID.randomUUID(),viewer=UUID.randomUUID();
    private final Map<UUID,WorldPosition> positions=new HashMap<>();
    private final List<TriggerEvent> events=new ArrayList<>();
    private int positionReads;

    private static WorldPosition at(double x){return new WorldPosition("world",new Vec3(x,64,0));}
    private void place(UUID id,double x){VirtualNpcState state=new VirtualNpcState(id,at(x),.6,1.8,20);state.spawn();index.upsert(state.snapshot());}
    private static ProximityTracker.Plan plan(UUID npc,ProximityTracker.Binding...bindings){
        double maximum=0;for(ProximityTracker.Binding binding:bindings)maximum=Math.max(maximum,binding.radius());
        return new ProximityTracker.Plan(Map.of(npc,List.of(bindings)),maximum);
    }
    private void tick(ProximityTracker tracker,ProximityTracker.Plan plan){
        tracker.tick(plan,positions.keySet(),id->{positionReads++;return Optional.ofNullable(positions.get(id));},events::add);
    }

    @Test void entersOnceAndLeavesOnceAroundTheRadius(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);ProximityTracker.Plan plan=plan(npc,ENTER,LEAVE);
        positions.put(viewer,at(20));tick(tracker,plan);assertEquals(List.of(),events);
        positions.put(viewer,at(5));tick(tracker,plan);tick(tracker,plan);
        assertEquals(List.of(new TriggerEvent.Proximity(npc,viewer,"b#enter")),events);events.clear();
        positions.put(viewer,at(20));tick(tracker,plan);tick(tracker,plan);
        assertEquals(List.of(new TriggerEvent.Proximity(npc,viewer,"b#leave")),events);
    }
    @Test void boundaryJitterDoesNotRefire(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);ProximityTracker.Plan plan=plan(npc,ENTER,LEAVE);
        positions.put(viewer,at(7.9));tick(tracker,plan);events.clear();
        positions.put(viewer,at(8.2));tick(tracker,plan);positions.put(viewer,at(7.9));tick(tracker,plan);assertEquals(List.of(),events);
    }
    @Test void leavingTheWorldCountsAsLeaving(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);ProximityTracker.Plan plan=plan(npc,LEAVE);
        positions.put(viewer,at(1));tick(tracker,plan);assertEquals(List.of(),events);
        positions.put(viewer,new WorldPosition("nether",new Vec3(1,64,0)));tick(tracker,plan);
        assertEquals(List.of(new TriggerEvent.Proximity(npc,viewer,"b#leave")),events);
    }
    @Test void sleepsWithoutScanningWhenNoNpcHasAProximityTrigger(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);positions.put(viewer,at(0));
        for(int i=0;i<10;i++)tick(tracker,ProximityTracker.Plan.EMPTY);
        assertEquals(0,tracker.scans());assertEquals(0,positionReads);
    }
    @Test void sleepsWithoutScanningWhenNoViewersExist(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);
        for(int i=0;i<10;i++)tick(tracker,plan(npc,ENTER));
        assertEquals(0,tracker.scans());
    }
    @Test void scansFollowTheViewerNotTheNpcPopulation(){
        place(npc,0);for(int i=0;i<40;i++)place(UUID.randomUUID(),2000+i);
        ProximityTracker tracker=new ProximityTracker(LIMITS,index);positions.put(viewer,at(5000));
        tick(tracker,plan(npc,ENTER));assertEquals(1,tracker.scans());assertEquals(0,tracker.candidates(),"no NPC near this viewer is visited");
    }
    @Test void npcsWithoutBindingsAreIgnoredNearAViewer(){
        UUID plain=UUID.randomUUID();place(plain,1);place(npc,500);ProximityTracker tracker=new ProximityTracker(LIMITS,index);positions.put(viewer,at(0));
        tick(tracker,plan(npc,ENTER));assertEquals(List.of(),events);
    }
    @Test void intervalAndViewerBudgetLimitWork(){
        place(npc,0);UUID second=UUID.randomUUID();ProximityTracker tracker=new ProximityTracker(new ProximityTracker.Limits(4,1,32,8),index);ProximityTracker.Plan plan=plan(npc,ENTER);
        positions.put(viewer,at(1));positions.put(second,at(2));
        tick(tracker,plan);assertEquals(1,tracker.scans());tick(tracker,plan);assertEquals(2,tracker.scans());
        tick(tracker,plan);tick(tracker,plan);assertEquals(2,tracker.scans(),"waits for its interval");
        tick(tracker,plan);assertEquals(3,tracker.scans());
    }
    @Test void largeCrowdsResumeFromTheScanCursor(){
        place(npc,0);for(int i=0;i<6;i++)place(UUID.randomUUID(),0);
        ProximityTracker tracker=new ProximityTracker(new ProximityTracker.Limits(1,16,2,16),index);positions.put(viewer,at(1));ProximityTracker.Plan plan=plan(npc,ENTER);
        for(int i=0;i<5;i++)tick(tracker,plan);
        assertEquals(List.of(new TriggerEvent.Proximity(npc,viewer,"b#enter")),events);
    }
    @Test void despawnedNpcsAndForgottenViewersRetireSilently(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);ProximityTracker.Plan plan=plan(npc,LEAVE);
        positions.put(viewer,at(1));tick(tracker,plan);assertEquals(1,tracker.tracked());
        index.remove(npc);tick(tracker,plan);assertEquals(List.of(),events);assertEquals(0,tracker.tracked());
        place(npc,0);tick(tracker,plan);assertEquals(1,tracker.tracked());
        tracker.forget(viewer);positions.clear();tick(tracker,plan);assertEquals(List.of(),events);assertEquals(0,tracker.tracked());
    }
    @Test void removedBindingsRetireWithoutFiring(){
        place(npc,0);ProximityTracker tracker=new ProximityTracker(LIMITS,index);positions.put(viewer,at(1));tick(tracker,plan(npc,LEAVE));
        tick(tracker,plan(npc,ENTER));assertEquals(List.of(new TriggerEvent.Proximity(npc,viewer,"b#enter")),events);
        tick(tracker,ProximityTracker.Plan.EMPTY);assertEquals(0,tracker.tracked());
    }
    @Test void trackedStateIsBoundedAndOverflowIsCounted(){
        place(npc,0);UUID other=UUID.randomUUID();place(other,1);
        ProximityTracker tracker=new ProximityTracker(new ProximityTracker.Limits(1,16,32,1),index);positions.put(viewer,at(0));
        Map<UUID,List<ProximityTracker.Binding>> both=Map.of(npc,List.of(ENTER),other,List.of(ENTER));
        tick(tracker,new ProximityTracker.Plan(both,8));
        assertEquals(1,tracker.tracked());assertEquals(1,tracker.overflow());assertEquals(1,events.size());
    }
}
