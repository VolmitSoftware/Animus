package art.arcane.animus.core.command;

import art.arcane.animus.api.*;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.behavior.schema.BehaviorSchemaGenerator;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerSink;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.behavior.runtime.BehaviorRuntime;
import art.arcane.animus.core.config.BehaviorSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Administrative behavior commands and the owned execution runtime. File work shares the engine lane; no private owner touches a command actor.
 * Every {@code [behaviors]} key hotloads: budgets go to their owners, which adopt them on their next tick, and lowered caps drain.
 */
public final class BehaviorCommands implements AutoCloseable {
    public enum State { DISABLED, WAITING_MODULES, LOADING, READY, FAILED, CLOSED }
    private static final int PAGE_SIZE=20,MAX_LINES=24,MAX_LINE_CHARS=2048;
    private static final BehaviorCatalog.Snapshot EMPTY_CATALOG=new BehaviorCatalog.Snapshot(0,Map.of(),Map.of(),List.of());
    private final Path dataDirectory;
    private volatile BehaviorSettings settings;
    private volatile AutoCloseable subscription;
    private final NpcRepository repository;
    private final DocumentStore documents;
    private final BehaviorStateStore behaviorState;
    private final ExtensionRuntime extensions;
    private final Executor files;
    private final Consumer<String> report;
    private final Object lifecycle=new Object();
    private final AtomicBoolean closed=new AtomicBoolean(),initializationSeen=new AtomicBoolean();
    private final AtomicReference<CatalogTask> fileTask=new AtomicReference<>();
    private final AtomicBoolean schemaPending=new AtomicBoolean(),reloadAgain=new AtomicBoolean();
    /** Behavior ids a committed change deleted on purpose; the next reload forgets their retained programs and checkpoints. */
    private final Set<String> removedSources=ConcurrentHashMap.newKeySet();
    private final BehaviorRuntime coordinator;
    private final TriggerDispatcher triggers;
    private final ServerTriggerDispatcher servers;
    private volatile ServerBehaviorList serverBehaviors=ServerBehaviorList.UNLOADED;
    private final AtomicLong unavailableReplies=new AtomicLong();
    private volatile BehaviorCatalog catalog;
    private volatile State state;
    private volatile String failure="none";

