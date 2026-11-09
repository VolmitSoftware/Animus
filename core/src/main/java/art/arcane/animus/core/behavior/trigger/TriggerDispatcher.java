package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorExpression;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.BehaviorFlags;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Turns events into bounded execution starts. {@link #publish}, {@link #click}, {@link #activated}, {@link #deactivated},
 * {@link #dead} and the diagnostic reads are thread-safe; {@link #pump} and {@link #finished} belong to the
 * behavior owner. Sources must be quick immutable observations. Overflow is dropped and counted, never queued.
 * Hotloaded settings wait in a pending slot until the owner's next pump; a lowered cap refuses new work and leaves
 * queued and running triggers alone.
 */
public final class TriggerDispatcher implements TriggerSink {
    public enum DropReason { EVENT_QUEUE, INACTIVE, NPC_QUEUE, TRIGGER_QUEUE, TOTAL_QUEUE, EXPIRED, STALE, START_FAILED, UNAVAILABLE, ACTIVE_CAPACITY, PLAYER_BUSY }
    @FunctionalInterface public interface Starter { Outcome<UUID> start(Request request); }
    /** {@code event} is what the run reads as {@code context.*}: the click's sneaking and hand for an interaction, nothing for other events. */
    public record Request(Binding binding, NpcDefinition definition, BehaviorCatalog.Accepted accepted, BehaviorProgram program, Optional<UUID> player, Map<String, Object> event) {}
    public record Row(String npcId, UUID npc, String behavior, String trigger, String event, String routine, OptionalLong lastFiredTicksAgo,
                      long fired, int active, int queued, long suppressed, long dropped) {}
    public record Snapshot(boolean available, String reason, long tick, int bindings, int timers, int proximityNpcs, int queued, int active,
                           long events, long started, long suppressed, Map<DropReason, Long> dropped, List<Diagnostic> diagnostics) {}

    /** Single writer (the behavior owner); volatile so diagnostics can read from a command thread. */
    private static final class Stats {
        volatile long lastFired = -1, lastAdmitted = -1, fired, suppressed, dropped;
        volatile int active, queued;
        /** Pump thread only: each player's queued and running runs of an interaction trigger, and when one was last admitted. */
        final Map<UUID, Integer> busy = new HashMap<>();
        final Map<UUID, Long> playerAdmitted = new HashMap<>();
    }
    /** An immutable NPC, accepted program and trigger combination. A source change produces a new one. */
    public static final class Binding {
        private final NpcDefinition definition; private final String behavior; private final BehaviorCatalog.Accepted accepted;
        private final TriggerBinding trigger; private final BehaviorProgram program; private final Stats stats; private final String key;
        private Binding(NpcDefinition definition, String behavior, BehaviorCatalog.Accepted accepted, TriggerBinding trigger, Stats stats) {
            this.definition = definition; this.behavior = behavior; this.accepted = accepted; this.trigger = trigger; this.stats = stats;
            key = behavior + "#" + trigger.id();
            program = trigger.routine().map(accepted.program()::withEntry).orElse(accepted.program());
        }
        public String key() { return key; }
        public String behavior() { return behavior; }
        public TriggerBinding trigger() { return trigger; }
    }
    private record Index(Map<UUID, List<Binding>> byNpc, List<Binding> ordered, List<Diagnostic> diagnostics) {
        static final Index EMPTY = new Index(Map.of(), List.of(), List.of());
    }
    /** {@code lifetime} is the queue lifetime when admitted, so a lowered setting never expires work already waiting. */
    private record Pending(Binding binding, Optional<UUID> player, Map<String, Object> event, long admitted, int lifetime) {}
    /** Thread-safe state and facts a consume condition reads at click time. */
    private record Consumption(BehaviorState state, BehaviorFacts facts) {}
    private record Timer(long due, Binding binding, int interval) {}

    private static final int MAX_DIAGNOSTICS = 64, MAX_TRACKED_NPCS = 16384, MAX_TRACKED_PLAYERS = 4096, MAX_COOLDOWN_TICKS = 72000;
    private static final String DISABLED = "disabled in config.toml", BEHAVIORS_DISABLED = "behaviors are disabled in config.toml";
    private volatile TriggerSettings settings; // written by the owner only
    private volatile ProximityTracker.Limits proximityLimits;
    private final AtomicReference<TriggerSettings> pendingSettings = new AtomicReference<>();
    private volatile boolean behaviorsEnabled = true;
    private final Supplier<Map<String, NpcDefinition>> definitions;
    private final Supplier<BehaviorCatalog.Snapshot> catalog;
    private final Object lock = new Object();
    private final ArrayDeque<TriggerEvent> inbox = new ArrayDeque<>(); // lock
    private final Set<UUID> active = new HashSet<>(), despawning = new HashSet<>(); // lock
    private final LinkedHashSet<UUID> spawning = new LinkedHashSet<>(); // lock
    private long activityGeneration; // lock
    private final LongAdder events = new LongAdder(), suppressed = new LongAdder(), started = new LongAdder();
    private final EnumMap<DropReason, LongAdder> drops = new EnumMap<>(DropReason.class);
    /** The platform or runtime reason; null once a bridge connects. Configuration switches are checked separately. */
    private volatile String blocked = "no platform bridge is connected";
    /** When queued runs last had to wait for the start budget; read by NPC owners to slow cosmetic work. */
    private volatile long backloggedAtNanos;
    private volatile Index index = Index.EMPTY;
    private volatile HiddenNpcs hidden = HiddenNpcs.NONE;
    private volatile Consumption consumption;
    private volatile ProximityTracker.Plan plan = ProximityTracker.Plan.EMPTY;
    private volatile List<Diagnostic> activityDiagnostics = List.of();
    private volatile Diagnostic lastStartFailure;
    private volatile long tick;
    private volatile int queuedCount, activeCount, timerCount;

    // Behavior owner only.
    private Map<String, NpcDefinition> observedDefinitions;
    private BehaviorCatalog.Snapshot observedCatalog;
    private boolean indexDirty, stateCleared;
    private long observedActivity = -1;
    private Set<UUID> activeView = Set.of(), despawningView = Set.of();
    private final Map<String, Stats> stats = new HashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Map<UUID, Integer> npcQueued = new HashMap<>(), npcActive = new HashMap<>();
    private final Map<UUID, Long> npcAdmitted = new HashMap<>();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator.comparingLong(Timer::due));
    private final Map<String, Long> timerDue = new HashMap<>();

    public TriggerDispatcher(TriggerSettings settings, Supplier<Map<String, NpcDefinition>> definitions, Supplier<BehaviorCatalog.Snapshot> catalog) {
        this.settings = Objects.requireNonNull(settings); this.definitions = Objects.requireNonNull(definitions); this.catalog = Objects.requireNonNull(catalog);
        proximityLimits = settings.proximityLimits();
        for (DropReason reason : DropReason.values()) drops.put(reason, new LongAdder());
    }

    /** The platform proved it can run NPCs on their owner. Disabled settings stay unavailable. */
    public void connect() { blocked = null; }
    public void unavailable(String reason) { blocked = Objects.requireNonNull(reason); }
    /** A hotload of {@code [behaviors.triggers]}; the owner adopts it on its next pump. Thread-safe. */
    public void settings(TriggerSettings next) { pendingSettings.set(Objects.requireNonNull(next)); }
    /** {@code behaviors.enabled}: while false no event is accepted and nothing new starts; running executions finish. Thread-safe. */
    public void behaviorsEnabled(boolean enabled) { behaviorsEnabled = enabled; }
    private String unavailable() {
        if (!behaviorsEnabled) return BEHAVIORS_DISABLED;
        if (!settings.enabled()) return DISABLED;
        return blocked;
    }
    @Override public boolean available() { return unavailable() == null; }
    @Override public String availability() { String reason = unavailable(); return reason == null ? "available" : reason; }
    public TriggerSettings settings() { return settings; }
    /** The current proximity budgets; trackers read them once per tick. */
    public ProximityTracker.Limits proximityLimits() { return proximityLimits; }
    public ProximityTracker.Plan proximityPlan() { return available() ? plan : ProximityTracker.Plan.EMPTY; }
    /** Connect with the runtime's previews: a viewer fires no trigger of a live NPC hidden from them. A later call replaces it. */
    public void hide(HiddenNpcs hidden) { this.hidden = Objects.requireNonNull(hidden); }
    /** The bound NPC is hidden from the viewer, so their proximity, interaction and choice events are dropped. Thread-safe. */
    public boolean hides(UUID viewer, UUID npc) {
        HiddenNpcs current = hidden;
        if (current == HiddenNpcs.NONE) return false;
        List<Binding> bindings = index.byNpc().get(npc);
        String id = bindings == null || bindings.isEmpty() ? "" : bindings.getFirst().definition.id();
        return current.hides(viewer, id, npc);
    }

    /** Connects what consume conditions read. {@code state} must allow reads from any thread. Until then no click is consumed. */
    public void consumption(BehaviorState state, BehaviorFacts facts) { consumption = new Consumption(Objects.requireNonNull(state), Objects.requireNonNull(facts)); }

    /**
     * Publishes a click or key press and answers whether a behavior consumes it. Called on the player's thread before any
     * default handling: an input is consumed when an interaction trigger of an enabled behavior on this NPC matches it and
     * its {@code consume} condition holds now, or, for a drop or swap-hands press, when the trigger has no condition. A
     * consumed click is not handed to modules (shops, hiring, GUIs), riding or selection, and a consumed press does not
     * drop or swap the item; any other input keeps its default handling, whether or not it also fires triggers. A failing
     * condition does not consume.
     */
    public boolean click(TriggerEvent.Interaction event) {
        publish(event);
        Consumption reads = consumption;
        if (reads == null || unavailable() != null || hides(event.viewer(), event.npc())) return false;
        List<Binding> bound = index.byNpc().get(event.npc());
        if (bound == null) return false;
        for (Binding binding : bound) {
            if (!(binding.trigger.spec() instanceof TriggerBinding.Interaction interaction) || !interaction.accepts(event)) continue;
            if (interaction.consume().isEmpty() ? event.action().key() : consumes(interaction.consume().get(), binding.behavior, event, reads)) return true;
        }
        return false;
    }
    private static boolean consumes(BehaviorExpression condition, String behavior, TriggerEvent.Interaction event, Consumption reads) {
        try { return Boolean.TRUE.equals(condition.evaluate(new ClickEnvironment(behavior, event, reads))); }
        catch (BehaviorExpression.Failed | BehaviorState.Rejected failed) { return false; }
    }
    /** What a consume condition reads: no variables, the click's context, and this behavior namespace's flags and quests. */
    private record ClickEnvironment(String behavior, TriggerEvent.Interaction event, Consumption reads) implements BehaviorExpression.Environment {
        private static final BehaviorProgram.Value UNSET = new BehaviorProgram.Value(BehaviorProgram.Type.STRING, "unset"), UNSET_STAGE = new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, 0L);
        @Override public BehaviorProgram.Value variable(String name) { return null; }
        @Override public BehaviorFacts facts() { return reads.facts(); }
        @Override public Optional<UUID> player() { return event.player(); }
        @Override public UUID npc() { return event.npc(); }
        @Override public Map<String, Object> context() { return event.context(); }
        @Override public Optional<BehaviorProgram.Value> flag(String scope, String name) {
            BehaviorProgram.Value value = reads.state().read(subject(BehaviorFlags.FLAGS), name, BehaviorFlags.declaration(scope), UNSET, BehaviorProgram.Type.ANY);
            return value == UNSET ? Optional.empty() : Optional.of(value);
        }
        @Override public long quest(String scope, String name) {
            return (Long) reads.state().read(subject(BehaviorFlags.QUESTS), name, BehaviorFlags.declaration(scope), UNSET_STAGE, BehaviorProgram.Type.INTEGER).value();
        }
        @Override public RandomGenerator random() { return ThreadLocalRandom.current(); }
        @Override public Optional<BehaviorProgram.Value> playerFlag(UUID player, String name) {
            BehaviorProgram.Value value = reads.state().read(BehaviorFlags.subject(BehaviorFlags.FLAGS, behavior, event.npc(), Optional.of(player)), name, BehaviorFlags.declaration("player"), UNSET, BehaviorProgram.Type.ANY);
            return value == UNSET ? Optional.empty() : Optional.of(value);
        }
        private BehaviorState.Subject subject(String kind) { return BehaviorFlags.subject(kind, behavior, event.npc(), event.player()); }
    }

    @Override public Outcome<Boolean> publish(TriggerEvent event) {
        Objects.requireNonNull(event);
        String reason = unavailable();
        if (reason != null) {
            drops.get(DropReason.UNAVAILABLE).increment();
            return Outcome.rejected(Outcome.Status.UNAVAILABLE, "trigger-dispatch-unavailable", reason);
        }
        if (!index.byNpc().containsKey(event.npc())) return Outcome.ok(false);
        Optional<UUID> player = event.player();
        if (player.isPresent() && hides(player.get(), event.npc())) return Outcome.ok(false);
        synchronized (lock) {
            if (inbox.size() >= settings.eventQueue()) {
                drops.get(DropReason.EVENT_QUEUE).increment();
                return Outcome.rejected(Outcome.Status.BUSY, "trigger-event-capacity", "Trigger event queue is full; the event was dropped");
            }
            inbox.addLast(event);
        }
        events.increment();
        return Outcome.ok(true);
    }
    /** The platform started presenting this NPC. Its spawn trigger waits for the first behavior catalog. */
    public void activated(UUID npc) {
        Objects.requireNonNull(npc);
        synchronized (lock) {
            if (active.size() >= MAX_TRACKED_NPCS && !active.contains(npc)) { drops.get(DropReason.ACTIVE_CAPACITY).increment(); return; }
            if (active.add(npc)) { spawning.add(npc); activityGeneration++; }
        }
    }
    /** The platform stopped presenting this NPC. Fires despawn unless it died or never reached its spawn trigger. */
    public void deactivated(UUID npc) {
        Objects.requireNonNull(npc);
        synchronized (lock) {
            if (!active.remove(npc)) return;
            activityGeneration++;
            if (spawning.remove(npc)) return;
            if (inbox.size() >= settings.eventQueue()) { drops.get(DropReason.EVENT_QUEUE).increment(); return; }
            despawning.add(npc);
            inbox.addLast(new TriggerEvent.Despawn(npc));
        }
        events.increment();
    }
    /** Health reached zero. Timers and proximity stop; a later removal fires no despawn. */
    public void dead(UUID npc) {
        Objects.requireNonNull(npc);
        synchronized (lock) {
            if (!active.remove(npc)) return;
            spawning.remove(npc);
            activityGeneration++;
        }
    }

    public void pump(Starter starter) {
        Objects.requireNonNull(starter);
        tick = tick + 1;
        adoptSettings();
        if (unavailable() != null) { clearState(); return; }
        stateCleared = false;
        refresh();
        drainSpawns();
        drainEvents();
        fireTimers();
        startQueued(starter);
    }
    /** Index and timer budgets change which bindings exist, so new settings rebuild the index on this pump. */
    private void adoptSettings() {
        TriggerSettings next = pendingSettings.getAndSet(null);
        if (next == null || next.equals(settings)) return;
        settings = next; proximityLimits = next.proximityLimits(); indexDirty = true;
    }
    public void finished(Request request) {
        Stats stat = request.binding().stats; stat.active = stat.active - 1;
        idle(request.binding(), request.player());
        activeCount = activeCount - 1;
        decrement(npcActive, request.definition().uuid());
    }

    private void clearState() {
        if (stateCleared) return;
        stateCleared = true;
        for (Pending pending : queue) {
            drops.get(DropReason.UNAVAILABLE).increment();
            pending.binding().stats.queued = pending.binding().stats.queued - 1;
            idle(pending.binding(), pending.player());
        }
        queue.clear(); npcQueued.clear(); queuedCount = 0;
        timers.clear(); timerDue.clear(); timerCount = 0;
        plan = ProximityTracker.Plan.EMPTY;
        observedActivity = -1;
        synchronized (lock) { inbox.clear(); spawning.clear(); despawning.clear(); }
    }

    private void refresh() {
        Map<String, NpcDefinition> currentDefinitions = definitions.get();
        BehaviorCatalog.Snapshot currentCatalog = catalog.get();
        boolean activityChanged = false;
        synchronized (lock) {
            if (activityGeneration != observedActivity || despawning.size() != despawningView.size()) {
                observedActivity = activityGeneration; activityChanged = true;
                activeView = Set.copyOf(active); despawningView = Set.copyOf(despawning);
            }
        }
        boolean rebuild = indexDirty || currentDefinitions != observedDefinitions || currentCatalog != observedCatalog
                || activityChanged && !dormantRetainedCurrent();
        if (rebuild) {
            observedDefinitions = currentDefinitions; observedCatalog = currentCatalog; indexDirty = false;
            rebuildIndex(currentDefinitions, currentCatalog);
        }
        if (rebuild || activityChanged) rebuildActivity();
    }
    /** Only a dormant NPC gaining or losing retention can change the index; ordinary activity leaves it alone. */
    private boolean dormantRetainedCurrent() {
        for (UUID npc : despawningView) if (!index.byNpc().containsKey(npc)) return false;
        return true;
    }

    private void rebuildIndex(Map<String, NpcDefinition> sources, BehaviorCatalog.Snapshot behaviors) {
        List<NpcDefinition> candidates = new ArrayList<>();
        for (NpcDefinition definition : sources.values()) {
            if (definition.behaviors().isEmpty()) continue;
            UUID uuid = definition.uuid();
            if (definition.spawned() || activeView.contains(uuid) || despawningView.contains(uuid)) candidates.add(definition);
        }
        candidates.sort(Comparator.comparing(NpcDefinition::id));
        Map<UUID, List<Binding>> byNpc = new HashMap<>();
        List<Binding> ordered = new ArrayList<>();
        Map<String, Stats> retained = new HashMap<>();
        Problems problems = new Problems();
        for (NpcDefinition definition : candidates) {
            String npcField = "npc=" + definition.id();
            List<Binding> bound = new ArrayList<>();
            for (String reference : new LinkedHashSet<>(definition.behaviors())) {
                String field = npcField + " behavior=" + reference;
                BehaviorCatalog.Resolution resolution = behaviors.byId().get(reference);
                if (resolution == null) { problems.add("trigger-behavior-missing", field, "NPC attaches a behavior that is not in the accepted catalog"); continue; }
                if (!resolution.available() || resolution.accepted().isEmpty()) { problems.add("trigger-behavior-unavailable", field, "Attached behavior source or capabilities are unavailable; its triggers are unbound"); continue; }
                BehaviorCatalog.Accepted accepted = resolution.accepted().orElseThrow();
                for (TriggerBinding trigger : accepted.program().triggers()) {
                    String triggerField = npcField + " trigger=" + reference + "#" + trigger.id();
                    if (trigger.spec() instanceof TriggerBinding.Proximity proximity && proximity.radius() > settings.maximumProximityRadius()) {
                        problems.add("trigger-proximity-budget", triggerField, "Radius exceeds behaviors.triggers.maximumProximityRadius"); continue;
                    }
                    if (trigger.spec() instanceof TriggerBinding.Timer timer && timer.intervalTicks() < settings.minimumTimerTicks()) {
                        problems.add("trigger-timer-budget", triggerField, "Interval is below behaviors.triggers.minimumTimerTicks"); continue;
                    }
                    if (ordered.size() >= settings.maximumBindings()) {
                        problems.add("trigger-binding-capacity", triggerField, "behaviors.triggers.maximumBindings reached; this trigger is unbound"); continue;
                    }
                    String statsKey = definition.uuid() + "|" + reference + "#" + trigger.id();
                    Stats stat = stats.get(statsKey);
                    if (stat == null) stat = new Stats();
                    retained.put(statsKey, stat);
                    Binding binding = new Binding(definition, reference, accepted, trigger, stat);
                    bound.add(binding); ordered.add(binding);
                }
            }
            if (!bound.isEmpty()) byNpc.put(definition.uuid(), List.copyOf(bound));
        }
        stats.clear(); stats.putAll(retained);
        npcAdmitted.keySet().retainAll(byNpc.keySet());
        index = new Index(Map.copyOf(byNpc), List.copyOf(ordered), problems.list());
    }

    /** Timers and the proximity plan cover only NPCs the platform currently presents. */
    private void rebuildActivity() {
        Map<String, Long> previous = new HashMap<>(timerDue);
        timers.clear(); timerDue.clear();
        Map<UUID, List<ProximityTracker.Binding>> proximity = new HashMap<>();
        double maximum = 0;
        Problems problems = new Problems();
        for (Binding binding : index.ordered()) {
            UUID npc = binding.definition.uuid();
            if (!binding.definition.spawned() || !activeView.contains(npc)) continue;
            switch (binding.trigger.spec()) {
                case TriggerBinding.Timer timer -> {
                    String timerKey = npc + "|" + binding.key();
                    if (timers.size() >= settings.maximumTimers()) {
                        problems.add("trigger-timer-capacity", "npc=" + binding.definition.id() + " trigger=" + binding.key(), "behaviors.triggers.maximumTimers reached; this timer is not scheduled");
                        continue;
                    }
                    long due = previous.getOrDefault(timerKey, tick + timer.intervalTicks());
                    timers.add(new Timer(due, binding, timer.intervalTicks())); timerDue.put(timerKey, due);
                }
                case TriggerBinding.Proximity near -> {
                    proximity.computeIfAbsent(npc, ignored -> new ArrayList<>()).add(new ProximityTracker.Binding(binding.key(), near.phase(), near.radius()));
                    maximum = Math.max(maximum, near.radius());
                }
                default -> {}
            }
        }
        timerCount = timers.size();
        activityDiagnostics = problems.list();
        plan = proximity.isEmpty() ? ProximityTracker.Plan.EMPTY : new ProximityTracker.Plan(copyLists(proximity), maximum);
    }
    private static Map<UUID, List<ProximityTracker.Binding>> copyLists(Map<UUID, List<ProximityTracker.Binding>> source) {
        Map<UUID, List<ProximityTracker.Binding>> copy = new HashMap<>();
        source.forEach((npc, bindings) -> copy.put(npc, List.copyOf(bindings)));
        return copy;
    }

    private void drainSpawns() {
        if (observedCatalog.generation() == 0) return;
        List<UUID> ready = new ArrayList<>();
        synchronized (lock) {
            Iterator<UUID> iterator = spawning.iterator();
            while (iterator.hasNext() && ready.size() < settings.eventsPerTick()) { ready.add(iterator.next()); iterator.remove(); }
        }
        for (UUID npc : ready) admit(new TriggerEvent.Spawn(npc));
    }
    private void drainEvents() {
        for (int count = 0; count < settings.eventsPerTick(); count++) {
            TriggerEvent event;
            synchronized (lock) { event = inbox.pollFirst(); }
            if (event == null) return;
            admit(event);
        }
    }
    private void admit(TriggerEvent event) {
        if (event instanceof TriggerEvent.Despawn) {
            synchronized (lock) { despawning.remove(event.npc()); }
            indexDirty = true;
        }
        List<Binding> bound = index.byNpc().get(event.npc());
        if (bound == null) return;
        // Terminal facts already happened to a presented NPC; they outrun the activity change they cause.
        boolean presented = activeView.contains(event.npc()) || event instanceof TriggerEvent.Despawn || event instanceof TriggerEvent.Death || event instanceof TriggerEvent.Damage;
        Long lastAdmitted = npcAdmitted.get(event.npc());
        boolean cooling = settings.npcCooldownTicks() > 0 && lastAdmitted != null && tick - lastAdmitted < settings.npcCooldownTicks();
        boolean admitted = false;
        for (Binding binding : bound) {
            if (!binding.trigger.matches(event, binding.key())) continue;
            if (!presented) { drop(binding, DropReason.INACTIVE); continue; }
            if (cooling) { suppress(binding); continue; }
            admitted |= enqueue(binding, event.player(), event.context());
        }
        if (admitted && settings.npcCooldownTicks() > 0) npcAdmitted.put(event.npc(), tick);
    }
    private void fireTimers() {
        for (int count = 0; count < settings.eventsPerTick() && !timers.isEmpty() && timers.peek().due() <= tick; count++) {
            Timer timer = timers.poll();
            long next = tick + timer.interval();
            timers.add(new Timer(next, timer.binding(), timer.interval()));
            timerDue.put(timer.binding().definition.uuid() + "|" + timer.binding().key(), next);
            enqueue(timer.binding(), Optional.empty(), Map.of());
        }
    }
    private boolean enqueue(Binding binding, Optional<UUID> player, Map<String, Object> event) {
        Stats stat = binding.stats;
        TriggerBinding.Interaction click = binding.trigger.spec() instanceof TriggerBinding.Interaction interaction && player.isPresent() ? interaction : null;
        // An interaction trigger's default cooldown is each player's own, so one player's clicks never hold back another's.
        int cooldown = binding.trigger.cooldownTicks().orElse(click != null || binding.trigger.spec() instanceof TriggerBinding.Timer ? 0 : settings.defaultCooldownTicks());
        if (stat.lastAdmitted >= 0 && tick - stat.lastAdmitted < cooldown) { suppress(binding); return false; }
        if (click != null) {
            int own = click.playerCooldownTicks().orElse(binding.trigger.cooldownTicks().isPresent() ? 0 : settings.playerCooldownTicks());
            Long last = stat.playerAdmitted.get(player.get());
            if (last != null && tick - last < own) { suppress(binding); return false; }
            if (!click.queue() && stat.busy.containsKey(player.get())) { drop(binding, DropReason.PLAYER_BUSY); return false; }
        }
        UUID npc = binding.definition.uuid();
        if (queue.size() >= settings.maximumQueued()) { drop(binding, DropReason.TOTAL_QUEUE); return false; }
        if (npcQueued.getOrDefault(npc, 0) >= settings.queuePerNpc()) { drop(binding, DropReason.NPC_QUEUE); return false; }
        if (stat.queued >= settings.queuePerTrigger()) { drop(binding, DropReason.TRIGGER_QUEUE); return false; }
        queue.addLast(new Pending(binding, player, event, tick, settings.queueLifetimeTicks()));
        queuedCount = queue.size(); stat.queued = stat.queued + 1; stat.lastAdmitted = tick;
        npcQueued.merge(npc, 1, Integer::sum);
        if (click != null) {
            stat.busy.merge(player.get(), 1, Integer::sum);
            if (stat.playerAdmitted.size() >= MAX_TRACKED_PLAYERS) stat.playerAdmitted.values().removeIf((Long admitted) -> tick - admitted >= MAX_COOLDOWN_TICKS);
            stat.playerAdmitted.put(player.get(), tick);
        }
        return true;
    }
    /** A player's queued or running run of an interaction trigger ended, so their next input may start another. */
    private static void idle(Binding binding, Optional<UUID> player) {
        if (player.isPresent()) binding.stats.busy.computeIfPresent(player.get(), (UUID key, Integer count) -> count <= 1 ? null : count - 1);
    }
    private void suppress(Binding binding) { binding.stats.suppressed = binding.stats.suppressed + 1; suppressed.increment(); }
    private void drop(Binding binding, DropReason reason) { binding.stats.dropped = binding.stats.dropped + 1; drops.get(reason).increment(); }

    private void startQueued(Starter starter) {
        int starts = 0;
        Iterator<Pending> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            Binding binding = pending.binding();
            UUID npc = binding.definition.uuid();
            if (tick - pending.admitted() > pending.lifetime()) { release(iterator, pending); idle(binding, pending.player()); drop(binding, DropReason.EXPIRED); continue; }
            if (!current(binding)) { release(iterator, pending); idle(binding, pending.player()); drop(binding, DropReason.STALE); continue; }
            if (starts >= settings.startsPerTick() || activeCount >= settings.concurrentTotal()) continue;
            if (npcActive.getOrDefault(npc, 0) >= settings.concurrentPerNpc() || binding.stats.active >= settings.concurrentPerTrigger()) continue;
            Outcome<UUID> result = starter.start(new Request(binding, binding.definition, binding.accepted, binding.program, pending.player(), pending.event()));
            if (result.status() == Outcome.Status.BUSY) { starts = settings.startsPerTick(); continue; }
            release(iterator, pending);
            if (result.status() != Outcome.Status.OK) {
                idle(binding, pending.player());
                if (!result.diagnostics().isEmpty()) {
                    Diagnostic refusal = result.diagnostics().getFirst();
                    lastStartFailure = new Diagnostic(refusal.severity(), refusal.code(), "npc=" + binding.definition.id() + " trigger=" + binding.key() + " " + refusal.field(), refusal.message());
                }
                drop(binding, DropReason.START_FAILED);
                continue;
            }
            starts++;
            Stats stat = binding.stats;
            stat.active = stat.active + 1; stat.fired = stat.fired + 1; stat.lastFired = tick;
            activeCount = activeCount + 1; started.increment();
            npcActive.merge(npc, 1, Integer::sum);
        }
        if (!queue.isEmpty() && (starts >= settings.startsPerTick() || activeCount >= settings.concurrentTotal())) backloggedAtNanos = System.nanoTime();
    }
    /** Any thread: queued runs waited for the start or concurrency budget within the last second. */
    public boolean backlogged() {
        long at = backloggedAtNanos;
        return at != 0 && System.nanoTime() - at < 1_000_000_000L;
    }
    private void release(Iterator<Pending> iterator, Pending pending) {
        iterator.remove(); queuedCount = queue.size();
        Stats stat = pending.binding().stats; stat.queued = stat.queued - 1;
        decrement(npcQueued, pending.binding().definition.uuid());
    }
    private static void decrement(Map<UUID, Integer> counts, UUID npc) { counts.computeIfPresent(npc, (key, count) -> count <= 1 ? null : count - 1); }
    private boolean current(Binding binding) {
        if (observedDefinitions.get(binding.definition.id()) != binding.definition) return false;
        BehaviorCatalog.Resolution resolution = observedCatalog.byId().get(binding.behavior);
        return resolution != null && resolution.available() && resolution.accepted().orElse(null) == binding.accepted;
    }

    public Snapshot snapshot() {
        Index view = index; String reason = unavailable();
        Map<DropReason, Long> counted = new EnumMap<>(DropReason.class);
        drops.forEach((drop, count) -> { long value = count.sum(); if (value > 0) counted.put(drop, value); });
        List<Diagnostic> diagnostics = new ArrayList<>();
        Diagnostic refusal = lastStartFailure;
        if (refusal != null) diagnostics.add(refusal);
        diagnostics.addAll(view.diagnostics()); diagnostics.addAll(activityDiagnostics);
        return new Snapshot(reason == null, reason == null ? "none" : reason, tick, view.ordered().size(), timerCount, proximityPlan().npcs().size(),
                queuedCount, activeCount, events.sum(), started.sum(), suppressed.sum(), Map.copyOf(counted), List.copyOf(diagnostics));
    }
    /** A bounded page of bindings ordered by NPC id and then declaration. */
    public List<Row> rows(int skip, int limit) {
        List<Binding> ordered = index.ordered(); long now = tick;
        List<Row> rows = new ArrayList<>();
        for (int position = Math.max(0, skip); position < ordered.size() && rows.size() < limit; position++) {
            Binding binding = ordered.get(position); Stats stat = binding.stats; long fired = stat.lastFired;
            rows.add(new Row(binding.definition.id(), binding.definition.uuid(), binding.behavior, binding.trigger.id(), binding.trigger.spec().kind().jsonName(),
                    binding.program.entry(), fired < 0 ? OptionalLong.empty() : OptionalLong.of(now - fired), stat.fired, stat.active, stat.queued, stat.suppressed, stat.dropped));
        }
        return List.copyOf(rows);
    }

    private static final class Problems {
        private final List<Diagnostic> items = new ArrayList<>();
        private int omitted;
        void add(String code, String field, String message) {
            if (items.size() < MAX_DIAGNOSTICS) items.add(new Diagnostic(Diagnostic.Severity.ERROR, code, field, message)); else omitted++;
        }
        List<Diagnostic> list() {
            if (omitted > 0) items.add(new Diagnostic(Diagnostic.Severity.ERROR, "trigger-diagnostics-truncated", "triggers", omitted + " additional diagnostics omitted"));
            return List.copyOf(items);
        }
    }
}
