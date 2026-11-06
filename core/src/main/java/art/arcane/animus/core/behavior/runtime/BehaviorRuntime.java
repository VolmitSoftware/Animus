package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.npc.NpcDefinition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Manual and trigger-started module/data execution. A private owner advances the interpreter; immutable source
 * suppliers must be quick, thread-safe observations with no file, platform, or world access.
 * Existing dormant definitions are allowed. Player identity grants no world or preview authority.
 */
public final class BehaviorRuntime implements AutoCloseable {
    public record Limits(int maximumOperations,int operationsPerTick,int tickMillis,int deliveryBudgetMillis) {
        public Limits {
            if(maximumOperations<1||maximumOperations>256||operationsPerTick<1||operationsPerTick>64
                    ||operationsPerTick>maximumOperations||tickMillis<50||tickMillis>1000
                    ||deliveryBudgetMillis<1||deliveryBudgetMillis>60000)
                throw new IllegalArgumentException("Invalid manual behavior coordinator limits");
        }
        public static Limits defaults(){return new Limits(64,8,50,250);}
    }
    public enum State { STARTING,RUNNING,STOPPING,STOPPED }
    public record Status(State state,int admittedOperations,int queuedOperations,int queuedDeliveries,
                         int activeDeliveries,int overBudgetDeliveries,long completedOverBudgetDeliveries,
                         BehaviorEngine.Status engine,long viewPublications,String failure) {}
    public record Execution(String npcId,UUID npcUuid,long npcRevision,String behaviorId,long behaviorRevision,
                            String sourceHash,Optional<UUID> player,Optional<String> trigger,BehaviorEngine.Report report) {}
    public record Snapshot(Status status,Map<UUID,Execution> executions) {
        public Snapshot {executions=Map.copyOf(executions);}
    }
    /** What one NPC is doing now, manual and trigger-started, and its newest finished runs, newest first. */
    public record NpcTrace(UUID npc,List<Execution> active,List<Execution> recent) {
        public NpcTrace {Objects.requireNonNull(npc);active=List.copyOf(active);recent=List.copyOf(recent);}
    }
    public static final int RECENT_PER_NPC=4;
    private static final long OBSERVATION_NANOS=TimeUnit.SECONDS.toNanos(2);
    private enum Phase { QUEUED,RUNNING,DELIVERY_QUEUED,DELIVERING }
    private record Capture(String npcId,NpcDefinition definition,UUID npcUuid,long revision,
                           String behaviorId,BehaviorCatalog.Accepted accepted,Optional<UUID> player,
                           Optional<TriggerDispatcher.Request> trigger,Optional<ServerTriggerDispatcher.Request> server) {
        Capture(String npcId,NpcDefinition definition,UUID npcUuid,long revision,String behaviorId,BehaviorCatalog.Accepted accepted,Optional<UUID> player,Optional<TriggerDispatcher.Request> trigger){
            this(npcId,definition,npcUuid,revision,behaviorId,accepted,player,trigger,Optional.empty());
        }
    }
    /** The npc id executions of server behaviors report. */
    public static final String SERVER_NPC_ID="server";
    private record Lease(UUID execution,Capture capture) {}
    private record Publication(BehaviorEngine.Status engine,Map<UUID,Execution> executions) {}
    private static final BehaviorEngine.Status EMPTY_ENGINE=new BehaviorEngine.Status(0,0,0,0,0,0,0,0,0,0,false);

