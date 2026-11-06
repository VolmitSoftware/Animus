package art.arcane.animus.core.command;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.config.*;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.MutationLane;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorCommandsTest {
    @TempDir Path root;
    private static final Pattern EXECUTION=Pattern.compile("execution=([a-f0-9-]{36})");
    private final Thread owner=Thread.currentThread();
    private final Queue<Runnable> replies=new ConcurrentLinkedQueue<>();
    private final List<String> messages=new ArrayList<>();
    private final UUID playerId=UUID.randomUUID();
    private CommandActor sender(boolean player,boolean permission){
        return new CommandActor(){
            @Override public boolean permitted(String node){assertSame(owner,Thread.currentThread(),"Permission access must remain on command/reply owner");return permission;}
            @Override public void tell(String message){assertSame(owner,Thread.currentThread());messages.add(message);}
            @Override public Optional<UUID> player(){assertSame(owner,Thread.currentThread(),"Viewer UUID must be captured before private execution");return player?Optional.of(playerId):Optional.empty();}
            @Override public boolean reply(Runnable action){replies.add(action);return true;}
        };
    }
    /** The words the Director tree routes to each typed behavior command, as {@code /animus behaviors ...} sends them. */
    private static void exec(BehaviorCommands service,CommandActor sender,String[] args){
        switch(args[1]){
            case "status"->service.status(sender);
            case "list"->service.list(sender,args.length>2?Integer.parseInt(args[2]):1);
            case "validate"->service.validate(sender,args.length>2?Optional.of(args[2]):Optional.empty());
            case "reload"->service.reload(sender);
            case "schema"->service.schemaCommand(sender);
            case "packs"->service.packs(sender,args.length>2?Integer.parseInt(args[2]):1);
            case "run"->service.run(sender,args[2],args[3]);
            case "inspect"->service.inspect(sender,UUID.fromString(args[2]));
            case "trace"->service.trace(sender,args[2]);
            case "cancel"->service.cancel(sender,UUID.fromString(args[2]));
            case "triggers"->service.triggers(sender,args.length>2?Integer.parseInt(args[2]):1);
            case "state"->{
                if(args[2].equals("status"))service.stateStatus(sender);
                else if(args[3].equals("npc"))service.purgeNpc(sender,args[4]);
                else throw new AssertionError(String.join(" ",args));
            }
            default->throw new AssertionError(String.join(" ",args));
        }
    }
    private void pump(){for(Runnable action;(action=replies.poll())!=null;)action.run();}
    private void until(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end){pump();Thread.sleep(5);}pump();assertTrue(condition.getAsBoolean());
    }
    private Path source(String id,long revision,int wait)throws Exception{
        Path path=root.resolve("behaviors/"+id.replace(':','/')+".json");Files.createDirectories(path.getParent());Files.writeString(path,"{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":"+wait+"}}}");return path;
    }
    private final class Harness implements AutoCloseable {
        final AnimusRuntime runtime;final ExtensionRuntime extensions;final BehaviorCommands service;final List<String> reports=new CopyOnWriteArrayList<>();
        Harness(BehaviorSettings settings,Function<MutationLane.EngineTaskObserver,Executor> supplied)throws Exception{
            runtime=new AnimusRuntime(RuntimeEnvironment.bukkit("26.3",root));runtime.start();
            if(runtime.npcs().all().isEmpty())new Administration(runtime).create("test","minecraft:player","world",0,64,0,"default","Test");
            AtomicReference<BehaviorCommands> ready=new AtomicReference<>();extensions=new ExtensionRuntime(root.resolve("modules"),art.arcane.animus.api.Platform.PAPER,runtime.definitionGateway(),new ModuleSettings(false,4,1000,250),reports::add,state->ready.get().modulesInitialized(state));
            service=new BehaviorCommands(root,settings,runtime.npcs(),runtime.documents(),runtime.behaviorState(),extensions,supplied==null?runtime::fileExecutor:supplied,reports::add);ready.set(service);
        }
        void start()throws Exception{extensions.start();until(()->service.statusLines().getFirst().startsWith("Behaviors READY"));}
        UUID run(CommandActor sender,String id)throws Exception{
            messages.clear();exec(service,sender,new String[]{"behaviors","run","test",id});until(()->messages.stream().anyMatch(line->line.startsWith("Behavior run: OK")));
            var match=EXECUTION.matcher(messages.stream().filter(line->line.startsWith("Behavior run: OK")).findFirst().orElseThrow());assertTrue(match.find());UUID execution=UUID.fromString(match.group(1));until(()->service.diagnosticRuntime().inspect(execution).isPresent());return execution;
        }
        public void close()throws Exception{
            service.close();extensions.close();runtime.closeAsync();
            if(service.diagnosticRuntime()!=null)assertTrue(service.diagnosticRuntime().awaitStopped(Duration.ofSeconds(3)));
            assertTrue(extensions.awaitStopped(Duration.ofSeconds(3)));runtime.close();assertTrue(runtime.awaitFileQuiescence(Duration.ofSeconds(3)));pump();
        }
    }
    @Test void startupWaitsForOneTerminalModuleObserverAndCommandsCaptureOnlyViewerUuid()throws Exception{
        source("example:wait",1,100);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandActor sender=sender(true,true);exec(harness.service,sender,new String[]{"behaviors","run","test","example:wait"});pump();assertTrue(messages.getFirst().contains("WAITING_MODULES"));assertNotNull(harness.service.diagnosticRuntime());assertTrue(harness.service.diagnosticRuntime().snapshot().executions().isEmpty());
            harness.start();UUID execution=harness.run(sender,"example:wait");until(()->harness.service.diagnosticRuntime().inspect(execution).isPresent());assertEquals(Optional.of(playerId),harness.service.diagnosticRuntime().inspect(execution).orElseThrow().player());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","inspect",execution.toString()});until(()->!messages.isEmpty());assertTrue(messages.getFirst().contains("npc-revision=1"));assertTrue(messages.getFirst().contains("behavior-revision=1"));assertTrue(messages.getFirst().contains("source-hash="));
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","cancel",execution.toString()});until(()->messages.stream().anyMatch(line->line.contains("cancelled=true")));until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            assertEquals(List.of("example:wait"),harness.service.behaviorIds());
        }
    }
    @Test void validateDoesNotPublishButReloadReplacesAuthorityAndCancelsExistingExecution()throws Exception{
        Path file=source("example:wait",1,200);try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);UUID execution=harness.run(sender,"example:wait");source("example:wait",2,1);String bytes=Files.readString(file);
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","validate"});until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors validate: VALID")));assertTrue(messages.getFirst().contains("generation=1"));assertTrue(messages.getFirst().contains("published=false"));assertEquals(bytes,Files.readString(file));assertEquals(BehaviorEngine.State.RUNNING,harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","reload"});until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors reload: APPLIED")));assertTrue(messages.getFirst().contains("generation=2"));until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            UUID replacement=harness.run(sender,"example:wait");until(()->harness.service.diagnosticRuntime().inspect(replacement).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);assertEquals(2,harness.service.diagnosticRuntime().inspect(replacement).orElseThrow().behaviorRevision());
        }
    }
    @Test void fileInitializationHasOneAdmissionAndColdCloseStopsOwnedRuntimeBeforeFirstCatalog()throws Exception{
        List<Runnable> queued=new ArrayList<>();AtomicReference<MutationLane.EngineTaskObserver> observer=new AtomicReference<>();
        try(var harness=new Harness(BehaviorSettings.DEFAULT,callback->{observer.set(callback);return queued::add;})){
            harness.extensions.start();harness.service.modulesInitialized(ExtensionRuntime.State.DISABLED);assertEquals(1,queued.size());var owned=harness.service.diagnosticRuntime();assertNotNull(owned);assertTrue(owned.snapshot().executions().isEmpty());
            exec(harness.service,sender(false,true),new String[]{"behaviors","reload"});pump();assertTrue(messages.getFirst().contains("BUSY"));assertEquals(1,queued.size());
            harness.service.close();assertTrue(owned.awaitStopped(Duration.ofSeconds(3)));queued.getFirst().run();assertSame(owned,harness.service.diagnosticRuntime());assertTrue(owned.snapshot().executions().isEmpty());assertTrue(harness.service.statusLines().getFirst().contains("CLOSED"));assertTrue(harness.service.statusLines().getFirst().contains("catalog-work=idle"));
        }
    }
    @Test void queueRejectionIsVisibleAndDoesNotStartASecondInitialization()throws Exception{
        try(var harness=new Harness(BehaviorSettings.DEFAULT,observer->task->{throw new RejectedExecutionException("full");})){
            harness.extensions.start();assertTrue(harness.service.statusLines().getFirst().contains("FAILED"));assertTrue(harness.service.statusLines().getFirst().contains("file-queue-unavailable"));assertNotNull(harness.service.diagnosticRuntime());assertTrue(harness.service.diagnosticRuntime().snapshot().executions().isEmpty());assertEquals(1,harness.reports.stream().filter(line->line.contains("file-queue-unavailable")).count());
            harness.service.modulesInitialized(ExtensionRuntime.State.DISABLED);assertEquals(1,harness.reports.stream().filter(line->line.contains("file-queue-unavailable")).count());
        }
    }
    @Test void sharedLaneCancellationSettlesPendingCatalogAndLateObserverCannotReopenClosedService()throws Exception{
        List<Runnable> queued=new ArrayList<>();AtomicReference<MutationLane.EngineTaskObserver> observer=new AtomicReference<>();
        try(var harness=new Harness(BehaviorSettings.DEFAULT,callback->{observer.set(callback);return queued::add;})){
            harness.extensions.start();assertEquals(1,queued.size());observer.get().report(queued.getFirst(),MutationLane.EngineTaskEvent.CANCELLED,null);
            assertTrue(harness.service.statusLines().getFirst().contains("FAILED"));assertTrue(harness.service.statusLines().getFirst().contains("catalog-work=idle"));assertTrue(harness.service.statusLines().getFirst().contains("file-task-cancelled"));
            harness.service.close();harness.service.modulesInitialized(ExtensionRuntime.State.READY);observer.get().report(queued.getFirst(),MutationLane.EngineTaskEvent.FAILED,new AssertionError("late"));
            assertTrue(harness.service.statusLines().getFirst().contains("CLOSED"));assertNotNull(harness.service.diagnosticRuntime());assertTrue(harness.service.diagnosticRuntime().awaitStopped(Duration.ofSeconds(3)));assertEquals(1,queued.size());
        }
    }
    private static BehaviorSettings enabled(boolean enabled,BehaviorCatalog.Limits catalog){
        var d=BehaviorSettings.DEFAULT;return new BehaviorSettings(enabled,d.maximumOperations(),d.operationsPerTick(),d.tickMillis(),d.deliveryBudgetMillis(),d.engine(),catalog,d.triggers(),d.state(),d.actions(),d.server());
    }
    @Test void behaviorsEnabledHotloadsOffAndOnWithoutStoppingRunningExecutions()throws Exception{
        source("example:wait",1,30);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);UUID running=harness.run(sender,"example:wait");
            harness.service.settings(enabled(false,BehaviorCatalog.Limits.defaults()));
            assertTrue(harness.service.statusLines().getFirst().startsWith("Behaviors DISABLED"));
            assertEquals("behaviors are disabled in config.toml",harness.service.triggers().availability());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","run","test","example:wait"});pump();assertTrue(messages.getFirst().contains("UNAVAILABLE | state=DISABLED"),messages.toString());
            until(()->harness.service.diagnosticRuntime().inspect(running).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            harness.service.settings(BehaviorSettings.DEFAULT);
            until(()->harness.service.statusLines().getFirst().startsWith("Behaviors READY"));
            UUID next=harness.run(sender,"example:wait");assertNotEquals(running,next);
        }
    }
    @Test void behaviorsStartedDisabledLoadTheCatalogWhenEnabled()throws Exception{
        source("example:wait",1,1);
        try(var harness=new Harness(enabled(false,BehaviorCatalog.Limits.defaults()),null)){
            harness.extensions.start();
            assertTrue(harness.service.statusLines().getFirst().startsWith("Behaviors DISABLED"));
            harness.service.settings(BehaviorSettings.DEFAULT);
            until(()->harness.service.statusLines().getFirst().startsWith("Behaviors READY"));
            harness.run(sender(false,true),"example:wait");
        }
    }
    @Test void changedCatalogBudgetsReloadTheCatalog()throws Exception{
        source("example:wait",1,1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();assertTrue(harness.service.statusLines().getFirst().contains("catalog-generation=1"));
            var d=BehaviorCatalog.Limits.defaults();
            BehaviorSettings tighter=enabled(true,new BehaviorCatalog.Limits(d.maximumDocuments()-1,d.maximumEntries(),d.maximumDepth(),d.maximumDocumentBytes(),d.maximumAggregateBytes(),d.maximumCapabilityChecks()));
            harness.service.settings(tighter);
            until(()->harness.service.statusLines().getFirst().contains("catalog-generation=2"));
            harness.service.settings(tighter);
            Thread.sleep(100);assertTrue(harness.service.statusLines().getFirst().contains("catalog-generation=2"),"unchanged budgets do not reload");
        }
    }
    @Test void disabledBehaviorSettingsNeverQueueCatalogWork()throws Exception{
        var defaults=BehaviorSettings.DEFAULT;var disabled=new BehaviorSettings(false,defaults.maximumOperations(),defaults.operationsPerTick(),defaults.tickMillis(),defaults.deliveryBudgetMillis(),defaults.engine(),defaults.catalog(),defaults.triggers(),defaults.state(),defaults.actions(),defaults.server());AtomicInteger submitted=new AtomicInteger();
        try(var harness=new Harness(disabled,observer->task->submitted.incrementAndGet())){
            harness.extensions.start();assertEquals(0,submitted.get());assertTrue(harness.service.diagnosticRuntime().snapshot().executions().isEmpty());CommandActor sender=sender(false,true);exec(harness.service,sender,new String[]{"behaviors","reload"});pump();assertTrue(messages.getFirst().contains("DISABLED"));assertEquals(0,submitted.get());
        }
    }
    @Test void pagesAndDiagnosticsAreBoundedAndDeniedCommandsDoNotRun()throws Exception{
        for(int i=0;i<30;i++)source("example:p"+i,1,1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);exec(harness.service,sender,new String[]{"behaviors","list","2"});pump();assertEquals(11,messages.size());assertTrue(messages.getFirst().contains("30 documents; page 2"));
            for(int i=0;i<30;i++)Files.writeString(root.resolve("behaviors/example/p"+i+".json"),"malformed");messages.clear();exec(harness.service,sender,new String[]{"behaviors","validate"});until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors validate: INVALID")));assertEquals(17,messages.size());
        }
    }
    private Path scopedSource(String id,long revision)throws Exception{
        Path path=root.resolve("behaviors/"+id.replace(':','/')+".json");Files.createDirectories(path.getParent());
        Files.writeString(path,"{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"variables\":{\"visits\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"persistent\"}},\"routines\":{\"main\":{\"type\":\"set\",\"variable\":\"visits\",\"value\":5}}}");
        return path;
    }
    private void command(Harness harness,CommandActor sender,String expected,String...args)throws Exception{
        messages.clear();String[] full=new String[args.length+1];full[0]="behaviors";System.arraycopy(args,0,full,1,args.length);
        exec(harness.service,sender,full);until(()->messages.stream().anyMatch(line->line.contains(expected)));
    }
    @Test void stateStatusTraceAndPurgeCommandsWorkThroughTheFileLane()throws Exception{
        scopedSource("example:quest",1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandActor sender=sender(false,true);harness.start();UUID execution=harness.run(sender,"example:quest");
            until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            command(harness,sender,"persistent-keys=1/65536","state","status");
            assertTrue(messages.getFirst().contains("invalid=0"),messages.toString());
            command(harness,sender,"state=COMPLETED","trace","test");
            assertTrue(messages.getFirst().contains("active=0"),messages.toString());assertTrue(messages.stream().anyMatch(line->line.contains("trigger=manual")),messages.toString());
            assertTrue(messages.stream().anyMatch(line->line.startsWith("Behavior recent: execution="+execution)),messages.toString());
            command(harness,sender,"NOT_FOUND","trace","nobody");
            assertEquals(1,harness.runtime.behaviorState().status().persistentKeys());
            command(harness,sender,"Behavior state purge: OK | documents=1 | keys=1","state","purge","npc","test");
            assertEquals(0,harness.runtime.behaviorState().status().persistentKeys());
        }
    }
    @Test void restartKeepsPersistentStateAndReportsMalformedSourceOnItsLastValidProgram()throws Exception{
        Path file=scopedSource("example:quest",1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandActor sender=sender(false,true);harness.start();UUID execution=harness.run(sender,"example:quest");
            until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
        }
        assertTrue(Files.exists(root.resolve("state/behavior-catalog/last-valid/behaviors/example/quest.json")));
        Files.writeString(file,"{\"schemaVersion\":1,");
        messages.clear();
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandActor sender=sender(false,true);harness.start();
            assertEquals(1,harness.runtime.behaviorState().status().persistentKeys());
            assertTrue(harness.reports.stream().anyMatch(line->line.contains("behavior-json-invalid")&&line.contains("$")),harness.reports.toString());
            UUID execution=harness.run(sender,"example:quest");
            until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            assertEquals(5L,harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().variables().get("visits").value());
        }
        assertEquals("{\"schemaVersion\":1,",Files.readString(file),"Invalid source bytes stay unchanged");
    }
    private void triggerSource(String id,long revision,String triggers)throws Exception{
        Path path=root.resolve("behaviors/"+id.replace(':','/')+".json");Files.createDirectories(path.getParent());Files.writeString(path,"{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":2000}},\"triggers\":"+triggers+"}");
    }
    private void attach(Harness harness,String behavior)throws Exception{
        var before=harness.runtime.npcs().draft("test");var document=before.document();document.addProperty("spawned",true);
        var references=new com.google.gson.JsonArray();references.add(behavior);document.add("behaviors",references);
        harness.runtime.npcs().apply(new art.arcane.animus.core.npc.NpcRepository.Draft(before.path(),before.expectedHash(),before.expectedRevision(),document));
    }
    @Test void triggerStartedScopedValuesReachDiskWhileRunningThroughTheRealWiring()throws Exception{
        Files.writeString(root.resolve("config.toml"),"statusMessage = 'Test'\n[behaviors.state]\nflushMillis = 100\n");
        Path behavior=root.resolve("behaviors/example/quest.json");Files.createDirectories(behavior.getParent());
        Files.writeString(behavior,"{\"schemaVersion\":1,\"revision\":1,\"id\":\"example:quest\",\"entry\":\"main\",\"variables\":{\"trust\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"relationship\",\"lifetime\":\"persistent\"}},\"routines\":{\"main\":{\"type\":\"set\",\"variable\":\"trust\",\"value\":7}},\"triggers\":[{\"id\":\"click\",\"event\":\"interaction\"},{\"id\":\"born\",\"event\":\"spawn\"}]}");
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:quest");var triggers=harness.service.triggers();triggers.connect();
            UUID npc=harness.runtime.npcs().find("test").orElseThrow().uuid();triggers.activated(npc);
            // The spawn event has no player, so this behavior's relationship variable refuses it at admission, and it is counted.
            until(()->triggers.snapshot().dropped().getOrDefault(art.arcane.animus.core.behavior.trigger.TriggerDispatcher.DropReason.START_FAILED,0L)==1);
            assertTrue(triggers.snapshot().diagnostics().stream().anyMatch(item->item.code().equals("behavior-state-player-required")&&item.field().contains("trigger=example:quest#born")),triggers.snapshot().diagnostics().toString());
            CommandActor sender=sender(false,true);
            command(harness,sender,"behavior-state-player-required","triggers");
            assertEquals(art.arcane.animus.api.Outcome.Status.OK,triggers.publish(new art.arcane.animus.core.behavior.trigger.TriggerEvent.Interaction(npc,playerId,art.arcane.animus.core.behavior.trigger.TriggerEvent.Action.INTERACT)).status());
            Path stored=root.resolve("state/behavior-state/relationship/"+npc+"/"+playerId+"/example/quest.json");
            // Nothing here flushes or closes: only the owner turn's maintenance can write this file.
            until(()->Files.exists(stored));
            assertTrue(Files.readString(stored).contains("\"value\": 7"),Files.readString(stored));
            command(harness,sender,"trigger=example:quest#click","trace","test");
            String recent=messages.stream().filter(line->line.startsWith("Behavior recent:")).findFirst().orElseThrow();assertTrue(recent.contains("state=COMPLETED"),recent);
            var execution=harness.service.diagnosticRuntime().snapshot().executions().values().stream().filter(item->item.trigger().isPresent()).findFirst().orElseThrow();
            assertEquals(Optional.of(playerId),execution.player());assertEquals(7L,execution.report().variables().get("trust").value());
            command(harness,sender,"trigger=example:quest#click","inspect",execution.report().id().toString());
        }
    }
    @Test void triggerCommandListsBindingsLastFiredAndDrops()throws Exception{
        triggerSource("example:greeter",1,"[{\"id\":\"click\",\"event\":\"interaction\"}]");
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:greeter");var triggers=harness.service.triggers();triggers.connect();
            UUID npc=harness.runtime.npcs().find("test").orElseThrow().uuid();triggers.activated(npc);
            until(()->triggers.snapshot().bindings()==1);
            CommandActor sender=sender(false,true);exec(harness.service,sender,new String[]{"behaviors","triggers"});pump();
            assertTrue(messages.getFirst().startsWith("Behavior triggers: AVAILABLE"),messages.getFirst());assertTrue(messages.getFirst().contains("bindings=1"));
            String row=messages.stream().filter(line->line.startsWith("Trigger ")).findFirst().orElseThrow();
            assertTrue(row.contains("npc=test"));assertTrue(row.contains("behavior=example:greeter"));assertTrue(row.contains("trigger=click"));assertTrue(row.contains("event=interaction"));assertTrue(row.contains("last-fired=never"));
            assertEquals(art.arcane.animus.api.Outcome.Status.OK,triggers.publish(new art.arcane.animus.core.behavior.trigger.TriggerEvent.Interaction(npc,playerId,art.arcane.animus.core.behavior.trigger.TriggerEvent.Action.INTERACT)).status());
            until(()->harness.service.diagnosticRuntime().snapshot().executions().values().stream().anyMatch(item->item.trigger().isPresent()));
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","triggers"});pump();
            assertTrue(messages.stream().anyMatch(line->line.startsWith("Trigger ")&&line.contains("fired=1")&&!line.contains("last-fired=never")),messages.toString());
            var execution=harness.service.diagnosticRuntime().snapshot().executions().values().iterator().next();assertEquals(Optional.of(playerId),execution.player());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","inspect",execution.report().id().toString()});until(()->!messages.isEmpty());assertTrue(messages.getFirst().contains("trigger=example:greeter#click"),messages.getFirst());
            assertTrue(harness.service.statusLines().stream().anyMatch(line->line.startsWith("Behavior triggers: AVAILABLE")));
        }
    }
    @Test void triggerPagesAreBounded()throws Exception{
        StringBuilder many=new StringBuilder("[");for(int i=0;i<30;i++)many.append(i==0?"":",").append("{\"id\":\"t").append(i).append("\",\"event\":\"interaction\"}");many.append(']');
        triggerSource("example:greeter",1,many.toString());
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:greeter");var triggers=harness.service.triggers();triggers.connect();triggers.activated(harness.runtime.npcs().find("test").orElseThrow().uuid());
            until(()->triggers.snapshot().bindings()==30);
            CommandActor sender=sender(false,true);exec(harness.service,sender,new String[]{"behaviors","triggers","2"});pump();
            assertTrue(messages.size()<=24,messages.toString());assertEquals(10,messages.stream().filter(line->line.startsWith("Trigger ")).count());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","triggers","0"});pump();assertTrue(messages.getFirst().contains("Page must be"));
        }
    }
    @Test void unavailablePlatformReportsItsDiagnosticAndStillAllowsManualRuns()throws Exception{
        triggerSource("example:greeter",1,"[{\"id\":\"click\",\"event\":\"interaction\"}]");
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:greeter");harness.service.triggers().unavailable("NPC runtime unavailable on Folia until region-safe ownership and snapshots are proven");
            CommandActor sender=sender(false,true);exec(harness.service,sender,new String[]{"behaviors","triggers"});pump();
            assertTrue(messages.getFirst().startsWith("Behavior triggers: UNAVAILABLE"),messages.getFirst());assertTrue(messages.getFirst().contains("Folia"));
            var outcome=harness.service.triggers().publish(new art.arcane.animus.core.behavior.trigger.TriggerEvent.Waypoint(UUID.randomUUID(),1));assertEquals(art.arcane.animus.api.Outcome.Status.UNAVAILABLE,outcome.status());
            harness.run(sender,"example:greeter");
        }
    }
    @Test void disabledBehaviorsKeepAnUnavailableDispatcherAndReportIt()throws Exception{
        var defaults=BehaviorSettings.DEFAULT;var disabled=new BehaviorSettings(false,defaults.maximumOperations(),defaults.operationsPerTick(),defaults.tickMillis(),defaults.deliveryBudgetMillis(),defaults.engine(),defaults.catalog(),defaults.triggers(),defaults.state(),defaults.actions(),defaults.server());
        try(var harness=new Harness(disabled,observer->task->{})){
            assertFalse(harness.service.triggers().available());exec(harness.service,sender(false,true),new String[]{"behaviors","triggers"});pump();assertTrue(messages.getFirst().contains("UNAVAILABLE | reason=behaviors are disabled"),messages.toString());
        }
    }
    @Test void schemaWritesGeneratedFilesThroughTheFileLane()throws Exception{
        source("example:wait",1,100);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","schema"});
            until(()->messages.stream().anyMatch(line->line.startsWith("Behavior schemas: WRITTEN")));
            for(String file:List.of("behavior.schema.json","npc.schema.json","pack.schema.json","completions.json"))assertTrue(Files.isRegularFile(root.resolve("schemas/"+file)),file);
            assertTrue(messages.stream().anyMatch(line->line.contains("capabilities="+harness.extensions.capabilities().size())),messages.toString());
            assertTrue(Files.readString(root.resolve("schemas/behavior.schema.json")).startsWith("{"));
        }
    }
    @Test void validateCanNameOneBehaviorAndCompletionOffersIdentifiers()throws Exception{
        source("example:wait",1,100);Files.writeString(root.resolve("behaviors/example/broken.json"),"{}");
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","validate","example:wait"});
            until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors validate:")));
            assertTrue(messages.stream().noneMatch(line->line.contains("broken.json")),messages.toString());
            assertTrue(messages.getFirst().contains("behavior=example:wait"),messages.getFirst());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","validate"});
            until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors validate: INVALID")));
            assertTrue(messages.stream().anyMatch(line->line.contains("broken.json")),messages.toString());
            assertEquals(List.of("example:wait"),harness.service.behaviorIds());
            assertTrue(harness.service.stateBehaviors().containsAll(List.of("example:wait","animus:flags/example","animus:quests/example")));
        }
    }
    @Test void deliberatelyDeletedSourcesLeaveTheCatalogAndTheirCheckpoints()throws Exception{
        Path file=source("example:wait",1,1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);
            assertNotNull(harness.runtime.documents().read("state/behavior-catalog/last-valid/behaviors/example/wait.json"));
            Files.delete(file);
            messages.clear();harness.service.reload(sender,java.util.Set.of("example:wait"));
            until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors reload: APPLIED")));
            assertTrue(messages.getFirst().contains("documents=0")&&messages.getFirst().contains("invalid=0"),messages.toString());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","reload"});
            until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors reload: APPLIED")));
            assertTrue(messages.stream().noneMatch(line->line.contains("behavior-source-missing")),messages.toString());
            assertNull(harness.runtime.documents().read("state/behavior-catalog/last-valid/behaviors/example/wait.json"));
        }
    }
    @Test void packsReloadWithoutRestartAndReportMissingModules()throws Exception{
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandActor sender=sender(false,true);
            Path pack=root.resolve("packs/town");Files.createDirectories(pack.resolve("behaviors/town"));
            Files.writeString(pack.resolve("pack.json"),"{\"schemaVersion\":1,\"id\":\"town\",\"modules\":{\"gatekeeper\":{\"configuration\":{\"openTicks\":200}}}}");
            Files.writeString(pack.resolve("behaviors/town/greeter.json"),"{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}");
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","reload"});
            until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors reload: APPLIED")));
            assertTrue(messages.stream().anyMatch(line->line.contains("pack-module-unavailable")),messages.toString());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","packs"});pump();
            assertTrue(messages.stream().anyMatch(line->line.startsWith("Behavior pack town")&&line.contains("manifest=valid")&&line.contains("missing-modules=gatekeeper")),messages.toString());
            messages.clear();exec(harness.service,sender,new String[]{"behaviors","list"});pump();
            assertTrue(messages.stream().anyMatch(line->line.contains("town:greeter")&&line.contains("available=false")),messages.toString());
        }
    }
    @Test void lineBoundDoesNotSplitUnicodeOrEmitEmbeddedNewlines(){
        String text="x".repeat(2045)+"🐉".repeat(30)+"\nsecret\rline";String bounded=BehaviorCommands.boundedLine(text);assertTrue(bounded.length()<=2048);assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(bounded));assertFalse(bounded.contains("\n"));assertFalse(bounded.contains("\r"));
    }
}
