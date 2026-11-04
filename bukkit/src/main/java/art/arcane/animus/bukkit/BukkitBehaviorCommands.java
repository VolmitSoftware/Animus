package art.arcane.animus.bukkit;

import art.arcane.animus.api.*;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntime;
import art.arcane.animus.core.config.BehaviorSettings;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.npc.NpcRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Administrative manual execution only. File work shares the engine lane; no private owner accesses Bukkit objects. */
final class BukkitBehaviorCommands implements AutoCloseable {
    enum State { DISABLED, WAITING_MODULES, LOADING, READY, FAILED, CLOSED }
    static final List<String> OPERATIONS=List.of("status","list","validate","reload","run","inspect","cancel");
    private static final int PAGE_SIZE=20,MAX_LINES=24,MAX_LINE_CHARS=2048;
    private static final BehaviorCatalog.Snapshot EMPTY_CATALOG=new BehaviorCatalog.Snapshot(0,Map.of(),Map.of(),List.of());
    private final Path dataDirectory;
    private final BehaviorSettings settings;
    private final NpcRepository repository;
    private final ExtensionRuntime extensions;
    private final Executor files;
    private final BiFunction<CommandSender,Runnable,Boolean> replies;
    private final Consumer<String> report;
    private final Object lifecycle=new Object();
    private final AtomicBoolean closed=new AtomicBoolean(),initializationSeen=new AtomicBoolean();
    private final AtomicReference<CatalogTask> fileTask=new AtomicReference<>();
    private final ManualBehaviorRuntime coordinator;
    private final AtomicLong unavailableReplies=new AtomicLong();
    private volatile BehaviorCatalog catalog;
    private volatile State state;
    private volatile String failure="none";