    /** The hard ceiling of {@code maximumOperations}; the delivery queue is sized to it so a raised cap never overflows. */
    private static final int MAXIMUM_OPERATIONS=256;
    private volatile Limits limits; // written by the owner only
    private BehaviorEngine.Limits engineLimits; // owner only
    private final AtomicReference<Limits> pendingLimits=new AtomicReference<>();
    private final AtomicReference<BehaviorEngine.Limits> pendingEngine=new AtomicReference<>();
    private final Supplier<Map<String,NpcDefinition>> definitions;
    private final Supplier<BehaviorCatalog.Snapshot> catalog;
    private final BehaviorInvoker invoker;
    private final TriggerDispatcher triggers;
    private final ServerTriggerDispatcher servers;
    private final ConnectedFacts facts=new ConnectedFacts();
    private final Object control=new Object();
    private final ArrayDeque<Operation<?>> requests=new ArrayDeque<>();
    private final Set<Operation<?>> admitted=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ScheduledThreadPoolExecutor ownerExecutor;
    private final ThreadPoolExecutor deliveries;
    private final Map<UUID,Lease> active=new LinkedHashMap<>(); // owner only
    private final Map<UUID,Lease> manualByNpc=new HashMap<>(); // one live manual execution per NPC
    private final LinkedHashMap<UUID,Execution> history=new LinkedHashMap<>(); // owner only, bounded
    private final LinkedHashMap<UUID,ArrayDeque<Execution>> recentByNpc=new LinkedHashMap<>(16,0.75f,true); // owner only, bounded
    private final BehaviorState state;
    private volatile long observedUntil=System.nanoTime();
    private volatile long viewPublications;
    private boolean viewsChanged; // owner only
    private volatile Publication published=new Publication(EMPTY_ENGINE,Map.of());
    private volatile Thread owner;
    private volatile boolean closing,started,stopped;
    private volatile String failure="";
    private long slowDeliveries; // control lock
    private BehaviorEngine engine; // owner only

