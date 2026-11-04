package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.schema.CapabilitySchema;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Bounded trusted-module lifecycle, capability registration and invocation. */
public final class ModuleHost implements AutoCloseable {
    public enum State { STARTING, ACTIVE, UNAVAILABLE, FAILED, SUSPENDED, STOPPED }
    public record ModuleStatus(ModuleManifest manifest,State state,String reason) {}
    public record InvocationStatus(int admitted,int handlersOutstanding,int timedOutHandlers,int unobservedHandlers,
                                   int queuedDeliveries,int activeDeliveries,int overBudgetDeliveries,
                                   long completedSlowDeliveries,boolean closed) {}
    /** Engine-private per-call authority. A delivered result is distinct from actual handler/delivery quiescence. */
    public static final class InvocationHandle<T> {
        private final CompletionStage<Outcome<T>> result;
        private final BooleanSupplier cancellation,quiescence;
        private InvocationHandle(CompletionStage<Outcome<T>> result,BooleanSupplier cancellation,BooleanSupplier quiescence){this.result=result;this.cancellation=cancellation;this.quiescence=quiescence;}
        public CompletionStage<Outcome<T>> result(){return result;}
        /** Returns true only when this request won cancellation before another terminal result was chosen. */
        public boolean cancel(){return cancellation.getAsBoolean();}
        /** Nonblocking engine observation; never interprets timeout/result delivery as Java preemption. */
        public boolean isQuiescent(){return quiescence.getAsBoolean();}
    }
    private record Registration(Entry owner,Descriptor descriptor,Action action,Condition condition,CapabilitySchema schema) {}
    private final ReentrantLock lifecycle=new ReentrantLock();
    private final Platform platform;
    private final DefinitionGateway definitions;
    private final int maximumInvocations;
    private final long deadlineNanos,deliveryBudgetNanos;
    private final Map<String,Entry> modules=new LinkedHashMap<>();
    private final Map<Key,Registration> capabilities=new HashMap<>();
    private final List<Entry> enabled=new ArrayList<>();
    private final Set<Call<?>> calls=new HashSet<>();
    private final ThreadPoolExecutor workers,deliveries;
    private final ScheduledThreadPoolExecutor deadlines;
    private boolean closed,admissionClosed;
    private long slowDeliveries;

