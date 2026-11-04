package art.arcane.animus.bukkit;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.config.*;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.MutationLane;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BukkitBehaviorCommandsTest {
    @TempDir Path root;
    private static final Pattern EXECUTION=Pattern.compile("execution=([a-f0-9-]{36})");
    private final Thread owner=Thread.currentThread();
    private final Queue<Runnable> replies=new ConcurrentLinkedQueue<>();
    private final List<String> messages=new ArrayList<>();
    private final UUID playerId=UUID.randomUUID();
    private CommandSender sender(boolean player,boolean permission){
        return (CommandSender)Proxy.newProxyInstance(CommandSender.class.getClassLoader(),new Class<?>[]{player?Player.class:CommandSender.class},(proxy,method,args)->switch(method.getName()){
            case "hasPermission"->{assertSame(owner,Thread.currentThread(),"Permission access must remain on command/reply owner");yield permission;}
            case "getUniqueId"->{assertSame(owner,Thread.currentThread(),"Viewer UUID must be captured before private execution");yield playerId;}
            case "sendMessage"->{assertSame(owner,Thread.currentThread());messages.add((String)args[0]);yield null;}
            case "toString"->"Behavior command test sender";
            case "hashCode"->System.identityHashCode(proxy);
            case "equals"->proxy==args[0];
            default->throw new AssertionError("Unexpected Bukkit access: "+method.getName());
        });
    }
    private void pump(){for(Runnable action;(action=replies.poll())!=null;)action.run();}
    private void until(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end){pump();Thread.sleep(5);}pump();assertTrue(condition.getAsBoolean());
    }
    private Path source(String id,long revision,int wait)throws Exception{
        Path path=root.resolve("behaviors/"+id.replace(':','/')+".json");Files.createDirectories(path.getParent());Files.writeString(path,"{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":"+wait+"}}}");return path;
    }
    private final class Harness implements AutoCloseable {
        final AnimusRuntime runtime;final ExtensionRuntime extensions;final BukkitBehaviorCommands service;final List<String> reports=new CopyOnWriteArrayList<>();
        Harness(BehaviorSettings settings,Function<MutationLane.EngineTaskObserver,Executor> supplied)throws Exception{
            runtime=new AnimusRuntime(RuntimeEnvironment.bukkit("26.3",root));runtime.start();
            new Administration(runtime).execute(new String[]{"create","test","minecraft:player","world","0","64","0","default","Test"});
            AtomicReference<BukkitBehaviorCommands> ready=new AtomicReference<>();extensions=new ExtensionRuntime(root.resolve("modules"),art.arcane.animus.api.Platform.PAPER,runtime.definitionGateway(),new ModuleSettings(false,4,1000,250),reports::add,state->ready.get().modulesInitialized(state));
            service=new BukkitBehaviorCommands(root,settings,runtime.npcs(),extensions,supplied==null?runtime::fileExecutor:supplied,(sender,task)->{replies.add(task);return true;},reports::add);ready.set(service);
        }
        void start()throws Exception{extensions.start();until(()->service.statusLines().getFirst().startsWith("Behaviors READY"));}
        UUID run(CommandSender sender,String id)throws Exception{
            messages.clear();service.execute(sender,new String[]{"behaviors","run","test",id});until(()->messages.stream().anyMatch(line->line.startsWith("Behavior run: OK")));
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
            CommandSender sender=sender(true,true);harness.service.execute(sender,new String[]{"behaviors","run","test","example:wait"});pump();assertTrue(messages.getFirst().contains("WAITING_MODULES"));assertNotNull(harness.service.diagnosticRuntime());assertTrue(harness.service.diagnosticRuntime().snapshot().executions().isEmpty());
            harness.start();UUID execution=harness.run(sender,"example:wait");until(()->harness.service.diagnosticRuntime().inspect(execution).isPresent());assertEquals(Optional.of(playerId),harness.service.diagnosticRuntime().inspect(execution).orElseThrow().player());
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","inspect",execution.toString()});pump();assertTrue(messages.getFirst().contains("npc-revision=1"));assertTrue(messages.getFirst().contains("behavior-revision=1"));assertTrue(messages.getFirst().contains("source-hash="));
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","cancel",execution.toString()});until(()->messages.stream().anyMatch(line->line.contains("cancelled=true")));until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            assertEquals(List.of("example:wait"),harness.service.complete(new String[]{"behaviors","run","test","example:"}));
        }
    }
    @Test void validateDoesNotPublishButReloadReplacesAuthorityAndCancelsExistingExecution()throws Exception{
        Path file=source("example:wait",1,200);try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandSender sender=sender(false,true);UUID execution=harness.run(sender,"example:wait");source("example:wait",2,1);String bytes=Files.readString(file);
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","validate"});until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors validate: VALID")));assertTrue(messages.getFirst().contains("generation=1"));assertTrue(messages.getFirst().contains("published=false"));assertEquals(bytes,Files.readString(file));assertEquals(BehaviorEngine.State.RUNNING,harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state());
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","reload"});until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors reload: APPLIED")));assertTrue(messages.getFirst().contains("generation=2"));until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.CANCELLED);
            UUID replacement=harness.run(sender,"example:wait");until(()->harness.service.diagnosticRuntime().inspect(replacement).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);assertEquals(2,harness.service.diagnosticRuntime().inspect(replacement).orElseThrow().behaviorRevision());
        }
    }
    @Test void fileInitializationHasOneAdmissionAndColdCloseStopsOwnedRuntimeBeforeFirstCatalog()throws Exception{
        List<Runnable> queued=new ArrayList<>();AtomicReference<MutationLane.EngineTaskObserver> observer=new AtomicReference<>();
        try(var harness=new Harness(BehaviorSettings.DEFAULT,callback->{observer.set(callback);return queued::add;})){
            harness.extensions.start();harness.service.modulesInitialized(ExtensionRuntime.State.DISABLED);assertEquals(1,queued.size());var owned=harness.service.diagnosticRuntime();assertNotNull(owned);assertTrue(owned.snapshot().executions().isEmpty());
            harness.service.execute(sender(false,true),new String[]{"behaviors","reload"});pump();assertTrue(messages.getFirst().contains("BUSY"));assertEquals(1,queued.size());
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
    @Test void disabledBehaviorSettingsNeverQueueCatalogOrCreateOwner()throws Exception{
        var defaults=BehaviorSettings.DEFAULT;var disabled=new BehaviorSettings(false,defaults.maximumOperations(),defaults.operationsPerTick(),defaults.tickMillis(),defaults.deliveryBudgetMillis(),defaults.engine(),defaults.catalog(),defaults.triggers());AtomicInteger submitted=new AtomicInteger();
        try(var harness=new Harness(disabled,observer->task->submitted.incrementAndGet())){
            harness.extensions.start();assertEquals(0,submitted.get());assertNull(harness.service.diagnosticRuntime());CommandSender sender=sender(false,true);harness.service.execute(sender,new String[]{"behaviors","reload"});pump();assertTrue(messages.getFirst().contains("DISABLED"));assertEquals(0,submitted.get());
        }
    }
    @Test void pagesAndDiagnosticsAreBoundedAndDeniedCommandsDoNotRun()throws Exception{
        for(int i=0;i<30;i++)source("example:p"+i,1,1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();CommandSender sender=sender(false,true);harness.service.execute(sender,new String[]{"behaviors","list","2"});pump();assertEquals(11,messages.size());assertTrue(messages.getFirst().contains("30 documents; page 2"));
            messages.clear();harness.service.execute(sender(false,false),new String[]{"behaviors","run","test","example:p0"});pump();assertEquals(List.of("You do not have permission to use this command."),messages);assertTrue(harness.service.diagnosticRuntime().snapshot().executions().isEmpty());
            for(int i=0;i<30;i++)Files.writeString(root.resolve("behaviors/example/p"+i+".json"),"malformed");messages.clear();harness.service.execute(sender,new String[]{"behaviors","validate"});until(()->messages.stream().anyMatch(line->line.startsWith("Behaviors validate: INVALID")));assertEquals(17,messages.size());
        }
    }
    private void triggerSource(String id,long revision,String triggers)throws Exception{
        Path path=root.resolve("behaviors/"+id.replace(':','/')+".json");Files.createDirectories(path.getParent());Files.writeString(path,"{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":2000}},\"triggers\":"+triggers+"}");
    }
    private void attach(Harness harness,String behavior)throws Exception{
        var before=harness.runtime.npcs().draft("test");var document=before.document();document.addProperty("spawned",true);
        var references=new com.google.gson.JsonArray();references.add(behavior);document.add("behaviors",references);
        harness.runtime.npcs().apply(new art.arcane.animus.core.npc.NpcRepository.Draft(before.path(),before.expectedHash(),before.expectedRevision(),document));
    }
    @Test void triggerCommandListsBindingsLastFiredAndDrops()throws Exception{
        triggerSource("example:greeter",1,"[{\"id\":\"click\",\"event\":\"interaction\"}]");
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:greeter");var triggers=harness.service.triggers();triggers.connect();
            UUID npc=harness.runtime.npcs().find("test").orElseThrow().uuid();triggers.activated(npc);
            until(()->triggers.snapshot().bindings()==1);
            CommandSender sender=sender(false,true);harness.service.execute(sender,new String[]{"behaviors","triggers"});pump();
            assertTrue(messages.getFirst().startsWith("Behavior triggers: AVAILABLE"),messages.getFirst());assertTrue(messages.getFirst().contains("bindings=1"));
            String row=messages.stream().filter(line->line.startsWith("Trigger ")).findFirst().orElseThrow();
            assertTrue(row.contains("npc=test"));assertTrue(row.contains("behavior=example:greeter"));assertTrue(row.contains("trigger=click"));assertTrue(row.contains("event=interaction"));assertTrue(row.contains("last-fired=never"));
            assertEquals(art.arcane.animus.api.Outcome.Status.OK,triggers.publish(new art.arcane.animus.core.behavior.trigger.TriggerEvent.Interaction(npc,playerId,art.arcane.animus.core.behavior.trigger.TriggerEvent.Action.INTERACT)).status());
            until(()->harness.service.diagnosticRuntime().snapshot().executions().values().stream().anyMatch(item->item.trigger().isPresent()));
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","triggers"});pump();
            assertTrue(messages.stream().anyMatch(line->line.startsWith("Trigger ")&&line.contains("fired=1")&&!line.contains("last-fired=never")),messages.toString());
            var execution=harness.service.diagnosticRuntime().snapshot().executions().values().iterator().next();assertEquals(Optional.of(playerId),execution.player());
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","inspect",execution.report().id().toString()});pump();assertTrue(messages.getFirst().contains("trigger=example:greeter#click"),messages.getFirst());
            assertTrue(harness.service.statusLines().stream().anyMatch(line->line.startsWith("Behavior triggers: AVAILABLE")));
            assertTrue(BukkitBehaviorCommands.OPERATIONS.contains("triggers"));
        }
    }
    @Test void triggerPagesAreBounded()throws Exception{
        StringBuilder many=new StringBuilder("[");for(int i=0;i<30;i++)many.append(i==0?"":",").append("{\"id\":\"t").append(i).append("\",\"event\":\"interaction\"}");many.append(']');
        triggerSource("example:greeter",1,many.toString());
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:greeter");var triggers=harness.service.triggers();triggers.connect();triggers.activated(harness.runtime.npcs().find("test").orElseThrow().uuid());
            until(()->triggers.snapshot().bindings()==30);
            CommandSender sender=sender(false,true);harness.service.execute(sender,new String[]{"behaviors","triggers","2"});pump();
            assertTrue(messages.size()<=24,messages.toString());assertEquals(10,messages.stream().filter(line->line.startsWith("Trigger ")).count());
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","triggers","0"});pump();assertTrue(messages.getFirst().contains("Page must be"));
        }
    }
    @Test void unavailablePlatformReportsItsDiagnosticAndStillAllowsManualRuns()throws Exception{
        triggerSource("example:greeter",1,"[{\"id\":\"click\",\"event\":\"interaction\"}]");
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            harness.start();attach(harness,"example:greeter");harness.service.triggers().unavailable("NPC runtime unavailable on Folia until region-safe ownership and snapshots are proven");
            CommandSender sender=sender(false,true);harness.service.execute(sender,new String[]{"behaviors","triggers"});pump();
            assertTrue(messages.getFirst().startsWith("Behavior triggers: UNAVAILABLE"),messages.getFirst());assertTrue(messages.getFirst().contains("Folia"));
            var outcome=harness.service.triggers().publish(new art.arcane.animus.core.behavior.trigger.TriggerEvent.Waypoint(UUID.randomUUID(),1));assertEquals(art.arcane.animus.api.Outcome.Status.UNAVAILABLE,outcome.status());
            harness.run(sender,"example:greeter");
        }
    }
    @Test void disabledBehaviorsHaveNoDispatcherAndReportIt()throws Exception{
        var defaults=BehaviorSettings.DEFAULT;var disabled=new BehaviorSettings(false,defaults.maximumOperations(),defaults.operationsPerTick(),defaults.tickMillis(),defaults.deliveryBudgetMillis(),defaults.engine(),defaults.catalog(),defaults.triggers());
        try(var harness=new Harness(disabled,observer->task->{})){
            assertNull(harness.service.triggers());harness.service.execute(sender(false,true),new String[]{"behaviors","triggers"});pump();assertTrue(messages.getFirst().contains("UNAVAILABLE"));
        }
    }
    @Test void lineBoundDoesNotSplitUnicodeOrEmitEmbeddedNewlines(){
        String text="x".repeat(2045)+"🐉".repeat(30)+"\nsecret\rline";String bounded=BukkitBehaviorCommands.boundedLine(text);assertTrue(bounded.length()<=2048);assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(bounded));assertFalse(bounded.contains("\n"));assertFalse(bounded.contains("\r"));
    }
}
