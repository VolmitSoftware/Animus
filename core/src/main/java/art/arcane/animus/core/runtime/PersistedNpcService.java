package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.CaptureProvenance;
import art.arcane.animus.core.navigation.DoorPassage;
import art.arcane.animus.core.navigation.IdleFall;
import art.arcane.animus.core.navigation.LongLeg;
import art.arcane.animus.core.navigation.MovementSettings;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.NavigationPlan;
import art.arcane.animus.core.navigation.OwnerNavigation;
import art.arcane.animus.core.navigation.PathExecution;
import art.arcane.animus.core.navigation.PathPlanner;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.config.NavigationSettings;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.RigSettings;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.DisplayTransform;
import art.arcane.animus.core.rig.NpcRig;
import art.arcane.animus.core.rig.RigCatalog;
import art.arcane.animus.core.rig.RigFrame;
import art.arcane.animus.core.rig.RigPlayback;
import art.arcane.animus.core.rig.RigReference;
import art.arcane.animus.core.rig.RigView;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.virtual.DragonMotion;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.ViewerSession;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Persisted virtual NPCs on a platform with one owner thread: Bukkit and Paper, and the mod loaders' server thread.
 * All world, presentation and movement authority belongs to that thread. The platform supplies a {@link RuntimeHost}
 * and forwards its player lifecycle events to the public event methods.
 * <p>
 * A hotload of {@code config.toml} reaches this runtime on its next tick: the {@code [npcs]}, {@code [navigation]}
 * and {@code [rigs]} tables are swapped in before any work. Budgets and view distance apply to that tick. A lowered
 * cap never takes away what is already running: viewers and visible bodies above it stay until they leave or retire
 * as usual, routes and path plans in progress finish, and only new work waits until usage is under the cap.
 * {@code enabled = false} hides every body by forgetting each viewer, stops all NPC work and closes the transport;
 * {@code enabled = true} binds the transport again and activates every spawned definition, as startup does.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public final class PersistedNpcService<P, I> implements NpcRuntime<P, I> {
    public record Settings(boolean enabled, double viewDistance, int maximumVisible, int maximumViewers, int updatesPerTick,
                           int packetsPerTick, int pendingSpawnsPerViewer, int lookUpdatesPerTick) {
        public static final Settings DEFAULT = from(NpcRuntimeSettings.DEFAULT);
        public Settings {
            if (!Double.isFinite(viewDistance) || viewDistance < 8 || viewDistance > 256 || maximumVisible < 1 || maximumVisible > 128
                    || maximumViewers < 1 || maximumViewers > 1024 || updatesPerTick < 1 || updatesPerTick > 64
                    || packetsPerTick < 1 || packetsPerTick > 1024 || pendingSpawnsPerViewer < 1 || pendingSpawnsPerViewer > 64
                    || lookUpdatesPerTick < 1 || lookUpdatesPerTick > 1024)
                throw new IllegalArgumentException("Invalid persisted NPC runtime budgets");
        }
        /** The {@code [npcs]} table of config.toml. */
        public static Settings from(NpcRuntimeSettings config) {
            return new Settings(config.enabled(), config.viewDistance(), config.maximumVisible(), config.maximumViewers(), config.updatesPerTick(),
                    config.packetsPerTick(), config.pendingSpawnsPerViewer(), config.lookUpdatesPerTick());
        }
    }
    /** Internal trusted integration lease; replacement/deletion revokes it. Access only on the owning scheduler. */
    public record CombatBinding(VirtualNpcState state, BooleanSupplier current, Consumer<VirtualNpcState.Snapshot> changed) {}
    private record Geometry(double width, double height, int ids, SeatGeometry seat) {}
    private static final int MAX_DEFINITIONS = 100_000, MAX_ACTIVE = 10_000, MAX_SKIN_READS = 8, MAX_ROUTE_READS = 8;
    private static final int RECONCILE_WORK = 64, MAX_ISSUED_IDS = 8192;
    /** Collision cells one step may check; a route waits for a later tick when fewer are left. */
    private static final int MOTION_CELLS = 256;
    /** A sleeping route looks for a nearby player again after this many ticks. */
    private static final int AWAKE_RECHECK_TICKS = 20;
    private static final int SUPPORT_UPDATES = 16, SUPPORT_CELLS = 4096;
    private static final double HULL_WIDTH_LIMIT = 4, HULL_HEIGHT_LIMIT = 8;
    /** Fastest route leg in blocks per second; combat pursuit is limited further by {@code [combat] maximumSpeed}. */
    public static final double MAXIMUM_SPEED = 10;
    final RuntimeHost<P, I> host;
    final AnimusRuntime runtime;
    private final Executor io;
    private static final String DISABLED = "disabled in config.toml";
    /** Owner thread only; a hotload replaces it at the start of a tick. */
    private Settings settings;
    /** Settings a hotload published on the file lane, taken by the owner at the start of its next tick. */
    private final AtomicReference<PluginSettings> pendingSettings = new AtomicReference<>();
    private final AutoCloseable settingsSubscription;
    private final ViewerPreviews previews;
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    private final DefinitionQueue definitions = new DefinitionQueue();
    private final ActivationWarnings activationWarnings = new ActivationWarnings(this::warnActivation);
    private final Deque<DefinitionQueue.Change> changes = new ArrayDeque<>();
    final Map<String, Entry> entries = new HashMap<>();
    private final Map<UUID, Entry> byUuid = new HashMap<>();
    final SpatialNpcIndex index = new SpatialNpcIndex(MAX_ACTIVE);
    private final Map<String, Geometry> geometries = new HashMap<>();
    final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Deque<Viewer> viewerOrder = new ArrayDeque<>();
    /** Every route takes turns here; there is no limit on how many exist, only on the work done in one tick. */
    private final RouteScheduler<Route> routeOrder;
    private NavigationSettings navigationSettings;
    private final NavigationPressure navigationPressure = new NavigationPressure();
    private int planStarts, motionCells, pathRefusals;
    /** Idle NPCs whose footing changed, and NPCs still falling; each is queued at most once. */
    private final Deque<Value> supportOrder = new ArrayDeque<>();
    /** Active NPCs whose definition names a rig; planning takes turns through it. */
    private final Deque<Value> rigOrder = new ArrayDeque<>();
    private final Deque<Value> lookOrder = new ArrayDeque<>();
    private RigSettings rigSettings;
    private final Deque<Value> routeReadQueue = new ArrayDeque<>();
    private final Set<RouteReadTask> routeReads = new HashSet<>();
    private final ConcurrentLinkedQueue<RouteReadCompletion> routeReadCompletions = new ConcurrentLinkedQueue<>();
    private final Map<UUID, String> selections = new HashMap<>(), viewerFailures = new HashMap<>();
    /** Packet callbacks from network threads; at most {@link #completionCapacity} wait, a bound a hotload may change. */
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger completionCount = new AtomicInteger();
    private volatile int completionCapacity;
    private final ConcurrentLinkedQueue<SkinCompletion> skinCompletions = new ConcurrentLinkedQueue<>();
    private final Set<SkinTask> skinTasks = new HashSet<>();
    private final ConcurrentLinkedQueue<AppearanceCompletion> appearanceCompletions = new ConcurrentLinkedQueue<>();
    private final Set<AppearanceTask> appearanceTasks = new HashSet<>();
    private MountRuntime<P, I> mountRuntime;
    private RiderService<P, I> riders;
    private Iterator<? extends P> admissions = Collections.emptyIterator();
    volatile BodyTransport<P, I> packets;
    private OwnerNavigation navigation;
    private AutoCloseable ticker;
    private TriggerBridge triggers;
    private volatile InteractionListener<P> interactions;
    private volatile InteractionObserver observer;
    private volatile String unavailable;
    private final RuntimeFailures failures;
    private volatile boolean closed;
    private long accepted, rejected;
    long tickNumber;
    /** Online players by chunk, rebuilt on the owner at most once per runtime tick, before the first lookup that needs it. */
    private final NearbyPlayers<P> nearby;
    private long nearbyTick = Long.MIN_VALUE;
    private final PresentationPressure pressure = new PresentationPressure();
    /** The metrics of the server start that built this runtime. */
    private final AnimusMetrics metrics = AnimusMetrics.current();
    /** Budget use summed per tick since the last metrics roll, as percentages; owner only. */
    private long navigationUse, packetUse, loadTicks;
    private final PlayerAccess<P> players = new PlayerAccess<P>() {
        @Override public boolean online(UUID player) { owner(); return host.player(player).isPresent(); }
        @Override public Optional<P> owned(UUID player) { owner(); return host.player(player); }
        @Override public List<P> ownedNear(WorldPosition at, double radius, int limit) { owner(); return playersNear(at, radius, limit); }
        @Override public boolean deliver(UUID player, Consumer<P> action) {
            owner(); Optional<P> online = host.player(player);
            if (online.isEmpty()) return false;
            action.accept(online.get()); return true;
        }
        @Override public boolean console(Runnable action) { owner(); action.run(); return true; }
    };

    /**
     * Construct on the owner thread. A disabled or unbindable runtime stays unavailable with its reason; a disabled one
     * still ticks so a hotload can enable it.
     */
    public PersistedNpcService(RuntimeHost<P, I> host, AnimusRuntime runtime, Executor io, Settings settings) {
        this.host = Objects.requireNonNull(host); this.runtime = Objects.requireNonNull(runtime);
        this.io = Objects.requireNonNull(io); this.settings = Objects.requireNonNull(settings);
        failures = new RuntimeFailures(host::warn);
        nearby = new NearbyPlayers<>((P player) -> host.online(player) ? host.position(player) : null);
        previews = new ViewerPreviews(io, runtime.documents());
        completionCapacity = completionCapacity(settings);
        rigSettings = runtime.isRunning() ? runtime.rigSettings() : RigSettings.DEFAULT;
        navigationSettings = runtime.isRunning() ? runtime.navigationSettings() : NavigationSettings.DEFAULT;
        routeOrder = new RouteScheduler<>(navigationSettings.routesPerTick(), navigationSettings.wakeChecksPerTick(), (Route route) -> route.nextAttempt);
        owner();
        settingsSubscription = runtime.onSettings(pendingSettings::set);
        if (settings.enabled()) bind(); else unavailable = DISABLED;
        try { ticker = host.everyTick(this::tick); }
        catch (RuntimeException failure) { closeQuietly(settingsSubscription); throw failure; }
        metrics.gauges(this::gauges);
    }

    /** Opens the transport and navigation, as startup and a hotload that enables the runtime do. Owner thread. */
    private void bind() {
        try {
            packets = host.openTransport();
            navigation = new OwnerNavigation(host, host::everyTick, navigationSettings.limits());
            geometries.clear();
            for (String type : packets.entityTypes()) {
                BodyTransport.Body body = packets.createBody(UUID.randomUUID(), type, "Animus", null);
                geometries.put(type, new Geometry(body.width(), body.height(), body.entityIds().length, body.seat()));
            }
            mountRuntime = new MountRuntime<>(this, packets.riderUnavailable());
            riders = mountRuntime.riders();
            admissions = host.onlinePlayers().iterator();
            unavailable = null;
            refresh.set(true);
        } catch (Exception | LinkageError failure) {
            unavailable = "unavailable: " + concise(failure);
            if (packets != null) packets.close();
            packets = null;
            if (navigation != null) navigation.close();
            navigation = null;
            host.warn("NPC runtime " + unavailable);
        }
    }

    private static int completionCapacity(Settings settings) { return settings.maximumViewers() * (settings.pendingSpawnsPerViewer() + 2); }

    /**
     * Swaps in the settings a hotload published, before the tick does any work. Budgets are read where they are spent,
     * so the tick that follows already uses them; caps that are below current usage only hold back new work.
     */
    private void adoptSettings() {
        PluginSettings next = pendingSettings.getAndSet(null);
        if (next == null) return;
        Settings previous = settings;
        settings = Settings.from(next.npcs());
        completionCapacity = completionCapacity(settings);
        navigationSettings = next.navigation();
        routeOrder.budgets(navigationSettings.routesPerTick(), navigationSettings.wakeChecksPerTick());
        if (navigation != null) navigation.limits(navigationSettings.limits());
        if (rigSettings.enabled() != next.rigs().enabled())
            for (Value value : rigOrder) value.rigGeneration = Long.MIN_VALUE;
        rigSettings = next.rigs();
        if (previous.maximumVisible() != settings.maximumVisible())
            for (Viewer viewer : viewers.values()) viewer.guard.limits(viewerLimits());
        if (!settings.enabled() && unavailable != DISABLED) suspend();
        else if (settings.enabled() && unavailable == DISABLED) bind();
    }

    /**
     * {@code [npcs] enabled = false}: every viewer is forgotten, which removes its bodies on the client, then every NPC
     * leaves the runtime through the same path as a despawned definition, routes stop and the transport closes. The
     * tick keeps running so a later hotload can enable the runtime again.
     */
    private void suspend() {
        if (available()) {
            for (Viewer viewer : List.copyOf(viewers.values())) {
                disconnect(viewer.player);
                if (triggers != null) triggers.forget(viewer.id);
            }
            for (Entry entry : List.copyOf(entries.values())) remove(entry);
            mountRuntime.close();
        }
        routeOrder.clear(); supportOrder.clear(); rigOrder.clear(); lookOrder.clear();
        for (RouteReadTask task : routeReads) task.cancelled = true;
        routeReads.clear(); routeReadQueue.clear(); routeReadCompletions.clear();
        for (SkinTask task : skinTasks) task.cancelled = true;
        skinTasks.clear(); skinCompletions.clear();
        for (AppearanceTask task : appearanceTasks) task.cancelled = true;
        appearanceTasks.clear(); appearanceCompletions.clear();
        clearCompletions();
        entries.clear(); byUuid.clear(); selections.clear(); viewerFailures.clear(); changes.clear(); definitions.clear();
        admissions = Collections.emptyIterator();
        if (navigation != null) navigation.close();
        navigation = null;
        if (packets != null) packets.close();
        packets = null;
        unavailable = DISABLED;
    }
    private void clearCompletions() { while (completions.poll() != null) completionCount.decrementAndGet(); }
    private ViewerSession.Limits viewerLimits() { return new ViewerSession.Limits(settings.maximumVisible(), MAX_ISSUED_IDS, 100_000_000L, 4.5); }

    @Override public boolean available() { return !closed && packets != null; }
    /** Connect during startup, before the first runtime tick can activate an NPC. */
    @Override public void interactions(InteractionListener<P> listener) { interactions = listener; }
    @Override public void observeInteractions(InteractionObserver observer) { this.observer = observer; }
    @Override public void bindTriggers(TriggerDispatcher dispatcher) { owner(); dispatcher.hide(previews); triggers = new TriggerBridge(dispatcher, index); }
    @Override public String status() {
        if (!available()) return "NPC runtime " + (closed ? "closed" : unavailable);
        owner();
        int visible = viewers.values().stream().mapToInt((Viewer v) -> v.visible.size()).sum();
        return "NPC runtime ready | active=" + index.size() + " | viewers=" + viewers.size() + " | visible=" + visible
                + " | skin-reads=" + skinTasks.size() + " | accepted=" + accepted + " | rejected=" + rejected
                + " | routes=" + routeOrder.size() + " | routes-sleeping=" + routeOrder.sleeping() + " | routes-waiting=" + routeOrder.waiting()
                + " | presentation=" + pressure.describe(tickNumber) + " | navigation-pressure=" + navigationPressure.describe() + " | path-jobs=" + navigation.outstandingJobs() + " | route-reads=" + routeReads.size()
                + " | mounted=" + mountRuntime.mountedCount() + " | mount-problems=" + mountRuntime.problemCount() + " | riders=" + riders.count()
                + " | chunks=" + host.chunkDelivery() + (failures.last() == null ? "" : " | last-error=" + failures.last());
    }
    @Override public boolean cosmeticAllowed() { return !available() || !pressure.strained(tickNumber); }
    /** Owner only: counts for the metrics roll, which runs on this owner's tick thread. */
    RuntimeGauges gauges() {
        if (!available()) return RuntimeGauges.NONE;
        owner();
        int visible = 0;
        for (Viewer viewer : viewers.values()) visible += viewer.visible.size();
        List<RuntimeGauges.NpcPlace> places = new ArrayList<>(index.size());
        int live = 0;
        for (Entry entry : entries.values()) {
            if (entry.value == null) continue;
            VirtualNpcState.Snapshot state = entry.value.state.snapshot();
            if (!state.active()) continue;
            live++;
            ChunkPosition chunk = state.position().chunk();
            places.add(new RuntimeGauges.NpcPlace(state.npcId(), chunk.world(), chunk.x(), chunk.z()));
        }
        int navigationLoad = loadTicks == 0 ? 0 : (int) (navigationUse / loadTicks), presentationLoad = loadTicks == 0 ? 0 : (int) (packetUse / loadTicks);
        navigationUse = 0; packetUse = 0; loadTicks = 0;
        return new RuntimeGauges("single owner thread", runtime.npcs().snapshot().size(), live, Math.max(0, entries.size() - live), routeOrder.sleeping(),
                viewers.size(), visible, routeOrder.size(), routeOrder.waiting(), navigation.outstandingJobs(), navigationPressure.pressured(),
                pressure.strained(tickNumber), pressure.deferred(), navigationLoad, presentationLoad, places);
    }
    @Override public String inspect(String id) {
        if (!available()) return "Runtime " + id + ": " + (closed ? "closed" : unavailable);
        owner();
        NpcDefinition definition = runtime.npcs().snapshot().get(id);
        if (definition == null) return "Runtime " + id + ": unknown";
        if (!DefinitionSpec.spawned(definition)) return "Runtime " + id + ": dormant";
        Entry entry = entries.get(id);
        if (entry == null) return "Runtime " + id + ": activation pending (active capacity " + MAX_ACTIVE + ")";
        String state = entry.value == null ? (entry.task == null ? "unavailable" : "loading skin")
                : entry.value.state.snapshot().active() ? "active" : entry.value.state.snapshot().health() == 0 ? "dead" : "inactive";
        long visible = viewers.values().stream().filter((Viewer v) -> v.visible.containsKey(definition.uuid())).count();
        return "Runtime " + id + ": " + state + "; uuid=" + definition.uuid() + "; revision="
                + (entry.value == null ? definition.revision() : entry.value.spec.definition().revision()) + "; viewers=" + visible
                + "; world-registered=" + (host.registeredEntity(entry.value == null ? definition.uuid() : entry.value.spec.definition().uuid()) ? 1 : 0)
                + (entry.value == null ? "" : "; health=" + entry.value.state.snapshot().health())
                + (entry.value == null ? "" : "; position=" + entry.value.state.snapshot().position().point() + "; navigation=" + entry.value.navigationStatus)
                + (entry.value == null || entry.value.routeError == null ? "" : "; route-error=" + entry.value.routeError)
                + (entry.value == null ? "" : appearance(entry.value))
                + (entry.value == null ? "" : rigStatus(entry.value))
                + (entry.value == null ? "" : mountRuntime.summary(entry.value))
                + (entry.error == null ? "" : "; last-valid-retained=" + (entry.value != null) + "; " + entry.error);
    }
    private static String rigStatus(PersistedNpcService<?, ?>.Value value) {
        if (value.rigId == null) return "";
        return value.rig != null ? "; " + value.rig.status() : "; rig=" + value.rigId + " unavailable: " + value.rigProblem;
    }
    private String appearance(Value value) { return NpcPresentation.describe(value.spec, value.equipment.size(), packets.presentationAvailable()); }
    @Override public List<String> validateActivation(NpcDefinition definition) {
        if (!available()) return List.of("NPC runtime " + (closed ? "closed" : unavailable));
        owner(); Objects.requireNonNull(definition);
        List<String> errors = new ArrayList<>(DefinitionSpec.validate(definition, geometries.keySet(), this::world, host::item));
        errors.addAll(mountRuntime.activationBlockers(definition));
        return List.copyOf(errors);
    }
    @Override public String rideStatus() {
        if (!available()) return "Riding unavailable: NPC runtime " + (closed ? "closed" : unavailable);
        owner();
        return mountRuntime.rideStatus();
    }
    @Override public boolean stopRider(String playerName) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        owner();
        return riders.stop(playerName, "stopped by administrator");
    }
    @Override public Optional<String> selection(UUID viewer) {
        if (!available()) return Optional.empty();
        owner(); String id = selections.get(viewer);
        return id != null && runtime.npcs().snapshot().containsKey(id) ? Optional.of(id) : Optional.empty();
    }
    @Override public void select(UUID viewer, String id) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        owner();
        if (!runtime.npcs().snapshot().containsKey(id)) throw new IllegalArgumentException("Unknown NPC: " + id);
        if (!selections.containsKey(viewer) && selections.size() >= settings.maximumViewers()) throw new IllegalStateException("Selection capacity reached");
        selections.put(viewer, id);
    }
    @Override public void refresh() { refresh.set(true); }
    /** One owner: work already on the owner thread runs now; anything else is scheduled there. */
    @Override public boolean onOwner(UUID npc, Runnable work, Consumer<String> refused) { return onMain(work, refused); }
    @Override public boolean onOwner(String id, Runnable work, Consumer<String> refused) { return onMain(work, refused); }
    private boolean onMain(Runnable work, Consumer<String> refused) {
        if (!available()) return false;
        if (host.ownerThread()) { work.run(); return true; }
        try { return host.execute(() -> { if (available()) work.run(); else refused.accept("NPC runtime " + (closed ? "closed" : unavailable)); }); }
        catch (RuntimeException rejectedWork) { return false; }
    }
    @Override public PlayerAccess<P> players() { return players; }
    /** Owner only. A runtime that stopped ticking rebuilds for every lookup instead of answering from a stale tick. */
    private List<P> playersNear(WorldPosition at, double radius, int limit) {
        if (nearbyTick != tickNumber || !available()) { nearby.rebuild(host.onlinePlayers()); nearbyTick = tickNumber; }
        return nearby.near(at, radius, limit);
    }
    @Override public ViewerPreviews previews() { return previews; }
    @Override public WorldPosition navigationOrigin(String id) { return navigable(id).state.snapshot().position(); }
    @Override public UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) {
        Value value = navigable(id); Objects.requireNonNull(destination); Objects.requireNonNull(mode);
        validateDestination(value, destination, mode, blocksPerSecond);
        failWatch(value, "cancelled: replaced by a new route");
        stopRoute(value, "cancelled: replaced by manual route");
        cancelRouteRead(value);
        value.waypoints = null;
        Route route = new Route(value, destination, mode, blocksPerSecond, null);
        value.route = route; routeOrder.add(route, tickNumber); value.navigationStatus = "queued; route=" + route.id;
        return route.id;
    }
    @Override public boolean stopNavigation(String id) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        if (value == null) return false;
        boolean running = value.route != null || value.routeReadQueued || value.routeTask != null || (value.waypoints != null && !value.waypoints.complete());
        cancelRouteRead(value); value.waypoints = null; stopRoute(value, "cancelled by administrator"); failWatch(value, "cancelled: navigation stopped"); return running;
    }
    @Override public String navigationStatus(String id) {
        if (!available()) return "Navigation " + id + ": " + (closed ? "closed" : unavailable);
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        return "Navigation " + id + ": " + (value == null ? "inactive" : value.navigationStatus + "; position="
                + value.state.snapshot().position().point() + "; tick=" + tickNumber + "; arrivals=" + value.arrivals
                + (value.routeSource == null ? "" : "; route-source=" + value.routeSource)
                + (value.routeError == null ? "" : "; route-error=" + value.routeError));
    }
    @Override public void lookAt(String id, WorldPosition target) {
        Value value = navigable(id); Objects.requireNonNull(target);
        WorldPosition from = value.state.snapshot().position();
        if (!from.world().equals(target.world()) || from.point().distance(target.point()) > 64)
            throw new IllegalArgumentException("Look target must be within64 blocks in the same world");
        LookClose.facing(LookClose.eye(from.point(), bodyHeight(value)), target.point())
                .ifPresent((LookClose.Facing facing) -> setPose(value, facing.yaw(), facing.pitch(), value.onGround));
    }
    @Override public void pose(String id, String pose) {
        Value value = navigable(id); Objects.requireNonNull(pose);
        art.arcane.animus.core.npc.NamedPoses.Facing facing = art.arcane.animus.core.npc.NamedPoses.parse(value.spec.definition().json()).find(pose)
                .orElseThrow(() -> new IllegalArgumentException("The NPC has no pose named " + pose));
        setPose(value, facing.yaw(), facing.pitch(), value.onGround);
    }
    /** Turns NPCs with animus:look-close toward the nearest player in range: a bounded slice per tick, each NPC at most every few ticks. */
    private void tickLooks() {
        if (lookOrder.isEmpty() || !pressure.allows(PresentationPressure.Tier.COSMETIC, tickNumber)) return;
        for (int i = 0, count = Math.min(settings.lookUpdatesPerTick(), lookOrder.size()); i < count && available(); i++) {
            Value value = lookOrder.removeFirst(); lookOrder.addLast(value);
            if (tickNumber < value.nextLook) continue;
            value.nextLook = tickNumber + LookClose.INTERVAL_TICKS;
            if (!valueCurrent(value) || !value.state.snapshot().active()) continue;
            LookClose look = value.lookClose;
            if (mountRuntime.vehicleOf(value.spec.definition().id()) != null || riders.riderOf(value.spec.definition().uuid()) != null) continue;
            if (!look.whileNavigating() && (value.routeTask != null || (value.route != null && (value.route.plan != null || value.route.execution != null)))) continue;
            WorldPosition at = value.state.snapshot().position();
            Vec3 eye = LookClose.eye(at.point(), bodyHeight(value));
            List<LookClose.Candidate<P>> candidates = new ArrayList<>();
            for (Viewer viewer : viewers.values()) {
                if (viewer.failed || !viewer.world.equals(at.world()) || !host.online(viewer.player) || host.dead(viewer.player)) continue;
                Presented body = viewer.visible.get(value.spec.definition().uuid());
                candidates.add(new LookClose.Candidate<>(viewer.player, host.eye(viewer.player), body != null && body.value == value && body.bound && !body.retired));
            }
            boolean looked = false;
            for (LookClose.Candidate<P> candidate : look.ranked(eye, candidates)) {
                double distance = candidate.eye().distance(eye);
                if (look.lineOfSight() && distance > .000001
                        && !host.clearSight(candidate.player(), candidate.eye(), eye.subtract(candidate.eye()).normalized(), Math.max(0, distance - .0001))) continue;
                LookClose.facing(eye, candidate.eye()).ifPresent((LookClose.Facing facing) -> setPose(value, facing.yaw(), facing.pitch(), value.onGround));
                looked = true;
                break;
            }
            if (!looked && look.npcs()) looked = lookAtNpc(value, at, eye, look.range());
            if (!looked && look.randomLook().isPresent() && look.randomLook().get().active() && tickNumber >= value.nextRandomLook) {
                LookClose.RandomLook random = look.randomLook().get();
                LookClose.Facing facing = random.pick(java.util.concurrent.ThreadLocalRandom.current());
                setPose(value, facing.yaw(), facing.pitch(), value.onGround);
                value.nextRandomLook = tickNumber + random.delayTicks();
            }
        }
    }

    /** NPCs scanned for a look-close NPC target: a few nearby chunks and candidates at most. */
    private static final SpatialNpcIndex.Budget NPC_LOOK_BUDGET = new SpatialNpcIndex.Budget(16, 32);

    /** Turns toward the nearest other active NPC in range; false when there is none. */
    private boolean lookAtNpc(Value value, WorldPosition at, Vec3 eye, double range) {
        UUID self = value.spec.definition().uuid();
        Vec3 nearest = null;
        double best = range;
        for (VirtualNpcState.Snapshot other : scanNpcs(at, range, null, NPC_LOOK_BUDGET).candidates()) {
            if (other.npcId().equals(self) || !other.active() || !other.position().world().equals(at.world())) continue;
            Entry entry = byUuid.get(other.npcId());
            double height = entry == null || entry.value == null ? 1.8 : bodyHeight(entry.value);
            Vec3 target = LookClose.eye(other.position().point(), height);
            double distance = target.distance(eye);
            if (distance <= best) { best = distance; nearest = target; }
        }
        if (nearest == null) return false;
        LookClose.facing(eye, nearest).ifPresent((LookClose.Facing facing) -> setPose(value, facing.yaw(), facing.pitch(), value.onGround));
        return true;
    }

    /** Thread-safe: the spatial index holds every active NPC's last published state. */
    @Override public Optional<VirtualNpcState.Snapshot> state(UUID npc) { return index.find(npc); }

    @Override public Optional<ActiveNpc> active(UUID npc) {
        if (!available()) return Optional.empty();
        owner();
        Entry entry = byUuid.get(npc); Value value = entry == null ? null : entry.value;
        if (value == null || !value.state.snapshot().active()) return Optional.empty();
        return Optional.of(new ActiveNpc(entry.id, value.displayName, value.state.snapshot()));
    }
    /** Combat lookup that, unlike active(), also finds a dead NPC whose definition is still spawned. */
    @Override public Optional<ActiveNpc> combatant(UUID npc) {
        if (!available()) return Optional.empty();
        owner();
        Entry entry = byUuid.get(npc); Value value = entry == null ? null : entry.value;
        if (value == null || !DefinitionSpec.spawned(runtime.npcs().snapshot().get(entry.id))) return Optional.empty();
        return Optional.of(new ActiveNpc(entry.id, value.displayName, value.state.snapshot()));
    }
    /** The NPC is following a route now. */
    public boolean routing(String id) {
        if (!available()) return false;
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        return value != null && (value.route != null || value.routeTask != null);
    }
    /** Swings the NPC's arm for everyone who sees it. */
    public void swing(String id) {
        if (!available()) return;
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        if (value == null) return;
        for (Viewer viewer : viewers.values()) {
            Presented body = viewer.visible.get(value.spec.definition().uuid());
            if (body != null && body.value == value && body.bound && !body.retired) packets.swing(viewer.player, body.body);
        }
    }
    /**
     * Moves an active NPC at once onto clear, supported, loaded ground, in its own world or another loaded one; false
     * leaves it in place. An NPC that carries a rider or rides another body stays where it is.
     */
    public boolean relocate(String id, WorldPosition destination) {
        Optional<CombatBinding> binding = combat(id);
        if (binding.isEmpty() || !binding.get().state().snapshot().active() || !host.worldLoaded(destination.world())) return false;
        UUID uuid = binding.get().state().snapshot().npcId();
        if (mountRuntime.vehicleOf(id) != null || riders.riderOf(uuid) != null || mountRuntime.carries(id)) return false;
        int chunkX = Math.floorDiv((int) Math.floor(destination.point().x()), 16), chunkZ = Math.floorDiv((int) Math.floor(destination.point().z()), 16);
        if (!host.chunkLoaded(destination.world(), chunkX, chunkZ)) return false;
        Hitbox box = binding.get().state().snapshot().bounds();
        MotionValidator.Result clear = MotionValidator.check(destination.point(), destination.point(), hullWidth(box.max().x() - box.min().x()),
                hullHeight(box.max().y() - box.min().y()), MotionValidator.Medium.GROUND, true, MOTION_CELLS, host.collision(destination.world()));
        if (!clear.clear()) return false;
        binding.get().changed().accept(binding.get().state().move(destination));
        return true;
    }
    public float yaw(UUID npc) {
        owner(); Entry entry = byUuid.get(npc);
        return entry == null || entry.value == null ? 0 : entry.value.yaw;
    }
    /** Active NPCs near a point, from loaded chunks only, within the caller's scan budget. */
    public SpatialNpcIndex.Scan scanNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget) {
        owner();
        if (!host.worldLoaded(center.world())) return new SpatialNpcIndex.Scan(List.of(), Optional.empty(), 0, 0);
        return index.scan(new SpatialNpcIndex.Query(center, radius), cursor, budget, (ChunkPosition chunk) -> host.chunkLoaded(center.world(), chunk.x(), chunk.z()));
    }
    /** Brings a dead NPC back at the given point. Fires its spawn trigger again. */
    public boolean revive(UUID npc, WorldPosition at) {
        if (!available()) return false;
        owner();
        Entry entry = byUuid.get(npc); Value value = entry == null ? null : entry.value;
        if (value == null || value.state.snapshot().health() > 0 || !DefinitionSpec.spawned(runtime.npcs().snapshot().get(entry.id))) return false;
        index.upsert(value.state.respawn(at));
        value.poseRevision++;
        if (triggers != null) triggers.activated(npc);
        return true;
    }
    /** Restarts the definition's own waypoint patrol from its first point, if it declares one and nothing else moves the NPC. */
    public void resumePatrol(String id) {
        Value value = navigable(id);
        if (value.route != null || value.routeTask != null || value.routeReadQueued) return;
        value.waypoints = WaypointSettings.parse(value.spec.definition()).map((WaypointSettings declared) -> progress(value, declared)).orElse(null);
        if (value.waypoints != null) beginLeg(value, value.waypoints.start(value.state.snapshot().position().point()));
        else requestRouteRead(value);
    }
    /** Replaces the current route with a reusable route; its read and any read failure are reported in navigation status. */
    @Override public void followRoute(String id, String route) {
        Value value = navigable(id);
        if (route == null || !route.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid reusable route identifier");
        if (routeReads.size() >= MAX_ROUTE_READS) throw new IllegalStateException("Reusable route reads are busy; retry shortly");
        failWatch(value, "cancelled: replaced by a new route");
        cancelRouteRead(value);
        stopRoute(value, "cancelled: replaced by reusable route");
        value.waypoints = null;
        RouteReadTask task = new RouteReadTask(value, route, runtime.documents());
        value.routeTask = task; routeReads.add(task);
        try { io.execute(task); }
        catch (RejectedExecutionException full) {
            routeReads.remove(task); value.routeTask = null;
            throw new IllegalStateException("I/O queue is full; retry shortly");
        }
        value.navigationStatus = "loading reusable route " + route;
    }
    /** The current route's outcome, for one watcher. Call right after starting the route, in the same owner turn. */
    @Override public CompletableFuture<Void> awaitRoute(String id) {
        Value value = navigable(id);
        if (value.route == null && value.routeTask == null) throw new IllegalStateException("The NPC has no route to wait for");
        failWatch(value, "cancelled: replaced by a new wait");
        value.routeWatch = new CompletableFuture<>();
        return value.routeWatch;
    }
    private void failWatch(Value value, String reason) {
        CompletableFuture<Void> watch = value.routeWatch;
        if (watch == null) return;
        value.routeWatch = null;
        watch.completeExceptionally(NavigationRefusals.of(reason));
    }
    private void finishWatch(Value value) {
        CompletableFuture<Void> watch = value.routeWatch;
        if (watch == null) return;
        value.routeWatch = null;
        watch.complete(null);
    }
    /** Runtime-only override. Restart or a definition change restores the definition's own appearance. */
    @Override public void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset) {
        Value value = navigable(id);
        NpcPresentation.validateChange(value.spec, name, skin);
        cancelAppearanceRead(value);
        if (reset) {
            value.displayName = value.spec.name();
            value.displaySkin = value.skin;
        } else {
            if (name.isPresent()) value.displayName = name.get();
            if (skin.isPresent()) { readAppearanceSkin(value, skin.get()); return; }
        }
        representBody(value);
    }
    /** An empty stack clears the slot. Runtime-only, like appearance. */
    @Override public void setEquipment(String id, EquipmentSlot slot, I item) {
        Value value = navigable(id);
        if (!packets.equipmentAvailable()) throw new IllegalStateException("Equipment packets are unavailable on this server version");
        value.equipment.put(slot, item);
        value.equipmentRevision++;
    }
    @Override public void playAnimation(String id, String animation, double speed, Optional<String> loop, int layer) {
        Value value = rigged(id);
        Optional<AnimationDocument.Loop> mode = loop.map((String text) -> AnimationDocument.Loop.parse(text)
                .orElseThrow(() -> new IllegalArgumentException("loop must be once, loop, pingpong or hold")));
        value.rig.play(animation, speed, mode, layer, tickNumber);
    }
    @Override public void stopAnimation(String id, OptionalInt layer, int fadeTicks) { rigged(id).rig.stop(layer, fadeTicks, tickNumber); }
    private Value rigged(String id) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        if (value == null || !value.state.snapshot().active()) throw new IllegalStateException("NPC must be active");
        if (value.rig == null) throw new IllegalStateException(value.rigId == null ? "NPC " + id + " has no rig" : "Rig " + value.rigId + " is unavailable: " + value.rigProblem);
        return value;
    }
    private void bindRig(Value value) {
        value.rig = null; value.rigProblem = null;
        if (value.rigId == null) return;
        if (!rigSettings.enabled()) { value.rigProblem = "rigs are disabled in config.toml"; return; }
        if (!packets.bonesAvailable()) { value.rigProblem = "display bones are unavailable on this server build"; return; }
        RigCatalog catalog = runtime.rigs().catalog();
        value.rigGeneration = catalog.generation();
        List<String> problems = new ArrayList<>();
        value.rig = NpcRig.bind(value.rigId, catalog, value.seat, (NpcItem item, String path, List<String> errors) -> host.item(item, path, errors), problems).orElse(null);
        if (value.rig == null) value.rigProblem = String.join("; ", problems.subList(0, Math.min(3, problems.size())));
    }
    private static boolean walking(PersistedNpcService<?, ?>.Value value) {
        return value.route != null && value.route.execution != null && !value.route.execution.finished();
    }
    /** Clip endings and walking state for every rigged NPC, then a bounded number of new segments, taking turns. */
    private void tickRigs() {
        if (rigOrder.isEmpty() || !runtime.isRunning()) return;
        RigCatalog catalog = runtime.rigs().catalog();
        boolean sampling = metrics.sampling();
        for (Value value : List.copyOf(rigOrder)) {
            if (!valueCurrent(value)) continue;
            if (value.rigGeneration != catalog.generation()) {
                boolean had = value.rig != null, toggled = value.rigGeneration == Long.MIN_VALUE;
                value.rigGeneration = catalog.generation();
                // A hotload that switched rigs on or off binds again; a catalog reload refreshes a rig in place when it can.
                if (toggled || value.rig == null || !value.rig.refresh(catalog, tickNumber)) {
                    bindRig(value);
                    if (had || value.rig != null) representBody(value);
                }
            }
            if (value.rig == null || !value.state.snapshot().active()) continue;
            long started = sampling ? metrics.start() : 0;
            for (RigPlayback.Finished done : value.rig.tick(tickNumber, walking(value)))
                if (triggers != null) triggers.animationFinished(value.spec.definition().uuid(), done.animation(), done.layer());
            if (sampling) metrics.npcCost(metrics.npc(value.spec.definition().uuid()), metrics.start() - started);
        }
        // Sampling new segments is cosmetic; clip endings above still fire their triggers under load.
        if (!pressure.allows(PresentationPressure.Tier.COSMETIC, tickNumber)) return;
        long nanos = System.nanoTime();
        int plans = 0;
        for (int i = 0, count = rigOrder.size(); i < count && plans < rigSettings.plansPerTick(); i++) {
            Value value = rigOrder.removeFirst(); rigOrder.addLast(value);
            NpcRig<I> rig = value.rig;
            if (rig == null || !value.state.snapshot().active() || !rig.due(tickNumber, value.yaw)) continue;
            rig.plan(tickNumber, value.yaw, rigSettings.sampleTicks(), nanos);
            plans++;
        }
    }
    /** The body shows bones of a rig the NPC no longer has: it is respawned, which is authoritative work. */
    private boolean rigReplaced(Presented body) {
        if (body.rigIdentity == 0) return false;
        NpcRig<I> rig = body.value.rig;
        NpcRig.Snapshot<I> shown = rig == null ? null : rig.snapshot();
        return shown == null || shown.identity() != body.rigIdentity;
    }
    /**
     * Cosmetic work for one viewer after every viewer's authoritative work: rig bones, taking turns so a viewer's rig
     * budget reaches every body over a few ticks. Returns NPC packet budget used.
     */
    private int presentCosmetics(Viewer viewer, int packetBudget) {
        if (!viewer.ready || !viewer.retirements.isEmpty()) return 0;
        int sent = 0;
        int[] rigBudget = {rigSettings.updatesPerTick()};
        for (int i = 0, count = viewer.rigOrder.size(); i < count && (sent < packetBudget || rigBudget[0] > 0); i++) {
            Presented body = viewer.rigOrder.removeFirst();
            if (body.retired) continue;
            viewer.rigOrder.addLast(body);
            sent += presentRig(viewer, body, packetBudget - sent, rigBudget);
            if (!current(viewer)) return sent;
        }
        return sent;
    }
    /**
     * Shows the body's bones once it is spawned, then sends changed bone transforms within the viewer's rig budget.
     * Returns NPC packet budget used: only showing bones costs it, because transforms have their own budget.
     */
    private int presentRig(Viewer viewer, Presented body, int packetBudget, int[] rigBudget) {
        NpcRig<I> rig = body.value.rig;
        NpcRig.Snapshot<I> shown = rig == null ? null : rig.snapshot();
        if (rigReplaced(body) || shown == null || shown.frame() == null || !body.spawnFinished || body.retired || body.rigFailed) return 0;
        RigFrame frame = shown.frame();
        if (body.bones == null) {
            if (packetBudget <= 0 || viewer.bones + shown.displays().size() > rigSettings.maximumBones()) return 0;
            try { body.bones = packets.createBones(body.body, shown.displays()); }
            catch (RuntimeException invalid) { body.rigFailed = true; body.value.rigProblem = concise(invalid); failures.record("rig bones", context(viewer, body.value), invalid); return 0; }
            body.rigIdentity = shown.identity(); body.view = new RigView(body.bones.count()); viewer.bones += body.bones.count();
            try {
                packets.showBones(viewer.player, body.body, body.bones, pose(body.value), List.of(frame.targets()), shown.hideBody())
                        .whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                            if (failure != null) { if (current(viewer) && !body.retired && !cosmeticFailed(viewer, "show bones", body.value, failure)) body.rigFailed = true; return; }
                            body.view.shown(frame); body.bonesShown = true;
                        }));
            } catch (RuntimeException failure) { if (!cosmeticFailed(viewer, "show bones", body.value, failure)) body.rigFailed = true; }
            return 1;
        }
        if (!body.bonesShown || rigBudget[0] <= 0) return 0;
        int[] due = body.view.due(frame, rigBudget[0]);
        if (due.length == 0) return 0;
        long nanos = System.nanoTime();
        DisplayTransform[] targets = new DisplayTransform[due.length];
        int[] durations = new int[due.length];
        for (int i = 0; i < due.length; i++) { targets[i] = frame.target(due[i]); durations[i] = frame.remaining(nanos, due[i]); }
        body.view.sent(frame, due);
        rigBudget[0] -= due.length;
        try {
            packets.moveBones(viewer.player, body.bones, due, targets, durations).whenComplete((Void nothing, Throwable failure) -> {
                if (failure != null) enqueue(viewer, () -> { if (current(viewer) && !body.retired) { body.view.unsent(due); cosmeticFailed(viewer, "move bones", body.value, failure); } });
            });
        } catch (RuntimeException failure) { body.view.unsent(due); cosmeticFailed(viewer, "move bones", body.value, failure); }
        return 0;
    }
    private void readAppearanceSkin(Value value, String skin) {
        if (appearanceTasks.size() >= MAX_SKIN_READS) throw new IllegalStateException("Skin reads are busy; retry shortly");
        AppearanceTask task = new AppearanceTask(value, skin, runtime.documents());
        value.appearanceTask = task; appearanceTasks.add(task);
        try { io.execute(task); }
        catch (RejectedExecutionException full) {
            appearanceTasks.remove(task); value.appearanceTask = null;
            throw new IllegalStateException("I/O queue is full; retry shortly");
        }
    }
    private void cancelAppearanceRead(Value value) {
        if (value.appearanceTask == null) return;
        value.appearanceTask.cancelled = true;
        value.appearanceTask = null;
    }
    private void finishAppearance(AppearanceCompletion completion) {
        AppearanceTask task = completion.task; appearanceTasks.remove(task); Value value = task.value;
        boolean owns = value.appearanceTask == task;
        if (owns) value.appearanceTask = null;
        if (task.cancelled || !owns || !valueCurrent(value)) return;
        if (completion.error != null) { failures.record("skin read", "npc=" + value.spec.definition().id(), "$.skin: " + completion.error); return; }
        value.displaySkin = completion.skin;
        representBody(value);
    }
    /** Identity is fixed when a body spawns, so viewers receive a fresh body through the normal retire and spawn path. */
    private void representBody(Value value) {
        UUID uuid = value.spec.definition().uuid();
        for (Viewer viewer : viewers.values()) {
            Presented body = viewer.visible.get(uuid);
            if (body != null && body.value == value) retire(viewer, body);
        }
    }

    private Value navigable(String id) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        if (value == null || !value.state.snapshot().active() || !value.authority.accepts(runtime.npcs().snapshot().get(id)))
            throw new IllegalStateException("NPC must be active with its current definition applied");
        if (mountRuntime.vehicleOf(id) != null) throw new IllegalStateException("NPC is riding " + mountRuntime.vehicleOf(id) + "; it moves with its vehicle");
        if (riders.riderOf(value.spec.definition().uuid()) != null) throw new IllegalStateException("A player is riding this NPC");
        return value;
    }
    private static double bodyWidth(PersistedNpcService<?, ?>.Value value) { Hitbox box = value.state.snapshot().bounds(); return box.max().x() - box.min().x(); }
    private static double bodyHeight(PersistedNpcService<?, ?>.Value value) { Hitbox box = value.state.snapshot().bounds(); return box.max().y() - box.min().y(); }
    /** Terrain checks use a central hull. A dragon's outstretched wings and tail are presentation, so they may overhang terrain. */
    static double moveWidth(PersistedNpcService<?, ?>.Value value) { return hullWidth(bodyWidth(value)); }
    static double moveHeight(PersistedNpcService<?, ?>.Value value) { return hullHeight(bodyHeight(value)); }
    public static double hullWidth(double bodyWidth) { return Math.min(HULL_WIDTH_LIMIT, bodyWidth); }
    public static double hullHeight(double bodyHeight) { return Math.min(HULL_HEIGHT_LIMIT, bodyHeight); }
    /** A small body keeps the flat per-move budget; a large hull gets the most its sweep can read, which stays bounded by the hull limits. */
    private static int motionCells(PersistedNpcService<?, ?>.Value value, double distance) { return motionCells(moveWidth(value), moveHeight(value), distance); }
    public static int motionCells(double hullWidth, double hullHeight, double distance) {
        return Math.max(MOTION_CELLS, MotionValidator.cellBudget(hullWidth, hullHeight, distance));
    }
    private void validateDestination(Value value, WorldPosition destination, MovementProfile.Mode mode, double speed) {
        validateLeg(value.state.snapshot().position(), destination, mode, speed, moveWidth(value), moveHeight(value));
    }
    /**
     * Shared by every runtime: one route leg in the NPC's own world. A leg of any length is planned as a {@link LongLeg},
     * one bounded capture at a time.
     */
    public static void validateLeg(WorldPosition origin, WorldPosition destination, MovementProfile.Mode mode, double speed, double hullWidth, double hullHeight) {
        if (!Double.isFinite(speed) || speed < 1 || speed > MAXIMUM_SPEED) throw new IllegalArgumentException("Navigation speed must be1..10 blocks/second");
        if (!origin.world().equals(destination.world())) throw new IllegalArgumentException("Navigation requires the same world");
        // The origin may rest on a partial surface (path, farmland, slab) where an earlier route ended; the goal names a block.
        if (mode == MovementProfile.Mode.WALK && destination.point().y() != Math.rint(destination.point().y()))
            throw new IllegalArgumentException("Walking goals require integer feet height");
        // Refuses a goal outside the world's coordinate bounds.
        BlockPos.containing(destination.point());
    }
    /**
     * Terrain blocks this leg for good. Its route fails as any failed leg does, and the server log says so once per NPC,
     * naming the leg, so an imported patrol that stops is explained.
     */
    private void blockLeg(Route route, String reason) {
        Value value = route.value;
        String id = value.spec.definition().id();
        boolean once = AnimusLog.shared().debug();
        if (!once || !value.blockedWarned) {
            if (once) value.blockedWarned = true;
            AnimusLog.shared().routine(BLOCKED_LEGS, id, blockedLeg(id, route.waypoint, route.origin, route.goal, reason,
                    route.leg != null && value.movement.teleportsWhenStuck()), host::warn);
        }
        failLeg(route, reason);
    }
    /**
     * The one log line for an NPC whose leg terrain blocks; shared by every runtime. An NPC whose movement teleports when
     * stuck goes on from the point instead of stopping.
     */
    /** The routine diagnostic kind of a blocked leg, counted per NPC while debug mode is off. */
    public static final String BLOCKED_LEGS = "NPCs hit blocked route legs";

    public static String blockedLeg(String npc, int waypoint, Vec3 from, WorldPosition to, String reason, boolean teleports) {
        return "NPC " + npc + (teleports ? " teleported" : " stopped") + ": terrain blocks its route leg" + (waypoint < 0 ? "" : " to waypoint " + waypoint)
                + " from " + block(from) + " to " + block(to.point()) + " in " + to.world() + " (" + reason + "); later blocked legs of this NPC are not logged";
    }
    private static String block(Vec3 point) {
        return (int) Math.floor(point.x()) + " " + (int) Math.floor(point.y()) + " " + (int) Math.floor(point.z());
    }
    private MovementProfile profile(Value value, MovementProfile.Mode mode) {
        return value.movement.apply(BodyMobility.forRoute(value.spec.type(), moveWidth(value), moveHeight(value), mode), value.spec.type());
    }
    private boolean routeCurrent(Route route) {
        Value value = route.value; Entry entry = byUuid.get(value.spec.definition().uuid());
        return available() && runtime.isRunning() && entry != null && entry.value == value && value.route == route
                && value.state.snapshot().active() && value.authority.accepts(runtime.npcs().snapshot().get(entry.id));
    }
    void stopRoute(Value value, String reason) {
        Route route = value.route; value.route = null;
        value.doors.closeAll(host.doors());
        if (route != null) {
            routeOrder.remove(route); if (route.plan != null) route.plan.cancel();
            if (route.execution != null) route.execution.cancel();
        }
        if (route == null) value.navigationStatus = reason;
        else { routeStatus(route, reason, ""); failWatch(value, reason); }
    }
    private WaypointProgress progress(Value value, WaypointSettings declared) {
        return WaypointProgress.of(declared, value.spec.position().point(), value.spec.definition().uuid());
    }
    /** Queues the route's next leg. A pause leg holds the route's slot until its wait ends; a random or wander leg that cannot start pauses instead. */
    private void beginLeg(Value value, Optional<WaypointProgress.Leg> next) {
        WaypointProgress progress = value.waypoints;
        if (progress == null || next.isEmpty() || !value.state.snapshot().active()) return;
        WaypointProgress.Leg leg = next.get();
        WaypointSettings waypointSettings = progress.settings();
        WorldPosition at = value.state.snapshot().position();
        WorldPosition goal = leg.pause() ? at : new WorldPosition(at.world(), leg.goal());
        double speed = value.movement.routeSpeed(value.routeSpeedDeclared, waypointSettings.speed());
        if (!leg.pause()) {
            try { validateDestination(value, goal, waypointSettings.mode(), speed); }
            catch (IllegalArgumentException invalid) {
                value.navigationStatus = "invalid waypoint " + leg.arrival() + ": " + invalid.getMessage(); failWatch(value, value.navigationStatus);
                Optional<WaypointProgress.Leg> retry = progress.failed(leg);
                if (retry.isPresent()) beginLeg(value, retry);
                return;
            }
        }
        Route route = new Route(value, goal, waypointSettings.mode(), speed, leg);
        route.nextAttempt = tickNumber + Math.max(1, leg.waitTicks());
        value.route = route; routeOrder.add(route, tickNumber); routeStatus(route, leg.pause() ? "waiting to retry" : "queued", "");
    }
    /** A leg that cannot finish: random and wander routes pause and choose again, every other route halts. */
    private void failLeg(Route route, String reason) {
        Value value = route.value;
        if (teleportStuck(route)) return;
        WaypointProgress progress = value.waypoints;
        stopRoute(value, reason);
        if (progress == null || route.leg == null || !progress.retries() || value.route != null || !valueCurrent(value)) return;
        String failure = value.navigationStatus;
        beginLeg(value, progress.failed(route.leg));
        if (value.route != null) routeStatus(value.route, "waiting to retry", "after " + failure);
    }
    /**
     * A waypoint leg of an NPC whose movement teleports when stuck ends at its point, as Citizens' default stuck action
     * does, and the route goes on. False when the NPC stops instead.
     */
    private boolean teleportStuck(Route route) {
        Value value = route.value;
        if (route.leg == null || route.leg.pause() || !value.movement.teleportsWhenStuck() || value.route != route || !routeCurrent(route)) return false;
        if (route.plan != null) route.plan.cancel();
        if (route.execution != null) route.execution.cancel();
        route.plan = null; route.result = null; route.execution = null;
        routeOrder.remove(route);
        value.state.move(route.goal); index.upsert(value.state.snapshot());
        value.poseRevision++; setPose(value, value.yaw, 0, true);
        arrive(route);
        return true;
    }
    private void routeStatus(Route route, String state, String detail) {
        route.value.navigationStatus = state + "; route=" + route.id + "; waypoint=" + route.waypoint + "; goal=" + route.goal.point()
                + (route.capture == null ? "" : "; capture=" + route.capture.firstTick() + ".." + route.capture.lastTick()
                + "; sampled=" + route.capture.sampledVoxels() + "; unavailable=" + route.capture.unavailableVoxels())
                + (detail.isEmpty() ? "" : "; " + detail);
    }
    private void suspend(Route route, String reason) {
        // A waiting behavior is told now and the route ends, rather than holding its slot until terrain returns.
        if (route.value.routeWatch != null) { stopRoute(route.value, "unavailable: " + reason); return; }
        if (route.plan != null) route.plan.cancel(); route.plan = null; route.result = null;
        // Resume by planning from the current point. An airborne kinematic phase is retained and revalidated.
        route.nextAttempt = tickNumber + 40; routeStatus(route, "suspended", reason);
    }
    private void tickNavigation() {
        planStarts = navigationSettings.planningStartsPerTick(); motionCells = navigationSettings.motionCellsPerTick(); pathRefusals = 0;
        RouteScheduler.Tick turn = routeOrder.tick(tickNumber, new RouteScheduler.Listener<>() {
            @Override public boolean awake(Route route) { return routeAwake(route); }
            @Override public RouteScheduler.Result work(Route route) {
                if (!available()) return RouteScheduler.Result.DEFER;
                if (!metrics.sampling()) return workRoute(route);
                long started = metrics.start();
                RouteScheduler.Result result = workRoute(route);
                metrics.npcCost(metrics.npc(route.value.spec.definition().uuid()), metrics.start() - started);
                return result;
            }
            @Override public void slept(Route route) {
                if (route.plan != null) route.plan.cancel(); route.plan = null; route.result = null;
                route.value.doors.closeAll(host.doors());
                routeStatus(route, "sleeping", "no player within " + (int) settings.viewDistance() + " blocks");
            }
            @Override public void woke(Route route) { route.progressTick = tickNumber; routeStatus(route, "queued", "a player is near"); }
        });
        navigationPressure.observe(tickNumber, turn.deferred() + pathRefusals, "routesPerTick=" + navigationSettings.routesPerTick()
                + ", motionCellsPerTick=" + navigationSettings.motionCellsPerTick() + ", planningStartsPerTick=" + navigationSettings.planningStartsPerTick()
                + ", planningJobs=" + navigationSettings.planningJobs() + ", captureReadsPerTick=" + navigationSettings.captureReadsPerTick()
                + " in [navigation] of config.toml").ifPresent(host::warn);
    }
    /**
     * A patrol sleeps while no player is within view distance: it holds no turn, plan or budget. A manual route, and one
     * a behavior waits for, never sleeps. The answer is kept for a second, so checking costs one player scan per route per second.
     */
    private boolean routeAwake(Route route) {
        if (route.leg == null || route.value.routeWatch != null || !routeCurrent(route)) return true;
        // A new route has never looked: awakeChecked starts unset, and subtracting from it must not overflow into "recent".
        if (route.awakeChecked != Long.MIN_VALUE && tickNumber - route.awakeChecked < AWAKE_RECHECK_TICKS) return route.awake;
        route.awakeChecked = tickNumber;
        route.awake = !playersNear(route.value.state.snapshot().position(), settings.viewDistance(), 1).isEmpty();
        return route.awake;
    }
    private RouteScheduler.Result workRoute(Route route) {
        Value value = route.value;
        if (!routeCurrent(route)) { stopRoute(value, "cancelled by definition/lifecycle change"); return RouteScheduler.Result.DONE; }
        if (route.leg != null && route.leg.pause()) {
            value.route = null;
            if (value.waypoints != null) beginLeg(value, value.waypoints.resume(route.leg, value.state.snapshot().position().point()));
            return RouteScheduler.Result.DONE;
        }
        String world = route.goal.world();
        if (!host.worldLoaded(world)) { stopRoute(value, "cancelled: world unavailable"); return RouteScheduler.Result.DONE; }
        if (route.leg != null && value.movement.stuck(tickNumber - route.progressTick)) {
            failLeg(route, "stuck: no progress for " + (tickNumber - route.progressTick) + " ticks");
            return routeCurrent(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
        }
        try {
            if (route.execution == null) {
                if (route.plan == null) {
                    BlockPos feet = BlockPos.containing(value.state.snapshot().position().point());
                    BlockPos goal = BlockPos.containing(route.goal.point());
                    LongLeg.Request request = route.longLeg.request(value.state.snapshot().position().point());
                    // A segment of a long leg needs only the terrain it captures; an unloaded chunk there makes its plan wait.
                    if (!host.chunkLoaded(world, Math.floorDiv(feet.x(), 16), Math.floorDiv(feet.z(), 16))
                            || (request.direct() && !host.chunkLoaded(world, Math.floorDiv(goal.x(), 16), Math.floorDiv(goal.z(), 16)))) {
                        suspend(route, "unavailable terrain; no chunk loading requested");
                    } else if (fallBeforeLeg(route)) {
                        return RouteScheduler.Result.KEEP;
                    } else if (planStarts <= 0) {
                        routeStatus(route, "queued", "waiting for a planning turn");
                        return RouteScheduler.Result.YIELD;
                    } else {
                        planStarts--;
                        route.plan = navigation.plan(world, request.bounds(), feet, request.aim(), profile(value, route.mode), request.limits());
                        route.result = route.plan.future().toCompletableFuture(); routeStatus(route, "planning", "");
                    }
                } else if (route.result.isDone()) {
                    PathPlanner.Result planned = route.result.join(); route.capture = route.plan.capture().orElse(null); route.plan = null; route.result = null;
                    if (planned.status() == PathPlanner.Status.UNAVAILABLE_TERRAIN) suspend(route, "unavailable captured terrain");
                    else {
                        LongLeg.Next next = route.longLeg.planned(planned);
                        if (next == LongLeg.Next.BLOCKED) blockLeg(route, "failed: " + planned.status());
                        else if (next == LongLeg.Next.RETRY) routeStatus(route, "replanning", "attempt " + route.longLeg.attempts() + " after " + planned.status());
                        else {
                            route.segment = next == LongLeg.Next.SEGMENT;
                            Vec3 end = route.segment ? planned.path().getLast().point() : route.goal.point();
                            route.execution = new PathExecution(value.state.snapshot().position().point(), planned.path(), end, profile(value, route.mode));
                            routeStatus(route, "moving", moving(route.segment));
                        }
                    }
                }
            }
            if (routeCurrent(route) && route.execution != null && tickNumber >= route.nextAttempt) {
                if (!route.execution.position().equals(value.state.snapshot().position().point())) { stopRoute(value, "cancelled: authoritative position changed"); }
                else if (route.execution.finished()) executed(route);
                else {
                    PathExecution.Proposal step = route.execution.next(route.speed / 20).orElseThrow();
                    int sweep = motionCells(value, step.from().distance(step.to())), settle = motionCells(value, 0);
                    // A step larger than the whole tick's cells still runs when it is the tick's first, so a big body never stalls.
                    if (motionCells < sweep + (step.landing() ? settle : 0) && motionCells < navigationSettings.motionCellsPerTick()) return RouteScheduler.Result.DEFER;
                    MotionValidator.Sampler terrain = host.collision(world);
                    if (route.mode == MovementProfile.Mode.WALK && value.movement.openDoors()) {
                        Optional<BlockPos> door = value.doors.prepare(world, step.from(), step.to(), moveWidth(value), moveHeight(value), terrain, host.doors());
                        if (door.isPresent()) {
                            failLeg(route, "blocked: the door at " + door.get().x() + " " + door.get().y() + " " + door.get().z() + " could not be opened");
                            return routeCurrent(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
                        }
                    }
                    MotionValidator.Result validation = MotionValidator.check(step.from(), step.to(), moveWidth(value), moveHeight(value), step.medium(), step.requireSupport(), sweep, terrain);
                    motionCells -= validation.sampledCells();
                    if (validation.clear() && step.landing()) {
                        validation = MotionValidator.check(step.to(), step.to(), moveWidth(value), moveHeight(value),
                                route.mode == MovementProfile.Mode.SWIM ? MotionValidator.Medium.WATER : MotionValidator.Medium.GROUND,
                                route.mode != MovementProfile.Mode.SWIM, settle, terrain);
                        motionCells -= validation.sampledCells();
                    }
                    if (validation.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) suspend(route, validation.detail());
                    else if (!validation.clear()) blockedStep(route, "blocked: " + validation.status() + "; " + validation.detail());
                    else {
                        route.execution.commit(step);
                        Vec3 delta = step.to().subtract(step.from());
                        float yaw = Math.hypot(delta.x(), delta.z()) > .000001 ? (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())) : value.yaw;
                        value.state.move(new WorldPosition(route.goal.world(), step.to())); index.upsert(value.state.snapshot());
                        value.poseRevision++; setPose(value, yaw, 0, step.onGround());
                        value.doors.passed(step.to(), moveWidth(value), host.doors());
                        route.progressTick = tickNumber;
                        routeStatus(route, "moving", moving(route.segment));
                        if (value.movement.arrived(step.to(), route.goal.point())) arrive(route);
                        else if (route.execution.finished()) executed(route);
                    }
                }
            }
        } catch (RejectedExecutionException full) { pathRefusals++; route.nextAttempt = tickNumber + 20; routeStatus(route, "queued", "path capacity"); }
        catch (RuntimeException failure) { if (value.route == route) failLeg(route, "failed: " + concise(failure)); else stopRoute(value, "failed: " + concise(failure)); }
        return routeCurrent(route) ? RouteScheduler.Result.KEEP : RouteScheduler.Result.DONE;
    }
    /** The status detail of a route walking a planned path; shared by every runtime. */
    public static String moving(boolean segment) {
        return segment ? "kinematic; live collision revalidation; long leg segment" : "kinematic; live collision revalidation";
    }
    /** A walked path ended: a segment of a long leg plans the next one from here, the last one arrives. */
    private void executed(Route route) {
        if (!route.segment) { arrive(route); return; }
        route.execution = null; route.segment = false;
        routeStatus(route, "queued", "next long leg segment");
    }
    /** Terrain changed in front of the body: plan again from here until the leg runs out of tries. */
    private void blockedStep(Route route, String reason) {
        if (route.longLeg.blocked() == LongLeg.Next.BLOCKED) { blockLeg(route, reason); return; }
        route.execution = null; route.segment = false;
        routeStatus(route, "replanning", reason);
    }
    /**
     * A walking body that stands in the air (its ground changed, or it was imported onto other terrain) falls one step per
     * turn before it plans a leg, so the leg starts on the ground instead of failing from the air. True while it falls.
     */
    private boolean fallBeforeLeg(Route route) {
        Value value = route.value;
        if (route.mode != MovementProfile.Mode.WALK || !value.falls || mountRuntime.vehicleOf(value.spec.definition().id()) != null
                || riders.riderOf(value.spec.definition().uuid()) != null) return false;
        int wanted = IdleFall.cells(moveWidth(value), moveHeight(value));
        if (motionCells < wanted && motionCells < navigationSettings.motionCellsPerTick()) return false;
        WorldPosition at = value.state.snapshot().position();
        boolean swims = BodyMobility.forBody(value.spec.type(), moveWidth(value), moveHeight(value)).supports(MovementProfile.Mode.SWIM);
        IdleFall.Step step = IdleFall.step(at.point(), value.fallSpeed, moveWidth(value), moveHeight(value), swims, host.collision(at.world()));
        motionCells -= step.sampled();
        value.fallSpeed = step.speed();
        boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
        if (falling || step.outcome() == IdleFall.Outcome.LANDED) {
            value.state.move(new WorldPosition(at.world(), step.to())); index.upsert(value.state.snapshot());
            value.poseRevision++; setPose(value, value.yaw, value.pitch, !falling);
        }
        if (falling) routeStatus(route, "falling", "to the ground before the leg starts");
        return falling;
    }
    /**
     * A block changed in a loaded chunk of this world, or is about to change in this tick. Idle walking NPCs over it check
     * their footing on a later tick and fall when nothing holds them up. Owner thread; reads at most four index buckets.
     */
    public void terrainChanged(String world, BlockPos at) {
        if (!available()) return;
        owner();
        for (UUID npc : IdleFall.standingOn(index, world, at)) {
            Entry entry = byUuid.get(npc); Value value = entry == null ? null : entry.value;
            if (value == null || value.supportQueued) continue;
            value.supportQueued = true; supportOrder.addLast(value);
        }
    }
    private boolean falls(Value value) {
        return valueCurrent(value) && value.state.snapshot().active() && value.route == null && value.routeTask == null
                && value.falls && mountRuntime.vehicleOf(value.spec.definition().id()) == null
                && riders.riderOf(value.spec.definition().uuid()) == null;
    }
    private void tickSupport() {
        int cells = SUPPORT_CELLS;
        for (int i = 0, count = Math.min(SUPPORT_UPDATES, supportOrder.size()); i < count && cells > 0 && available(); i++) {
            Value value = supportOrder.removeFirst();
            WorldPosition at = value.state.snapshot().position();
            if (!falls(value) || !host.worldLoaded(at.world())) { value.supportQueued = false; value.fallSpeed = 0; continue; }
            boolean swims = BodyMobility.forBody(value.spec.type(), moveWidth(value), moveHeight(value)).supports(MovementProfile.Mode.SWIM);
            IdleFall.Step step = IdleFall.step(at.point(), value.fallSpeed, moveWidth(value), moveHeight(value), swims, host.collision(at.world()));
            cells -= step.sampled();
            value.fallSpeed = step.speed();
            boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
            if (falling || step.outcome() == IdleFall.Outcome.LANDED) {
                value.state.move(new WorldPosition(at.world(), step.to())); index.upsert(value.state.snapshot());
                value.poseRevision++; setPose(value, value.yaw, value.pitch, !falling);
            }
            if (falling) supportOrder.addLast(value);
            else value.supportQueued = false;
        }
    }
    /** Arrival keeps doors the NPC still stands in only while another leg follows; a finished route closes them all. */
    private void arrive(Route route) {
        Value value = route.value;
        arriveAt(route);
        if (value.route == null) value.doors.closeAll(host.doors());
        else value.doors.passed(value.state.snapshot().position().point(), moveWidth(value), host.doors());
    }
    private void arriveAt(Route route) {
        Value value = route.value; if (!routeCurrent(route)) return;
        WaypointProgress progress = value.waypoints;
        value.route = null; routeStatus(route, "arrived", "");
        value.arrivals++;
        host.waypointReached(value.spec.definition().uuid(), value.spec.definition().id(), route.id, route.waypoint, value.state.snapshot().position());
        if (triggers != null) triggers.waypoint(value.spec.definition().uuid(), route.waypoint);
        if (progress == null) finishWatch(value);
        Entry entry = byUuid.get(value.spec.definition().uuid());
        if (!available() || entry == null || entry.value != value || value.route != null || value.waypoints != progress || progress == null
                || !value.state.snapshot().active() || !value.authority.accepts(runtime.npcs().snapshot().get(entry.id))) return;
        Optional<WaypointProgress.Leg> next = route.leg == null ? Optional.empty() : progress.arrived(route.leg, value.state.snapshot().position().point());
        if (next.isPresent()) beginLeg(value, next);
        else finishWatch(value);
    }
    static void setPose(PersistedNpcService<?, ?>.Value value, float yaw, float pitch, boolean onGround) {
        if (value.yaw != yaw || value.pitch != pitch || value.onGround != onGround) {
            value.yaw = yaw; value.pitch = pitch; value.onGround = onGround; value.poseRevision++;
        }
    }
    boolean valueCurrent(Value value) {
        Entry entry = byUuid.get(value.spec.definition().uuid());
        return available() && entry != null && entry.value == value && value.authority.accepts(runtime.npcs().snapshot().get(entry.id));
    }
    private void requestRouteRead(Value value) {
        if (value.routeSource == null || value.routeReadQueued || value.routeTask != null) return;
        // Each NPC queues at most once, so the queue never outgrows the active NPCs; reads start a few per tick.
        value.routeReadQueued = true; routeReadQueue.addLast(value);
        if (value.route == null) value.navigationStatus = "loading reusable route";
    }
    void cancelRouteRead(Value value) {
        routeReadQueue.remove(value); value.routeReadQueued = false;
        if (value.routeTask != null) { value.routeTask.cancelled = true; value.routeTask = null; }
    }
    private void tickRouteReads() {
        for (int i = 0; i < MAX_ROUTE_READS; i++) {
            RouteReadCompletion completion = routeReadCompletions.poll(); if (completion == null) break;
            RouteReadTask task = completion.task; routeReads.remove(task); Value value = task.value;
            boolean ownsTask = value.routeTask == task;
            if (ownsTask) value.routeTask = null;
            if (task.cancelled || !ownsTask || !valueCurrent(value)) continue;
            if (completion.error != null) {
                value.routeError = completion.error + "; last-valid-route-retained=" + (value.waypoints != null);
                failWatch(value, "unavailable: " + completion.error);
                if (value.route == null && value.waypoints == null) value.navigationStatus = "unavailable: reusable route";
                continue;
            }
            value.routeError = null;
            if (value.routeWatch != null && completion.settings.endless()) { value.navigationStatus = "invalid: a looping route never finishes"; failWatch(value, value.navigationStatus); continue; }
            if (value.waypoints != null && !value.waypoints.reload(completion.settings)) continue;
            stopRoute(value, "replaced by reusable route reload");
            if (value.waypoints == null) value.waypoints = progress(value, completion.settings);
            beginLeg(value, value.waypoints.start(value.state.snapshot().position().point()));
        }
        for (int i = 0; i < MAX_ROUTE_READS && routeReads.size() < MAX_ROUTE_READS && !routeReadQueue.isEmpty(); i++) {
            Value value = routeReadQueue.removeFirst(); value.routeReadQueued = false;
            if (!valueCurrent(value)) continue;
            RouteReadTask task = new RouteReadTask(value, value.routeSource, runtime.documents()); value.routeTask = task; routeReads.add(task);
            try { io.execute(task); }
            catch (RejectedExecutionException full) { routeReads.remove(task); value.routeTask = null; value.routeError = "I/O queue full; retry reload"; }
        }
    }
    public Optional<CombatBinding> combat(String id) {
        if (!available()) return Optional.empty();
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        if (!available() || value == null || !value.state.snapshot().active()) return Optional.empty();
        BooleanSupplier current = () -> {
            if (!available() || !runtime.isRunning() || entries.get(id) != entry || entry.value != value) return false;
            NpcDefinition desired = runtime.npcs().snapshot().get(id);
            return desired != null && desired.uuid().equals(value.spec.definition().uuid())
                    && (desired == value.spec.definition() || DefinitionSpec.spawned(desired));
        };
        return Optional.of(new CombatBinding(value.state, current, (VirtualNpcState.Snapshot snapshot) -> {
            owner();
            if (!current.getAsBoolean()) throw new IllegalStateException("NPC combat lease retired");
            if (!value.state.snapshot().equals(snapshot)) throw new IllegalArgumentException("Snapshot is not authoritative NPC state");
            VirtualNpcState.Snapshot before = index.find(snapshot.npcId()).orElse(null);
            boolean moved = before != null && !before.position().equals(snapshot.position());
            index.upsert(snapshot);
            if (triggers != null) triggers.changed(before, snapshot);
            if (!snapshot.active() || moved) stopRoute(value, "cancelled by combat lifecycle/position change");
            if (moved) value.poseRevision++;
            for (Viewer viewer : viewers.values()) {
                Presented p = viewer.visible.get(snapshot.npcId());
                if (p == null || p.value != value) continue;
                if (!snapshot.active()) retire(viewer, p);
                else if (p.bound) viewer.guard.updateSnapshot(p.body.entityId(), snapshot);
            }
        }));
    }

    private void tick() {
        if (closed) return;
        owner();
        adoptSettings();
        if (!available()) return;
        tickNumber++;
        long mark = metrics.start();
        try {
            // Completion work is bounded independently of connection count and packet acknowledgments.
            for (int i = 0; i < settings.packetsPerTick() * 2; i++) {
                Runnable completion = completions.poll(); if (completion == null) break;
                completionCount.decrementAndGet(); completion.run();
            }
            for (int i = 0; i < MAX_SKIN_READS; i++) {
                SkinCompletion completion = skinCompletions.poll(); if (completion == null) break;
                finishSkin(completion);
            }
            for (int i = 0; i < MAX_SKIN_READS; i++) {
                AppearanceCompletion completion = appearanceCompletions.poll(); if (completion == null) break;
                finishAppearance(completion);
            }
            tickRouteReads();
            boolean forced = refresh.getAndSet(false);
            Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
            if (snapshot.size() > MAX_DEFINITIONS) throw new IllegalStateException("Definition runtime limit exceeded: " + MAX_DEFINITIONS);
            definitions.publish(snapshot, forced);
            if (forced) { viewerFailures.clear(); admissions = host.onlinePlayers().iterator(); }
            int work = 0;
            while (work < RECONCILE_WORK) {
                if (changes.isEmpty()) {
                    DefinitionQueue.Batch batch = definitions.poll(RECONCILE_WORK - work); work += batch.examined();
                    changes.addAll(batch.changes());
                    if (changes.isEmpty() || work >= RECONCILE_WORK) break;
                }
                DefinitionQueue.Change change = changes.peekFirst();
                if (!definitions.current(change)) { changes.removeFirst(); continue; }
                if (!reconcile(change)) break;
                changes.removeFirst(); work++;
            }
            mountRuntime.resolve();
            mark = metrics.lap(Subsystem.RUNTIME, mark);
            riders.tick();
            tickNavigation();
            int cellLimit = navigationSettings.motionCellsPerTick();
            navigationUse += navigationPressure.lastDeferred() > 0 ? 100 : Math.min(100, Math.max(0, cellLimit - motionCells) * 100 / Math.max(1, cellLimit));
            tickSupport();
            mark = metrics.lap(Subsystem.NAVIGATION, mark);
            tickRigs();
            mark = metrics.lap(Subsystem.RIGS, mark);
            tickLooks();
            if (!available()) return; // Arrival listeners may disable the plugin or retire the entire runtime.
            mountRuntime.syncPassengers();
            mountRuntime.tickDragons();
            admit();
            mark = metrics.lap(Subsystem.PRESENTATION, mark);
            if (triggers != null) triggers.proximity(viewers.keySet(), this::viewerPosition);
            mark = metrics.lap(Subsystem.BEHAVIORS, mark);
            List<Viewer> updated = new ArrayList<>();
            int used = FairViewerWork.run(viewerOrder, settings.updatesPerTick(), settings.packetsPerTick(), this::current, (Viewer viewer, int remaining) -> {
                if (viewer.failed) { fail(viewer, "packet callbacks", null, "Packet callback queue full"); return 0; }
                if (!host.online(viewer.player)) { disconnect(viewer.player); return 0; }
                if (host.dead(viewer.player)) { disconnect(viewer.player); return 0; }
                if (!viewer.world.equals(host.position(viewer.player).world())) { disconnect(viewer.player); admissions = host.onlinePlayers().iterator(); return 0; }
                if (viewer.guard.issuedCount() >= MAX_ISSUED_IDS - settings.maximumVisible()) {
                    disconnect(viewer.player); admissions = host.onlinePlayers().iterator(); return 0;
                }
                updated.add(viewer);
                return update(viewer, remaining);
            });
            if (used >= settings.packetsPerTick()) pressure.shortOfBudget(tickNumber);
            // Cosmetic work spends only what every viewer's despawns, moves and spawns left over.
            if (pressure.allows(PresentationPressure.Tier.COSMETIC, tickNumber))
                for (Viewer viewer : updated) {
                    if (!current(viewer)) continue;
                    used += presentCosmetics(viewer, settings.packetsPerTick() - used);
                }
            metrics.lap(Subsystem.PRESENTATION, mark);
            packetUse += Math.min(100, used * 100 / Math.max(1, settings.packetsPerTick()));
            loadTicks++;
        } catch (RuntimeException failure) {
            failures.record("tick", "", failure);
            mountRuntime.close();
            // Capability failure must not leave clients able to interact with stale presentation.
            for (Viewer viewer : List.copyOf(viewers.values())) disconnect(viewer.player);
            unavailable = "unavailable after runtime failure: " + failures.last();
            if (triggers != null) triggers.failed("NPC runtime " + unavailable);
            closeQuietly(ticker); packets.close(); packets = null;
            if (navigation != null) navigation.close();
        }
    }

    private void warnActivation(String message) { host.warn(message); }
    private boolean reconcile(DefinitionQueue.Change change) {
        NpcDefinition definition = change.definition(); Entry entry = entries.get(change.id());
        if (!DefinitionSpec.spawned(definition)) { activationWarnings.cleared(change.id()); if (entry != null) remove(entry); return true; }
        if (entry == null) {
            if (entries.size() >= MAX_ACTIVE) { failures.record("activation", "", "Active definition capacity reached: " + MAX_ACTIVE); return true; }
            entry = new Entry(change.id()); entries.put(change.id(), entry);
        }
        if (entry.task != null && entry.task.spec.definition() != definition) { entry.task.cancelled = true; entry.task = null; }
        DefinitionSpec<I> spec;
        try { spec = DefinitionSpec.parse(definition, geometries.keySet(), this::world, host::item); }
        catch (IllegalArgumentException invalid) { entry.error = invalid.getMessage(); activationWarnings.failed(change.id(), entry.error); return true; }
        if (entry.value != null && entry.value.spec.serialized().equals(spec.serialized())) { entry.error = null; activationWarnings.cleared(change.id()); requestRouteRead(entry.value); return true; }
        if (entry.task != null) return true;
        if (spec.skin() == null) { activate(entry, spec, null); return true; }
        if (skinTasks.size() >= MAX_SKIN_READS) return false;
        SkinTask task = new SkinTask(entry, spec, runtime.documents()); entry.task = task; skinTasks.add(task);
        try { io.execute(task); }
        catch (RejectedExecutionException rejectedRead) { skinTasks.remove(task); entry.task = null; entry.error = "$.skin: I/O queue is full; retry reload"; }
        return true;
    }
    private void finishSkin(SkinCompletion completion) {
        SkinTask task = completion.task; skinTasks.remove(task);
        if (closed || task.cancelled || entries.get(task.entry.id) != task.entry || task.entry.task != task
                || runtime.npcs().snapshot().get(task.entry.id) != task.spec.definition()) return;
        task.entry.task = null;
        if (completion.error != null) { task.entry.error = "$.skin: " + completion.error; activationWarnings.failed(task.entry.id, task.entry.error); return; }
        activate(task.entry, task.spec, completion.skin);
    }
    private void activate(Entry entry, DefinitionSpec<I> spec, BodySkin skin) {
        Geometry geometry = geometries.get(spec.type());
        Value previous = entry.value;
        VirtualNpcState state;
        if (previous != null && previous.spec.definition().uuid().equals(spec.definition().uuid())) {
            WorldPosition next = previous.spec.position().equals(spec.position()) ? previous.state.snapshot().position() : spec.position();
            state = previous.state.reconfigured(next, geometry.width, geometry.height, maximumHealth(spec));
        } else {
            state = new VirtualNpcState(spec.definition().uuid(), spec.position(), geometry.width, geometry.height, maximumHealth(spec));
            state.spawn();
        }
        boolean sameNpc = previous != null && previous.spec.definition().uuid().equals(spec.definition().uuid());
        if (previous != null) removeValue(entry);
        if (previous != null && !sameNpc && triggers != null) triggers.removed(previous.spec.definition().uuid());
        entry.value = new Value(spec, skin, state, geometry.ids, geometry.seat); entry.error = null; activationWarnings.cleared(entry.id);
        byUuid.put(spec.definition().uuid(), entry); index.upsert(state.snapshot());
        mountRuntime.activated(entry.value);
        if (entry.value.rigId != null) { bindRig(entry.value); rigOrder.addLast(entry.value); }
        if (entry.value.lookClose != null) lookOrder.addLast(entry.value);
        if (!sameNpc && triggers != null) triggers.activated(spec.definition().uuid());
        Value created = entry.value;
        created.waypoints = WaypointSettings.parse(spec.definition()).map((WaypointSettings declared) -> progress(created, declared)).orElse(null);
        if (created.waypoints != null) beginLeg(created, created.waypoints.start(created.state.snapshot().position().point()));
        else requestRouteRead(entry.value);
    }
    public static double maximumHealth(DefinitionSpec<?> spec) {
        return CombatSettings.active(spec.definition()).map((CombatSettings combat) -> combat.defense().maximumHealth()).orElse(20.0);
    }
    private void remove(Entry entry) {
        if (entry.task != null) { entry.task.cancelled = true; entry.task = null; }
        if (entry.value != null) {
            UUID uuid = entry.value.spec.definition().uuid();
            removeValue(entry);
            if (triggers != null) triggers.removed(uuid);
        }
        entries.remove(entry.id);
    }
    private void removeValue(Entry entry) {
        Value old = entry.value;
        riders.vehicleGone(old.spec.definition().uuid(), "definition-changed");
        entry.value = null;
        mountRuntime.removed(old);
        stopRoute(old, "cancelled by definition replacement/despawn");
        failWatch(old, "cancelled by definition replacement/despawn");
        cancelRouteRead(old);
        cancelAppearanceRead(old);
        rigOrder.remove(old);
        lookOrder.remove(old);
        UUID uuid = old.spec.definition().uuid(); byUuid.remove(uuid, entry); index.remove(uuid); old.state.despawn();
        for (Viewer viewer : viewers.values()) {
            Presented body = viewer.visible.get(uuid);
            if (body != null && body.value == old) retire(viewer, body);
        }
    }

    private void admit() {
        for (int i = 0; i < settings.updatesPerTick() && viewers.size() < settings.maximumViewers(); i++) {
            P player;
            try { if (!admissions.hasNext()) return; player = admissions.next(); }
            catch (ConcurrentModificationException changed) { admissions = host.onlinePlayers().iterator(); return; }
            UUID id = host.id(player);
            if (!host.online(player) || host.dead(player) || viewers.containsKey(id) || viewerFailures.containsKey(id)) continue;
            Viewer viewer = new Viewer(player); viewers.put(id, viewer); viewerOrder.addLast(viewer);
            try {
                packets.watch(player, (BodyTransport.Interaction input) -> interact(viewer, input)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                    if (!current(viewer)) return;
                    if (failure == null) viewer.ready = true; else watchFailed(viewer, failure);
                }));
            } catch (RuntimeException failure) { watchFailed(viewer, failure); }
        }
    }
    private int update(Viewer viewer, int packetBudget) {
        int sent = 0;
        WorldPosition at = host.position(viewer.player);
        Map<Long, Boolean> delivered = new HashMap<>();
        Predicate<ChunkPosition> visibleChunk = (ChunkPosition chunk) -> chunk.world().equals(at.world()) && delivered.computeIfAbsent(ChunkPosition.key(chunk.x(), chunk.z()),
                (Long ignored) -> host.chunkLoaded(at.world(), chunk.x(), chunk.z()) && host.chunkSent(viewer.player, chunk.x(), chunk.z()));
        for (Presented body : List.copyOf(viewer.visible.values()))
            if (!eligible(viewer, body.value, at, visibleChunk)) retire(viewer, body);
        while (!viewer.retirements.isEmpty() && sent < packetBudget) {
            Presented body = viewer.retirements.removeFirst(); sent++;
            packets.despawn(viewer.player, body.body).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                if (!current(viewer)) return;
                body.despawnFinished = true;
                if (failure != null) fail(viewer, "despawn", body.value, failure); else releaseRetired(viewer, body);
            }));
        }
        // Despawns still waiting: moves and spawns wait behind them, so this viewer is short of budget.
        if (!viewer.retirements.isEmpty()) { pressure.shortOfBudget(tickNumber); return sent; }
        // One outstanding move per body; rotating the body queue prevents a busy subset starving other updates.
        if (viewer.ready) {
            for (int i = 0, count = viewer.presentationOrder.size(); i < count && sent < packetBudget; i++) {
                Presented body = viewer.presentationOrder.removeFirst();
                if (body.retired) continue;
                viewer.presentationOrder.addLast(body);
                if (rigReplaced(body)) { retire(viewer, body); continue; }
                if (body.spawnFinished && !body.moving && body.deliveredPoseRevision != body.value.poseRevision) {
                    move(viewer, body); sent++;
                    if (!current(viewer)) return sent;
                }
                if (body.spawnFinished && !body.equipping && sent < packetBudget && body.deliveredEquipmentRevision != body.value.equipmentRevision) {
                    equip(viewer, body); sent++;
                    if (!current(viewer)) return sent;
                }
            }
            sent += mountRuntime.update(viewer, packetBudget - sent);
        }
        if (viewer.ready && sent < packetBudget)
            sent += viewer.preview.update(viewer.player, packets, at, settings.viewDistance(), visibleChunk,
                    (NpcDefinition definition) -> DefinitionSpec.parse(definition, geometries.keySet(), this::world, host::item),
                    (Runnable completion) -> enqueue(viewer, () -> { if (current(viewer)) completion.run(); }), viewer.nativeIds, packetBudget - sent);
        if (!viewer.ready || !viewer.retirements.isEmpty() || sent >= packetBudget || viewer.pendingSpawns >= settings.pendingSpawnsPerViewer()
                || viewer.visible.size() >= settings.maximumVisible()) return sent;
        int capacity = Math.min(Math.min(8, packetBudget - sent), Math.min(settings.maximumVisible() - viewer.visible.size(), settings.pendingSpawnsPerViewer() - viewer.pendingSpawns));
        SpatialNpcIndex.Scan scan = index.scan(new SpatialNpcIndex.Query(at, settings.viewDistance()), viewer.cursor, new SpatialNpcIndex.Budget(32, Math.max(1, capacity * 2)), visibleChunk);
        viewer.cursor = scan.next().orElse(null);
        for (VirtualNpcState.Snapshot snapshot : scan.candidates()) {
            if (capacity == 0) break;
            Entry entry = byUuid.get(snapshot.npcId()); Value value = entry == null ? null : entry.value;
            if (value == null || viewer.visible.containsKey(snapshot.npcId()) || viewer.retiring.containsKey(snapshot.npcId())
                    || viewer.nativeIds + viewer.preview.reservedIds() + nativeIds(value) > 256 || !eligible(viewer, value, at, visibleChunk)) continue;
            spawn(viewer, value); sent++; capacity--;
            if (!current(viewer)) break;
        }
        return sent;
    }
    /** A live NPC a preview stands in for stays hidden from that viewer only. */
    private boolean eligible(Viewer viewer, Value value, WorldPosition at, Predicate<ChunkPosition> delivered) {
        Entry entry = byUuid.get(value.spec.definition().uuid());
        VirtualNpcState.Snapshot snapshot = value.state.snapshot();
        return entry != null && entry.value == value && snapshot.active() && !previews.hides(viewer.id, entry.id, value.spec.definition().uuid())
                && snapshot.position().world().equals(at.world())
                && snapshot.position().point().distance(at.point()) <= settings.viewDistance() && delivered.test(snapshot.position().chunk());
    }
    private void spawn(Viewer viewer, Value value) {
        try {
            BodyTransport.Body body = NpcPresentation.body(packets, value.spec, value.displayName, value.displaySkin);
            Presented presented = new Presented(value, body); viewer.visible.put(body.uuid(), presented);
            viewer.presentationOrder.addLast(presented); viewer.rigOrder.addLast(presented);
            viewer.nativeIds += body.entityIds().length; viewer.pendingSpawns++;
            presented.deliveredPoseRevision = value.poseRevision;
            BodyPose pose = pose(value);
            packets.spawn(viewer.player, body, pose).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                if (!current(viewer)) return;
                presented.spawnFinished = true; viewer.pendingSpawns--;
                if (presented.retired) { releaseRetired(viewer, presented); return; }
                if (failure != null) { fail(viewer, "spawn", value, failure); return; }
                if (!eligibleNow(viewer, value)) { retire(viewer, presented); return; }
                viewer.rosterDirty = true;
                try { viewer.guard.bind(body.entityId(), value.state.snapshot()); presented.bound = true; }
                catch (RuntimeException invalid) { fail(viewer, "spawn", value, invalid); }
            }));
        } catch (RuntimeException failure) { fail(viewer, "spawn", value, failure); }
    }
    /** Native IDs a body of this NPC reserves, including its text display and seat. */
    private int nativeIds(Value value) {
        return NpcPresentation.nativeIds(packets, value.spec, value.displayName, value.ids);
    }
    private static BodyPose pose(PersistedNpcService<?, ?>.Value value) {
        WorldPosition position = value.state.snapshot().position(); Vec3 p = position.point();
        return new BodyPose(position.world(), p.x(), p.y(), p.z(), value.yaw, value.pitch, value.onGround);
    }
    private void move(Viewer viewer, Presented body) {
        long revision = body.value.poseRevision;
        body.moving = true;
        try {
            packets.move(viewer.player, body.body, pose(body.value)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                if (!current(viewer) || body.retired || viewer.visible.get(body.body.uuid()) != body) return;
                body.moving = false;
                if (failure != null) { fail(viewer, "move", body.value, failure); return; }
                if (!eligibleNow(viewer, body.value)) { retire(viewer, body); return; }
                body.deliveredPoseRevision = revision;
                if (body.bound) viewer.guard.updateSnapshot(body.body.entityId(), body.value.state.snapshot());
            }));
        } catch (RuntimeException failure) { fail(viewer, "move", body.value, failure); }
    }
    /** Cosmetic: a failed send is recorded and not retried, and never costs the viewer their session. */
    private void equip(Viewer viewer, Presented body) {
        long revision = body.value.equipmentRevision;
        body.equipping = true;
        try {
            packets.equipment(viewer.player, body.body, Map.copyOf(body.value.equipment)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                body.equipping = false;
                body.deliveredEquipmentRevision = revision;
                if (failure != null && current(viewer) && !body.retired) cosmeticFailed(viewer, "equipment", body.value, failure);
            }));
        } catch (RuntimeException failure) {
            body.equipping = false;
            body.deliveredEquipmentRevision = revision;
            cosmeticFailed(viewer, "equipment", body.value, failure);
        }
    }
    private boolean eligibleNow(Viewer viewer, Value value) {
        if (!current(viewer) || !host.online(viewer.player) || host.dead(viewer.player)) return false;
        WorldPosition at = host.position(viewer.player);
        return eligible(viewer, value, at, (ChunkPosition chunk) -> host.chunkLoaded(at.world(), chunk.x(), chunk.z()) && host.chunkSent(viewer.player, chunk.x(), chunk.z()));
    }
    private void retire(Viewer viewer, Presented body) {
        if (body.retired) return;
        body.retired = true; viewer.visible.remove(body.body.uuid(), body); viewer.guard.unbind(body.body.entityId());
        if (body.bones != null) { viewer.bones -= body.bones.count(); body.bones = null; }
        viewer.rosterDirty = true;
        viewer.presentationOrder.remove(body); viewer.rigOrder.remove(body);
        viewer.retiring.put(body.body.uuid(), body); viewer.retirements.addLast(body);
    }
    private void releaseRetired(Viewer viewer, Presented body) {
        if (body.spawnFinished && body.despawnFinished && viewer.retiring.remove(body.body.uuid(), body)) viewer.nativeIds -= body.body.entityIds().length;
    }

    private void interact(Viewer viewer, BodyTransport.Interaction input) {
        if (!current(viewer) || host.dead(viewer.player)) return;
        // Preview bodies are inert: no trigger, listener, selection, GUI, shop, ride or damage.
        if (viewer.preview.owns(input.body().uuid())) return;
        Presented body = viewer.visible.get(input.body().uuid());
        if (body == null || body.body != input.body() || !body.bound || body.retired || !eligibleNow(viewer, body.value)) { rejected++; return; }
        // A command publication can precede the bounded reconciliation pass: deletion/despawn still revokes hits immediately.
        if (!DefinitionSpec.spawned(runtime.npcs().snapshot().get(body.value.spec.definition().id()))) { rejected++; retire(viewer, body); return; }
        // Transport acknowledgment is presentation bookkeeping; hits always use the current authoritative position.
        viewer.guard.updateSnapshot(body.body.entityId(), body.value.state.snapshot());
        Vec3 eye = host.eye(viewer.player), direction = host.direction(viewer.player);
        String world = host.position(viewer.player).world();
        ViewerSession.HitShape shape = body.value.spec.type().equals(DragonBody.TYPE) ? DragonBody.hitShape(body.value.yaw) : ViewerSession.HitShape.BOUNDS;
        ViewerSession.Validation validated = viewer.guard.validate(new ViewerSession.Request(host.id(viewer.player), viewer.guard.sessionId(), input.body().entityId(),
                new WorldPosition(world, eye), direction, System.nanoTime()), (WorldPosition from, Vec3 to, double distance) -> {
            int minX = ((int) Math.floor(Math.min(eye.x(), to.x()))) >> 4, maxX = ((int) Math.floor(Math.max(eye.x(), to.x()))) >> 4;
            int minZ = ((int) Math.floor(Math.min(eye.z(), to.z()))) >> 4, maxZ = ((int) Math.floor(Math.max(eye.z(), to.z()))) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!host.chunkLoaded(world, x, z)) return false;
            return distance <= 0.000001 || host.clearSight(viewer.player, eye, direction, Math.max(0, distance - 0.0001));
        }, shape);
        if (!validated.accepted()) { rejected++; return; }
        RuntimeHost.Interaction action = input.action() == BodyTransport.Action.ATTACK ? RuntimeHost.Interaction.ATTACK : RuntimeHost.Interaction.INTERACT;
        boolean allowed = host.interactionAllowed(viewer.player, body.body.uuid(), action, validated.distance());
        if (!allowed || !current(viewer) || host.dead(viewer.player) || viewer.visible.get(body.body.uuid()) != body || !eligibleNow(viewer, body.value)
                || body.value.state.snapshot().revision() != validated.npc().revision()
                || !DefinitionSpec.spawned(runtime.npcs().snapshot().get(body.value.spec.definition().id()))) return;
        accepted++;
        if (triggers != null) triggers.interaction(body.body.uuid(), host.id(viewer.player),
                action == RuntimeHost.Interaction.ATTACK ? TriggerEvent.Action.ATTACK : TriggerEvent.Action.INTERACT, false);
        InteractionObserver observed = observer;
        if (observed != null) observed.accepted(body.body.uuid(), host.id(viewer.player), action == RuntimeHost.Interaction.ATTACK, validated.distance(), false);
        InteractionListener<P> listener = interactions;
        if (listener != null && listener.interacted(viewer.player, body.body.uuid(), action == RuntimeHost.Interaction.ATTACK, input.secondaryAction())) return;
        // A sneaking interaction keeps its administrative meaning, so a rideable NPC can still be selected.
        if (body.value.ride != null && action == RuntimeHost.Interaction.INTERACT && !input.secondaryAction() && "MAIN_HAND".equals(input.hand())) {
            String refusal = riders.request(viewer.player, mountRuntime.vehicle(body.value));
            if (refusal != null) host.tell(viewer.player, "Cannot ride " + body.value.spec.name() + ": " + refusal);
            return;
        }
        if (host.permitted(viewer.player, "animus.admin")) {
            select(host.id(viewer.player), body.value.spec.definition().id());
            host.tell(viewer.player, "Selected " + body.value.spec.definition().id() + " via " + action);
        }
    }

    /** Cosmetic work of one viewer failed: an error is recorded and the viewer keeps its session. True when the watch had ended and the viewer was dropped. */
    boolean cosmeticFailed(Viewer viewer, String operation, Value value, Throwable failure) {
        if (RuntimeFailures.viewerGone(failure)) { if (current(viewer)) lost(viewer); return true; }
        failures.record(operation, context(viewer, value), failure);
        return false;
    }
    double playerWidth() { return geometries.get(art.arcane.animus.core.presentation.BodyRules.PLAYER).width(); }
    double playerHeight() { return geometries.get(art.arcane.animus.core.presentation.BodyRules.PLAYER).height(); }
    SeatGeometry playerSeat() { return geometries.get(art.arcane.animus.core.presentation.BodyRules.PLAYER).seat(); }
    void enqueue(Viewer viewer, Runnable action) {
        if (closed) return;
        if (completionCount.incrementAndGet() > completionCapacity) { completionCount.decrementAndGet(); viewer.failed = true; return; }
        completions.add(action);
    }
    private Optional<WorldPosition> viewerPosition(UUID id) {
        Viewer viewer = viewers.get(id);
        return viewer == null || !host.online(viewer.player) || host.dead(viewer.player) ? Optional.empty() : Optional.of(host.position(viewer.player));
    }
    boolean current(Viewer viewer) { return !closed && viewers.get(host.id(viewer.player)) == viewer; }
    private String context(Viewer viewer, Value value) {
        return "viewer=" + host.name(viewer.player) + (value == null ? "" : ", npc=" + value.spec.definition().id());
    }
    /** Body work of one viewer failed. A watch that ended (the connection closed or was re-watched) is not an error: the viewer is dropped and watched again. */
    private void fail(Viewer viewer, String operation, Value value, Throwable failure) {
        if (!current(viewer)) return;
        if (RuntimeFailures.viewerGone(failure)) { lost(viewer); return; }
        fail(viewer, operation, value, concise(failure));
    }
    private void fail(Viewer viewer, String operation, Value value, String error) {
        if (!current(viewer)) return;
        failures.record(operation, context(viewer, value), error);
        if (viewerFailures.size() < settings.maximumViewers()) viewerFailures.put(host.id(viewer.player), error);
        disconnect(viewer.player);
    }
    /** The transport could not watch the viewer. A closed connection waits quietly for the quit, rejoin, teleport or respawn that follows it. */
    private void watchFailed(Viewer viewer, Throwable failure) {
        if (!current(viewer)) return;
        if (!RuntimeFailures.viewerGone(failure)) { fail(viewer, "watch", null, failure); return; }
        if (viewerFailures.size() < settings.maximumViewers()) viewerFailures.put(host.id(viewer.player), "connection closed");
        disconnect(viewer.player);
    }
    /** Drops a viewer whose watch ended without counting it as a failure; the next admission watches it again. */
    private void lost(Viewer viewer) { disconnect(viewer.player); admissions = host.onlinePlayers().iterator(); }
    private void disconnect(P player) {
        if (riders != null) riders.viewerLost(player);
        Viewer old = viewers.remove(host.id(player));
        if (old != null) { old.guard.close(); viewerOrder.remove(old); old.visible.clear(); old.retiring.clear(); old.retirements.clear(); old.presentationOrder.clear(); old.rigOrder.clear(); }
        if (packets != null) packets.forget(player);
    }
    private String world(String reference) { return host.resolveWorld(reference).orElse(null); }
    public static String concise(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message.substring(0, Math.min(256, message.length())));
    }
    private void owner() { if (!host.ownerThread()) throw new IllegalStateException("NPC runtime requires the owner thread"); }
    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) return;
        try { handle.close(); } catch (Exception ignored) { /* The tick checks availability first. */ }
    }

    /** A player joined. Call on the owner thread. */
    public void joined(P player) { if (available()) admissions = host.onlinePlayers().iterator(); }
    /** A player left the server. */
    public void quit(P player) {
        UUID id = host.id(player);
        previews.left(id);
        if (!available()) return;
        riders.playerLeaving(player, "offline");
        disconnect(player); selections.remove(id);
        if (triggers != null) triggers.forget(id);
        viewerFailures.remove(id);
        admissions = host.onlinePlayers().iterator();
    }
    /** An accepted teleport. The dismount teleport keeps the world and nearby presentation. */
    public void teleported(P player) {
        if (!available()) return;
        // Rebuilding presentation after the dismount teleport would flicker every body.
        if (riders.restoring()) return;
        riders.playerLeaving(player, "teleport");
        reset(player);
    }
    public void changedWorld(P player) { if (available()) { riders.playerLeaving(player, "world-changed"); reset(player); } }
    public void respawned(P player) { if (available()) { riders.playerLeaving(player, "respawn"); reset(player); } }
    public void died(P player) { if (available()) riders.playerLeaving(player, "death"); }
    public void worldLoaded() { if (available()) refresh(); }
    public void worldUnloaded(String world) {
        if (!available()) return;
        for (Route route : routeOrder.routes()) if (route.goal.world().equals(world))
            stopRoute(route.value, "cancelled: world unloaded");
        refresh();
    }
    private void reset(P player) { disconnect(player); viewerFailures.remove(host.id(player)); admissions = host.onlinePlayers().iterator(); }
    @Override public void close() {
        if (closed) return;
        if (mountRuntime != null) mountRuntime.close();
        closed = true;
        closeQuietly(settingsSubscription);
        previews.close();
        closeQuietly(ticker);
        for (Route route : routeOrder.routes()) stopRoute(route.value, "cancelled: runtime shutdown");
        routeOrder.clear(); supportOrder.clear(); rigOrder.clear(); lookOrder.clear(); if (navigation != null) navigation.close();
        for (RouteReadTask task : routeReads) task.cancelled = true;
        routeReads.clear(); routeReadQueue.clear(); routeReadCompletions.clear();
        for (SkinTask task : skinTasks) task.cancelled = true;
        skinTasks.clear(); skinCompletions.clear(); clearCompletions();
        for (AppearanceTask task : appearanceTasks) task.cancelled = true;
        appearanceTasks.clear(); appearanceCompletions.clear();
        viewers.values().forEach((Viewer viewer) -> viewer.guard.close()); viewers.clear(); viewerOrder.clear();
        entries.values().forEach((Entry entry) -> { if (entry.value != null) { failWatch(entry.value, "cancelled: runtime shutdown"); entry.value.state.despawn(); index.remove(entry.value.spec.definition().uuid()); } });
        entries.clear(); byUuid.clear(); selections.clear(); viewerFailures.clear(); changes.clear(); definitions.clear();
        if (packets != null) packets.close();
    }

    final class Entry {
        final String id;
        Value value; SkinTask task; String error;
        Entry(String id) { this.id = id; }
    }
    final class Value {
        final DefinitionSpec<I> spec; final BodySkin skin; final VirtualNpcState state; final int ids;
        final DefinitionAuthority authority;
        float yaw, pitch; boolean onGround = true; long poseRevision, arrivals;
        Route route; WaypointProgress waypoints; String navigationStatus = "idle", routeError;
        /** The log already said terrain blocks one of this NPC's legs. */
        boolean blockedWarned;
        final String routeSource;
        RouteReadTask routeTask; boolean routeReadQueued;
        final MountReference mountReference; final RideSettings ride; final SeatGeometry seat;
        boolean supportQueued; double fallSpeed;
        final LookClose lookClose; long nextLook, nextRandomLook;
        final MovementSettings movement; final boolean falls, routeSpeedDeclared; final DoorPassage doors;
        final DragonMotion dragonMotion = new DragonMotion(); int dragonPhase = DragonBody.PHASE_HOVERING;
        CompletableFuture<Void> routeWatch;
        String displayName; BodySkin displaySkin; AppearanceTask appearanceTask;
        final Map<EquipmentSlot, I> equipment = new EnumMap<>(EquipmentSlot.class);
        long equipmentRevision;
        final String rigId; NpcRig<I> rig; String rigProblem; long rigGeneration;
        Value(DefinitionSpec<I> spec, BodySkin skin, VirtualNpcState state, int ids, SeatGeometry seat) {
            this.spec = spec; this.skin = skin; this.state = state; this.ids = ids; this.seat = seat; yaw = spec.yaw(); pitch = spec.pitch();
            displayName = spec.name(); displaySkin = skin;
            equipment.putAll(NpcPresentation.equipment(spec, host::copy)); equipmentRevision = equipment.isEmpty() ? 0 : 1;
            mountReference = MountDeclaration.parse(spec.definition().json()).orElse(null);
            ride = RideSettings.parse(spec.definition().json()).orElse(null);
            lookClose = LookClose.parse(spec.definition().json()).orElse(null);
            movement = MovementSettings.parse(spec.definition().json());
            doors = new DoorPassage(spec.definition().uuid());
            falls = MovementSettings.falls(spec.type(), spec.definition().json());
            routeSpeedDeclared = MovementSettings.routeDeclaresSpeed(spec.definition().json());
            routeSource = WaypointSettings.reference(spec.definition());
            authority = new DefinitionAuthority(spec.definition());
            rigId = RigReference.parse(spec.definition().json()).orElse(null);
        }
    }
    private final class Route {
        final UUID id = UUID.randomUUID(); final Value value; final WorldPosition goal;
        final MovementProfile.Mode mode; final double speed; final int waypoint; final WaypointProgress.Leg leg;
        final Vec3 origin; final LongLeg longLeg;
        NavigationPlan plan; CompletableFuture<PathPlanner.Result> result; PathExecution execution; CaptureProvenance capture;
        long nextAttempt, awakeChecked = Long.MIN_VALUE, progressTick;
        /** Segment: the path being walked ends part way along a long leg. */
        boolean awake = true, segment;
        Route(Value value, WorldPosition goal, MovementProfile.Mode mode, double speed, WaypointProgress.Leg leg) {
            this.value = value; this.goal = goal; this.mode = mode; this.speed = speed; this.leg = leg; this.waypoint = leg == null ? -1 : leg.arrival();
            progressTick = tickNumber;
            origin = value.state.snapshot().position().point();
            longLeg = new LongLeg(origin, goal.point(), moveWidth(value), moveHeight(value), value.movement.pathRange());
        }
    }
    final class Presented {
        final Value value; final BodyTransport.Body body;
        boolean bound, retired, spawnFinished, despawnFinished, moving, equipping;
        long deliveredPoseRevision, deliveredEquipmentRevision;
        List<Integer> sentRoster = List.of();
        int sentDragonPhase = DragonBody.PHASE_HOVERING;
        BodyTransport.Bones bones; RigView view; long rigIdentity; boolean bonesShown, rigFailed;
        Presented(Value value, BodyTransport.Body body) { this.value = value; this.body = body; }
    }
    final class Viewer {
        final P player; final UUID id; final String world; final ViewerSession guard; final PreviewBodies<P, I> preview;
        final Map<UUID, Presented> visible = new HashMap<>(), retiring = new HashMap<>();
        final Deque<Presented> retirements = new ArrayDeque<>();
        final Deque<Presented> presentationOrder = new ArrayDeque<>();
        /** The same bodies in their own rotation for cosmetic rig work, which runs after every viewer's authoritative work. */
        final Deque<Presented> rigOrder = new ArrayDeque<>();
        SpatialNpcIndex.Cursor cursor;
        int nativeIds, pendingSpawns, bones;
        boolean ready, rosterDirty; volatile boolean failed;
        long rosterRevision = -1;
        Viewer(P player) {
            this.player = player; id = host.id(player); world = host.position(player).world();
            preview = new PreviewBodies<>(previews, id);
            guard = new ViewerSession(host.id(player), UUID.randomUUID(), viewerLimits());
        }
    }
    private final class SkinCompletion {
        final SkinTask task; final BodySkin skin; final String error;
        SkinCompletion(SkinTask task, BodySkin skin, String error) { this.task = task; this.skin = skin; this.error = error; }
    }
    private final class RouteReadCompletion {
        final RouteReadTask task; final WaypointSettings settings; final String error;
        RouteReadCompletion(RouteReadTask task, WaypointSettings settings, String error) { this.task = task; this.settings = settings; this.error = error; }
    }
    private final class AppearanceCompletion {
        final AppearanceTask task; final BodySkin skin; final String error;
        AppearanceCompletion(AppearanceTask task, BodySkin skin, String error) { this.task = task; this.skin = skin; this.error = error; }
    }
    private final class AppearanceTask implements Runnable {
        final Value value; final String reference; final DocumentStore documents; volatile boolean cancelled;
        AppearanceTask(Value value, String reference, DocumentStore documents) { this.value = value; this.reference = reference; this.documents = documents; }
        @Override public void run() {
            BodySkin skin = null; String error = null;
            try { if (!cancelled && !closed) skin = SkinDocuments.read(documents, reference); }
            catch (Exception failure) { error = concise(failure); }
            if (!closed) appearanceCompletions.add(new AppearanceCompletion(this, skin, error));
        }
    }
    private final class RouteReadTask implements Runnable {
        final Value value; final String source; final DocumentStore documents; volatile boolean cancelled;
        RouteReadTask(Value value, String source, DocumentStore documents) { this.value = value; this.source = source; this.documents = documents; }
        @Override public void run() {
            WaypointSettings loaded = null; String error = null;
            try { if (!cancelled && !closed) loaded = RouteDocuments.read(documents, source); }
            catch (Exception failure) { error = concise(failure); }
            if (!closed) routeReadCompletions.add(new RouteReadCompletion(this, loaded, error));
        }
    }
    private final class SkinTask implements Runnable {
        final Entry entry; final DefinitionSpec<I> spec; final DocumentStore documents;
        volatile boolean cancelled;
        SkinTask(Entry entry, DefinitionSpec<I> spec, DocumentStore documents) { this.entry = entry; this.spec = spec; this.documents = documents; }
        @Override public void run() {
            BodySkin skin = null; String error = null;
            try {
                if (!cancelled && !closed) {
                    skin = SkinDocuments.read(documents, spec.skin());
                }
            } catch (Exception failure) { error = concise(failure); }
            // At most MAX_SKIN_READS tasks remain admitted until their completion is drained by the owner.
            if (!closed) skinCompletions.add(new SkinCompletion(this, skin, error));
        }
    }
}
