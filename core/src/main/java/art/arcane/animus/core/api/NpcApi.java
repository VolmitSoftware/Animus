package art.arcane.animus.core.api;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.runtime.NpcRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The public NPC service of one running server: one {@link NpcService} per plugin or Java module, sharing the definitions,
 * the NPC runtime and this hub's temporary NPCs, modules, hiding and follows. Releasing an owner ends what it made.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public final class NpcApi<P, I> implements NpcScopes, AutoCloseable {
    /**
     * @param ticks runs a task once per server tick on the global owner until the handle closes
     * @param heard tells the hear triggers what an NPC said, on the NPC's thread
     */
    public record Parts<P, I>(AnimusRuntime runtime, NpcRuntime<P, I> living, BehaviorPlatform platform, ApiPlatform<P, I> ports,
                              Supplier<BehaviorCommands> behaviors, Function<Runnable, AutoCloseable> ticks, BiConsumer<NpcHandle, String> heard,
                              Consumer<String> log) {
        public Parts {
            Objects.requireNonNull(runtime); Objects.requireNonNull(living); Objects.requireNonNull(platform); Objects.requireNonNull(ports);
            Objects.requireNonNull(behaviors); Objects.requireNonNull(ticks); Objects.requireNonNull(heard); Objects.requireNonNull(log);
        }
    }

    private record Index(Map<String, NpcDefinition> source, Map<UUID, NpcDefinition> byUuid) {}

    final Parts<P, I> parts;
    final ApiModules modules;
    final ApiHiding hiding = new ApiHiding();
    final ApiFollows<P, I> follows;
    final ApiControl<P, I> control;
    final ApiLifecycle<P, I> lifecycle;
    final ApiState<P, I> state;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("Animus-api-", 1).factory());
    private final Map<String, ScopedNpcs<P, I>> services = new ConcurrentHashMap<>();
    private final AutoCloseable ticking;
    private volatile Index index = new Index(null, Map.of());
    /** Callers waiting for an NPC's body, with the tick each gives up at. */
    private record Waiter(CompletableFuture<Boolean> active, long until) {}
    private final Map<UUID, List<Waiter>> waiting = new ConcurrentHashMap<>();
    /** How long a new spawned NPC is waited for, in ticks; one that waits on its chunk past this is reported as saved but dormant. */
    static final int ACTIVE_WAIT_TICKS = 100;
    private volatile boolean closed;
    private long tick;

    public NpcApi(Parts<P, I> parts) {
        this.parts = Objects.requireNonNull(parts);
        NpcRepository repository = parts.runtime().npcs();
        modules = new ApiModules((String id) -> repository.temporary(id).isPresent(), parts.log());
        repository.moduleRules(modules);
        parts.living().previews().alsoHide(hiding);
        follows = new ApiFollows<>(this);
        control = new ApiControl<>(this);
        lifecycle = new ApiLifecycle<>(this);
        state = new ApiState<>(this);
        ticking = parts.ticks().apply(this::tick);
    }

    /** The service of one owner, such as {@code plugin:Shops} or {@code module:reference}; the same object for the same owner. */
    @Override public NpcService open(String owner, BooleanSupplier available) {
        Objects.requireNonNull(owner); Objects.requireNonNull(available);
        if (closed) throw new IllegalStateException("Animus is stopping");
        return services.computeIfAbsent(owner, (String key) -> new ScopedNpcs<>(this, key, available));
    }

    /**
     * The owner disabled: its temporary NPCs are removed, its modules unregistered, its hiding undone and its follows end.
     * Its service answers UNAVAILABLE from now on. Any thread.
     */
    @Override public void release(String owner) {
        ScopedNpcs<P, I> service = services.remove(owner);
        if (service != null) service.release();
        follows.release(owner);
        hiding.release(owner);
        List<String> keys = modules.release(owner);
        List<String> removed = parts.runtime().isRunning() ? parts.runtime().npcs().removeTemporaries(owner) : List.of();
        if (!removed.isEmpty() || !keys.isEmpty())
            parts.log().accept(owner + " stopped: removed " + removed.size() + " temporary NPC" + (removed.size() == 1 ? "" : "s")
                    + (keys.isEmpty() ? "" : " and the modules " + String.join(", ", keys)));
    }

    /** A player left: nothing they had hidden stays recorded. */
    public void playerLeft(UUID player) { hiding.left(player); }

    /** On the player's thread: an accepted click reaches the modules of the clicked NPC. */
    public void clicked(UUID npc, UUID player, boolean attack) {
        definition(npc).ifPresent((NpcDefinition definition) -> modules.clicked(definition, player, attack));
    }

    /** On the NPC's thread: an NPC became active. */
    public void spawned(UUID npc) {
        definition(npc).ifPresent(modules::spawned);
        arrived(npc);
    }

    private void arrived(UUID npc) {
        List<Waiter> waiters = waiting.remove(npc);
        if (waiters != null) waiters.forEach((Waiter waiter) -> waiter.active().complete(true));
    }

    /** Completes with true once the NPC's body is active, or with false after {@link #ACTIVE_WAIT_TICKS} ticks. Any thread. */
    CompletionStage<Boolean> untilActive(UUID npc) {
        CompletableFuture<Boolean> active = new CompletableFuture<>();
        waiting.computeIfAbsent(npc, (UUID key) -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new Waiter(active, tick + ACTIVE_WAIT_TICKS));
        if (live(npc)) arrived(npc);
        return active;
    }

    @Override public void close() {
        closed = true;
        for (String owner : List.copyOf(services.keySet())) release(owner);
        follows.close();
        waiting.values().forEach((List<Waiter> waiters) -> waiters.forEach((Waiter waiter) -> waiter.active().complete(false)));
        waiting.clear();
        try { ticking.close(); }
        catch (Exception ignored) { /* The scheduler stops with the server. */ }
        workers.shutdown();
    }

    boolean closed() { return closed; }

    private void tick() {
        if (closed || !parts.runtime().isRunning()) return;
        long now = ++tick;
        if (!waiting.isEmpty()) expireWaiters(now);
        follows.tick(now);
        modules.tick(now, parts.runtime().npcs().snapshot(), this::live,
                (UUID npc, Runnable work) -> parts.living().onOwner(npc, work, (String reason) -> {}));
    }

    private void expireWaiters(long now) {
        for (Map.Entry<UUID, List<Waiter>> entry : List.copyOf(waiting.entrySet())) {
            if (live(entry.getKey())) { arrived(entry.getKey()); continue; }
            for (Waiter waiter : entry.getValue()) if (waiter.until() <= now) { waiter.active().complete(false); entry.getValue().remove(waiter); }
            if (entry.getValue().isEmpty()) waiting.remove(entry.getKey(), entry.getValue());
        }
    }

    // ---- Lookups ----

    /** Whether the NPC's body is active, from its published state: any thread, unlike {@code NpcRuntime.active}. */
    boolean live(UUID npc) { return parts.living().state(npc).filter(art.arcane.animus.core.virtual.VirtualNpcState.Snapshot::active).isPresent(); }

    /** The published definition with this UUID, saved or temporary. Any thread. */
    Optional<NpcDefinition> definition(UUID npc) {
        if (!parts.runtime().isRunning()) return Optional.empty();
        Map<String, NpcDefinition> snapshot = parts.runtime().npcs().snapshot();
        Index known = index;
        if (known.source() != snapshot) {
            Map<UUID, NpcDefinition> byUuid = new HashMap<>();
            for (NpcDefinition definition : snapshot.values()) byUuid.put(definition.uuid(), definition);
            known = new Index(snapshot, Map.copyOf(byUuid));
            index = known;
        }
        return Optional.ofNullable(known.byUuid().get(npc));
    }

    // ---- Completion ----

    /** A stage that completes on a worker thread with {@code outcome}. */
    <T> CompletionStage<Outcome<T>> done(Outcome<T> outcome) {
        CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        deliver(result, outcome);
        return result.minimalCompletionStage();
    }

    <T> void deliver(CompletableFuture<Outcome<T>> result, Outcome<T> outcome) {
        try { workers.execute(() -> result.complete(outcome)); }
        catch (RejectedExecutionException stopped) { result.complete(outcome); }
    }

    /** {@code stage} moved onto a worker thread, with a failure turned into FAILED. */
    <T> CompletionStage<Outcome<T>> delivered(CompletionStage<Outcome<T>> stage) {
        CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        stage.whenComplete((Outcome<T> outcome, Throwable failure) -> deliver(result, failure == null ? outcome : refused(failure)));
        return result.minimalCompletionStage();
    }

    /** Work on Animus's file queue, which commands and the web editor share. */
    <T> CompletionStage<Outcome<T>> files(Supplier<Outcome<T>> work) {
        if (closed || !parts.runtime().isRunning()) return done(stopped());
        try {
            return delivered(parts.runtime().fileWork(() -> {
                try { return work.get(); }
                catch (RuntimeException failure) { return refused(failure); }
            }));
        } catch (IllegalStateException stopping) { return done(stopped()); }
    }

    /**
     * Work on the thread that owns the active NPC. NOT_FOUND when no NPC has the UUID and UNAVAILABLE when it is not active;
     * a refusal the work throws becomes its outcome.
     */
    <T> CompletionStage<Outcome<T>> onNpc(NpcHandle npc, Function<NpcRuntime.ActiveNpc, Outcome<T>> work) {
        Objects.requireNonNull(npc);
        if (closed || !parts.runtime().isRunning()) return done(stopped());
        if (definition(npc.uuid()).isEmpty()) return done(missing());
        if (!live(npc.uuid())) return done(inactive());
        CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        boolean accepted = parts.living().onOwner(npc.uuid(), () -> {
            Outcome<T> outcome;
            try { outcome = parts.living().active(npc.uuid()).map(work).orElseGet(NpcApi::inactive); }
            catch (RuntimeException | LinkageError failure) { outcome = refused(failure); }
            deliver(result, outcome);
        }, (String reason) -> deliver(result, Outcome.rejected(Outcome.Status.UNAVAILABLE, "npc-owner-refused", reason)));
        if (!accepted) deliver(result, inactive());
        return result.minimalCompletionStage();
    }

    /**
     * Whether the definition may go live now, checked where the runtime checks it; empty when it may. A dormant definition
     * always may.
     */
    public CompletionStage<List<String>> activation(NpcDefinition definition) {
        if (!definition.spawned()) return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        boolean accepted = parts.living().players().console(() -> {
            try { result.complete(parts.living().validateActivation(definition)); }
            catch (RuntimeException failure) { result.complete(List.of(String.valueOf(failure.getMessage()))); }
        });
        if (!accepted) result.complete(List.of("the server refused the spawn check; retry shortly"));
        return result;
    }

    // ---- Outcomes ----

    static <T> Outcome<T> missing() { return Outcome.rejected(Outcome.Status.NOT_FOUND, "npc-missing", "No NPC has this UUID"); }
    static <T> Outcome<T> inactive() { return Outcome.rejected(Outcome.Status.UNAVAILABLE, "npc-inactive", "The NPC is not active"); }
    static <T> Outcome<T> stopped() { return Outcome.rejected(Outcome.Status.UNAVAILABLE, "animus-stopped", "Animus is not running"); }
    static <T> Outcome<T> invalid(String code, String field, String message) {
        return new Outcome<>(Outcome.Status.INVALID, Optional.empty(), List.of(NpcSpecs.error(code, field, message)));
    }
    static <T> Outcome<T> invalid(List<Diagnostic> problems) { return new Outcome<>(Outcome.Status.INVALID, Optional.empty(), problems); }

    /** A thrown refusal as its outcome: the refusal's own status, INVALID for a rejected argument, UNAVAILABLE for a refused state. */
    static <T> Outcome<T> refused(Throwable thrown) {
        Throwable failure = thrown instanceof CompletionException && thrown.getCause() != null ? thrown.getCause() : thrown;
        if (failure instanceof CapabilityRefusal refusal) return new Outcome<>(refusal.status(), Optional.empty(), refusal.diagnostics());
        String message = failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
        message = message.substring(0, Math.min(message.length(), 1000));
        if (failure instanceof IllegalArgumentException) return Outcome.rejected(Outcome.Status.INVALID, "rejected", message);
        if (failure instanceof IllegalStateException) return Outcome.rejected(Outcome.Status.UNAVAILABLE, "unavailable", message);
        if (failure instanceof java.util.concurrent.CancellationException) return Outcome.rejected(Outcome.Status.CANCELLED, "cancelled", message);
        return Outcome.rejected(Outcome.Status.FAILED, "failed", failure.getClass().getSimpleName() + ": " + message);
    }
}