    BukkitBehaviorCommands(Path dataDirectory,AnimusRuntime runtime,ExtensionRuntime extensions,ReplyScheduler replies,Consumer<String> report){
        this(dataDirectory,runtime.behaviorSettings(),runtime.npcs(),extensions,runtime::fileExecutor,replies::reply,report);
    }
    // Engine-owned seams allow lifecycle/queue tests without creating a server or accessing world state.
    BukkitBehaviorCommands(Path dataDirectory,BehaviorSettings settings,NpcRepository repository,ExtensionRuntime extensions,
                          Function<MutationLane.EngineTaskObserver,Executor> fileExecutor,
                          BiFunction<CommandSender,Runnable,Boolean> replies,Consumer<String> report){
        this.dataDirectory=Objects.requireNonNull(dataDirectory);this.settings=Objects.requireNonNull(settings);
        this.repository=Objects.requireNonNull(repository);this.extensions=Objects.requireNonNull(extensions);
        this.replies=Objects.requireNonNull(replies);this.report=Objects.requireNonNull(report);
        files=Objects.requireNonNull(fileExecutor.apply(this::fileEvent));state=settings.enabled()?State.WAITING_MODULES:State.DISABLED;
        // Own and resolve lifecycle classes while the plugin archive is open. Startup catalog I/O
        // remains asynchronous; the empty source snapshot cannot admit executions before READY.
        coordinator=settings.enabled()?new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(settings.maximumOperations(),settings.operationsPerTick(),settings.tickMillis(),settings.deliveryBudgetMillis()),
                settings.engine(),repository::snapshot,this::catalogSnapshot,extensions.behaviorInvoker()):null;
    }
    /** The one initialization observer only admits bounded file work; it never scans on module management. */
    void modulesInitialized(ExtensionRuntime.State terminal){
        if(!Set.of(ExtensionRuntime.State.READY,ExtensionRuntime.State.DISABLED,ExtensionRuntime.State.FAILED).contains(terminal))throw new IllegalArgumentException("Expected terminal module initialization state");
        if(!initializationSeen.compareAndSet(false,true)||!settings.enabled()||!publishState(State.LOADING))return;
        submitCatalog(null,false,true);
    }
    boolean execute(CommandSender sender,String[] args){
        if(!sender.hasPermission("animus.admin")){if(!closed.get())replies.apply(sender,()->{if(!closed.get())sender.sendMessage("You do not have permission to use this command.");});return true;}
        try{
            String operation=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
            switch(operation){
                case "status"->{arity(args,1,2,"behaviors status");reply(sender,statusLines());}
                case "list"->{arity(args,2,3,"behaviors list [page]");int page=args.length==3?Integer.parseInt(args[2]):1;reply(sender,listLines(page));}
                case "validate","reload"->{arity(args,2,2,"behaviors "+operation);if(!catalogAdmission(sender,operation))return true;submitCatalog(sender,operation.equals("validate"),false);}
                case "run"->{
                    arity(args,4,4,"behaviors run <npc-id> <behavior-id>");ManualBehaviorRuntime runtime=ready(sender,"run");if(runtime==null)return true;
                    String npc=args[2],behavior=args[3];Optional<UUID> player=sender instanceof Player viewer?Optional.of(viewer.getUniqueId()):Optional.empty();
                    runtime.run(npc,behavior,player).whenComplete((outcome,error)->{
                        if(error!=null)reply(sender,List.of("Behavior run: FAILED | result-delivery"));
                        else reply(sender,outcomeLines("Behavior run",outcome,outcome.value().map(id->"execution="+id+" | npc="+npc+" | behavior="+behavior).orElse("")));
                    });
                }
                case "inspect"->{arity(args,3,3,"behaviors inspect <execution-uuid>");ManualBehaviorRuntime runtime=coordinator;UUID id=UUID.fromString(args[2]);reply(sender,runtime==null?List.of("Behavior inspect: UNAVAILABLE | state="+state):runtime.inspect(id).map(BukkitBehaviorCommands::executionLines).orElse(List.of("Behavior inspect: NOT_FOUND | execution="+id)));}
                case "cancel"->{
                    arity(args,3,3,"behaviors cancel <execution-uuid>");ManualBehaviorRuntime runtime=ready(sender,"cancel");if(runtime==null)return true;UUID id=UUID.fromString(args[2]);
                    runtime.cancel(id).whenComplete((outcome,error)->{
                        if(error!=null)reply(sender,List.of("Behavior cancel: FAILED | result-delivery"));
                        else reply(sender,outcomeLines("Behavior cancel",outcome,"execution="+id+outcome.value().map(value->" | cancelled="+value).orElse("")));
                    });
                }
                default->throw new IllegalArgumentException("behaviors status|list [page]|validate|reload|run <npc-id> <behavior-id>|inspect <execution-uuid>|cancel <execution-uuid>");
            }
        }catch(RuntimeException invalid){reply(sender,List.of("Animus behaviors: "+Objects.toString(invalid.getMessage(),invalid.getClass().getSimpleName())));}
        return true;
    }
    List<String> complete(String[] args){
        if(closed.get())return List.of();
        if(args.length==2)return matching(OPERATIONS,args[1].toLowerCase(Locale.ROOT));
        if(args.length==3&&args[1].equalsIgnoreCase("run"))return matching(repository.snapshot().keySet(),args[2]);
        BehaviorCatalog current=catalog;
        if(args.length==4&&args[1].equalsIgnoreCase("run")&&current!=null)return matching(current.snapshot().byId().keySet(),args[3]);
        ManualBehaviorRuntime runtime=coordinator;
        if(args.length==3&&List.of("inspect","cancel").contains(args[1].toLowerCase(Locale.ROOT))&&runtime!=null)return matching(runtime.snapshot().executions().keySet().stream().map(UUID::toString).toList(),args[2]);
        return List.of();
    }
    private static List<String> matching(Collection<String> candidates,String prefix){return candidates.stream().filter(value->value.startsWith(prefix)).sorted().limit(100).toList();}
    private static void arity(String[] args,int minimum,int maximum,String usage){if(args.length<minimum||args.length>maximum)throw new IllegalArgumentException(usage);}
    private boolean catalogAdmission(CommandSender sender,String operation){
        if(closed.get()||!settings.enabled()||!initializationSeen.get()){
            reply(sender,List.of("Behaviors "+operation+": UNAVAILABLE | state="+state));return false;
        }
        return true;
    }
    private ManualBehaviorRuntime ready(CommandSender sender,String operation){
        ManualBehaviorRuntime runtime=coordinator;
        if(closed.get()||state!=State.READY||runtime==null){reply(sender,List.of("Behavior "+operation+": UNAVAILABLE | state="+state));return null;}
        return runtime;
    }
    private void submitCatalog(CommandSender sender,boolean validate,boolean initial){
        BehaviorCatalog current=catalog;CatalogTask task=new CatalogTask(sender,validate,initial,current==null?0:current.snapshot().generation());
        if(!fileTask.compareAndSet(null,task)){if(sender!=null)reply(sender,List.of("Behaviors "+(validate?"validate":"reload")+": BUSY | catalog-work=pending"));return;}
        if(closed.get()){fileTask.compareAndSet(task,null);return;}
        try{files.execute(task);}
        catch(RejectedExecutionException rejected){fileTask.compareAndSet(task,null);task.failed("file-queue-unavailable");}
    }
    private void fileEvent(Runnable task,MutationLane.EngineTaskEvent event,Throwable error){
        if(task instanceof CatalogTask owned&&fileTask.compareAndSet(owned,null))owned.failed(event==MutationLane.EngineTaskEvent.CANCELLED?"file-task-cancelled":"file-task-failed");
    }
    private BehaviorCatalog.Snapshot catalogSnapshot(){BehaviorCatalog current=catalog;return current==null?EMPTY_CATALOG:current.snapshot();}
    private void failedState(){if(catalogSnapshot().generation()==0)publishState(State.FAILED);}
    private final class CatalogTask implements Runnable {
        final CommandSender sender;final boolean validate,initial;final long generation;
        CatalogTask(CommandSender sender,boolean validate,boolean initial,long generation){this.sender=sender;this.validate=validate;this.initial=initial;this.generation=generation;}
        public void run(){
            try{
                if(closed.get())return;
                BehaviorCatalog current=catalog;
                if(current==null){current=new BehaviorCatalog(dataDirectory,settings.catalog(),requirement->new BehaviorCatalog.Capability(extensions.capabilityAvailable(requirement.key(),requirement.kind()),"module capability not ready or unavailable"));catalog=current;}
                List<String> messages;
                if(validate){
                    var checked=current.validate(generation);messages=validationLines(checked);
                }else{
                    var changed=current.reload(generation,Set.of());messages=reloadLines(changed);
                    if(changed.published()){
                        if(closed.get())return;
                        failure="none";publishState(State.READY);
                    }else{
                        failure=changed.diagnostics().isEmpty()?"catalog-reload-rejected":changed.diagnostics().getFirst().code();
                        failedState();
                    }
                }
                if(closed.get())return;
                if(initial)report.accept(messages.getFirst());else reply(sender,messages);
            }catch(IOException | RuntimeException | LinkageError | AssertionError error){failed("catalog-operation: "+error.getClass().getSimpleName());}
            finally{fileTask.compareAndSet(this,null);}
        }
        void failed(String reason){
            if(closed.get())return;failure=reason;failedState();
            String message="Behaviors "+(validate?"validate":"reload")+": FAILED | "+reason;
            if(initial)report.accept(message);else if(sender!=null)reply(sender,List.of(message));
        }
    }
    List<String> statusLines(){
        BehaviorCatalog current=catalog;BehaviorCatalog.Snapshot snapshot=current==null?null:current.snapshot();ManualBehaviorRuntime runtime=coordinator;
        List<String> result=new ArrayList<>();result.add("Behaviors "+state+" | catalog-generation="+(snapshot==null?0:snapshot.generation())+" | documents="+(snapshot==null?0:snapshot.entries().size())+" | available="+(snapshot==null?0:available(snapshot))+" | catalog-work="+(fileTask.get()==null?"idle":"pending")+" | unavailable-replies="+unavailableReplies.get()+" | failure="+failure);
        if(runtime!=null){var status=runtime.status();result.add("Behavior runtime: "+status.state()+" | admitted="+status.admittedOperations()+" | queued="+status.queuedOperations()+" | active="+status.engine().activeExecutions()+" | retained-calls="+status.engine().retiredCalls()+" | tick="+status.engine().tick()+" | delivery-queued="+status.queuedDeliveries()+" | delivery-active="+status.activeDeliveries()+" | delivery-over-budget="+status.overBudgetDeliveries()+" | failure="+status.failure());}
        return List.copyOf(result);
    }
    /** Engine diagnostics only. Enabled services own this from construction; null only when disabled. */
    ManualBehaviorRuntime diagnosticRuntime(){return coordinator;}
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
    static List<String> validationLines(BehaviorCatalog.Validation result){
        var snapshot=result.candidate();List<String> rows=new ArrayList<>();rows.add("Behaviors validate: "+(!result.scanned()?"UNAVAILABLE":result.valid()?"VALID":"INVALID")+" | generation="+snapshot.generation()+" | candidate-documents="+snapshot.entries().size()+" | scanned="+result.observedDocuments()+" | bytes="+result.observedBytes()+" | published=false");
        appendDiagnostics(rows,result.diagnostics(),snapshot);return rows;
    }
    private static long available(BehaviorCatalog.Snapshot snapshot){return snapshot.byId().values().stream().filter(BehaviorCatalog.Resolution::available).count();}
    private static long invalid(BehaviorCatalog.Snapshot snapshot){return snapshot.entries().values().stream().filter(entry->entry.state()!=BehaviorCatalog.SourceState.VALID).count();}
    private static void appendDiagnostics(List<String> rows,List<Diagnostic> global,BehaviorCatalog.Snapshot snapshot){
        for(Diagnostic diagnostic:global){if(rows.size()>=17)return;rows.add(diagnosticLine("behaviors",diagnostic));}
        for(var entry:snapshot.entries().values())for(Diagnostic diagnostic:entry.diagnostics()){if(rows.size()>=17)return;rows.add(diagnosticLine(entry.path(),diagnostic));}
    }
    static List<String> executionLines(ManualBehaviorRuntime.Execution execution){
        BehaviorEngine.Report report=execution.report();List<String> rows=new ArrayList<>();
        rows.add("Behavior execution="+report.id()+" | state="+report.state()+" | npc="+execution.npcId()+" | npc-uuid="+execution.npcUuid()+" | npc-revision="+execution.npcRevision()+" | behavior="+execution.behaviorId()+" | behavior-revision="+execution.behaviorRevision()+" | source-hash="+execution.sourceHash()+" | steps="+report.steps()+" | reason="+report.reason());
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
    private void reply(CommandSender sender,List<String> lines){
        if(sender==null||closed.get())return;List<String> bounded=lines.stream().limit(MAX_LINES).map(BukkitBehaviorCommands::boundedLine).toList();
        if(!replies.apply(sender,()->{if(!closed.get()&&sender.hasPermission("animus.admin"))bounded.forEach(sender::sendMessage);}))unavailableReplies.incrementAndGet();
    }
    private boolean publishState(State next){synchronized(lifecycle){if(closed.get())return false;state=next;return true;}}
    /** Admission ends immediately. Pending file work settles on its shared worker; coordinator drains cooperatively. */
    @Override public void close(){
        synchronized(lifecycle){if(!closed.compareAndSet(false,true))return;state=State.CLOSED;}
        ManualBehaviorRuntime runtime=coordinator;if(runtime!=null)runtime.close();
    }
}