    public ModuleHost(Platform platform,DefinitionGateway definitions,int maximumInvocations,Duration deadline) {
        this(platform,definitions,maximumInvocations,deadline,Duration.ofMillis(250));
    }
    public ModuleHost(Platform platform,DefinitionGateway definitions,int maximumInvocations,Duration deadline,Duration deliveryBudget) {
        this.platform=Objects.requireNonNull(platform);this.definitions=Objects.requireNonNull(definitions);
        if(maximumInvocations<1 || maximumInvocations>256 || deadline==null || deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofMinutes(1))>0
                || deliveryBudget==null || deliveryBudget.isNegative() || deliveryBudget.isZero() || deliveryBudget.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("Invalid invocation limits");
        this.maximumInvocations=maximumInvocations;deadlineNanos=deadline.toNanos();deliveryBudgetNanos=deliveryBudget.toNanos();
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(maximumInvocations),job->{Thread t=new Thread(job,"Animus-module");t.setDaemon(true);return t;});
        deliveries=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(maximumInvocations),job->{Thread t=new Thread(job,"Animus-module-delivery");t.setDaemon(true);return t;});
        deadlines=new ScheduledThreadPoolExecutor(1,job->{Thread t=new Thread(job,"Animus-module-deadline");t.setDaemon(true);return t;});deadlines.setRemoveOnCancelPolicy(true);
    }
    /** Lifecycle is serialized separately from the registry; callbacks never hold its monitor. */
    public Outcome<ModuleStatus> install(TrustedModule module) {return install(module,null);}
    /** Engine-owned capabilities use the same registry and schema path under the reserved namespace. Never for discovered archives. */
    public Outcome<ModuleStatus> installBuiltin(TrustedModule module,BuiltinPolicy policy) {return install(module,Objects.requireNonNull(policy));}
    private Outcome<ModuleStatus> install(TrustedModule module,BuiltinPolicy policy) {
        synchronized(this){if(admissionClosed)return rejected(Outcome.Status.UNAVAILABLE,"host-admission-closed");}
        lifecycle.lock();
        try {
            synchronized(this){if(admissionClosed)return rejected(Outcome.Status.UNAVAILABLE,"host-admission-closed");}
            ModuleManifest manifest;
            try{manifest=Objects.requireNonNull(module.manifest());}
            catch(RuntimeException | LinkageError | AssertionError error){return rejected(Outcome.Status.FAILED,"manifest-failed");}
            Entry entry;
            synchronized(this){
                if(admissionClosed)return rejected(Outcome.Status.UNAVAILABLE,"host-admission-closed");
                if(policy==null && modules.values().stream().filter(existing->existing.policy==null).count()>=64)return rejected(Outcome.Status.BUSY,"module-capacity");
                if(manifest.id().equals("animus")&&policy==null || modules.containsKey(manifest.id()))return rejected(Outcome.Status.INVALID,"module-identity-reserved-or-duplicate");
                entry=new Entry(module,manifest,policy);modules.put(manifest.id(),entry);
                if(!manifest.api().accepts(AnimusApi.API_VERSION))return unavailable(entry,"api-version-incompatible");
                if(!manifest.platforms().contains(platform))return unavailable(entry,"platform-unavailable");
                for(ModuleManifest.Dependency dependency:manifest.dependencies()){
                    Entry required=modules.get(dependency.id());
                    if(required==null || !available(required) || !dependency.accepts(required.manifest.version()))return unavailable(entry,"dependency-unavailable: "+dependency.id());
                }
            }
            try {
                module.enable(entry);
                Map<Key,Registration> committed=entry.seal();
                synchronized(this){
                    if(admissionClosed)throw new IllegalStateException("Host admission closed while enabling module");
                    if(capabilities.size()+committed.size()>1024)throw new IllegalArgumentException("Capability capacity reached");
                    for(Key key:committed.keySet())if(capabilities.containsKey(key))throw new IllegalArgumentException("Capability collision");
                    capabilities.putAll(committed);entry.state=State.ACTIVE;entry.reason="active";enabled.add(entry);
                }
                return Outcome.ok(status(entry));
            } catch(Exception | LinkageError | AssertionError failure) {
                entry.seal();entry.state=State.FAILED;entry.reason="enable-failed: "+failure.getClass().getSimpleName();cleanup(entry);
                if(failure instanceof CapabilitySchema.SchemaException invalid)return new Outcome<>(Outcome.Status.FAILED,Optional.empty(),invalid.diagnostics());
                return rejected(Outcome.Status.FAILED,entry.reason);
            }
        } finally {lifecycle.unlock();}
    }
    /** Trusted Java modules only; built-in capabilities appear in the capability list instead. */
    public synchronized List<ModuleStatus> modules(){return modules.values().stream().filter(entry->entry.policy==null).map(this::status).toList();}
    /** Documentation/editor metadata and invocation availability use this same committed registry. */
    public synchronized List<Availability> capabilities(){
        return capabilities.values().stream().sorted(Comparator.comparing(r->r.descriptor.key().toString()))
                .map(r->{Optional<String> reason=unavailableReason(r);return new Availability(r.descriptor,reason.isEmpty(),reason.orElse("available"));}).toList();
    }
    /** Engine-private single-key admission hint; actual invocation rechecks authority. No catalog copy/sort. */
    public synchronized boolean capabilityAvailable(Key key,Kind kind,boolean preview,boolean privilegedGranted){
        Registration registration=capabilities.get(Objects.requireNonNull(key));
        if(registration==null || registration.descriptor.kind()!=Objects.requireNonNull(kind) || kind==Kind.TRIGGER)return false;
        Descriptor descriptor=registration.descriptor;
        if(descriptor.privileged() && (kind==Kind.CONDITION || preview || !privilegedGranted))return false;
        return unavailableReason(registration).isEmpty();
    }
    /** Why this registered capability cannot run on this platform now. A privileged grant is checked separately. */
    public synchronized Optional<String> unavailableReason(Key key,Kind kind){
        Registration registration=capabilities.get(Objects.requireNonNull(key));
        if(registration==null || registration.descriptor.kind()!=Objects.requireNonNull(kind))return Optional.of("capability-not-registered");
        return unavailableReason(registration);
    }
    public synchronized boolean privileged(Key key){
        Registration registration=capabilities.get(Objects.requireNonNull(key));
        return registration!=null && registration.descriptor.privileged();
    }
    /** Schema and built-in checks without entering a handler. An unregistered capability has nothing to check. */
    public List<Diagnostic> validateArguments(Key key,Kind kind,String argumentsJson){
        Registration registration;
        synchronized(this){registration=capabilities.get(Objects.requireNonNull(key));}
        if(registration==null || registration.descriptor.kind()!=Objects.requireNonNull(kind))return List.of();
        return argumentProblems(registration,argumentsJson);
    }
    private List<Diagnostic> argumentProblems(Registration registration,String argumentsJson){
        List<Diagnostic> errors=registration.schema.validate(argumentsJson);
        if(!errors.isEmpty() || registration.owner.policy==null)return errors;
        return registration.owner.policy.validate(registration.descriptor.key(),argumentsJson);
    }
    private Optional<String> unavailableReason(Registration registration){
        Descriptor descriptor=registration.descriptor;
        if(descriptor.kind()==Kind.TRIGGER)return Optional.of("trigger-dispatch-unwired");
        if(!descriptor.platforms().contains(platform))return Optional.of("platform-unavailable");
        if(!available(registration.owner))return Optional.of(reason(registration.owner));
        return builtinRefusal(registration);
    }
    private static Optional<String> builtinRefusal(Registration registration){
        if(registration.owner.policy==null)return Optional.empty();
        return registration.owner.policy.unavailable(registration.descriptor.key()).map(reason->"platform-unavailable: "+reason);
    }
    public synchronized int outstandingInvocations(){return calls.size();}
    /** Slow public continuations are observed, never preempted or mistaken for handler termination. */
    public synchronized InvocationStatus invocationStatus(){
        int handlers=0,timedOut=0,unobserved=0,queued=0,active=0,slow=0;long now=System.nanoTime();
        for(Call<?> call:calls){
            if(!call.finished.get()){handlers++;if(call.terminal.get()==1)timedOut++;if(call.terminal.get()==4)unobserved++;}
            if(call.deliveryPhase==1)queued++;
            else if(call.deliveryPhase==2){active++;if(now-call.deliveryStartedNanos>=deliveryBudgetNanos)slow++;}
        }
        return new InvocationStatus(calls.size(),handlers,timedOut,unobserved,queued,active,slow,slowDeliveries,admissionClosed);
    }
    public CompletionStage<Outcome<String>> action(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,boolean privilegedGranted){
        return actionHandle(key,npc,player,argumentsJson,preview,privilegedGranted).result();
    }
    public InvocationHandle<String> actionHandle(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,boolean privilegedGranted){
        return actionHandle(key,npc,player,argumentsJson,preview,privilegedGranted,()->true);
    }
    /** Source observation is engine-owned, quick and thread-safe; never a module callback or world read. */
    public InvocationHandle<String> actionHandle(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,boolean privilegedGranted,BooleanSupplier sourceCurrent){
        return actionHandle(key,npc,player,argumentsJson,preview,privilegedGranted,sourceCurrent,Optional.empty());
    }
    /** A behavior call carries its accepted program identity into the handler. */
    public InvocationHandle<String> actionHandle(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,boolean privilegedGranted,BooleanSupplier sourceCurrent,Optional<String> behavior){
        Objects.requireNonNull(sourceCurrent);Objects.requireNonNull(behavior);
        Registration registration;
        synchronized(this){registration=capabilities.get(key);}
        if(registration==null || registration.descriptor.kind()!=Kind.ACTION)return completedHandle(Outcome.Status.UNAVAILABLE,"action-unavailable");
        if(registration.descriptor.privileged() && (preview || !privilegedGranted))return completedHandle(Outcome.Status.UNAVAILABLE,"privileged-capability-not-granted");
        Optional<String> refused=builtinRefusal(registration);
        if(refused.isPresent())return completedHandle(Outcome.Status.UNAVAILABLE,refused.get());
        return invokeHandle(registration.owner,registration.descriptor,token->registration.action.run(invocation(registration,npc,player,argumentsJson,preview,token,behavior)),true,sourceCurrent);
    }
    public CompletionStage<Outcome<Boolean>> condition(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview){
        return conditionHandle(key,npc,player,argumentsJson,preview).result();
    }
    public InvocationHandle<Boolean> conditionHandle(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview){
        return conditionHandle(key,npc,player,argumentsJson,preview,()->true);
    }
    /** Same source fence and cooperative cancellation contract as actionHandle. */
    public InvocationHandle<Boolean> conditionHandle(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,BooleanSupplier sourceCurrent){
        return conditionHandle(key,npc,player,argumentsJson,preview,sourceCurrent,Optional.empty());
    }
    public InvocationHandle<Boolean> conditionHandle(Key key,NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,BooleanSupplier sourceCurrent,Optional<String> behavior){
        Objects.requireNonNull(sourceCurrent);Objects.requireNonNull(behavior);
        Registration registration;
        synchronized(this){registration=capabilities.get(key);}
        if(registration==null || registration.descriptor.kind()!=Kind.CONDITION)return completedHandle(Outcome.Status.UNAVAILABLE,"condition-unavailable");
        if(registration.descriptor.privileged())return completedHandle(Outcome.Status.UNAVAILABLE,"privileged-condition-not-granted");
        Optional<String> refused=builtinRefusal(registration);
        if(refused.isPresent())return completedHandle(Outcome.Status.UNAVAILABLE,refused.get());
        return invokeHandle(registration.owner,registration.descriptor,token->registration.condition.test(invocation(registration,npc,player,argumentsJson,preview,token,behavior)),false,sourceCurrent);
    }
    private Invocation invocation(Registration registration,NpcHandle npc,Optional<UUID> player,String json,boolean preview,Cancellation token,Optional<String> behavior){
        List<Diagnostic> errors=argumentProblems(registration,json);
        if(!errors.isEmpty())throw new InvalidArguments(errors);
        // Validation is bounded preparation, not authority to enter trusted code. Recheck at the
        // callback admission boundary; a callback already admitted remains cooperative work.
        if(token.cancelled() || !available(registration.owner))throw new CancellationException("Module admission revoked before callback");
        return new Invocation(npc,player,json,preview,token,behavior);
    }
    private static final class InvalidArguments extends IllegalArgumentException {
        final List<Diagnostic> diagnostics;
        InvalidArguments(List<Diagnostic> diagnostics){super("Arguments violate the registered capability schema");this.diagnostics=List.copyOf(diagnostics);}
    }
    private static final class InvalidPayload extends IllegalArgumentException {InvalidPayload(){super("Expected a bounded JSON object");}}
    private static void jsonObject(String text){
        try{if(text==null || text.length()>65536 || !StandardCharsets.UTF_8.newEncoder().canEncode(text) || text.getBytes(StandardCharsets.UTF_8).length>65536)throw new InvalidPayload();JsonDocuments.object(text);}
        catch(java.io.IOException failure){throw new InvalidPayload();}
    }
    @FunctionalInterface private interface Operation<T>{CompletionStage<T> run(Cancellation token)throws Exception;}
    private <T> CompletionStage<Outcome<T>> invoke(Entry entry,Descriptor descriptor,Operation<T> operation,boolean jsonResult){
        return invokeHandle(entry,descriptor,operation,jsonResult).result();
    }
    private <T> InvocationHandle<T> invokeHandle(Entry entry,Descriptor descriptor,Operation<T> operation,boolean jsonResult){
        return invokeHandle(entry,descriptor,operation,jsonResult,()->true);
    }
    private <T> InvocationHandle<T> invokeHandle(Entry entry,Descriptor descriptor,Operation<T> operation,boolean jsonResult,BooleanSupplier sourceCurrent){
        Call<T> call;
        synchronized(this){
            if(!available(entry) || descriptor!=null && !descriptor.platforms().contains(platform))return completedHandle(Outcome.Status.UNAVAILABLE,reason(entry));
            if(calls.size()>=maximumInvocations)return completedHandle(Outcome.Status.BUSY,"invocation-capacity");
            call=new Call<>(entry,operation,jsonResult,sourceCurrent);calls.add(call);
            call.deadline=deadlines.schedule(call::timeout,deadlineNanos,TimeUnit.NANOSECONDS);
            workers.execute(call);
        }
        return new InvocationHandle<>(call.result.minimalCompletionStage(),()->call.cancel("invocation-cancelled"),call::quiescent);
    }
    private synchronized boolean available(Entry entry){
        if(admissionClosed || entry.state!=State.ACTIVE)return false;
        return dependenciesAvailable(entry,new HashSet<>());
    }
    private boolean dependenciesAvailable(Entry entry,Set<Entry> visited){
        if(!visited.add(entry))return true;
        for(ModuleManifest.Dependency dep:entry.manifest.dependencies()){
            Entry required=modules.get(dep.id());
            if(required==null || required.state!=State.ACTIVE || !dependenciesAvailable(required,visited))return false;
        }
        return true;
    }
    private synchronized String reason(Entry entry){return admissionClosed?"host-admission-closed":available(entry)?"available":entry.state==State.ACTIVE?"dependency-unavailable":entry.reason;}
    private ModuleStatus status(Entry entry){return new ModuleStatus(entry.manifest,entry.state,entry.reason);}
    private Outcome<ModuleStatus> unavailable(Entry entry,String reason){entry.seal();entry.state=State.UNAVAILABLE;entry.reason=reason;return rejected(Outcome.Status.UNAVAILABLE,reason);}
    private static <T> Outcome<T> rejected(Outcome.Status status,String reason){return Outcome.rejected(status,reason.split(":",2)[0],reason);}
    private static <T> CompletionStage<Outcome<T>> completed(Outcome.Status status,String reason){return CompletableFuture.completedFuture(rejected(status,reason));}
    private static <T> InvocationHandle<T> completedHandle(Outcome.Status status,String reason){return new InvocationHandle<>(ModuleHost.<T>completed(status,reason),()->false,()->true);}
    private void cleanup(Entry entry){
        if(entry.cleaned)return;entry.cleaned=true;int failures=0;
        try{entry.module.disable();}catch(Exception | LinkageError | AssertionError error){failures++;}
        List<AutoCloseable> resources=entry.takeResources();
        for(int i=resources.size()-1;i>=0;i--)try{resources.get(i).close();}catch(Exception | LinkageError | AssertionError error){failures++;}
        if(failures>0)entry.reason+="; cleanup-failures="+failures;
    }
    /** Revoke all engine admission without entering trusted lifecycle callbacks or waiting for them. */
    public void revoke(){
        List<Call<?>> pending;
        synchronized(this){
            if(admissionClosed)return;
            admissionClosed=true;pending=List.copyOf(calls);
            workers.shutdown();deadlines.shutdownNow();
        }
        pending.forEach(Call::cancel);
        synchronized(this){maybeStopDeliveriesLocked();}
    }
    @Override public void close(){
        revoke();
        lifecycle.lock();
        try {
            List<Call<?>> pending;List<Entry> reverse;
            synchronized(this){
                if(closed)return;closed=true;pending=List.copyOf(calls);capabilities.clear();
                reverse=new ArrayList<>(enabled);Collections.reverse(reverse);
                reverse.forEach(entry->{entry.state=State.STOPPED;entry.reason="stopped";});
                workers.shutdown();deadlines.shutdownNow();
            }
            pending.forEach(Call::cancel);
            synchronized(this){maybeStopDeliveriesLocked();}
            reverse.forEach(this::cleanup);
        } finally {lifecycle.unlock();}
    }
    /** A terminal winner may be preparing its result while close runs; wait until every accepted result is queued. */
    private void maybeStopDeliveriesLocked(){
        if(admissionClosed && calls.stream().allMatch(call->call.deliveryPhase!=0))deliveries.shutdown();
    }
    private final class Entry implements ModuleContext,Registrar {
        final TrustedModule module;final ModuleManifest manifest;final BuiltinPolicy policy;final DefinitionService scopedDefinitions;final Map<Key,Registration> staged=new LinkedHashMap<>();final List<AutoCloseable> resources=new ArrayList<>();
        volatile State state=State.STARTING;volatile String reason="starting";boolean staging=true,cleaned;
        Entry(TrustedModule module,ModuleManifest manifest,BuiltinPolicy policy){this.module=module;this.manifest=manifest;this.policy=policy;scopedDefinitions=definitions.scoped(()->available(this));}
        synchronized Map<Key,Registration> seal(){staging=false;return Map.copyOf(staged);}
        synchronized List<AutoCloseable> takeResources(){List<AutoCloseable> copy=List.copyOf(resources);resources.clear();return copy;}
        public DefinitionService definitions(){return scopedDefinitions;}
        public Registrar registrations(){return this;}
        public ModuleScheduler scheduler(){return work->invoke(this,null,token->CompletableFuture.completedFuture(work.run(token)),true);}
        public synchronized void onClose(AutoCloseable resource){if(!staging || resources.size()>=64)throw new IllegalStateException("Startup resource registration closed/full");resources.add(Objects.requireNonNull(resource));}
        public void action(Descriptor descriptor,Action action){stage(descriptor,Kind.ACTION,Objects.requireNonNull(action),null);}
        public void condition(Descriptor descriptor,Condition condition){stage(descriptor,Kind.CONDITION,null,Objects.requireNonNull(condition));}
        public void trigger(Descriptor descriptor){stage(descriptor,Kind.TRIGGER,null,null);}
        private synchronized void stage(Descriptor descriptor,Kind kind,Action action,Condition condition){
            if(!staging || staged.size()>=128)throw new IllegalStateException("Registration closed/full");
            if(!descriptor.key().namespace().equals(manifest.id()) || descriptor.kind()!=kind)throw new IllegalArgumentException("Foreign namespace or wrong capability kind");
            CapabilitySchema schema=CapabilitySchema.compile(descriptor.configurationSchemaJson());
            if(staged.putIfAbsent(descriptor.key(),new Registration(this,descriptor,action,condition,schema))!=null)throw new IllegalArgumentException("Duplicate capability");
        }
    }
    private final class Call<T> implements Runnable {
        final Entry entry;final Operation<T> operation;final boolean jsonResult;final BooleanSupplier sourceCurrent;
        final AtomicBoolean cancelled=new AtomicBoolean(),sourceRevoked=new AtomicBoolean(),finished=new AtomicBoolean();final AtomicInteger terminal=new AtomicInteger();final CompletableFuture<Outcome<T>> result=new CompletableFuture<>();
        volatile ScheduledFuture<?> deadline;
        // Host monitor protects delivery phase: 0 not submitted, 1 queued, 2 delivering, 3 returned.
        int deliveryPhase;long deliveryStartedNanos;
        Call(Entry entry,Operation<T> operation,boolean jsonResult,BooleanSupplier sourceCurrent){this.entry=entry;this.operation=operation;this.jsonResult=jsonResult;this.sourceCurrent=Objects.requireNonNull(sourceCurrent);}
        /** Observed revocation stays latched, including if a source is subsequently restored. */
        boolean isCancelled(){
            if(cancelled.get())return true;
            try{if(sourceCurrent.getAsBoolean())return cancelled.get();}
            catch(RuntimeException | LinkageError | AssertionError invalid){/* Failed source observation cannot authorize a handler. */}
            sourceRevoked.set(true);cancelled.set(true);
            // Revocation is cancellation, not a module deadline failure. Publish once now while
            // retaining actual handler/preparation work until its normal completion path returns.
            cancel("source-authority-revoked");return true;
        }
        public void run(){
            if(!available(entry) || isCancelled()){finish(null,null);return;}
            CompletionStage<T> stage;
            try{stage=Objects.requireNonNull(operation.run(this::isCancelled),"Module returned null stage");}
            catch(Exception | LinkageError | AssertionError error){finish(null,error);return;}
            try{stage.whenComplete(this::finish);}
            catch(Exception | LinkageError | AssertionError error){observerFailed(error);}
        }
        /** A broken stage observer does not prove that the returned asynchronous work terminated. */
        void observerFailed(Throwable failure){
            if(!terminal.compareAndSet(0,4))return;
            cancelled.set(true);
            ScheduledFuture<?> timer=deadline;if(timer!=null)timer.cancel(false);
            synchronized(ModuleHost.this){if(entry.state==State.ACTIVE){entry.state=State.SUSPENDED;entry.reason="stage-observer-failed";}}
            publish(rejected(Outcome.Status.FAILED,"stage-observer-failed: "+failure.getClass().getSimpleName()));
            // Some custom stages register then throw; their actual callback may still settle accounting later.
            // Without a callback, the bounded slot/archive remains retained and visible as unobserved work.
            releaseIfComplete();
        }
        void timeout(){
            if(!terminal.compareAndSet(0,1))return;
            cancelled.set(true);
            // A slow platform owner delays built-in work without proving it hung, so only that call times out.
            synchronized(ModuleHost.this){if(entry.policy==null && entry.state==State.ACTIVE){entry.state=State.SUSPENDED;entry.reason="invocation-deadline-exceeded";}}
            publish(rejected(Outcome.Status.TIMED_OUT,"invocation-deadline-exceeded"));
            // A running/returned trusted stage retains its slot until it actually terminates. No preemption is claimed.
            if(workers.remove(this))finish(null,null);
        }
        void cancel(){cancel("host-stopped");}
        boolean cancel(String reason){
            if(!terminal.compareAndSet(0,3))return false;
            cancelled.set(true);ScheduledFuture<?> timer=deadline;if(timer!=null)timer.cancel(false);
            publish(rejected(Outcome.Status.CANCELLED,reason));if(workers.remove(this))finish(null,null);return true;
        }
        boolean quiescent(){synchronized(ModuleHost.this){return finished.get()&&deliveryPhase==3&&!calls.contains(this);}}
        void finish(T value,Throwable failure){
            if(!finished.compareAndSet(false,true))return;
            ScheduledFuture<?> timer=deadline;if(timer!=null)timer.cancel(false);
            if(!terminal.compareAndSet(0,2)){releaseIfComplete();return;}
            Outcome<T> outcome;
            if(isCancelled() || !available(entry))outcome=rejected(Outcome.Status.CANCELLED,sourceRevoked.get()?"source-authority-revoked":"module-unavailable-after-callback");
            else if(failure instanceof InvalidArguments invalid)outcome=new Outcome<>(Outcome.Status.INVALID,Optional.empty(),invalid.diagnostics);
            else if(failure instanceof CapabilityRefusal refusal)outcome=refusal.outcome();
            else if(failure instanceof CompletionException wrapped && wrapped.getCause() instanceof CapabilityRefusal refusal)outcome=refusal.outcome();
            else if(failure!=null)outcome=rejected(failure instanceof InvalidPayload?Outcome.Status.INVALID:Outcome.Status.FAILED,"handler-failed: "+failure.getClass().getSimpleName());
            else if(value==null)outcome=rejected(Outcome.Status.FAILED,"handler-returned-null");
            else {
                try{if(jsonResult)jsonObject((String)value);else if(!(value instanceof Boolean))throw new InvalidPayload();outcome=Outcome.ok(value);}
                catch(Exception invalid){outcome=rejected(Outcome.Status.INVALID,"result-must-be-json-object");}
            }
            publish(outcome);releaseIfComplete();
        }
        void publish(Outcome<T> outcome){
            synchronized(ModuleHost.this){
                if(deliveryPhase!=0)throw new IllegalStateException("Duplicate invocation result");
                deliveryPhase=1;
                deliveries.execute(()->deliver(outcome));
                maybeStopDeliveriesLocked();
            }
        }
        void deliver(Outcome<T> outcome){
            synchronized(ModuleHost.this){deliveryPhase=2;deliveryStartedNanos=System.nanoTime();}
            try{result.complete(outcome);}
            finally{
                synchronized(ModuleHost.this){
                    if(System.nanoTime()-deliveryStartedNanos>=deliveryBudgetNanos)slowDeliveries++;
                    deliveryPhase=3;releaseIfComplete();
                }
            }
        }
        void releaseIfComplete(){
            synchronized(ModuleHost.this){if(finished.get() && deliveryPhase==3)calls.remove(this);maybeStopDeliveriesLocked();}
        }
    }
}