    public BehaviorCommands(Path dataDirectory,AnimusRuntime runtime,ExtensionRuntime extensions,Consumer<String> report){
        this(dataDirectory,runtime.behaviorSettings(),runtime.npcs(),runtime.documents(),runtime.behaviorState(),extensions,runtime::fileExecutor,report);
        subscription=runtime.onSettings((PluginSettings next)->settings(next.behaviors()));
    }
    // Engine-owned seams allow lifecycle/queue tests without creating a server or accessing world state.
    BehaviorCommands(Path dataDirectory,BehaviorSettings settings,NpcRepository repository,DocumentStore documents,BehaviorStateStore behaviorState,ExtensionRuntime extensions,
                          Function<MutationLane.EngineTaskObserver,Executor> fileExecutor,Consumer<String> report){
        this.dataDirectory=Objects.requireNonNull(dataDirectory);this.settings=Objects.requireNonNull(settings);
        this.repository=Objects.requireNonNull(repository);this.documents=Objects.requireNonNull(documents);this.behaviorState=Objects.requireNonNull(behaviorState);this.extensions=Objects.requireNonNull(extensions);
        this.report=Objects.requireNonNull(report);
        files=Objects.requireNonNull(fileExecutor.apply(this::fileEvent));state=settings.enabled()?State.WAITING_MODULES:State.DISABLED;
        // Own and resolve lifecycle classes while the plugin archive is open. Startup catalog I/O
        // remains asynchronous; the empty source snapshot cannot admit executions before READY.
        // Both exist while disabled too, so a hotload can enable behaviors and the platform bridge is already bound.
        triggers=new TriggerDispatcher(settings.triggers(),repository::snapshot,this::catalogSnapshot);
        triggers.behaviorsEnabled(settings.enabled());
        servers=new ServerTriggerDispatcher(settings.server(),this::catalogSnapshot,()->serverBehaviors);
        servers.behaviorsEnabled(settings.enabled());
        extensions.connectTriggers(new ModuleTriggers(triggers,servers));
        coordinator=new BehaviorRuntime(runtimeLimits(settings),settings.engine(),repository::snapshot,this::catalogSnapshot,extensions.behaviorInvoker(),triggers,servers,behaviorState);
    }
    private static BehaviorRuntime.Limits runtimeLimits(BehaviorSettings settings){
        return new BehaviorRuntime.Limits(settings.maximumOperations(),settings.operationsPerTick(),settings.tickMillis(),settings.deliveryBudgetMillis());
    }
    /**
     * A hotload of {@code [behaviors]}, on the file lane. Owners adopt the new budgets on their next tick; nothing running is
     * stopped. Disabling refuses new runs and trigger events while running executions finish; enabling loads the catalog as
     * startup does. Changed catalog budgets reload the catalog.
     */
    public void settings(BehaviorSettings next){
        Objects.requireNonNull(next);
        BehaviorSettings previous;boolean load=false;
        synchronized(lifecycle){
            if(closed.get())return;
            previous=settings;settings=next;
            if(previous.enabled()&&!next.enabled())state=State.DISABLED;
            else if(!previous.enabled()&&next.enabled()){
                if(!initializationSeen.get())state=State.WAITING_MODULES;
                else{state=catalogSnapshot().generation()>0?State.READY:State.LOADING;load=true;}
            }
        }
        triggers.settings(next.triggers());triggers.behaviorsEnabled(next.enabled());servers.behaviorsEnabled(next.enabled());
        coordinator.limits(runtimeLimits(next),next.engine());
        behaviorState.limits(next.state());
        BehaviorCatalog current=catalog;if(current!=null)current.limits(next.catalog());
        if(next.enabled()&&initializationSeen.get()&&!previous.catalog().equals(next.catalog()))load=true;
        if(load)submitCatalog(null,false,true,Optional.empty(),true);
    }
    /** The one initialization observer only admits bounded file work; it never scans on module management. */
    public void modulesInitialized(ExtensionRuntime.State terminal){
        if(!Set.of(ExtensionRuntime.State.READY,ExtensionRuntime.State.DISABLED,ExtensionRuntime.State.FAILED).contains(terminal))throw new IllegalArgumentException("Expected terminal module initialization state");
        if(!initializationSeen.compareAndSet(false,true)||!settings.enabled()||!publishState(State.LOADING))return;
        submitCatalog(null,false,true,Optional.empty());
    }
    public void status(CommandActor sender){reply(sender,statusLines());}
    public void list(CommandActor sender,int page){guarded(sender,()->reply(sender,listLines(page)));}
    /** Validates every behavior, or only the named one. */
    public void validate(CommandActor sender,Optional<String> only){
        guarded(sender,()->{only.ifPresent(BehaviorPath::forId);if(catalogAdmission(sender,"validate"))submitCatalog(sender,true,false,only);});
    }
    public void reload(CommandActor sender){if(catalogAdmission(sender,"reload"))submitCatalog(sender,false,false,Optional.empty());}
    public void schemaCommand(CommandActor sender){if(catalogAdmission(sender,"schema"))schema(sender);}
    public void packs(CommandActor sender,int page){guarded(sender,()->reply(sender,packLines(page)));}
    public void run(CommandActor sender,String npc,String behavior){
        BehaviorRuntime runtime=ready(sender,"run",false);if(runtime==null)return;Optional<UUID> player=sender.player();
        runtime.run(npc,behavior,player).whenComplete((outcome,error)->{
            if(error!=null)reply(sender,List.of("Behavior run: FAILED | result-delivery"));
            else reply(sender,outcomeLines("Behavior run",outcome,outcome.value().map(id->"execution="+id+" | npc="+npc+" | behavior="+behavior).orElse("")));
        });
    }
    public void trace(CommandActor sender,String npc){
        BehaviorRuntime runtime=ready(sender,"trace",true);if(runtime==null)return;
        runtime.observeNpc(npc).whenComplete((outcome,error)->{
            if(error!=null)reply(sender,List.of("Behavior trace: FAILED | result-delivery"));
            else if(outcome.value().isEmpty())reply(sender,outcomeLines("Behavior trace",outcome,"npc="+npc));
            else reply(sender,traceLines(npc,outcome.value().orElseThrow()));
        });
    }
    public void inspect(CommandActor sender,UUID id){
        BehaviorRuntime runtime=coordinator;
        if(runtime==null){reply(sender,List.of("Behavior inspect: UNAVAILABLE | state="+state));return;}
        runtime.observe(id).whenComplete((outcome,error)->{
            if(error!=null)reply(sender,List.of("Behavior inspect: FAILED | result-delivery"));
            else reply(sender,outcome.value().map(BehaviorCommands::executionLines).orElse(List.of("Behavior inspect: "+outcome.status()+" | execution="+id)));
        });
    }
    public void cancel(CommandActor sender,UUID id){
        BehaviorRuntime runtime=ready(sender,"cancel",true);if(runtime==null)return;
        runtime.cancel(id).whenComplete((outcome,error)->{
            if(error!=null)reply(sender,List.of("Behavior cancel: FAILED | result-delivery"));
            else reply(sender,outcomeLines("Behavior cancel",outcome,"execution="+id+outcome.value().map(value->" | cancelled="+value).orElse("")));
        });
    }
    public void triggers(CommandActor sender,int page){guarded(sender,()->reply(sender,triggerLines(page)));}
    public void stateStatus(CommandActor sender){reply(sender,stateLines());}
    /** The stored variables of one behavior, one NPC (id or UUID), one player, one NPC and player pair, or everything. */
    public void purgeBehavior(CommandActor sender,String behavior){guarded(sender,()->{BehaviorPath.forId(behavior);purge(sender,BehaviorStateStore.Purge.behavior(behavior));});}
    public void purgeNpc(CommandActor sender,String npc){guarded(sender,()->purge(sender,BehaviorStateStore.Purge.npc(npcIdentity(npc))));}
    public void purgePlayer(CommandActor sender,UUID player){purge(sender,BehaviorStateStore.Purge.player(player));}
    public void purgeRelationship(CommandActor sender,String npc,UUID player){guarded(sender,()->purge(sender,BehaviorStateStore.Purge.relationship(npcIdentity(npc),player)));}
    public void purgeAll(CommandActor sender){purge(sender,BehaviorStateStore.Purge.all());}
    private void purge(CommandActor sender,BehaviorStateStore.Purge selector){
        StateTask task=new StateTask(sender,selector);
        try{files.execute(task);}
        catch(RejectedExecutionException rejected){task.failed("file-queue-unavailable");}
    }
    /** An invalid id or page is answered like every other behavior refusal. */
    private void guarded(CommandActor sender,Runnable work){
        try{work.run();}
        catch(RuntimeException invalid){reply(sender,List.of("Animus behaviors: "+Objects.toString(invalid.getMessage(),invalid.getClass().getSimpleName())));}
    }
    /** Ids for completion: loaded behaviors, NPC definitions and the executions running now. */
    public List<String> behaviorIds(){return catalogSnapshot().byId().keySet().stream().sorted().limit(100).toList();}
    public List<String> executionIds(){BehaviorRuntime runtime=coordinator;return runtime==null?List.of():runtime.snapshot().executions().keySet().stream().map(UUID::toString).sorted().limit(100).toList();}
    /** A deleted NPC has no definition left; its stored state stays reachable through the UUID. */
    private UUID npcIdentity(String npc){
        NpcDefinition definition=repository.snapshot().get(npc);
        return definition!=null?definition.uuid():UUID.fromString(npc);
    }
    private final class StateTask implements Runnable {
        final CommandActor sender;final BehaviorStateStore.Purge selector;
        StateTask(CommandActor sender,BehaviorStateStore.Purge selector){this.sender=sender;this.selector=selector;}
        public void run(){
            try{
                BehaviorStateStore.PurgeResult result=behaviorState.purge(selector);
                reply(sender,List.of("Behavior state purge: OK | documents="+result.documents()+" | keys="+result.keys()));
            }catch(IOException | RuntimeException error){failed(error.getClass().getSimpleName()+": "+error.getMessage());}
        }
        void failed(String reason){reply(sender,List.of("Behavior state purge: FAILED | "+reason));}
    }
    List<String> stateLines(){
        BehaviorStateStore.Status status=behaviorState.status();
        List<String> rows=new ArrayList<>();
        rows.add("Behavior state: persistent-keys="+status.persistentKeys()+"/"+settings.state().maximumKeys()+" | transient-keys="+status.transientKeys()+"/"+settings.state().maximumTransientKeys()+" | persistent-bytes="+status.persistentBytes()+"/"+settings.state().maximumBytes()+" | documents="+status.documents()+" | dirty="+status.dirtyDocuments()+" | flushes="+status.flushes()+" | flush-failures="+status.flushFailures()+" | invalid="+status.invalidDocuments()+(status.lastFailure().isEmpty()?"":" | last-failure="+status.lastFailure()));
        for(BehaviorStateStore.Problem problem:behaviorState.problems()){
            if(rows.size()>=17)break;
            rows.add("Behavior state invalid: "+problem.path()+" | "+problem.message());
        }
        return rows;
    }
    private static String origin(BehaviorRuntime.Execution execution){return execution.trigger().map(trigger->" | trigger="+trigger).orElse(" | trigger=manual");}
    static List<String> traceLines(String npc,BehaviorRuntime.NpcTrace trace){
        List<String> rows=new ArrayList<>();
        rows.add("Behavior trace: npc="+npc+" | npc-uuid="+trace.npc()+" | active="+trace.active().size()+" | recent="+trace.recent().size());
        for(BehaviorRuntime.Execution execution:trace.active()){
            BehaviorEngine.Report report=execution.report();
            String step=report.trace().isEmpty()?"none":report.trace().getLast().path()+" "+report.trace().getLast().event();
            rows.add("Behavior active: execution="+report.id()+" | behavior="+execution.behaviorId()+origin(execution)+" | steps="+report.steps()+" | waits="+report.waits()+" | last-step="+step);
        }
        for(BehaviorRuntime.Execution execution:trace.recent()){
            BehaviorEngine.Report report=execution.report();
            rows.add("Behavior recent: execution="+report.id()+" | behavior="+execution.behaviorId()+origin(execution)+" | state="+report.state()+" | steps="+report.steps()+" | reason="+report.reason());
        }
        return rows;
    }
    /** Behavior IDs plus the flag and quest state of their namespaces, which purge reaches by these names. */
    public List<String> stateBehaviors(){
        Set<String> ids=new TreeSet<>(catalogSnapshot().byId().keySet());
        for(String id:List.copyOf(ids)){String namespace=id.substring(0,id.indexOf(':'));ids.add("animus:flags/"+namespace);ids.add("animus:quests/"+namespace);}
        return List.copyOf(ids);
    }
    private boolean catalogAdmission(CommandActor sender,String operation){
        if(closed.get()||!settings.enabled()||!initializationSeen.get()){
            reply(sender,List.of("Behaviors "+operation+": UNAVAILABLE | state="+state));return false;
        }
        return true;
    }
    /** Trace and cancel stay open while disabled, so executions still running can be watched or stopped. */
    private BehaviorRuntime ready(CommandActor sender,String operation,boolean whileDisabled){
        BehaviorRuntime runtime=coordinator;State current=state;
        if(closed.get()||runtime==null||current!=State.READY&&!(whileDisabled&&current==State.DISABLED)){reply(sender,List.of("Behavior "+operation+": UNAVAILABLE | state="+state));return null;}
        return runtime;
    }
    /**
     * The reload after a committed change. {@code removed} names behaviors whose sources the change deleted on purpose: their
     * retained programs and last-valid checkpoints go too, so nothing reports them missing. Pending catalog work runs it again after.
     */
    public void reload(CommandActor sender,Set<String> removed){
        removedSources.addAll(removed);
        if(!catalogAdmission(sender,"reload"))return;
        submitCatalog(sender,false,false,Optional.empty(),true);
    }
    private void submitCatalog(CommandActor sender,boolean validate,boolean initial,Optional<String> only){submitCatalog(sender,validate,initial,only,false);}
    private void submitCatalog(CommandActor sender,boolean validate,boolean initial,Optional<String> only,boolean follow){
        BehaviorCatalog current=catalog;CatalogTask task=new CatalogTask(sender,validate,initial,current==null?0:current.snapshot().generation(),only);
        if(!fileTask.compareAndSet(null,task)){
            if(follow){reloadAgain.set(true);return;}
            if(sender!=null)reply(sender,List.of("Behaviors "+(validate?"validate":"reload")+": BUSY | catalog-work=pending"));return;
        }
        if(closed.get()){fileTask.compareAndSet(task,null);return;}
        try{files.execute(task);}
        catch(RejectedExecutionException rejected){fileTask.compareAndSet(task,null);task.failed("file-queue-unavailable");}
    }
    private void fileEvent(Runnable task,MutationLane.EngineTaskEvent event,Throwable error){
        if(task instanceof CatalogTask owned&&fileTask.compareAndSet(owned,null))owned.failed(event==MutationLane.EngineTaskEvent.CANCELLED?"file-task-cancelled":"file-task-failed");
        if(task instanceof SchemaTask owned&&schemaPending.compareAndSet(true,false))owned.failed(event==MutationLane.EngineTaskEvent.CANCELLED?"file-task-cancelled":"file-task-failed");
    }
    /** Removal tokens for accepted programs of {@code ids} whose source file is gone. */
    private Set<BehaviorCatalog.Removal> removals(BehaviorCatalog.Snapshot snapshot,Set<String> ids){
        if(ids.isEmpty())return Set.of();
        Set<BehaviorCatalog.Removal> removals=new HashSet<>();
        for(BehaviorCatalog.Entry entry:snapshot.entries().values()){
            if(entry.accepted().isEmpty()||!ids.contains(entry.accepted().orElseThrow().program().id()))continue;
            if(!java.nio.file.Files.exists(dataDirectory.resolve(entry.path()),java.nio.file.LinkOption.NOFOLLOW_LINKS))removals.add(BehaviorCatalog.Removal.of(entry));
        }
        return removals;
    }
    /** Reads server-behaviors.json on the file lane after each published catalog. Reports listed ids that cannot run. */
    private List<String> loadServerBehaviors(){
        ServerBehaviorList next=ServerBehaviorList.read(dataDirectory,serverBehaviors.generation()+1);
        serverBehaviors=next;
        List<String> lines=new ArrayList<>();
        for(Diagnostic diagnostic:next.problems())lines.add(diagnosticLine(ServerBehaviorList.PATH,diagnostic));
        BehaviorCatalog.Snapshot snapshot=catalogSnapshot();int runnable=0;
        for(String id:next.ids()){
            BehaviorCatalog.Resolution resolution=snapshot.byId().get(id);
            if(resolution==null)lines.add("Server behavior "+id+": not in the catalog; skipped");
            else if(resolution.accepted().isEmpty()||!resolution.available())lines.add("Server behavior "+id+": unavailable; skipped");
            else if(!resolution.accepted().orElseThrow().program().server())lines.add("Server behavior "+id+": not a server behavior (\"server\": true); skipped");
            else runnable++;
        }
        if(!next.ids().isEmpty()||!next.problems().isEmpty())lines.addFirst("Server behaviors: enabled="+runnable+"/"+next.ids().size()+" | file="+ServerBehaviorList.PATH);
        return lines;
    }
    /** The server behaviors enabled by the last read of server-behaviors.json. */
    public ServerBehaviorList serverBehaviors(){return serverBehaviors;}
    private BehaviorCatalog.Snapshot catalogSnapshot(){BehaviorCatalog current=catalog;return current==null?EMPTY_CATALOG:current.snapshot();}
    private void failedState(){if(catalogSnapshot().generation()==0)publishState(State.FAILED);}
    private final class CatalogTask implements Runnable {
        final CommandActor sender;final boolean validate,initial;final long generation;final Optional<String> only;
        CatalogTask(CommandActor sender,boolean validate,boolean initial,long generation,Optional<String> only){this.sender=sender;this.validate=validate;this.initial=initial;this.generation=generation;this.only=only;}
        public void run(){
            try{
                if(closed.get())return;
                BehaviorCatalog current=catalog;
                if(current==null){current=new BehaviorCatalog(dataDirectory,settings.catalog(),extensions.catalogResolver(),new BehaviorCheckpoints(documents));catalog=current;}
                List<String> messages;
                if(validate){
                    var checked=current.validate(generation);messages=validationLines(checked,only);
                }else{
                    Set<String> forgotten=Set.copyOf(removedSources);removedSources.removeAll(forgotten);
                    var changed=current.reload(generation,removals(current.snapshot(),forgotten));messages=reloadLines(changed);
                    if(!changed.published())removedSources.addAll(forgotten);
                    if(changed.published()){
                        if(closed.get())return;
                        messages=new ArrayList<>(messages);messages.addAll(loadServerBehaviors());
                        failure="none";publishState(State.READY);
                    }else{
                        failure=changed.diagnostics().isEmpty()?"catalog-reload-rejected":changed.diagnostics().getFirst().code();
                        failedState();
                    }
                }
                if(closed.get())return;
                // Startup reports every line: a malformed source kept on its last-valid program must name its exact errors.
                if(initial)messages.forEach(report);else reply(sender,messages);
            }catch(IOException | RuntimeException | LinkageError | AssertionError error){failed("catalog-operation: "+error.getClass().getSimpleName());}
            finally{fileTask.compareAndSet(this,null);if(reloadAgain.compareAndSet(true,false)&&!closed.get())submitCatalog(null,false,false,Optional.empty(),true);}
        }
        void failed(String reason){
            if(closed.get())return;failure=reason;failedState();
            String message="Behaviors "+(validate?"validate":"reload")+": FAILED | "+reason;
            if(initial)report.accept(message);else if(sender!=null)reply(sender,List.of(message));
        }
    }
    /** Generated schema and completion files, written on the shared file lane. Bounded by the generator; nothing is written when it refuses. */
    private void schema(CommandActor sender){
        if(!schemaPending.compareAndSet(false,true)){reply(sender,List.of("Behavior schemas: BUSY | schema-work=pending"));return;}
        SchemaTask task=new SchemaTask(sender);
        if(closed.get()){schemaPending.set(false);return;}
        try{files.execute(task);}
        catch(RejectedExecutionException rejected){schemaPending.set(false);task.failed("file-queue-unavailable");}
    }
    private final class SchemaTask implements Runnable {
        final CommandActor sender;
        SchemaTask(CommandActor sender){this.sender=sender;}
        public void run(){
            try{
                if(closed.get())return;
                List<Capabilities.Availability> capabilities=extensions.capabilities();
                Map<String,String> generated=BehaviorSchemaGenerator.generate(capabilities);
                long bytes=0;
                for(Map.Entry<String,String> document:generated.entrySet()){
                    DocumentStore.atomic(documents.resolve(document.getKey()),document.getValue());
                    bytes+=document.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
                reply(sender,List.of("Behavior schemas: WRITTEN | directory=schemas | files="+generated.size()+" | bytes="+bytes+" | capabilities="+capabilities.size()));
            }catch(IOException | RuntimeException error){failed(error.getClass().getSimpleName()+": "+error.getMessage());}
            finally{schemaPending.set(false);}
        }
        void failed(String reason){reply(sender,List.of("Behavior schemas: FAILED | "+reason));}
    }
    private List<String> packLines(int page){
        if(page<1||page>1000)throw new IllegalArgumentException("Page must be 1..1000");
        BehaviorCatalog.Snapshot snapshot=catalogSnapshot();List<String> lines=new ArrayList<>();
        lines.add("Behavior packs: "+snapshot.packs().size()+" | page="+page);
        for(BehaviorCatalog.PackStatus pack:snapshot.packs().values().stream().skip((page-1L)*PAGE_SIZE).limit(PAGE_SIZE).toList()){
            long behaviors=0,available=0;
            for(BehaviorCatalog.Entry entry:snapshot.entries().values()){
                if(!BehaviorPath.pack(entry.path()).map(pack.id()::equals).orElse(false))continue;
                behaviors++;
                String id=entry.declaredId().orElseGet(()->entry.accepted().map(value->value.program().id()).orElse(""));
                if(snapshot.find(id).map(BehaviorCatalog.Resolution::available).orElse(false))available++;
            }
            boolean missing=pack.diagnostics().stream().anyMatch(problem->problem.code().equals("pack-manifest-missing"));
            boolean invalid=pack.diagnostics().stream().anyMatch(problem->problem.code().equals("pack-manifest-invalid"));
            String manifest=missing?"missing":invalid?(pack.manifest().isPresent()?"invalid-retained":"invalid"):"valid";
            lines.add("Behavior pack "+pack.id()+" | manifest="+manifest+pack.manifest().flatMap(PackManifest::name).map(name->" | name="+name).orElse("")
                    +pack.manifest().flatMap(PackManifest::version).map(version->" | version="+version).orElse("")+" | behaviors="+behaviors+" | available="+available
                    +" | missing-modules="+(pack.missingModules().isEmpty()?"none":String.join(",",pack.missingModules())));
        }
        return lines;
    }
    public List<String> statusLines(){
        BehaviorCatalog current=catalog;BehaviorCatalog.Snapshot snapshot=current==null?null:current.snapshot();BehaviorRuntime runtime=coordinator;
        List<String> result=new ArrayList<>();result.add("Behaviors "+state+" | catalog-generation="+(snapshot==null?0:snapshot.generation())+" | documents="+(snapshot==null?0:snapshot.entries().size())+" | available="+(snapshot==null?0:available(snapshot))+" | catalog-work="+(fileTask.get()==null?"idle":"pending")+" | unavailable-replies="+unavailableReplies.get()+" | failure="+failure);
        if(runtime!=null){var status=runtime.status();result.add("Behavior runtime: "+status.state()+" | admitted="+status.admittedOperations()+" | queued="+status.queuedOperations()+" | active="+status.engine().activeExecutions()+" | retained-calls="+status.engine().retiredCalls()+" | tick="+status.engine().tick()+" | delivery-queued="+status.queuedDeliveries()+" | delivery-active="+status.activeDeliveries()+" | delivery-over-budget="+status.overBudgetDeliveries()+" | failure="+status.failure());}
        result.add(triggerSummary(triggers.snapshot()));
        result.add(serverSummary(servers.snapshot()));
        return List.copyOf(result);
    }
    private static String triggerSummary(TriggerDispatcher.Snapshot view){
        long dropped=view.dropped().values().stream().mapToLong(Long::longValue).sum();
        return "Behavior triggers: "+(view.available()?"AVAILABLE":"UNAVAILABLE | reason="+view.reason())+" | bindings="+view.bindings()+" | timers="+view.timers()+" | proximity-npcs="+view.proximityNpcs()+" | active="+view.active()+" | queued="+view.queued()+" | events="+view.events()+" | started="+view.started()+" | suppressed="+view.suppressed()+" | dropped="+dropped;
    }
    private static String serverSummary(ServerTriggerDispatcher.Snapshot view){
        long dropped=view.dropped().values().stream().mapToLong(Long::longValue).sum();
        return "Server triggers: "+(view.available()?"AVAILABLE":"UNAVAILABLE | reason="+view.reason())+" | behaviors="+view.behaviors()+" | bindings="+view.bindings()+" | timers="+view.timers()+" | commands="+view.commands()+" | active="+view.active()+" | queued="+view.queued()+" | events="+view.events()+" | started="+view.started()+" | suppressed="+view.suppressed()+" | dropped="+dropped;
    }
    private List<String> triggerLines(int page){
        if(page<1||page>1000)throw new IllegalArgumentException("Page must be 1..1000");
        TriggerDispatcher.Snapshot view=triggers.snapshot();List<String> lines=new ArrayList<>();lines.add(triggerSummary(view)+" | page="+page);
        if(!view.dropped().isEmpty())lines.add("Trigger drops: "+new TreeMap<>(view.dropped()));
        view.diagnostics().stream().limit(2).forEach(diagnostic->lines.add(diagnosticLine("triggers",diagnostic)));
        if(view.diagnostics().size()>2)lines.add("Trigger diagnostics: "+(view.diagnostics().size()-2)+" more");
        List<TriggerDispatcher.Row> npcRows=triggers.rows((page-1)*PAGE_SIZE,PAGE_SIZE);
        for(TriggerDispatcher.Row row:npcRows)
            lines.add("Trigger npc="+row.npcId()+" | behavior="+row.behavior()+" | trigger="+row.trigger()+" | event="+row.event()+" | routine="+row.routine()+" | last-fired="+(row.lastFiredTicksAgo().isPresent()?row.lastFiredTicksAgo().getAsLong()+"t-ago":"never")+" | fired="+row.fired()+" | active="+row.active()+" | queued="+row.queued()+" | suppressed="+row.suppressed()+" | dropped="+row.dropped());
        // Server rows follow the NPC rows on later pages: page numbering continues across both lists.
        ServerTriggerDispatcher.Snapshot server=servers.snapshot();
        int npcTotal=view.bindings(),skip=Math.max(0,(page-1)*PAGE_SIZE-npcTotal),room=PAGE_SIZE-npcRows.size();
        if(page==1||room>0){
            lines.add(serverSummary(server));
            if(!server.dropped().isEmpty())lines.add("Server trigger drops: "+new TreeMap<>(server.dropped()));
            server.diagnostics().stream().limit(2).forEach(diagnostic->lines.add(diagnosticLine("server-triggers",diagnostic)));
            if(server.diagnostics().size()>2)lines.add("Server trigger diagnostics: "+(server.diagnostics().size()-2)+" more");
        }
        if(room>0)for(ServerTriggerDispatcher.Row row:servers.rows(skip,room))
            lines.add("Trigger server | behavior="+row.behavior()+" | trigger="+row.trigger()+" | event="+row.event()+" | routine="+row.routine()+" | last-fired="+(row.lastFiredTicksAgo().isPresent()?row.lastFiredTicksAgo().getAsLong()+"t-ago":"never")+" | fired="+row.fired()+" | active="+row.active()+" | queued="+row.queued()+" | suppressed="+row.suppressed()+" | dropped="+row.dropped());
        return lines;
    }
    /** The dispatcher the platform bridge connects to. While behaviors are disabled it refuses every event. */
    public TriggerDispatcher triggers(){return triggers;}
    /** The server-behavior dispatcher platforms publish server events to and read commands from. */
    public ServerTriggerDispatcher servers(){return servers;}
    /** Connects the platform's world facts to every behavior run. */
    public void facts(art.arcane.animus.core.behavior.BehaviorFacts provider){coordinator.facts(provider);}
    /** Engine diagnostics only. Owned from construction, enabled or not. */
    public BehaviorRuntime diagnosticRuntime(){return coordinator;}
    private List<String> listLines(int page){
        if(page<1||page>1000)throw new IllegalArgumentException("Page must be 1..1000");BehaviorCatalog current=catalog;BehaviorCatalog.Snapshot snapshot=current==null?null:current.snapshot();List<String> lines=new ArrayList<>();
        lines.add("Behaviors: "+(snapshot==null?0:snapshot.entries().size())+" documents; page "+page);if(snapshot==null)return lines;
        snapshot.entries().values().stream().skip((page-1L)*PAGE_SIZE).limit(PAGE_SIZE).forEach(entry->{
            String id=entry.declaredId().orElseGet(()->entry.accepted().map(value->value.program().id()).orElse("<unidentified>"));boolean available=snapshot.find(id).map(BehaviorCatalog.Resolution::available).orElse(false);
            lines.add("Behavior "+id+" | path="+entry.path()+" | state="+entry.state()+" | available="+available+" | accepted-revision="+entry.accepted().map(BehaviorCatalog.Accepted::revision).orElse(0L)+" | observed-revision="+(entry.observedRevision().isPresent()?entry.observedRevision().getAsLong():0)+" | source-hash="+entry.accepted().map(BehaviorCatalog.Accepted::sourceHash).orElse("none"));
        });return lines;
    }
    static List<String> reloadLines(BehaviorCatalog.Reload result){
        var snapshot=result.snapshot();List<String> rows=new ArrayList<>();rows.add("Behaviors reload: "+(result.published()?"APPLIED":"REJECTED")+" | generation="+snapshot.generation()+" | documents="+snapshot.entries().size()+" | available="+available(snapshot)+" | invalid="+invalid(snapshot)+" | scanned="+result.scannedDocuments()+" | bytes="+result.scannedBytes());
        appendDiagnostics(rows,result.diagnostics(),snapshot);return rows;
    }
    static List<String> validationLines(BehaviorCatalog.Validation result,Optional<String> only){
        var snapshot=result.candidate();List<String> rows=new ArrayList<>();
        if(only.isPresent()&&result.scanned()){
            String id=only.get();
            List<BehaviorCatalog.Entry> matching=snapshot.entries().values().stream().filter(entry->entry.declaredId().map(id::equals).orElse(false)
                    ||entry.accepted().map(value->value.program().id().equals(id)).orElse(false)).toList();
            boolean valid=!matching.isEmpty()&&matching.stream().allMatch(entry->entry.state()==BehaviorCatalog.SourceState.VALID
                    &&entry.diagnostics().stream().noneMatch(problem->problem.severity()==Diagnostic.Severity.ERROR));
            boolean available=snapshot.find(id).map(BehaviorCatalog.Resolution::available).orElse(false);
            rows.add("Behaviors validate: "+(matching.isEmpty()?"NOT_FOUND":valid?"VALID":"INVALID")+" | behavior="+id+" | available="+available+" | sources="+matching.size()+" | published=false");
            for(BehaviorCatalog.Entry entry:matching)for(Diagnostic diagnostic:entry.diagnostics()){if(rows.size()>=17)return rows;rows.add(diagnosticLine(entry.path(),diagnostic));}
            return rows;
        }
        rows.add("Behaviors validate: "+(!result.scanned()?"UNAVAILABLE":result.valid()?"VALID":"INVALID")+" | generation="+snapshot.generation()+" | candidate-documents="+snapshot.entries().size()+" | scanned="+result.observedDocuments()+" | bytes="+result.observedBytes()+" | published=false");
        appendDiagnostics(rows,result.diagnostics(),snapshot);return rows;
    }
    private static long available(BehaviorCatalog.Snapshot snapshot){return snapshot.byId().values().stream().filter(BehaviorCatalog.Resolution::available).count();}
    private static long invalid(BehaviorCatalog.Snapshot snapshot){return snapshot.entries().values().stream().filter(entry->entry.state()!=BehaviorCatalog.SourceState.VALID).count();}
    private static void appendDiagnostics(List<String> rows,List<Diagnostic> global,BehaviorCatalog.Snapshot snapshot){
        for(Diagnostic diagnostic:global){if(rows.size()>=17)return;rows.add(diagnosticLine("behaviors",diagnostic));}
        for(var entry:snapshot.entries().values())for(Diagnostic diagnostic:entry.diagnostics()){if(rows.size()>=17)return;rows.add(diagnosticLine(entry.path(),diagnostic));}
    }
    static List<String> executionLines(BehaviorRuntime.Execution execution){
        BehaviorEngine.Report report=execution.report();List<String> rows=new ArrayList<>();
        rows.add("Behavior execution="+report.id()+" | state="+report.state()+" | npc="+execution.npcId()+" | npc-uuid="+execution.npcUuid()+" | npc-revision="+execution.npcRevision()+" | behavior="+execution.behaviorId()+" | behavior-revision="+execution.behaviorRevision()+" | source-hash="+execution.sourceHash()+execution.trigger().map(trigger->" | trigger="+trigger).orElse("")+" | steps="+report.steps()+" | reason="+report.reason());
        report.waits().stream().limit(4).forEach(wait->rows.add("Behavior wait: "+wait));
        for(Diagnostic diagnostic:report.diagnostics()){if(rows.size()>=18)break;rows.add(diagnosticLine("execution",diagnostic));}
        report.trace().stream().skip(Math.max(0,report.trace().size()-6)).limit(6).forEach(trace->rows.add("Behavior trace: tick="+trace.tick()+" | "+trace.path()+" | "+trace.event()));
        return rows;
    }
    private static List<String> outcomeLines(String prefix,Outcome<?> outcome,String value){
        List<String> rows=new ArrayList<>();rows.add(prefix+": "+outcome.status()+(value.isEmpty()?"":" | "+value));outcome.diagnostics().stream().limit(16).forEach(diagnostic->rows.add(diagnosticLine("request",diagnostic)));return rows;
    }
    private static String diagnosticLine(String source,Diagnostic diagnostic){return "Behavior diagnostic: "+source+" | "+diagnostic.code()+" | "+diagnostic.field()+" | "+diagnostic.message();}
    static String boundedLine(String text){
        String safe=text.replace('\n',' ').replace('\r',' ');if(safe.length()<=MAX_LINE_CHARS)return safe;int end=MAX_LINE_CHARS-2;if(Character.isHighSurrogate(safe.charAt(end-1)))end--;return safe.substring(0,end)+" …";
    }
    private void reply(CommandActor sender,List<String> lines){
        if(sender==null||closed.get())return;List<String> bounded=lines.stream().limit(MAX_LINES).map(BehaviorCommands::boundedLine).toList();
        if(!sender.reply(()->{if(!closed.get()&&sender.permitted("animus.admin"))bounded.forEach(sender::tell);}))unavailableReplies.incrementAndGet();
    }
    private boolean publishState(State next){synchronized(lifecycle){if(closed.get())return false;state=settings.enabled()?next:State.DISABLED;return true;}}
    /** Module triggers reach the NPC they name and every enabled server behavior listening for their key. */
    private record ModuleTriggers(TriggerDispatcher npcs,ServerTriggerDispatcher servers) implements TriggerSink {
        public boolean available(){return npcs.available()||servers.available();}
        public String availability(){return npcs.available()?npcs.availability():servers.availability();}
        public Outcome<Boolean> publish(TriggerEvent event){
            Outcome<Boolean> npc=npcs.publish(event);
            if(!(event instanceof TriggerEvent.Module module)||!servers.listens(TriggerKind.MODULE))return npc;
            Outcome<Boolean> server=servers.publish(ServerEvent.module(module.player(),module.key()));
            if(npc.value().orElse(false)||server.value().orElse(false))return Outcome.ok(true);
            return npc.status()==Outcome.Status.OK?server:npc;
        }
    }
    /** Admission ends immediately. Pending file work settles on its shared worker; coordinator drains cooperatively. */
    @Override public void close(){
        synchronized(lifecycle){if(!closed.compareAndSet(false,true))return;state=State.CLOSED;}
        AutoCloseable listening=subscription;
        if(listening!=null){try{listening.close();}catch(Exception ignored){/* The runtime's listener list is in memory; removal cannot fail. */}}
        BehaviorRuntime runtime=coordinator;if(runtime!=null)runtime.close();
    }
}
