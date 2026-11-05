package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Turns server events into bounded starts of the server behaviors listed in {@code server-behaviors.json}.
 * {@link #publish}, {@link #commands}, {@link #snapshot} and {@link #rows} are thread-safe; {@link #pump} and
 * {@link #finished} belong to the behavior owner. Overflow is dropped and counted, never queued without limit.
 */
public final class ServerTriggerDispatcher {
    public enum DropReason { EVENT_QUEUE, TRIGGER_QUEUE, EXPIRED, STALE, START_FAILED, UNAVAILABLE }
    @FunctionalInterface public interface Starter { Outcome<UUID> start(Request request); }
    /**
     * One admitted firing. {@code event} is the event context the run sees as {@code context.*}; {@code current} stays
     * true while the behavior is enabled with the same accepted program.
     */
    public record Request(Binding binding, BehaviorCatalog.Accepted accepted, BehaviorProgram program, Optional<UUID> player,
                          Map<String, Object> event, BooleanSupplier current) {}
    public record Row(String behavior, String trigger, String event, String routine, OptionalLong lastFiredTicksAgo,
                      long fired, int active, int queued, long suppressed, long dropped) {}
    public record Snapshot(boolean available, String reason, long tick, int behaviors, int bindings, int timers, int commands,
                           int queued, int active, long events, long started, long suppressed, Map<DropReason, Long> dropped,
                           List<Diagnostic> diagnostics) {}
    /** An Animus-managed command a platform registers for an enabled {@code command} trigger. */
    public record Command(String behavior, String trigger, String name, List<String> aliases, Optional<String> permission,
                          Optional<String> description, Optional<String> usage) {
        public Command { aliases = List.copyOf(aliases); }
    }
    /** The commands to register; the generation changes only when the list does. */
    public record Commands(long generation, List<Command> commands) {
        public Commands { commands = List.copyOf(commands); }
        public static final Commands EMPTY = new Commands(0, List.of());
    }

    private static final class Stats {
        volatile long lastFired = -1, fired, suppressed, dropped, lastAdmitted = -1;
        volatile int active, queued;
        final LinkedHashMap<UUID, Long> players = new LinkedHashMap<>(); // owner only: last admission per player
    }
    /** An enabled behavior, its accepted program and one of its triggers. */
    public static final class Binding {
        private final String behavior; private final BehaviorCatalog.Accepted accepted; private final TriggerBinding trigger;
        private final BehaviorProgram program; private final Stats stats; private final String key;
        private Binding(String behavior, BehaviorCatalog.Accepted accepted, TriggerBinding trigger, Stats stats) {
            this.behavior = behavior; this.accepted = accepted; this.trigger = trigger; this.stats = stats;
            key = behavior + "#" + trigger.id();
            program = trigger.routine().map(accepted.program()::withEntry).orElse(accepted.program());
        }
        public String key() { return key; }
        public String behavior() { return behavior; }
        public TriggerBinding trigger() { return trigger; }
    }
    private record Index(Map<TriggerKind, List<Binding>> byKind, List<Binding> ordered, Set<String> behaviors, List<Diagnostic> diagnostics) {
        static final Index EMPTY = new Index(Map.of(), List.of(), Set.of(), List.of());
    }
    private record Pending(Binding binding, Optional<UUID> player, Map<String, Object> event, long admitted) {}
    private record Timer(long due, Binding binding, int interval) {}

    private static final int MAX_DIAGNOSTICS = 64, QUEUE_LIFETIME_TICKS = 100, TRACKED_PLAYERS = 4096;
    private final ServerTriggerSettings settings;
    private final Supplier<BehaviorCatalog.Snapshot> catalog;
    private final Supplier<ServerBehaviorList> enabled;
    private final Object lock = new Object();
    private final ArrayDeque<ServerEvent> inbox = new ArrayDeque<>(); // lock
    private final LongAdder events = new LongAdder(), suppressed = new LongAdder(), started = new LongAdder();
    private final EnumMap<DropReason, LongAdder> drops = new EnumMap<>(DropReason.class);
    private volatile String unavailable = "no platform bridge is connected";
    private volatile Index index = Index.EMPTY;
    private volatile Commands commands = Commands.EMPTY;
    private volatile Diagnostic lastStartFailure;
    private volatile long tick;
    private volatile int queuedCount, activeCount, timerCount;

    // Behavior owner only.
    private BehaviorCatalog.Snapshot observedCatalog;
    private ServerBehaviorList observedEnabled;
    private boolean startFired, stateCleared;
    private final Map<String, Stats> stats = new HashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator.comparingLong(Timer::due));
    private final Map<String, Long> timerDue = new HashMap<>();

    public ServerTriggerDispatcher(ServerTriggerSettings settings, Supplier<BehaviorCatalog.Snapshot> catalog, Supplier<ServerBehaviorList> enabled) {
        this.settings = Objects.requireNonNull(settings); this.catalog = Objects.requireNonNull(catalog); this.enabled = Objects.requireNonNull(enabled);
        for (DropReason reason : DropReason.values()) drops.put(reason, new LongAdder());
        if (!settings.enabled()) unavailable = "disabled in config.toml [behaviors.server]";
    }

    /** The platform publishes server events. Disabled settings stay unavailable. */
    public void connect() { if (settings.enabled()) unavailable = null; }
    public void unavailable(String reason) { unavailable = Objects.requireNonNull(reason); }
    public boolean available() { return unavailable == null; }
    public String availability() { String reason = unavailable; return reason == null ? "available" : reason; }
    public ServerTriggerSettings settings() { return settings; }

    /** Quick and nonblocking. True means the event was queued because an enabled trigger listens for its kind. */
    public Outcome<Boolean> publish(ServerEvent event) {
        Objects.requireNonNull(event);
        String reason = unavailable;
        if (reason != null) {
            drops.get(DropReason.UNAVAILABLE).increment();
            return Outcome.rejected(Outcome.Status.UNAVAILABLE, "server-trigger-unavailable", reason);
        }
        if (!index.byKind().containsKey(event.kind())) return Outcome.ok(false);
        synchronized (lock) {
            if (inbox.size() >= settings.eventQueue()) {
                drops.get(DropReason.EVENT_QUEUE).increment();
                return Outcome.rejected(Outcome.Status.BUSY, "server-event-capacity", "Server event queue is full; the event was dropped");
            }
            inbox.addLast(event);
        }
        events.increment();
        return Outcome.ok(true);
    }
    /** Whether any enabled trigger listens for this event kind. Lets platforms skip building events nobody wants. */
    public boolean listens(TriggerKind kind) { return available() && index.byKind().containsKey(kind); }
    /** The commands platforms register now. */
    public Commands commands() { return commands; }

    public void pump(Starter starter) {
        Objects.requireNonNull(starter);
        tick = tick + 1;
        if (unavailable != null) { clearState(); return; }
        stateCleared = false;
        refresh();
        drainEvents();
        fireTimers();
        startQueued(starter);
    }
    public void finished(Request request) {
        Stats stat = request.binding().stats; stat.active = stat.active - 1;
        activeCount = activeCount - 1;
    }

    /** Thread-safe: the behavior is still enabled with the same accepted program. */
    public boolean current(Binding binding) {
        if (unavailable != null || !enabled.get().ids().contains(binding.behavior)) return false;
        BehaviorCatalog.Resolution resolution = catalog.get().byId().get(binding.behavior);
        return resolution != null && resolution.available() && resolution.accepted().orElse(null) == binding.accepted;
    }

    private void clearState() {
        if (stateCleared) return;
        stateCleared = true;
        for (Pending pending : queue) { drops.get(DropReason.UNAVAILABLE).increment(); pending.binding().stats.queued = pending.binding().stats.queued - 1; }
        queue.clear(); queuedCount = 0;
        synchronized (lock) { inbox.clear(); }
    }

    private void refresh() {
        BehaviorCatalog.Snapshot currentCatalog = catalog.get();
        ServerBehaviorList currentEnabled = enabled.get();
        if (currentCatalog == observedCatalog && currentEnabled == observedEnabled) return;
        observedCatalog = currentCatalog; observedEnabled = currentEnabled;
        rebuild(currentCatalog, currentEnabled);
        if (!startFired && currentCatalog.generation() > 0 && currentEnabled.loaded()) {
            startFired = true;
            admit(ServerEvent.serverStart());
        }
    }

    private void rebuild(BehaviorCatalog.Snapshot behaviors, ServerBehaviorList list) {
        Problems problems = new Problems();
        list.problems().forEach(problems::add);
        Map<TriggerKind, List<Binding>> byKind = new EnumMap<>(TriggerKind.class);
        List<Binding> ordered = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        Map<String, Stats> retained = new HashMap<>();
        List<Command> registered = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (String id : list.ids()) {
            String field = "server-behaviors.json behavior=" + id;
            BehaviorCatalog.Resolution resolution = behaviors.byId().get(id);
            if (resolution == null) { problems.add("server-behavior-missing", field, "Listed server behavior is not in the accepted catalog"); continue; }
            if (!resolution.available() || resolution.accepted().isEmpty()) { problems.add("server-behavior-unavailable", field, "Listed server behavior source or capabilities are unavailable"); continue; }
            BehaviorCatalog.Accepted accepted = resolution.accepted().orElseThrow();
            if (!accepted.program().server()) { problems.add("server-behavior-not-server", field, "Listed behavior is not a server behavior (\"server\": true); skipped"); continue; }
            listed.add(id);
            for (TriggerBinding trigger : accepted.program().triggers()) {
                String triggerField = "trigger=" + id + "#" + trigger.id();
                if (trigger.spec() instanceof TriggerBinding.Timer timer && timer.intervalTicks() < settings.minimumTimerTicks()) {
                    problems.add("server-timer-budget", triggerField, "Interval is below behaviors.server.minimumTimerTicks"); continue;
                }
                if (trigger.spec() instanceof TriggerBinding.Command command && !command(id, trigger, command, registered, labels, problems, triggerField)) continue;
                String key = id + "#" + trigger.id();
                Stats stat = stats.getOrDefault(key, new Stats());
                retained.put(key, stat);
                Binding binding = new Binding(id, accepted, trigger, stat);
                ordered.add(binding);
                byKind.computeIfAbsent(trigger.spec().kind(), ignored -> new ArrayList<>()).add(binding);
            }
        }
        stats.clear(); stats.putAll(retained);
        Map<TriggerKind, List<Binding>> frozen = new EnumMap<>(TriggerKind.class);
        byKind.forEach((kind, bound) -> frozen.put(kind, List.copyOf(bound)));
        index = new Index(Collections.unmodifiableMap(frozen), List.copyOf(ordered), Set.copyOf(listed), problems.list());
        if (!registered.equals(commands.commands())) commands = new Commands(commands.generation() + 1, registered);
        rebuildTimers();
    }
    /** A name or alias another enabled command already uses, and every command past the maximum, stays unregistered. */
    private boolean command(String behavior, TriggerBinding trigger, TriggerBinding.Command command, List<Command> registered, Set<String> labels, Problems problems, String field) {
        List<String> names = new ArrayList<>(command.aliases()); names.addFirst(command.name());
        for (String name : names)
            if (labels.contains(name)) { problems.add("server-command-duplicate", field, "Another enabled server behavior already registers /" + name + "; this command is not registered"); return false; }
        if (registered.size() >= settings.maximumCommands()) { problems.add("server-command-capacity", field, "behaviors.server.maximumCommands reached; /" + command.name() + " is not registered"); return false; }
        labels.addAll(names);
        registered.add(new Command(behavior, trigger.id(), command.name(), command.aliases(), command.permission(), command.description(), command.usage()));
        return true;
    }
    private void rebuildTimers() {
        Map<String, Long> previous = new HashMap<>(timerDue);
        timers.clear(); timerDue.clear();
        for (Binding binding : index.ordered()) {
            if (!(binding.trigger.spec() instanceof TriggerBinding.Timer timer)) continue;
            long due = previous.getOrDefault(binding.key, tick + timer.intervalTicks());
            timers.add(new Timer(due, binding, timer.intervalTicks())); timerDue.put(binding.key, due);
        }
        timerCount = timers.size();
    }

    private void drainEvents() {
        for (int count = 0; count < settings.eventsPerTick(); count++) {
            ServerEvent event;
            synchronized (lock) { event = inbox.pollFirst(); }
            if (event == null) return;
            admit(event);
        }
    }
    private void admit(ServerEvent event) {
        List<Binding> bound = index.byKind().get(event.kind());
        if (bound == null) return;
        for (Binding binding : bound) if (event.matches(binding.trigger.spec())) enqueue(binding, event.player(), event.context());
    }
    private void fireTimers() {
        for (int count = 0; count < settings.eventsPerTick() && !timers.isEmpty() && timers.peek().due() <= tick; count++) {
            Timer timer = timers.poll();
            long next = tick + timer.interval();
            timers.add(new Timer(next, timer.binding(), timer.interval()));
            timerDue.put(timer.binding().key, next);
            enqueue(timer.binding(), Optional.empty(), Map.of());
        }
    }
    /** Player events cool down per trigger and player; the others per trigger. */
    private void enqueue(Binding binding, Optional<UUID> player, Map<String, Object> event) {
        Stats stat = binding.stats;
        int cooldown = binding.trigger.cooldownTicks().orElse(binding.trigger.spec() instanceof TriggerBinding.Timer ? 0 : settings.defaultCooldownTicks());
        long last = player.isPresent() ? stat.players.getOrDefault(player.get(), -1L) : stat.lastAdmitted;
        if (cooldown > 0 && last >= 0 && tick - last < cooldown) { suppress(binding); return; }
        if (queue.size() >= settings.eventQueue()) { drop(binding, DropReason.EVENT_QUEUE); return; }
        if (stat.queued >= settings.queuePerTrigger()) { drop(binding, DropReason.TRIGGER_QUEUE); return; }
        queue.addLast(new Pending(binding, player, event, tick));
        queuedCount = queue.size(); stat.queued = stat.queued + 1;
        if (cooldown > 0 && player.isPresent()) remember(stat, player.get(), cooldown);
        else stat.lastAdmitted = tick;
    }
    private void remember(Stats stat, UUID player, int cooldown) {
        stat.players.remove(player);
        stat.players.put(player, tick);
        if (stat.players.size() <= TRACKED_PLAYERS) return;
        stat.players.values().removeIf(admitted -> tick - admitted >= cooldown);
        Iterator<UUID> eldest = stat.players.keySet().iterator();
        while (stat.players.size() > TRACKED_PLAYERS && eldest.hasNext()) { eldest.next(); eldest.remove(); }
    }
    private void suppress(Binding binding) { binding.stats.suppressed = binding.stats.suppressed + 1; suppressed.increment(); }
    private void drop(Binding binding, DropReason reason) { binding.stats.dropped = binding.stats.dropped + 1; drops.get(reason).increment(); }

    private void startQueued(Starter starter) {
        int starts = 0;
        Iterator<Pending> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            Binding binding = pending.binding();
            if (tick - pending.admitted() > QUEUE_LIFETIME_TICKS) { release(iterator, pending); drop(binding, DropReason.EXPIRED); continue; }
            if (!current(binding)) { release(iterator, pending); drop(binding, DropReason.STALE); continue; }
            if (starts >= settings.startsPerTick() || activeCount >= settings.concurrentTotal() || binding.stats.active >= settings.concurrentPerTrigger()) continue;
            Outcome<UUID> result = starter.start(new Request(binding, binding.accepted, binding.program, pending.player(), pending.event(), () -> current(binding)));
            if (result.status() == Outcome.Status.BUSY) { starts = settings.startsPerTick(); continue; }
            release(iterator, pending);
            if (result.status() != Outcome.Status.OK) {
                drop(binding, DropReason.START_FAILED);
                if (!result.diagnostics().isEmpty()) {
                    Diagnostic refusal = result.diagnostics().getFirst();
                    lastStartFailure = new Diagnostic(refusal.severity(), refusal.code(), "trigger=" + binding.key + " " + refusal.field(), refusal.message());
                }
                continue;
            }
            starts++;
            Stats stat = binding.stats;
            stat.active = stat.active + 1; stat.fired = stat.fired + 1; stat.lastFired = tick;
            activeCount = activeCount + 1; started.increment();
        }
    }
    private void release(Iterator<Pending> iterator, Pending pending) {
        iterator.remove(); queuedCount = queue.size();
        Stats stat = pending.binding().stats; stat.queued = stat.queued - 1;
    }

    public Snapshot snapshot() {
        Index view = index; String reason = unavailable;
        Map<DropReason, Long> counted = new EnumMap<>(DropReason.class);
        drops.forEach((drop, count) -> { long value = count.sum(); if (value > 0) counted.put(drop, value); });
        List<Diagnostic> diagnostics = new ArrayList<>();
        Diagnostic refusal = lastStartFailure;
        if (refusal != null) diagnostics.add(refusal);
        diagnostics.addAll(view.diagnostics());
        return new Snapshot(reason == null, reason == null ? "none" : reason, tick, view.behaviors().size(), view.ordered().size(), timerCount,
                commands.commands().size(), queuedCount, activeCount, events.sum(), started.sum(), suppressed.sum(), Map.copyOf(counted), List.copyOf(diagnostics));
    }
    /** A bounded page of bindings in server-behaviors.json order and then declaration. */
    public List<Row> rows(int skip, int limit) {
        List<Binding> ordered = index.ordered(); long now = tick;
        List<Row> rows = new ArrayList<>();
        for (int position = Math.max(0, skip); position < ordered.size() && rows.size() < limit; position++) {
            Binding binding = ordered.get(position); Stats stat = binding.stats; long fired = stat.lastFired;
            rows.add(new Row(binding.behavior, binding.trigger.id(), binding.trigger.spec().kind().jsonName(), binding.program.entry(),
                    fired < 0 ? OptionalLong.empty() : OptionalLong.of(now - fired), stat.fired, stat.active, stat.queued, stat.suppressed, stat.dropped));
        }
        return List.copyOf(rows);
    }

    private static final class Problems {
        private final List<Diagnostic> items = new ArrayList<>();
        private int omitted;
        void add(String code, String field, String message) { add(new Diagnostic(Diagnostic.Severity.ERROR, code, field, message)); }
        void add(Diagnostic diagnostic) { if (items.size() < MAX_DIAGNOSTICS) items.add(diagnostic); else omitted++; }
        List<Diagnostic> list() {
            if (omitted > 0) items.add(new Diagnostic(Diagnostic.Severity.ERROR, "trigger-diagnostics-truncated", "server-behaviors.json", omitted + " additional diagnostics omitted"));
            return List.copyOf(items);
        }
    }
}
