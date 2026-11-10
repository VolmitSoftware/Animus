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
        return new TriggerSettings(true,inbox,32,8,64,queuePerNpc,queuePerTrigger,100,32,concurrentPerNpc,concurrentPerTrigger,cooldown,0,4096,1024,1,32,5,16,32,64,0);
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
        dispatcher(settings(4,4,4,4,5,16));behavior("[{\"id\":\"stop\",\"event\":\"waypoint\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(new TriggerEvent.Waypoint(npc,1));pump();dispatcher.publish(new TriggerEvent.Waypoint(npc,1));pump();
        assertEquals(1,started.size());assertEquals(1,dispatcher.snapshot().suppressed());
        assertEquals(1,dispatcher.rows(0,10).getFirst().suppressed());
        pump(5);dispatcher.publish(new TriggerEvent.Waypoint(npc,1));pump();assertEquals(2,started.size());
    }
    private TriggerDispatcher players(int playerCooldown){
        TriggerDispatcher made=new TriggerDispatcher(new TriggerSettings(true,64,32,8,64,16,16,100,32,16,16,20,0,4096,1024,1,32,5,16,32,64,playerCooldown),definitions::get,catalog::get);
        made.connect();return made;
    }
    private TriggerEvent.Interaction sneakClick(UUID who){return new TriggerEvent.Interaction(npc,who,TriggerEvent.Action.INTERACT,true,TriggerEvent.Hand.MAIN_HAND);}

    @Test void spammedClicksStartOneRunWhileItRunsAndThePlayerCooldownSpacesTheRest()throws Exception{
        dispatcher=players(20);behavior("[{\"id\":\"talk\",\"event\":\"interaction\",\"action\":\"sneak_interact\"}]");npc(true);dispatcher.activated(npc);pump();
        for(int i=0;i<10;i++){dispatcher.click(sneakClick(viewer));pump(4);}
        assertEquals(1,started.size(),"ten clicks in two seconds while the run is active start it once");
        assertTrue(dropped(TriggerDispatcher.DropReason.PLAYER_BUSY)>0);
        UUID other=UUID.randomUUID();dispatcher.click(sneakClick(other));pump();
        assertEquals(2,started.size(),"another player is not held back by the first one's run or cooldown");
        dispatcher.finished(started.getFirst());dispatcher.click(sneakClick(viewer));pump();
        assertEquals(3,started.size(),"the run ended long after the cooldown, so the next click starts it");
        dispatcher.finished(started.get(2));dispatcher.click(sneakClick(viewer));pump();
        assertEquals(3,started.size(),"a run that ends quickly still leaves the player cooldown");
        pump(20);dispatcher.click(sneakClick(viewer));pump();
        assertEquals(4,started.size());
    }
    @Test void explicitTriggerSettingsKeepTheirMeaning()throws Exception{
        dispatcher=players(20);behavior("[{\"id\":\"a\",\"event\":\"interaction\",\"cooldownTicks\":0,\"whileRunning\":\"queue\"},{\"id\":\"b\",\"event\":\"interaction\",\"playerCooldownTicks\":0}]");
        npc(true);dispatcher.activated(npc);pump();
        dispatcher.click(click(viewer));dispatcher.click(click(viewer));pump();
        assertEquals(List.of("a","b","a"),started.stream().map((TriggerDispatcher.Request request)->request.binding().trigger().id()).toList(),
                "cooldownTicks 0 turns every cooldown off and queue keeps the second click; b drops it while its run is active");
        dispatcher=players(0);behavior("[{\"id\":\"c\",\"event\":\"interaction\"}]");dispatcher.activated(npc);pump();started.clear();
        dispatcher.click(click(viewer));pump();dispatcher.finished(started.getFirst());dispatcher.click(click(viewer));pump();
        assertEquals(2,started.size(),"playerCooldownTicks 0 in config.toml turns the default off");
    }
    private TriggerEvent.Interaction click(UUID who){return new TriggerEvent.Interaction(npc,who,TriggerEvent.Action.INTERACT);}
    @Test void bindingCooldownOverridesTheConfiguredDefault()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"cooldownTicks\":50}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();pump(10);dispatcher.publish(click());pump();assertEquals(1,started.size());
    }
    @Test void npcCooldownAppliesAcrossBindingsOfOneNpc()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,4,4,0,10,4096,1024,1,32,5,16,32,64,0));
        behavior("[{\"id\":\"a\",\"event\":\"interaction\"},{\"id\":\"b\",\"event\":\"interaction\"},{\"id\":\"c\",\"event\":\"waypoint\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());pump();assertEquals(2,started.size());
        dispatcher.publish(new TriggerEvent.Waypoint(npc,1));pump();assertEquals(2,started.size());assertEquals(1,dispatcher.snapshot().suppressed());
    }
    @Test void triggerConcurrencyHoldsWorkInItsBoundedQueueAndOverflowIsDroppedAndCounted()throws Exception{
        dispatcher(settings(8,2,8,1,0,16));behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");npc(true);dispatcher.activated(npc);pump();
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
        dispatcher(new TriggerSettings(true,64,32,8,1,4,4,100,32,1,1,0,0,4096,1024,1,32,5,16,32,64,0));
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
        dispatcher(new TriggerSettings(true,16,1,8,64,8,8,100,32,8,8,0,0,4096,1024,1,32,5,16,32,64,0));behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());pump();assertEquals(2,started.size());
    }
    @Test void startsPerTickBudgetIsHonored()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,1,64,8,8,100,32,8,8,0,0,4096,1024,1,32,5,16,32,64,0));behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");npc(true);dispatcher.activated(npc);pump();
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
        dispatcher(new TriggerSettings(true,16,32,8,64,4,4,3,32,4,1,0,0,4096,1024,1,32,5,16,32,64,0));behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());assertEquals(1,dispatcher.snapshot().queued());
        pump(4);assertEquals(0,dispatcher.snapshot().queued());assertEquals(1,dropped(TriggerDispatcher.DropReason.EXPIRED));
        dispatcher.finished(started.getFirst());pump();assertEquals(1,started.size());
    }
    @Test void replacingTheBehaviorDropsQueuedWorkAsStale()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(click());dispatcher.publish(click());pump();assertEquals(1,started.size());
        behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]");pump();
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
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,4096,1024,20,32,5,16,32,64,0));
        behavior("[{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":3}]");npc(true);dispatcher.activated(npc);pump(5);
        assertEquals(0,started.size());assertEquals(0,dispatcher.snapshot().timers());
        Diagnostic diagnostic=dispatcher.snapshot().diagnostics().getFirst();assertEquals("trigger-timer-budget",diagnostic.code());assertTrue(diagnostic.field().contains("example:greeter#clock"));
    }
    @Test void timerCapacityIsBoundedAndDiagnosed()throws Exception{
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,4096,1,1,32,5,16,32,64,0));
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
        dispatcher.publish(new TriggerEvent.Death(npc, java.util.Optional.empty()));dispatcher.dead(npc);pump();
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
    @Test void aLinkFollowsTheNpcRuntimeTurnedOnAndOffLive(){
        TriggerDispatcher fresh=new TriggerDispatcher(TriggerSettings.DEFAULT,definitions::get,catalog::get);
        java.util.concurrent.atomic.AtomicBoolean on=new java.util.concurrent.atomic.AtomicBoolean(false);
        List<TriggerDispatcher> bound=new ArrayList<>();List<String> log=new ArrayList<>();
        art.arcane.animus.core.gui.FakeNpcRuntime<Object> living=new art.arcane.animus.core.gui.FakeNpcRuntime<>((UUID id)->Optional.empty()){
            @Override public boolean available(){return on.get();}
            @Override public String status(){return on.get()?"ready":"NPC runtime disabled in config.toml";}
            @Override public void bindTriggers(TriggerDispatcher dispatcher){bound.add(dispatcher);}
        };
        TriggerLink link=new TriggerLink(fresh,null,living,log::add);
        assertEquals(List.of(fresh),bound);assertFalse(fresh.available());assertTrue(fresh.snapshot().reason().contains("disabled"));
        on.set(true);link.run();assertTrue(fresh.available());
        on.set(false);link.run();link.run();assertFalse(fresh.available());assertEquals(2,log.size());
    }
    @Test void disabledSettingsCannotConnect(){
        TriggerSettings disabled=new TriggerSettings(false,16,32,8,64,4,2,100,32,2,1,0,0,4096,1024,1,32,5,16,32,64,0);
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
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,1,1024,1,32,5,16,32,64,0));
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
        dispatcher(new TriggerSettings(true,16,32,8,64,4,2,100,32,2,1,0,0,4096,1024,1,4,5,16,32,64,0));
        behavior("[{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":8}]");npc(true);dispatcher.activated(npc);pump();
        assertTrue(dispatcher.proximityPlan().npcs().isEmpty());assertEquals("trigger-proximity-budget",dispatcher.snapshot().diagnostics().getFirst().code());
    }
    @Test void proximityEventsFireOnlyTheirNamedBinding()throws Exception{
        behavior("[{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":8},{\"id\":\"far\",\"event\":\"proximity\",\"phase\":\"leave\",\"radius\":8}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.publish(new TriggerEvent.Proximity(npc,viewer,"example:greeter#far"));pump();
        assertEquals(List.of("example:greeter#far"),started.stream().map(request->request.binding().key()).toList());assertEquals(Optional.of(viewer),started.getFirst().player());
    }
    @Test void proximityPlayerCooldownHoldsBackOnlyThatPlayer()throws Exception{
        behavior("[{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":8,\"playerCooldownTicks\":100}]");npc(true);dispatcher.activated(npc);pump();
        UUID other=UUID.randomUUID();
        dispatcher.publish(new TriggerEvent.Proximity(npc,viewer,"example:greeter#near"));pump();
        dispatcher.publish(new TriggerEvent.Proximity(npc,viewer,"example:greeter#near"));dispatcher.publish(new TriggerEvent.Proximity(npc,other,"example:greeter#near"));pump();
        assertEquals(1,dispatcher.snapshot().suppressed(),"the same player waits out their cooldown");
        assertEquals(2,dispatcher.snapshot().queued()+started.size(),"another player is not held back");
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
    @Test void playerEventsForAHiddenLiveNpcAreDroppedForThatViewerOnly()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"},{\"id\":\"stop\",\"event\":\"waypoint\"}]");npc(true);dispatcher.activated(npc);pump();
        UUID other=UUID.randomUUID();Set<UUID> hiding=new HashSet<>(Set.of(viewer));
        dispatcher.hide((UUID player,String id,UUID uuid)->hiding.contains(player)&&"test".equals(id));
        assertTrue(dispatcher.hides(viewer,npc));assertFalse(dispatcher.hides(other,npc));
        assertEquals(Optional.of(false),dispatcher.publish(click()).value(),"the hidden viewer's click is not queued");
        assertEquals(Optional.of(false),dispatcher.publish(new TriggerEvent.Proximity(npc,viewer,"example:greeter#click")).value());
        assertEquals(Optional.of(true),dispatcher.publish(new TriggerEvent.Interaction(npc,other,TriggerEvent.Action.INTERACT)).value());
        assertEquals(Optional.of(true),dispatcher.publish(new TriggerEvent.Waypoint(npc,0)).value(),"events without a player are unaffected");
        pump();assertEquals(Optional.of(other),started.getFirst().player());
        hiding.clear();assertFalse(dispatcher.hides(viewer,npc));
        assertEquals(Optional.of(true),dispatcher.publish(click()).value(),"the viewer's triggers return when the preview ends");
    }

    private void consumption(art.arcane.animus.core.behavior.BehaviorState state){dispatcher.consumption(state,art.arcane.animus.core.behavior.BehaviorFacts.NONE);}
    private TriggerEvent.Interaction sneak(boolean sneaking,TriggerEvent.Hand hand){return new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.INTERACT,sneaking,hand);}
    private void flag(art.arcane.animus.core.behavior.BehaviorState state,String scope,String name,Object value,BehaviorProgram.Type type){
        state.write(art.arcane.animus.core.behavior.BehaviorFlags.subject(art.arcane.animus.core.behavior.BehaviorFlags.FLAGS,BEHAVIOR,npc,Optional.of(viewer)),
                Map.of(name,new BehaviorProgram.Value(type,value)),Map.of(name,art.arcane.animus.core.behavior.BehaviorFlags.declaration(scope)));
    }

    @Test void aClickIsConsumedOnlyWhileTheConsumeConditionHolds()throws Exception{
        art.arcane.animus.core.behavior.builtin.MemoryBehaviorState state=new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState();
        behavior("""
                [{"id":"talk","event":"interaction","routine":"greet","consume":"context.sneaking && same(context.hand, 'main_hand') && has_flag('npc', 'story') && !has_flag('player', 'done')"}]""");
        npc(true);dispatcher.activated(npc);consumption(state);pump();
        assertFalse(dispatcher.click(sneak(true,TriggerEvent.Hand.MAIN_HAND)),"the NPC has no story flag yet");
        flag(state,"npc","story","intro",BehaviorProgram.Type.STRING);
        assertTrue(dispatcher.click(sneak(true,TriggerEvent.Hand.MAIN_HAND)));
        assertFalse(dispatcher.click(sneak(false,TriggerEvent.Hand.MAIN_HAND)),"a plain click goes to the shop");
        assertFalse(dispatcher.click(sneak(true,TriggerEvent.Hand.OFF_HAND)));
        assertFalse(dispatcher.click(new TriggerEvent.Interaction(npc,viewer,TriggerEvent.Action.ATTACK,true,TriggerEvent.Hand.MAIN_HAND)),"the trigger takes interact hits only");
        flag(state,"player","done",true,BehaviorProgram.Type.BOOLEAN);
        assertFalse(dispatcher.click(sneak(true,TriggerEvent.Hand.MAIN_HAND)));
    }
    @Test void clicksWithoutAConsumeConditionOrConsumptionStayWithDefaultHandling()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\"},{\"id\":\"talk\",\"event\":\"interaction\",\"routine\":\"greet\",\"consume\":\"true\"}]");
        npc(true);dispatcher.activated(npc);pump();
        assertFalse(dispatcher.click(sneak(false,TriggerEvent.Hand.MAIN_HAND)),"nothing connected the state yet");
        consumption(new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState());
        assertTrue(dispatcher.click(sneak(false,TriggerEvent.Hand.MAIN_HAND)));
        dispatcher.behaviorsEnabled(false);
        assertFalse(dispatcher.click(sneak(false,TriggerEvent.Hand.MAIN_HAND)),"disabled behaviors leave clicks to modules");
    }
    @Test void aFailingConsumeConditionDoesNotConsume()throws Exception{
        behavior("[{\"id\":\"talk\",\"event\":\"interaction\",\"consume\":\"num(context.hand) > 1\"}]");
        npc(true);dispatcher.activated(npc);consumption(new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState());pump();
        assertFalse(dispatcher.click(sneak(true,TriggerEvent.Hand.MAIN_HAND)));
    }
    private TriggerEvent.Interaction input(TriggerEvent.Action action,boolean sneaking){return new TriggerEvent.Interaction(npc,viewer,action,sneaking,TriggerEvent.Hand.MAIN_HAND);}
    private List<String> fired(TriggerEvent.Interaction event){started.clear();dispatcher.click(event);pump();List<String> ids=new ArrayList<>();for(TriggerDispatcher.Request request:started){ids.add(request.binding().trigger().id());dispatcher.finished(request);}return ids;}

    @Test void eachActionFiresItsTriggersAndThePlainOnesIgnoreSneaking()throws Exception{
        behavior("""
                [{"id":"interact","event":"interaction","action":"interact"},{"id":"attack","event":"interaction","action":"attack"},{"id":"any","event":"interaction","action":"any"},
                 {"id":"sneak_interact","event":"interaction","action":"sneak_interact"},{"id":"sneak_attack","event":"interaction","action":"sneak_attack"},
                 {"id":"drop","event":"interaction","action":"drop"},{"id":"sneak_drop","event":"interaction","action":"sneak_drop"},
                 {"id":"swap_hands","event":"interaction","action":"swap_hands"},{"id":"sneak_swap_hands","event":"interaction","action":"sneak_swap_hands"}]""");
        npc(true);dispatcher(settings(16,2,16,1,0,16));dispatcher.activated(npc);pump();
        assertEquals(List.of("interact","any"),fired(input(TriggerEvent.Action.INTERACT,false)));
        assertEquals(List.of("interact","any","sneak_interact"),fired(input(TriggerEvent.Action.INTERACT,true)));
        assertEquals(List.of("attack","any"),fired(input(TriggerEvent.Action.ATTACK,false)));
        assertEquals(List.of("attack","any","sneak_attack"),fired(input(TriggerEvent.Action.ATTACK,true)));
        assertEquals(List.of("drop"),fired(input(TriggerEvent.Action.DROP,false)),"any stays a click filter");
        assertEquals(List.of("drop","sneak_drop"),fired(input(TriggerEvent.Action.DROP,true)));
        assertEquals(List.of("swap_hands"),fired(input(TriggerEvent.Action.SWAP_HANDS,false)));
        assertEquals(List.of("swap_hands","sneak_swap_hands"),fired(input(TriggerEvent.Action.SWAP_HANDS,true)));
    }
    @Test void aDropOrSwapIsConsumedByDefaultWhenATriggerMatches()throws Exception{
        behavior("""
                [{"id":"drop","event":"interaction","action":"sneak_drop"},{"id":"swap","event":"interaction","action":"swap_hands","consume":"false"},
                 {"id":"click","event":"interaction","action":"any"}]""");
        npc(true);dispatcher.activated(npc);consumption(new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState());pump();
        assertTrue(dispatcher.click(input(TriggerEvent.Action.DROP,true)));
        assertFalse(dispatcher.click(input(TriggerEvent.Action.DROP,false)),"no trigger takes a plain drop, so the item drops");
        assertFalse(dispatcher.click(input(TriggerEvent.Action.SWAP_HANDS,false)),"consume false lets the swap happen");
        assertFalse(dispatcher.click(input(TriggerEvent.Action.INTERACT,false)),"a click without consume still goes to modules");
    }
    @Test void aConsumeConditionDecidesADropAndReadsTheAction()throws Exception{
        art.arcane.animus.core.behavior.builtin.MemoryBehaviorState state=new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState();
        behavior("[{\"id\":\"drop\",\"event\":\"interaction\",\"action\":\"drop\",\"consume\":\"same(context.action, 'drop') && has_flag('npc', 'keep')\"}]");
        npc(true);dispatcher.activated(npc);consumption(state);pump();
        assertFalse(dispatcher.click(input(TriggerEvent.Action.DROP,false)));
        flag(state,"npc","keep",true,BehaviorProgram.Type.BOOLEAN);
        assertTrue(dispatcher.click(input(TriggerEvent.Action.DROP,false)));
    }
    @Test void cooldownsApplyToTheNewActions()throws Exception{
        behavior("[{\"id\":\"drop\",\"event\":\"interaction\",\"action\":\"sneak_drop\",\"cooldownTicks\":5}]");npc(true);dispatcher.activated(npc);pump();
        assertEquals(List.of("drop"),fired(input(TriggerEvent.Action.DROP,true)));
        assertEquals(List.of(),fired(input(TriggerEvent.Action.DROP,true)));
        pump(5);assertEquals(List.of("drop"),fired(input(TriggerEvent.Action.DROP,true)));
    }
    @Test void aClickRunReadsTheClickContext()throws Exception{
        behavior("[{\"id\":\"click\",\"event\":\"interaction\",\"routine\":\"greet\"}]");npc(true);dispatcher.activated(npc);pump();
        dispatcher.click(sneak(true,TriggerEvent.Hand.OFF_HAND));pump();
        assertEquals(Map.of("action","interact","sneaking",true,"hand","off_hand"),started.getFirst().event());
    }
}
