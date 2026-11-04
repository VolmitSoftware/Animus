package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TriggerDispatcherTest {
    private static final String ROUTINES="\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]},\"greet\":{\"type\":\"sequence\",\"steps\":[]}}";
    private static final String BEHAVIOR="example:greeter";
    private final UUID npc=UUID.randomUUID(),viewer=UUID.randomUUID();
    private final AtomicReference<Map<String,NpcDefinition>> definitions=new AtomicReference<>(Map.of());
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog=new AtomicReference<>(new BehaviorCatalog.Snapshot(0,Map.of(),Map.of(),List.of()));
    private final List<TriggerDispatcher.Request> started=new ArrayList<>();
    private Outcome<UUID> startResult=null;
    private TriggerDispatcher dispatcher;
    private long revision;

    private static BehaviorProgram program(String triggers){return BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\""+BEHAVIOR+"\",\"entry\":\"main\","+ROUTINES+",\"triggers\":"+triggers+"}").program().orElseThrow();}
    private void behavior(String triggers){
        revision++;
        BehaviorCatalog.Accepted accepted=new BehaviorCatalog.Accepted(revision,String.format("%064x",revision),100,program(triggers),Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(revision,Map.of(),Map.of(BEHAVIOR,new BehaviorCatalog.Resolution(BEHAVIOR,true,Optional.of(accepted),List.of())),List.of()));
    }
    private static NpcDefinition definition(String id,UUID uuid,boolean spawned,String behaviors)throws Exception{
        return NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\""+uuid+"\",\"id\":\""+id+"\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":"+spawned+",\"behaviors\":"+behaviors+",\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}");
    }
    private void npc(boolean spawned)throws Exception{definitions.set(Map.of("test",definition("test",npc,spawned,"[\""+BEHAVIOR+"\"]")));}
    private static TriggerSettings settings(int queuePerNpc,int queuePerTrigger,int concurrentPerNpc,int concurrentPerTrigger,int cooldown,int inbox){
        return new TriggerSettings(true,inbox,32,8,64,queuePerNpc,queuePerTrigger,100,32,concurrentPerNpc,concurrentPerTrigger,cooldown,0,4096,1024,1,32,5,16,32,64);
    }
    private void dispatcher(TriggerSettings settings){dispatcher=new TriggerDispatcher(settings,definitions::get,catalog::get);dispatcher.connect();}
    private Outcome<UUID> start(TriggerDispatcher.Request request){started.add(request);return startResult!=null?startResult:Outcome.ok(UUID.randomUUID());}
    private void pump(){dispatcher.pump(this::start);}
    private void pump(int count){for(int i=0;i<count;i++)pump();}
    private TriggerEvent click(){return new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT);}
    private long dropped(TriggerDispatcher.DropReason reason){return dispatcher.snapshot().dropped().getOrDefault(reason,0L);}

    @BeforeEach void defaults(){dispatcher(settings(4,2,2,1,0,16));}

    @Test void interactionStartsItsRoutineWithThePlayerAndNoPreview()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"routine\":\"greet\"}]");npc(true);dispatcher.activated(npc);pump();
        assertTrue(dispatcher.publish(click()).value().orElseThrow());pump();
        assertEquals(1,started.size());TriggerDispatcher.Request request=started.getFirst();
        assertEquals("greet",request.program().entry());assertEquals(Optional.of(viewer),request.player());
        assertEquals("example:greeter#click",request.binding().key());assertSame(definitions.get().get("test"),request.definition());
        assertSame(catalog.get().byId().get(BEHAVIOR).accepted().orElseThrow(),request.accepted());
    }
    @Test void missingRoutineUsesTheDocumentEntry()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();dispatcher.publish(click());pump();
        assertEquals("main",started.getFirst().program().entry());
    }
    @Test void filtersRejectEventsThatDoNotMatchTheBinding()throws Exception{
        behavior("""
                [{"id":"hit","event":"interaction","action":"attack"},{"id":"stop","event":"waypoint","waypoint":3},
                 {"id":"yes","event":"dialogue_choice","dialogue":"intro","choice":"yes"},{"id":"door","event":"module","key":"sample:door"}]""");
        npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(new TriggerEvent.Waypoint(npc,2));
        dispatcher.publish(new TriggerEvent.DialogueChoice(npc,viewer,"intro","no"));dispatcher.publish(new TriggerEvent.DialogueChoice(npc,viewer,"other","yes"));
        dispatcher.publish(new TriggerEvent.Module(npc,Optional.empty(),new Capabilities.Key("sample","other")));pump();
        assertEquals(List.of(),started);
        dispatcher.publish(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.ATTACK));dispatcher.publish(new TriggerEvent.Waypoint(npc,3));
        dispatcher.publish(new TriggerEvent.DialogueChoice(npc,viewer,"intro","yes"));dispatcher.publish(new TriggerEvent.Module(npc,Optional.of(viewer),new Capabilities.Key("sample","door")));pump();
        assertEquals(List.of("example:greeter#hit","example:greeter#stop"),started.stream().map(request->request.binding().key()).toList(),"per-NPC concurrency of two holds the rest");
        dispatcher.finished(started.get(0));dispatcher.finished(started.get(1));pump();
        assertEquals(List.of("example:greeter#hit","example:greeter#stop","example:greeter#yes","example:greeter#door"),started.stream().map(request->request.binding().key()).toList());
        assertEquals(Optional.of(viewer),started.getLast().player());
    }
    @Test void cooldownSuppressesAndCountsUntilItElapses()throws Exception{
        dispatcher(settings(4,4,4,4,5,16));behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();dispatcher.publish(click());pump();
        assertEquals(1,started.size());assertEquals(1,dispatcher.snapshot().suppressed());
        assertEquals(1,dispatcher.rows(0,10).getFirst().suppressed());
        pump(5);dispatcher.publish(click());pump();assertEquals(2,started.size());
    }
    @Test void bindingCooldownOverridesTheConfiguredDefault()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"cooldownTicks\":50}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();pump(10);dispatcher.publish(click());pump();assertEquals(1,started.size());
    }
    @Test void npcCooldownAppliesAcrossBindingsOfOneNpc()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,4,4,0,10,4096,1024,1,32,5,16,32,64));
        behavior("[{\"id\":\"a\",\"event\":\"interaction\"},{\"id\":\"b\",\"event\":\"interaction\"},{\"id\":\"c\",\"event\":\"waypoint\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();assertEquals(2,started.size());
        dispatcher.publish(new TriggerEvent.Waypoint(npc,1));pump();assertEquals(2,started.size());assertEquals(1,dispatcher.snapshot().suppressed());
    }
    @Test void triggerConcurrencyHoldsWorkInItsBoundedQueueAndOverflowIsDroppedAndCounted()throws Exception{
        dispatcher(settings(8,2,8,1,0,16));behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        for(int i=0;i<5;i++)dispatcher.publish(click());pump();
        assertEquals(1,started.size(),"only one concurrent execution of this trigger");
        assertEquals(1,dispatcher.rows(0,10).getFirst().active());assertEquals(1,dispatcher.rows(0,10).getFirst().queued());
        assertEquals(3,dropped(TriggerDispatcher.DropReason.TRIGGER_QUEUE));assertEquals(3,dispatcher.rows(0,10).getFirst().dropped());
        dispatcher.finished(started.getFirst());pump();assertEquals(2,started.size());
        dispatcher.finished(started.getLast());pump();assertEquals(2,started.size());assertEquals(0,dispatcher.rows(0,10).getFirst().queued());
    }
    @Test void npcBudgetsBoundConcurrencyAndQueueAcrossItsTriggers()throws Exception{
        dispatcher(settings(1,4,1,4,0,16));behavior("[{\"id\":\"a\",\"event\":\"interaction\"},{\"id\":\"b\",\"event\":\"interaction\"},{\"id\":\"c\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();
        assertEquals(1,started.size());assertEquals(2,dropped(TriggerDispatcher.DropReason.NPC_QUEUE));
        dispatcher.publish(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT));pump();
        assertEquals(1,started.size(),"the NPC limit holds across its triggers");assertEquals(1,dispatcher.snapshot().queued());
    }
    @Test void totalQueueIsBoundedAcrossNpcs()throws Exception{
        dispatcher(new TriggerSettings(true,64,32,8,1,4,4,100,32,1,1,0,0,4096,1024,1,32,5,16,32,64));
        behavior("[{\"id\":\"a\",\"event\":\"interaction\"},{\"id\":\"b\",\"event\":\"interaction\"},{\"id\":\"c\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();
        assertEquals(1,started.size());assertEquals(2,dropped(TriggerDispatcher.DropReason.TOTAL_QUEUE));
    }
    @Test void eventInboxOverflowIsDroppedAndReportedAsBusy()throws Exception{
        dispatcher(settings(4,4,4,4,0,2));behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        assertEquals(Outcome.Status.OK,dispatcher.publish(click()).status());assertEquals(Outcome.Status.OK,dispatcher.publish(click()).status());
        Outcome<Boolean> full=dispatcher.publish(click());
        assertEquals(Outcome.Status.BUSY,full.status());assertEquals("trigger-event-capacity",full.diagnostics().getFirst().code());
        assertEquals(1,dropped(TriggerDispatcher.DropReason.EVENT_QUEUE));
    }
    @Test void eventsPerTickBudgetLeavesTheRestForLaterTicks()throws Exception{
        dispatcher(new TriggerSettings(true,16,1,8,64,8,8,100,32,8,8,0,0,4096,1024,1,32,5,16,32,64));behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());pump();assertEquals(2,started.size());
    }
    @Test void startsPerTickBudgetIsHonored()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,1,64,8,8,100,32,8,8,0,0,4096,1024,1,32,5,16,32,64));behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());pump();assertEquals(2,started.size());
    }
    @Test void engineCapacityLeavesWorkQueued()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();dispatcher.publish(click());
        startResult=Outcome.rejected(Outcome.Status.BUSY,"behavior-capacity","full");pump();assertEquals(1,started.size());assertEquals(1,dispatcher.snapshot().queued());
        startResult=null;pump();assertEquals(2,started.size());assertEquals(0,dispatcher.snapshot().queued());assertEquals(1,dispatcher.snapshot().active());
    }
    @Test void failedStartIsDroppedAndCounted()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();dispatcher.publish(click());
        startResult=Outcome.rejected(Outcome.Status.CONFLICT,"captured-source-changed","changed");pump();
        assertEquals(0,dispatcher.snapshot().queued());assertEquals(0,dispatcher.snapshot().active());assertEquals(1,dropped(TriggerDispatcher.DropReason.START_FAILED));assertEquals(0,dispatcher.rows(0,1).getFirst().fired());
    }
    @Test void queuedWorkExpiresAtItsLifetime()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,4,3,32,4,1,0,0,4096,1024,1,32,5,16,32,64));behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());assertEquals(1,dispatcher.snapshot().queued());
        pump(4);assertEquals(0,dispatcher.snapshot().queued());assertEquals(1,dropped(TriggerDispatcher.DropReason.EXPIRED));
        dispatcher.finished(started.getFirst());pump();assertEquals(1,started.size());
    }
    @Test void replacingTheBehaviorDropsQueuedWorkAsStale()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");pump();
        assertEquals(1,started.size());assertEquals(0,dispatcher.snapshot().queued());assertEquals(1,dropped(TriggerDispatcher.DropReason.STALE));
    }
    @Test void timersFireOnTheirIntervalOnlyWhileActive()throws Exception{
        behavior("[{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":3}]");npc(true);pump(5);assertEquals(0,started.size(),"not active on the platform yet");
        dispatcher.activated(npc);pump();assertEquals(1,dispatcher.snapshot().timers());pump(2);assertEquals(0,started.size());
        pump();assertEquals(1,started.size());assertTrue(started.getFirst().player().isEmpty());dispatcher.finished(started.getFirst());
        pump(3);assertEquals(2,started.size());dispatcher.finished(started.getLast());
        npc(false);dispatcher.deactivated(npc);pump(10);assertEquals(2,started.size());assertEquals(0,dispatcher.snapshot().timers());
    }
    @Test void npcsWithoutTimersDoNotScheduleAny()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();assertEquals(0,dispatcher.snapshot().timers());
    }
    @Test void timerIntervalBelowTheConfiguredMinimumIsDiagnosedAndNotScheduled()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,4096,1024,20,32,5,16,32,64));
        behavior("[{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":3}]");npc(true);dispatcher.activated(npc);pump(5);
        assertEquals(0,started.size());assertEquals(0,dispatcher.snapshot().timers());
        Diagnostic diagnostic=dispatcher.snapshot().diagnostics().getFirst();assertEquals("trigger-timer-budget",diagnostic.code());assertTrue(diagnostic.field().contains("example:greeter#clock"));
    }
    @Test void timerCapacityIsBoundedAndDiagnosed()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,4096,1,1,32,5,16,32,64));
        behavior("[{\"id\":\"a\",\"event\":\"timer\",\"intervalTicks\":3},{\"id\":\"b\",\"event\":\"timer\",\"intervalTicks\":3}]");npc(true);dispatcher.activated(npc);pump();
        assertEquals(1,dispatcher.snapshot().timers());assertTrue(dispatcher.snapshot().diagnostics().stream().anyMatch(item->item.code().equals("trigger-timer-capacity")));
    }
    @Test void spawnWaitsForTheFirstCatalogAndFiresOnce()throws Exception{
        npc(true);dispatcher.activated(npc);pump(3);assertEquals(0,started.size());
        behavior("[{\"id\":\"born\",\"event\":\"spawn\"}]");pump(3);assertEquals(1,started.size());
        behavior("[{\"id\":\"born\",\"event\":\"spawn\"}]");pump(3);assertEquals(1,started.size(),"a behavior reload is not a spawn");
    }
    @Test void despawnFiresAgainstTheCurrentDormantDefinitionAndThenReleasesTheBinding()throws Exception{
        behavior("[{\"id\":\"gone\",\"event\":\"despawn\"}]");npc(true);dispatcher.activated(npc);pump();assertEquals(1,dispatcher.snapshot().bindings());
        npc(false);pump();dispatcher.deactivated(npc);pump();
        assertEquals(1,started.size());assertSame(definitions.get().get("test"),started.getFirst().definition());
        pump();assertEquals(0,dispatcher.snapshot().bindings(),"a dormant NPC holds no binding capacity");
    }
    @Test void despawnObservedBeforeTheDefinitionPublicationStillFires()throws Exception{
        behavior("[{\"id\":\"gone\",\"event\":\"despawn\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.deactivated(npc);npc(false);pump();assertEquals(1,started.size());
    }
    @Test void deletedNpcHasNoDefinitionToRunDespawnAgainst()throws Exception{
        behavior("[{\"id\":\"gone\",\"event\":\"despawn\"}]");npc(true);dispatcher.activated(npc);pump();
        definitions.set(Map.of());dispatcher.deactivated(npc);pump(2);assertEquals(0,started.size());assertEquals(0,dispatcher.snapshot().bindings());
    }
    @Test void deathFiresThenTimersStopAndNoDespawnFollows()throws Exception{
        behavior("[{\"id\":\"dead\",\"event\":\"death\"},{\"id\":\"gone\",\"event\":\"despawn\"},{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":2}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(new TriggerEvent.Death(npc));dispatcher.dead(npc);pump();
        assertEquals(List.of("example:greeter#dead"),started.stream().map(request->request.binding().key()).toList());
        pump(6);dispatcher.deactivated(npc);npc(false);pump(3);assertEquals(1,started.size());
    }
    @Test void importedDormantNpcsStayUnboundAndFireNothing()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"},{\"id\":\"door\",\"event\":\"module\",\"key\":\"sample:door\"},{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":1}]");npc(false);pump(3);
        assertEquals(0,dispatcher.snapshot().bindings());
        assertEquals(Optional.of(false),dispatcher.publish(new TriggerEvent.Module(npc,Optional.empty(),new Capabilities.Key("sample","door"))).value());
        dispatcher.publish(click());pump(3);assertEquals(List.of(),started);
    }
    @Test void spawnedButInactiveNpcsIgnoreEvents()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);pump();dispatcher.publish(click());pump();assertEquals(0,started.size());assertEquals(1,dropped(TriggerDispatcher.DropReason.INACTIVE));
    }
    @Test void unknownNpcsCostNothingAndReportNoBinding(){
        assertEquals(Optional.of(false),dispatcher.publish(click()).value());assertEquals(0,dispatcher.snapshot().events());
    }
    @Test void unavailableReportsADiagnosticAndNeverRuns()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"},{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":1}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.unavailable("NPC runtime unavailable on Folia");
        Outcome<Boolean> outcome=dispatcher.publish(click());
        assertEquals(Outcome.Status.UNAVAILABLE,outcome.status());assertEquals("trigger-dispatch-unavailable",outcome.diagnostics().getFirst().code());
        assertTrue(outcome.diagnostics().getFirst().message().contains("Folia"));
        pump(5);assertEquals(0,started.size());
        assertFalse(dispatcher.snapshot().available());assertEquals("NPC runtime unavailable on Folia",dispatcher.snapshot().reason());
        assertEquals(1,dropped(TriggerDispatcher.DropReason.UNAVAILABLE));assertTrue(dispatcher.proximityPlan().npcs().isEmpty());
    }
    @Test void newDispatchersAreUnavailableUntilAPlatformConnects(){
        TriggerDispatcher fresh=new TriggerDispatcher(TriggerSettings.DEFAULT,definitions::get,catalog::get);
        assertFalse(fresh.available());assertEquals(Outcome.Status.UNAVAILABLE,fresh.publish(click()).status());
    }
    @Test void disabledSettingsCannotConnect(){
        TriggerSettings disabled=new TriggerSettings(false,16,32,8,64,4,2,100,32,2,1,0,0,4096,1024,1,32,5,16,32,64);
        TriggerDispatcher fresh=new TriggerDispatcher(disabled,definitions::get,catalog::get);fresh.connect();
        assertFalse(fresh.available());assertTrue(fresh.snapshot().reason().contains("config.toml"));
    }
    @Test void missingAndUnavailableBehaviorsAreDiagnosedAgainstTheNpc()throws Exception{
        definitions.set(Map.of("test",definition("test",npc,true,"[\"example:absent\"]")));dispatcher.activated(npc);pump();
        Diagnostic missing=dispatcher.snapshot().diagnostics().getFirst();assertEquals("trigger-behavior-missing",missing.code());assertTrue(missing.field().contains("test"));
        BehaviorCatalog.Accepted accepted=new BehaviorCatalog.Accepted(1,String.format("%064x",1),100,program("[{\"id\":\"click\",\"event\":\"interaction\"}]"),Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(5,Map.of(),Map.of(BEHAVIOR,new BehaviorCatalog.Resolution(BEHAVIOR,false,Optional.of(accepted),List.of())),List.of()));npc(true);pump();
        assertEquals("trigger-behavior-unavailable",dispatcher.snapshot().diagnostics().getFirst().code());assertEquals(0,dispatcher.snapshot().bindings());
    }
    @Test void bindingCapacityIsBoundedAndDiagnosed()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,1,1024,1,32,5,16,32,64));
        behavior("[{\"id\":\"a\",\"event\":\"interaction\"},{\"id\":\"b\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();
        assertEquals(1,dispatcher.snapshot().bindings());assertTrue(dispatcher.snapshot().diagnostics().stream().anyMatch(item->item.code().equals("trigger-binding-capacity")));
    }
    @Test void proximityPlanContainsOnlyActiveNpcsWithProximityBindings()throws Exception{
        behavior("[{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":8},{\"id\":\"far\",\"event\":\"proximity\",\"phase\":\"leave\",\"radius\":12},{\"id\":\"click\",\"event\":\"interaction\"}]");
        UUID other=UUID.randomUUID();
        definitions.set(Map.of("test",definition("test",npc,true,"[\""+BEHAVIOR+"\"]"),"plain",definition("plain",other,true,"[]")));pump();
        assertTrue(dispatcher.proximityPlan().npcs().isEmpty(),"sleeps until the NPC is active");
        dispatcher.activated(npc);dispatcher.activated(other);pump();
        ProximityTracker.Plan plan=dispatcher.proximityPlan();
        assertEquals(Set.of(npc),plan.npcs().keySet());assertEquals(12,plan.maximumRadius());assertEquals(2,plan.npcs().get(npc).size());
        dispatcher.dead(npc);pump();assertTrue(dispatcher.proximityPlan().npcs().isEmpty());
    }
    @Test void proximityRadiusAboveTheBudgetIsDiagnosedAndUnbound()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,4096,1024,1,4,5,16,32,64));
        behavior("[{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":8}]");npc(true);dispatcher.activated(npc);pump();
        assertTrue(dispatcher.proximityPlan().npcs().isEmpty());assertEquals("trigger-proximity-budget",dispatcher.snapshot().diagnostics().getFirst().code());
    }
    @Test void proximityEventsFireOnlyTheirNamedBinding()throws Exception{
        behavior("[{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":8},{\"id\":\"far\",\"event\":\"proximity\",\"phase\":\"leave\",\"radius\":8}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(new TriggerEvent.Proximity(npc,viewer,"example:greeter#far"));pump();
        assertEquals(List.of("example:greeter#far"),started.stream().map(request->request.binding().key()).toList());assertEquals(Optional.of(viewer),started.getFirst().player());
    }
    @Test void attachingABehaviorTwiceBindsOnce()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");definitions.set(Map.of("test",definition("test",npc,true,"[\""+BEHAVIOR+"\",\""+BEHAVIOR+"\"]")));dispatcher.activated(npc);pump();
        assertEquals(1,dispatcher.snapshot().bindings());
    }
    @Test void rowsArePagedAndCarryLastFired()throws Exception{
        behavior("[{\"id\":\"a\",\"event\":\"interaction\"},{\"id\":\"b\",\"event\":\"waypoint\"},{\"id\":\"c\",\"event\":\"spawn\"}]");npc(true);dispatcher.activated(npc);pump();
        List<TriggerDispatcher.Row> firstTwo=dispatcher.rows(0,2);assertEquals(2,firstTwo.size());assertEquals(1,dispatcher.rows(2,10).size());assertEquals(List.of(),dispatcher.rows(3,10));
        TriggerDispatcher.Row spawned=dispatcher.rows(2,1).getFirst();assertEquals("c",spawned.trigger());assertEquals("spawn",spawned.event());assertEquals("test",spawned.npcId());assertEquals(1,spawned.fired());assertEquals(OptionalLong.of(0),spawned.lastFiredTicksAgo());
        pump(2);assertEquals(OptionalLong.of(2),dispatcher.rows(2,1).getFirst().lastFiredTicksAgo());
        assertEquals(OptionalLong.empty(),dispatcher.rows(0,1).getFirst().lastFiredTicksAgo());
    }
    @Test void statisticsSurviveABehaviorReload()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");npc(true);dispatcher.activated(npc);pump();dispatcher.publish(click());pump();
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"}]");pump();assertEquals(1,dispatcher.rows(0,1).getFirst().fired());
    }
}
