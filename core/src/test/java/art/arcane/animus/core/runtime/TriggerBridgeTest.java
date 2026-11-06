package art.arcane.animus.core.runtime;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.animus.api.Outcome;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TriggerBridgeTest {
    private static final String BEHAVIOR="example:greeter";
    private final UUID npc=UUID.randomUUID(),viewer=UUID.randomUUID();
    private final List<String> fired=new ArrayList<>();
    private final SpatialNpcIndex index=new SpatialNpcIndex(16);
    private TriggerDispatcher dispatcher;
    private TriggerBridge bridge;

    @BeforeEach void setUp()throws Exception{
        String triggers="""
                [{"id":"click","event":"interaction"},{"id":"hit","event":"interaction","action":"attack"},{"id":"talk","event":"interaction","consume":"context.sneaking"},
                 {"id":"born","event":"spawn"},{"id":"gone","event":"despawn"},{"id":"hurt","event":"damage"},{"id":"dead","event":"death"},
                 {"id":"stop","event":"waypoint"},{"id":"near","event":"proximity","phase":"enter","radius":8}]""";
        var program=BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\""+BEHAVIOR+"\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":"+triggers+"}").program().orElseThrow();
        var accepted=new BehaviorCatalog.Accepted(1,String.format("%064x",1),100,program,Set.of());
        BehaviorCatalog.Snapshot catalog=new BehaviorCatalog.Snapshot(1,Map.of(),Map.of(BEHAVIOR,new BehaviorCatalog.Resolution(BEHAVIOR,true,Optional.of(accepted),List.of())),List.of());
        NpcDefinition definition=NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\""+npc+"\",\"id\":\"test\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":true,\"behaviors\":[\""+BEHAVIOR+"\"],\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}");
        TriggerSettings settings=new TriggerSettings(true,64,64,64,64,32,8,100,32,16,8,0,0,4096,1024,1,32,1,16,32,64);
        dispatcher=new TriggerDispatcher(settings,()->Map.of("test",definition),()->catalog);dispatcher.connect();
        dispatcher.consumption(new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState(),art.arcane.animus.core.behavior.BehaviorFacts.NONE);
        bridge=new TriggerBridge(dispatcher,index);
        pump();
    }
    private void pump(){dispatcher.pump(request->{fired.add(request.binding().trigger().id());return Outcome.ok(UUID.randomUUID());});}
    private void active(){bridge.activated(npc);pump();fired.clear();}
    private VirtualNpcState state(){VirtualNpcState state=new VirtualNpcState(npc,new WorldPosition("world",new Vec3(0,64,0)),.6,1.8,20);state.spawn();return state;}

    @Test void activationFiresSpawnAndRemovalFiresDespawn(){
        bridge.activated(npc);pump();assertEquals(List.of("born"),fired);fired.clear();
        bridge.removed(npc);pump();assertEquals(List.of("gone"),fired);
    }
    @Test void validatedInteractionFiresWithTheViewerAction(){
        active();bridge.interaction(npc,viewer,TriggerEvent.Action.INTERACT,false,TriggerEvent.Hand.MAIN_HAND,false);pump();assertEquals(List.of("click","talk"),fired);fired.clear();
        bridge.interaction(npc,viewer,TriggerEvent.Action.ATTACK,false,TriggerEvent.Hand.MAIN_HAND,false);pump();assertEquals(List.of("hit"),fired);
    }
    @Test void aSneakingClickIsConsumedAndAPlainOneIsNot(){
        active();
        assertTrue(bridge.interaction(npc,viewer,TriggerEvent.Action.INTERACT,true,TriggerEvent.Hand.MAIN_HAND,false));
        assertFalse(bridge.interaction(npc,viewer,TriggerEvent.Action.INTERACT,false,TriggerEvent.Hand.MAIN_HAND,false));
        assertFalse(bridge.interaction(npc,viewer,TriggerEvent.Action.INTERACT,true,TriggerEvent.Hand.MAIN_HAND,true),"previews never consume");
    }
    @Test void previewInteractionNeverFires(){
        active();bridge.interaction(npc,viewer,TriggerEvent.Action.INTERACT,false,TriggerEvent.Hand.MAIN_HAND,true);pump();assertEquals(List.of(),fired);assertEquals(0,dispatcher.snapshot().events());
    }
    @Test void waypointArrivalFires(){
        active();bridge.waypoint(npc,2);pump();assertEquals(List.of("stop"),fired);
    }
    @Test void healthLossFiresDamageAndZeroHealthAlsoFiresDeathWithoutDespawn(){
        active();VirtualNpcState state=state();
        VirtualNpcState.Snapshot before=state.snapshot(),after=state.damage(5);bridge.changed(before,after);pump();assertEquals(List.of("hurt"),fired);fired.clear();
        before=after;after=state.damage(100);bridge.changed(before,after);pump();assertEquals(List.of("hurt","dead"),fired);fired.clear();
        bridge.removed(npc);pump();assertEquals(List.of(),fired);
    }
    @Test void movementAndUnobservedStateFireNothing(){
        active();VirtualNpcState state=state();VirtualNpcState.Snapshot before=state.snapshot();
        bridge.changed(before,state.move(new WorldPosition("world",new Vec3(1,64,0))));bridge.changed(null,state.snapshot());pump();assertEquals(List.of(),fired);
    }
    @Test void proximityUsesTheSpatialIndexAndSleepsWithoutViewers(){
        bridge.activated(npc);pump();pump();fired.clear();index.upsert(state().snapshot());
        WorldPosition near=new WorldPosition("world",new Vec3(1,64,0));
        bridge.proximity(List.of(),id->Optional.of(near));pump();assertEquals(List.of(),fired);assertEquals(0,bridge.proximityScans());
        bridge.proximity(List.of(viewer),id->Optional.of(near));pump();assertEquals(List.of("near"),fired);assertEquals(1,bridge.proximityScans());
    }
    @Test void forgottenViewersDoNotFire(){
        bridge.activated(npc);pump();pump();fired.clear();index.upsert(state().snapshot());
        WorldPosition near=new WorldPosition("world",new Vec3(1,64,0));bridge.proximity(List.of(viewer),id->Optional.of(near));pump();fired.clear();
        bridge.forget(viewer);bridge.proximity(List.of(viewer),id->Optional.of(near));pump();assertEquals(List.of("near"),fired,"a returning viewer enters again");
    }
    @Test void runtimeFailureMarksTriggersUnavailable(){
        active();bridge.failed("NPC runtime tick failed");assertFalse(dispatcher.available());assertEquals("NPC runtime tick failed",dispatcher.snapshot().reason());
        bridge.interaction(npc,viewer,TriggerEvent.Action.INTERACT,false,TriggerEvent.Hand.MAIN_HAND,false);pump();assertEquals(List.of(),fired);
    }
}
