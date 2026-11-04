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
            if(runtime.npcs().all().isEmpty())new Administration(runtime).execute(new String[]{"create","test","minecraft:player","world","0","64","0","default","Test"});
            AtomicReference<BukkitBehaviorCommands> ready=new AtomicReference<>();extensions=new ExtensionRuntime(root.resolve("modules"),art.arcane.animus.api.Platform.PAPER,runtime.definitionGateway(),new ModuleSettings(false,4,1000,250),reports::add,state->ready.get().modulesInitialized(state));
            service=new BukkitBehaviorCommands(root,settings,runtime.npcs(),runtime.documents(),runtime.behaviorState(),extensions,supplied==null?runtime::fileExecutor:supplied,(sender,task)->{replies.add(task);return true;},reports::add);ready.set(service);
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
            messages.clear();harness.service.execute(sender,new String[]{"behaviors","inspect",execution.toString()});until(()->!messages.isEmpty());assertTrue(messages.getFirst().contains("npc-revision=1"));assertTrue(messages.getFirst().contains("behavior-revision=1"));assertTrue(messages.getFirst().contains("source-hash="));
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
        var defaults=BehaviorSettings.DEFAULT;var disabled=new BehaviorSettings(false,defaults.maximumOperations(),defaults.operationsPerTick(),defaults.tickMillis(),defaults.deliveryBudgetMillis(),defaults.engine(),defaults.catalog(),defaults.state());AtomicInteger submitted=new AtomicInteger();
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
    private Path scopedSource(String id,long revision)throws Exception{
        Path path=root.resolve("behaviors/"+id.replace(':','/')+".json");Files.createDirectories(path.getParent());
        Files.writeString(path,"{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"variables\":{\"visits\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"persistent\"}},\"routines\":{\"main\":{\"type\":\"set\",\"variable\":\"visits\",\"value\":5}}}");
        return path;
    }
    private void command(Harness harness,CommandSender sender,String expected,String...args)throws Exception{
        messages.clear();String[] full=new String[args.length+1];full[0]="behaviors";System.arraycopy(args,0,full,1,args.length);
        harness.service.execute(sender,full);until(()->messages.stream().anyMatch(line->line.contains(expected)));
    }
    @Test void stateStatusTraceAndPurgeCommandsWorkThroughTheFileLane()throws Exception{
        scopedSource("example:quest",1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandSender sender=sender(false,true);harness.start();UUID execution=harness.run(sender,"example:quest");
            until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            command(harness,sender,"persistent-keys=1/65536","state","status");
            assertTrue(messages.getFirst().contains("invalid=0"),messages.toString());
            command(harness,sender,"state=COMPLETED","trace","test");
            assertTrue(messages.getFirst().contains("active=none"),messages.toString());
            assertTrue(messages.stream().anyMatch(line->line.startsWith("Behavior recent: execution="+execution)),messages.toString());
            command(harness,sender,"NOT_FOUND","trace","nobody");
            command(harness,sender,"purge all confirm","state","purge","all");
            assertEquals(1,harness.runtime.behaviorState().status().persistentKeys(),"An unconfirmed full purge removes nothing");
            command(harness,sender,"Invalid UUID","state","purge","player","not-a-uuid");
            command(harness,sender,"Behavior state purge: OK | documents=1 | keys=1","state","purge","npc","test");
            assertEquals(0,harness.runtime.behaviorState().status().persistentKeys());
            assertEquals(List.of("state"),harness.service.complete(new String[]{"behaviors","sta"}).stream().filter("state"::equals).toList());
            assertEquals(List.of("purge"),harness.service.complete(new String[]{"behaviors","state","pu"}));
            assertEquals(List.of("relationship"),harness.service.complete(new String[]{"behaviors","state","purge","rel"}));
        }
    }
    @Test void restartKeepsPersistentStateAndReportsMalformedSourceOnItsLastValidProgram()throws Exception{
        Path file=scopedSource("example:quest",1);
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandSender sender=sender(false,true);harness.start();UUID execution=harness.run(sender,"example:quest");
            until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
        }
        assertTrue(Files.exists(root.resolve("state/behavior-catalog/last-valid/behaviors/example/quest.json")));
        Files.writeString(file,"{\"schemaVersion\":1,");
        messages.clear();
        try(var harness=new Harness(BehaviorSettings.DEFAULT,null)){
            CommandSender sender=sender(false,true);harness.start();
            assertEquals(1,harness.runtime.behaviorState().status().persistentKeys());
            assertTrue(harness.reports.stream().anyMatch(line->line.contains("behavior-json-invalid")&&line.contains("$")),harness.reports.toString());
            UUID execution=harness.run(sender,"example:quest");
            until(()->harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().state()==BehaviorEngine.State.COMPLETED);
            assertEquals(5L,harness.service.diagnosticRuntime().inspect(execution).orElseThrow().report().variables().get("visits").value());
        }
        assertEquals("{\"schemaVersion\":1,",Files.readString(file),"Invalid source bytes stay unchanged");
    }
    @Test void lineBoundDoesNotSplitUnicodeOrEmitEmbeddedNewlines(){
        String text="x".repeat(2045)+"🐉".repeat(30)+"\nsecret\rline";String bounded=BukkitBehaviorCommands.boundedLine(text);assertTrue(bounded.length()<=2048);assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(bounded));assertFalse(bounded.contains("\n"));assertFalse(bounded.contains("\r"));
    }
}