    /** A null dispatcher runs manual executions only. */
    public BehaviorRuntime(Limits limits,BehaviorEngine.Limits engineLimits,
                                 Supplier<Map<String,NpcDefinition>> definitions,
                                 Supplier<BehaviorCatalog.Snapshot> catalog,BehaviorInvoker invoker,TriggerDispatcher triggers,BehaviorState state) {
        this(limits,engineLimits,definitions,catalog,invoker,triggers,null,state,daemonFactory("animus-behavior-owner"));
    }
    /** A null server dispatcher runs no server behaviors. */
    public BehaviorRuntime(Limits limits,BehaviorEngine.Limits engineLimits,
                                 Supplier<Map<String,NpcDefinition>> definitions,
                                 Supplier<BehaviorCatalog.Snapshot> catalog,BehaviorInvoker invoker,TriggerDispatcher triggers,ServerTriggerDispatcher servers,BehaviorState state) {
        this(limits,engineLimits,definitions,catalog,invoker,triggers,servers,state,daemonFactory("animus-behavior-owner"));
    }
    // Package-private deterministic owner-start seam; production always uses its private daemon owner.
    BehaviorRuntime(Limits limits,BehaviorEngine.Limits engineLimits,
                          Supplier<Map<String,NpcDefinition>> definitions,
                          Supplier<BehaviorCatalog.Snapshot> catalog,BehaviorInvoker invoker,TriggerDispatcher triggers,BehaviorState state,ThreadFactory ownerFactory) {
        this(limits,engineLimits,definitions,catalog,invoker,triggers,null,state,ownerFactory);
    }
    BehaviorRuntime(Limits limits,BehaviorEngine.Limits engineLimits,
                          Supplier<Map<String,NpcDefinition>> definitions,
                          Supplier<BehaviorCatalog.Snapshot> catalog,BehaviorInvoker invoker,TriggerDispatcher triggers,ServerTriggerDispatcher servers,BehaviorState state,ThreadFactory ownerFactory) {
        this.limits=Objects.requireNonNull(limits);this.engineLimits=Objects.requireNonNull(engineLimits);
        this.definitions=Objects.requireNonNull(definitions);this.catalog=Objects.requireNonNull(catalog);
        this.invoker=Objects.requireNonNull(invoker);this.triggers=triggers;this.servers=servers;this.state=Objects.requireNonNull(state);
        LifecycleClasses.prepare();
        deliveries=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(MAXIMUM_OPERATIONS),
                daemonFactory("animus-behavior-result"),new ThreadPoolExecutor.AbortPolicy());
        ownerExecutor=new ScheduledThreadPoolExecutor(1,Objects.requireNonNull(ownerFactory));
        ownerExecutor.setRemoveOnCancelPolicy(true);ownerExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // The scheduler is private. Only this task schedules its one successor; close never queues a task.
        ownerExecutor.execute(this::turn);
    }
    private static ThreadFactory daemonFactory(String name){return task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;};}

    /**
     * New budgets from a hotload, adopted by the owner at the start of its next turn. A lowered operation cap admits nothing
     * new until admitted work drains under it; running executions keep the step, lifetime and depth budgets they started with.
     */
    public void limits(Limits next,BehaviorEngine.Limits engine){pendingLimits.set(Objects.requireNonNull(next));pendingEngine.set(Objects.requireNonNull(engine));}
    private void adoptLimits(){
        Limits next=pendingLimits.getAndSet(null);if(next!=null)limits=next;
        BehaviorEngine.Limits nextEngine=pendingEngine.getAndSet(null);
        if(nextEngine!=null){engineLimits=nextEngine;if(engine!=null)engine.limits(nextEngine);}
    }
    public CompletionStage<Outcome<UUID>> run(String npcId,String behaviorId){return run(npcId,behaviorId,Optional.empty());}
    public CompletionStage<Outcome<UUID>> run(String npcId,String behaviorId,Optional<UUID> player) {
        if(Thread.currentThread()==owner)return rejected(Outcome.Status.BUSY,"owner-reentrant","Queue run requests from outside the behavior owner");
        synchronized(control){if(closing)return unavailable();if(admitted.size()>=limits.maximumOperations())return busy();}
        if(npcId==null||npcId.length()>64||!npcId.matches("[a-z0-9][a-z0-9_-]{0,63}")||player==null)
            return rejected(Outcome.Status.INVALID,"run-identity","Expected an NPC identifier and optional player UUID");
        try{BehaviorPath.forId(behaviorId);}catch(IllegalArgumentException invalid){return rejected(Outcome.Status.INVALID,"behavior-identity",invalid.getMessage());}
        final Capture capture;
        try {
            NpcDefinition definition=Objects.requireNonNull(definitions.get()).get(npcId);
            if(definition==null)return rejected(Outcome.Status.NOT_FOUND,"npc-missing","NPC definition does not exist");
            BehaviorCatalog.Resolution resolution=Objects.requireNonNull(catalog.get()).byId().get(behaviorId);
            if(resolution==null)return rejected(Outcome.Status.NOT_FOUND,"behavior-missing","Behavior is not in the accepted catalog");
            if(!resolution.available()||resolution.accepted().isEmpty())return rejected(Outcome.Status.UNAVAILABLE,"behavior-unavailable","Behavior source or capabilities are unavailable");
            capture=new Capture(npcId,definition,definition.uuid(),definition.revision(),behaviorId,resolution.accepted().orElseThrow(),player,Optional.empty());
        } catch(RuntimeException | AssertionError | LinkageError invalid) {
            return rejected(Outcome.Status.FAILED,"source-observation","Immutable source observation failed: "+invalid.getClass().getSimpleName());
        }
        return submit(new RunOperation(capture));
    }
    public CompletionStage<Outcome<Boolean>> cancel(UUID execution) {
        if(execution==null)return rejected(Outcome.Status.INVALID,"execution-identity","Expected execution UUID");
        if(Thread.currentThread()==owner)return rejected(Outcome.Status.BUSY,"owner-reentrant","Queue cancellation from outside the behavior owner");
        return submit(new CancelOperation(execution));
    }
    private <T> CompletionStage<Outcome<T>> submit(Operation<T> operation) {
        synchronized(control){
            if(closing)return unavailable();if(admitted.size()>=limits.maximumOperations())return busy();
            admitted.add(operation);requests.addLast(operation);return operation.result.minimalCompletionStage();
        }
    }
    public Status status(){return status(published);}
    /** The last published views. Asking keeps views publishing for a short window; use {@link #observe} for a current answer. */
    public Snapshot snapshot(){observed();Publication view=published;return new Snapshot(status(view),view.executions());}
    /** The last published view, as {@link #snapshot} describes. */
    public Optional<Execution> inspect(UUID execution){observed();return Optional.ofNullable(published.executions().get(Objects.requireNonNull(execution)));}
    /** One execution's current waits, conditions and recent steps, answered by the owner on its next turn. */
    public CompletionStage<Outcome<Execution>> observe(UUID execution) {
        if(execution==null)return rejected(Outcome.Status.INVALID,"execution-identity","Expected execution UUID");
        if(Thread.currentThread()==owner)return rejected(Outcome.Status.BUSY,"owner-reentrant","Queue observation from outside the behavior owner");
        return submit(new ObserveOperation(execution));
    }
    /** One NPC's active run and bounded recent runs. Accepts a definition identifier, or the UUID of an NPC since deleted. */
    public CompletionStage<Outcome<NpcTrace>> observeNpc(String npc) {
        if(npc==null||npc.length()>64)return rejected(Outcome.Status.INVALID,"npc-identity","Expected an NPC identifier or UUID");
        if(Thread.currentThread()==owner)return rejected(Outcome.Status.BUSY,"owner-reentrant","Queue observation from outside the behavior owner");
        return submit(new ObserveNpcOperation(npc));
    }
    private void observed(){observedUntil=System.nanoTime()+OBSERVATION_NANOS;}
    private Status status(Publication view) {
        synchronized(control){
            int queued=0,delivering=0,slow=0;long now=System.nanoTime();
            for(Operation<?> operation:admitted){
                if(operation.phase==Phase.DELIVERY_QUEUED)queued++;
                if(operation.phase==Phase.DELIVERING){delivering++;if(now-operation.deliveryStarted>=TimeUnit.MILLISECONDS.toNanos(limits.deliveryBudgetMillis()))slow++;}
            }
            return new Status(stopped?State.STOPPED:closing?State.STOPPING:started?State.RUNNING:State.STARTING,
                    admitted.size(),requests.size(),queued,delivering,slow,slowDeliveries,view.engine(),viewPublications,failure);
        }
    }
    /** Revokes new requests immediately. Neither trusted handlers nor public result callbacks run here. */
    /**
     * Connects the platform's world facts. Integration seam: the engine construction in turn() receives {@link #facts()}
     * once the engine accepts a {@link BehaviorFacts}. A later call replaces the provider; until then runs see {@link BehaviorFacts#NONE}.
     */
    public void facts(BehaviorFacts provider){facts.connect(provider);}
    /** The facts every run of this runtime reads; it forwards to the connected provider. */
    public BehaviorFacts facts(){return facts;}
    @Override public void close(){if(triggers!=null)triggers.unavailable("behavior runtime is stopping");if(servers!=null)servers.unavailable("behavior runtime is stopping");synchronized(control){closing=true;control.notifyAll();}}
    /** Standalone owner/test convenience; platform shutdown must use close() plus immutable diagnostics. */
    public boolean awaitStopped(Duration duration)throws InterruptedException {
        Objects.requireNonNull(duration);if(duration.isNegative()||duration.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("Wait must be between zero and one minute");
        if(Thread.currentThread()==owner)throw new IllegalStateException("Behavior owner cannot wait for itself");
        long remaining=duration.toNanos(),until=System.nanoTime()+remaining;
        synchronized(control){while(!stopped&&remaining>0){TimeUnit.NANOSECONDS.timedWait(control,remaining);remaining=until-System.nanoTime();}return stopped;}
    }

    private void turn() {
        owner=Thread.currentThread();List<Operation<?>> completed=new ArrayList<>();
        try {
            adoptLimits();
            // Integration seam: hand facts() to the engine here once BehaviorEngine takes a BehaviorFacts.
            if(engine==null){engine=new BehaviorEngine(engineLimits,new GuardedInvoker(),this::terminal,state);started=true;}
            if(closing)engine.close();
            for(int count=0,budget=limits.operationsPerTick();count<budget;count++){
                Operation<?> operation;
                synchronized(control){operation=requests.pollFirst();if(operation!=null)operation.phase=Phase.RUNNING;}
                if(operation==null)break;
                operation.perform();completed.add(operation);
            }
            if(closing)engine.close();
            else{
                for(Lease lease:List.copyOf(active.values()))if(!current(lease.capture()))engine.cancel(lease.execution(),"captured-source-changed");
                pumpTriggers();
            }
            engine.tick();publish();state.maintain();
        } catch(RuntimeException | AssertionError | LinkageError fault) {
            fail(fault);
            if(engine!=null){try{engine.close();publish();}catch(RuntimeException | AssertionError | LinkageError ignored){/* retain the failure and all accounting */}}
        } finally {
            for(Operation<?> operation:completed)queueDelivery(operation);
            boolean finish;
            synchronized(control){finish=closing&&admitted.isEmpty()&&active.isEmpty()&&(engine==null||published.engine().closed()&&published.engine().callsInFlight()==0&&published.engine().terminalObserverFailures()==0);}
            if(finish){
                deliveries.shutdown();ownerExecutor.shutdown();
                synchronized(control){stopped=true;control.notifyAll();}
            } else ownerExecutor.schedule(this::turn,limits.tickMillis(),TimeUnit.MILLISECONDS);
        }
    }
    /** A dispatcher fault retires triggers and leaves manual execution running. */
    private void pumpTriggers(){
        if(triggers!=null){
            try{triggers.pump(this::startTriggered);}
            catch(RuntimeException | AssertionError | LinkageError fault){triggers.unavailable("trigger dispatcher failed: "+fault.getClass().getSimpleName());}
        }
        if(servers!=null){
            try{servers.pump(this::startServer);}
            catch(RuntimeException | AssertionError | LinkageError fault){servers.unavailable("server trigger dispatcher failed: "+fault.getClass().getSimpleName());}
        }
    }
    /** A server behavior runs with {@link ServerNpc#HANDLE} and no NPC definition. */
    private Outcome<UUID> startServer(ServerTriggerDispatcher.Request request){
        Capture capture=new Capture(SERVER_NPC_ID,null,ServerNpc.HANDLE.uuid(),0,request.binding().behavior(),request.accepted(),request.player(),Optional.empty(),Optional.of(request));
        if(!current(capture))return Outcome.rejected(Outcome.Status.CONFLICT,"captured-source-changed","Server behavior changed or was disabled after trigger admission");
        Outcome<UUID> result=engine.start(request.program(),serverContext(request,()->current(capture)));
        result.value().ifPresent(id->{active.put(id,new Lease(id,capture));viewsChanged=true;});
        return result;
    }
    /**
     * Integration seam: the one place a server event's context map ({@code request.event()}: plain Boolean, String and
     * List of String values) is handed to the run. Once {@link BehaviorEngine.Context} carries an event map, pass
     * {@code request.event()} to it here so expressions read it as {@code context.*}.
     */
    private static BehaviorEngine.Context serverContext(ServerTriggerDispatcher.Request request,java.util.function.BooleanSupplier current){
        return new BehaviorEngine.Context(ServerNpc.HANDLE,request.player(),false,current);
    }
    private Outcome<UUID> startTriggered(TriggerDispatcher.Request request){
        NpcDefinition definition=request.definition();
        Capture capture=new Capture(definition.id(),definition,definition.uuid(),definition.revision(),request.binding().behavior(),request.accepted(),request.player(),Optional.of(request));
        if(!current(capture))return Outcome.rejected(Outcome.Status.CONFLICT,"captured-source-changed","NPC or accepted behavior changed after trigger admission");
        Outcome<UUID> result=engine.start(request.program(),new BehaviorEngine.Context(new NpcHandle(capture.npcUuid()),capture.player(),false,()->current(capture)));
        result.value().ifPresent(id->{active.put(id,new Lease(id,capture));viewsChanged=true;});
        return result;
    }
    private void fail(Throwable fault){synchronized(control){if(failure.isEmpty())failure=fault.getClass().getSimpleName();closing=true;}}
    private boolean current(Capture capture) {
        if(capture.server().isPresent())return !closing&&capture.server().get().current().getAsBoolean();
        if(closing||Objects.requireNonNull(definitions.get()).get(capture.npcId())!=capture.definition())return false;
        BehaviorCatalog.Resolution resolution=Objects.requireNonNull(catalog.get()).byId().get(capture.behaviorId());
        return resolution!=null&&resolution.available()&&resolution.accepted().orElse(null)==capture.accepted();
    }
    private void terminal(BehaviorEngine.Report report) {
        Lease lease=active.remove(report.id());
        if(lease==null)throw new IllegalStateException("Terminal execution has no captured lease");
        Execution finished=view(lease,report);
        manualByNpc.remove(lease.capture().npcUuid(),lease);lease.capture().trigger().ifPresent(request->triggers.finished(request));lease.capture().server().ifPresent(request->servers.finished(request));history.put(report.id(),finished);viewsChanged=true;
        while(history.size()>engineLimits.historyEntries())history.remove(history.keySet().iterator().next());
        ArrayDeque<Execution> recent=recentByNpc.get(lease.capture().npcUuid());
        if(recent==null){recent=new ArrayDeque<>(RECENT_PER_NPC);recentByNpc.put(lease.capture().npcUuid(),recent);}
        recent.addFirst(finished);if(recent.size()>RECENT_PER_NPC)recent.removeLast();
        if(recentByNpc.size()>engineLimits.historyEntries())recentByNpc.remove(recentByNpc.keySet().iterator().next());
    }
    private void publish() {
        BehaviorEngine.Status status=engine.status();
        if(status.terminalObserverFailures()>0){
            published=new Publication(status,published.executions());
            throw new IllegalStateException("Terminal lease observation failed");
        }
        // Reports copy traces and wait lists: rebuild them only when the set of runs changed or someone recently asked.
        if(!viewsChanged&&observedUntil-System.nanoTime()<=0){published=new Publication(status,published.executions());return;}
        viewsChanged=false;
        Map<UUID,Execution> views=new LinkedHashMap<>(history);
        for(Lease lease:active.values())views.put(lease.execution(),view(lease,engine.inspect(lease.execution()).orElseThrow()));
        published=new Publication(status,Map.copyOf(views));
        viewPublications++;
    }
    private static Execution view(Lease lease,BehaviorEngine.Report report){Capture c=lease.capture();return new Execution(c.npcId(),c.npcUuid(),c.revision(),c.behaviorId(),c.accepted().revision(),c.accepted().sourceHash(),c.player(),c.trigger().map(request->request.binding().key()).or(()->c.server().map(request->request.binding().key())),report);}
    private void queueDelivery(Operation<?> operation) {
        synchronized(control){operation.phase=Phase.DELIVERY_QUEUED;}
        // Every queued/running delivery retains one global admission, so this bounded queue cannot overflow.
        try{deliveries.execute(operation::deliver);}catch(RejectedExecutionException rejected){fail(rejected);}
    }
    private abstract class Operation<T> {
        final CompletableFuture<Outcome<T>> result=new CompletableFuture<>();Phase phase=Phase.QUEUED;long deliveryStarted;
        Outcome<T> outcome;
        abstract Outcome<T> execute();
        final void perform(){
            try{outcome=closing?Outcome.rejected(Outcome.Status.CANCELLED,"runtime-closing","Behavior runtime is stopping"):execute();}
            catch(RuntimeException | AssertionError | LinkageError fault){outcome=Outcome.rejected(Outcome.Status.FAILED,"operation-failed",fault.getClass().getSimpleName());fail(fault);}
        }
        final void deliver(){
            synchronized(control){phase=Phase.DELIVERING;deliveryStarted=System.nanoTime();}
            try{result.complete(outcome);}finally{
                synchronized(control){if(System.nanoTime()-deliveryStarted>=TimeUnit.MILLISECONDS.toNanos(limits.deliveryBudgetMillis()))slowDeliveries++;admitted.remove(this);control.notifyAll();}
            }
        }
    }
    private final class RunOperation extends Operation<UUID> {
        final Capture capture;RunOperation(Capture capture){this.capture=capture;}
        Outcome<UUID> execute(){
            if(!current(capture))return Outcome.rejected(Outcome.Status.CONFLICT,"captured-source-changed","NPC or accepted behavior changed after request admission");
            if(manualByNpc.containsKey(capture.npcUuid()))return Outcome.rejected(Outcome.Status.BUSY,"npc-execution-active","Only one manual execution per NPC may be active");
            Outcome<UUID> result=engine.start(capture.accepted().program(),new BehaviorEngine.Context(new NpcHandle(capture.npcUuid()),capture.player(),false,()->current(capture)));
            result.value().ifPresent(id->{Lease lease=new Lease(id,capture);active.put(id,lease);manualByNpc.put(capture.npcUuid(),lease);viewsChanged=true;});return result;
        }
    }
    private final class CancelOperation extends Operation<Boolean> {
        final UUID id;CancelOperation(UUID id){this.id=id;}
        Outcome<Boolean> execute(){return Outcome.ok(engine.cancel(id,"administrator-cancelled"));}
    }
    private final class ObserveOperation extends Operation<Execution> {
        final UUID id;ObserveOperation(UUID id){this.id=id;}
        Outcome<Execution> execute(){
            Lease lease=active.get(id);
            if(lease!=null)return Outcome.ok(view(lease,engine.inspect(id).orElseThrow()));
            Execution finished=history.get(id);
            return finished==null?Outcome.rejected(Outcome.Status.NOT_FOUND,"execution-missing","No active or retained execution has this UUID"):Outcome.ok(finished);
        }
    }
    private final class ObserveNpcOperation extends Operation<NpcTrace> {
        final String npc;ObserveNpcOperation(String npc){this.npc=npc;}
        Outcome<NpcTrace> execute(){
            NpcDefinition definition=Objects.requireNonNull(definitions.get()).get(npc);
            UUID identity=definition!=null?definition.uuid():uuid(npc);
            if(identity==null)return Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","No NPC definition has this identifier");
            List<Execution> current=new ArrayList<>();
            for(Lease lease:active.values())if(lease.capture().npcUuid().equals(identity))current.add(view(lease,engine.inspect(lease.execution()).orElseThrow()));
            ArrayDeque<Execution> recent=recentByNpc.get(identity);
            return Outcome.ok(new NpcTrace(identity,current,recent==null?List.of():List.copyOf(recent)));
        }
    }
    private static UUID uuid(String text){try{return UUID.fromString(text);}catch(IllegalArgumentException notUuid){return null;}}
    private final class GuardedInvoker implements BehaviorInvoker {
        public boolean available(Capabilities.Key key,Capabilities.Kind kind){return !closing&&invoker.available(key,kind);}
        @Override public boolean available(Capabilities.Key key,Capabilities.Kind kind,String behavior){return !closing&&invoker.available(key,kind,behavior);}
        // The engine carries each execution's own source guard. The host worker may evaluate it after
        // queueing/schema preparation: it reads only immutable publications and the volatile closing flag.
        private Invocation guarded(Invocation invocation){
            if(!invocation.sourceCurrent().getAsBoolean())return null;
            return new Invocation(invocation.capability(),invocation.npc(),invocation.player(),invocation.argumentsJson(),invocation.variables(),invocation.preview(),invocation.sourceCurrent(),invocation.behavior());
        }
        public Call<String> action(Invocation invocation){Invocation guarded=guarded(invocation);return guarded==null?revoked():invoker.action(guarded);}
        public Call<Boolean> condition(Invocation invocation){Invocation guarded=guarded(invocation);return guarded==null?revoked():invoker.condition(guarded);}
        private <T> Call<T> revoked(){return new Call<>(CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"source-revoked","Captured NPC or behavior authority is no longer current")),()->{},()->true);}
    }
    private static <T> CompletionStage<Outcome<T>> rejected(Outcome.Status status,String code,String message){return CompletableFuture.completedFuture(Outcome.<T>rejected(status,code,message)).minimalCompletionStage();}
    private static <T> CompletionStage<Outcome<T>> unavailable(){return rejected(Outcome.Status.UNAVAILABLE,"runtime-closing","Behavior runtime is stopping");}
    private static <T> CompletionStage<Outcome<T>> busy(){return rejected(Outcome.Status.BUSY,"operation-capacity","Behavior operation capacity reached");}
}
