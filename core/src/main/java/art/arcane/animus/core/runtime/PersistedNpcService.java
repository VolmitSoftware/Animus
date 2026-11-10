package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.building.BuildNpcs;
import art.arcane.animus.core.config.NavigationSettings;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.config.RigSettings;
import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.DoorPassage;
import art.arcane.animus.core.navigation.FallTracker;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.MovementSettings;
import art.arcane.animus.core.navigation.OwnerNavigation;
import art.arcane.animus.core.navigation.StyledLeg;
import art.arcane.animus.core.navigation.HomeSettings;
import art.arcane.animus.core.navigation.RoutePause;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NamedPoses;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingRun;
import art.arcane.animus.core.recording.RecordingSource;
import art.arcane.animus.core.rig.NpcRig;
import art.arcane.animus.core.rig.RigReference;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.virtual.DragonMotion;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.ViewerSession;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Persisted virtual NPCs on a platform with one owner thread: Bukkit and Paper, and the mod loaders' server thread.
 * All world, presentation and movement authority belongs to that thread. The platform supplies a {@link RuntimeHost}
 * and forwards its player lifecycle events to the public event methods.
 * <p>
 * This class owns the NPC lifecycle (definitions, activation, quarantine) and the tick; routes, recordings, idle falls,
 * look-close, rigs, document reads and viewers each run in their own helper of this package.
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
public final class PersistedNpcService<P, I> implements NpcRuntime<P, I>, BuildNpcs<P, I> {
    public record Settings(boolean enabled, double viewDistance, int maximumVisible, int maximumViewers, int updatesPerTick,
                           int packetsPerTick, int pendingSpawnsPerViewer, int lookUpdatesPerTick,
                           int simulationPerTick, int queuePerViewer, int degradedIntervalTicks) {
        public static final Settings DEFAULT = from(NpcRuntimeSettings.DEFAULT);
        public Settings {
            if (!Double.isFinite(viewDistance) || viewDistance < 8 || viewDistance > 256 || maximumVisible < 1 || maximumVisible > 128
                    || maximumViewers < 1 || maximumViewers > 1024 || updatesPerTick < 1 || updatesPerTick > 64
                    || packetsPerTick < 1 || packetsPerTick > 1024 || pendingSpawnsPerViewer < 1 || pendingSpawnsPerViewer > 64
                    || lookUpdatesPerTick < 1 || lookUpdatesPerTick > 1024 || simulationPerTick < 16 || simulationPerTick > 65536
                    || queuePerViewer < 4 || queuePerViewer > BodyRules.MAX_OPERATIONS || degradedIntervalTicks < 1 || degradedIntervalTicks > 40)
                throw new IllegalArgumentException("Invalid persisted NPC runtime budgets");
        }
        /** The {@code [npcs]} table of config.toml. */
        public static Settings from(NpcRuntimeSettings config) {
            return new Settings(config.enabled(), config.viewDistance(), config.maximumVisible(), config.maximumViewers(), config.updatesPerTick(),
                    config.packetsPerTick(), config.pendingSpawnsPerViewer(), config.lookUpdatesPerTick(),
                    config.simulationPerTick(), config.queuePerViewer(), config.degradedIntervalTicks());
        }
    }
    /** Internal trusted integration lease; replacement/deletion revokes it. Access only on the owning scheduler. */
    public record CombatBinding(VirtualNpcState state, BooleanSupplier current, Consumer<VirtualNpcState.Snapshot> changed) {}
    private record Geometry(double width, double height, int ids, SeatGeometry seat) {}
    private static final int MAX_DEFINITIONS = 100_000;
    static final int MAX_ACTIVE = 10_000;
    private static final int RECONCILE_WORK = 64;
    static final int MAX_ISSUED_IDS = 8192;
    /** How long a body that died stays shown lying down, as a vanilla death does. */
    private static final int DYING_TICKS = 20;
    private static final String DISABLED = "disabled in config.toml";
    final RuntimeHost<P, I> host;
    final AnimusRuntime runtime;
    /** Owner thread only; a hotload replaces it at the start of a tick. */
    Settings settings;
    /** Settings a hotload published on the file lane, taken by the owner at the start of its next tick. */
    private final AtomicReference<PluginSettings> pendingSettings = new AtomicReference<>();
    private final AutoCloseable settingsSubscription;
    final ViewerPreviews previews;
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    private final DefinitionQueue definitions = new DefinitionQueue();
    private final ActivationWarnings activationWarnings = new ActivationWarnings(this::warnActivation);
    private final Deque<DefinitionQueue.Change> changes = new ArrayDeque<>();
    final Map<String, Entry> entries = new HashMap<>();
    final Map<UUID, Entry> byUuid = new HashMap<>();
    /** Values that show a speech bubble, checked each tick for its end. */
    private final java.util.Set<Value> bubbles = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    final SpatialNpcIndex index = new SpatialNpcIndex(MAX_ACTIVE);
    private final Map<String, Geometry> geometries = new HashMap<>();
    private final Map<UUID, String> selections = new HashMap<>();
    /** Owner thread only; replaced by a hotload at the start of a tick. */
    NavigationSettings navigationSettings;
    RigSettings rigSettings;
    RecordingSettings recordingSettings;
    /** What routes, recordings, falls and rigs may still spend this tick. */
    final TickBudget budget = new TickBudget();
    final ViewerPresenter<P, I> views;
    final RouteNavigator<P, I> routes;
    final RecordingPlayer<P, I> recordings;
    final DocumentReads<P, I> reads;
    final IdleFalls<P, I> falls;
    private final LookTurns<P, I> looks;
    private final HomeTurns<P, I> homes;
    private final RigTurns<P, I> rigs;
    private final RuntimeReport<P, I> report;
    MountRuntime<P, I> mountRuntime;
    RiderService<P, I> riders;
    volatile BodyTransport<P, I> packets;
    OwnerNavigation navigation;
    private AutoCloseable ticker;
    TriggerBridge triggers;
    /** Told the NPC's UUID and the fall damage, on the owner, when an idle fall lands hard enough to hurt. */
    volatile BiConsumer<UUID, Double> landings = (UUID npc, Double damage) -> {};
    volatile NpcRig.Events animationEvents;
    volatile InteractionListener<P> interactions;
    volatile InteractionObserver observer;
    private volatile String unavailable;
    final RuntimeFailures failures;
    volatile boolean closed;
    long tickNumber;
    final PresentationPressure pressure = new PresentationPressure();
    /** The metrics of the server start that built this runtime. */
    final AnimusMetrics metrics = AnimusMetrics.current();
    private final RuntimePlayers<P, I> players;

    /**
     * Construct on the owner thread. A disabled or unbindable runtime stays unavailable with its reason; a disabled one
     * still ticks so a hotload can enable it.
     */
    public PersistedNpcService(RuntimeHost<P, I> host, AnimusRuntime runtime, Executor io, Settings settings) {
        this.host = Objects.requireNonNull(host); this.runtime = Objects.requireNonNull(runtime);
        Objects.requireNonNull(io); this.settings = Objects.requireNonNull(settings);
        failures = new RuntimeFailures(host::warn);
        players = new RuntimePlayers<>(this, host);
        previews = new ViewerPreviews(io, runtime.documents());
        views = new ViewerPresenter<>(this);
        views.limits(settings);
        pressure.interval(settings.degradedIntervalTicks());
        rigSettings = runtime.isRunning() ? runtime.rigSettings() : RigSettings.DEFAULT;
        recordingSettings = runtime.isRunning() ? runtime.recordingSettings() : RecordingSettings.DEFAULT;
        navigationSettings = runtime.isRunning() ? runtime.navigationSettings() : NavigationSettings.DEFAULT;
        routes = new RouteNavigator<>(this, navigationSettings);
        recordings = new RecordingPlayer<>(this);
        reads = new DocumentReads<>(this, io);
        falls = new IdleFalls<>(this);
        looks = new LookTurns<>(this);
        homes = new HomeTurns<>(this);
        rigs = new RigTurns<>(this);
        report = new RuntimeReport<>(this);
        owner();
        for (P online : host.onlinePlayers()) players.joined(host.id(online));
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
                // Displays have no size of their own; their NPCs still need a hitbox to be placed and found.
                geometries.put(type, new Geometry(Math.max(BodyRules.MINIMUM_SIZE, body.width()), Math.max(BodyRules.MINIMUM_SIZE, body.height()), body.entityIds().length, body.seat()));
            }
            runtime.bodyTypes(packets.entityTypes());
            mountRuntime = new MountRuntime<>(this, packets.riderUnavailable());
            riders = mountRuntime.riders();
            views.readmit();
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

    /**
     * Swaps in the settings a hotload published, before the tick does any work. Budgets are read where they are spent,
     * so the tick that follows already uses them; caps that are below current usage only hold back new work.
     */
    private void adoptSettings() {
        PluginSettings next = pendingSettings.getAndSet(null);
        if (next == null) return;
        Settings previous = settings;
        settings = Settings.from(next.npcs());
        pressure.interval(settings.degradedIntervalTicks());
        views.limits(settings);
        navigationSettings = next.navigation();
        routes.order.budgets(navigationSettings.routesPerTick(), navigationSettings.wakeChecksPerTick());
        if (navigation != null) navigation.limits(navigationSettings.limits());
        if (rigSettings.enabled() != next.rigs().enabled()) rigs.toggled();
        rigSettings = next.rigs();
        recordingSettings = next.recordings();
        if (previous.maximumVisible() != settings.maximumVisible())
            for (ViewerPresenter<P, I>.Viewer viewer : views.viewers.values()) viewer.guard.limits(viewerLimits());
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
            views.forgetAll();
            for (Entry entry : List.copyOf(entries.values())) remove(entry);
            mountRuntime.close();
        }
        routes.order.clear(); falls.clear(); rigs.clear(); looks.clear(); homes.clear();
        reads.cancelAll();
        views.forgetPending();
        entries.clear(); byUuid.clear(); selections.clear(); changes.clear(); definitions.clear();
        if (navigation != null) navigation.close();
        navigation = null;
        if (packets != null) packets.close();
        packets = null;
        unavailable = DISABLED;
    }
    ViewerSession.Limits viewerLimits() { return new ViewerSession.Limits(settings.maximumVisible(), MAX_ISSUED_IDS, 100_000_000L, 4.5); }
    /** Why the runtime cannot serve a request now. */
    private String down() { return "NPC runtime " + (closed ? "closed" : unavailable); }
    /** The NPC's current value, or null when it is not in the runtime. */
    private Value value(String id) { Entry entry = entries.get(id); return entry == null ? null : entry.value; }
    DefinitionSpec<I> parse(NpcDefinition definition) { return DefinitionSpec.parse(definition, geometries.keySet(), this::world, host::item); }

    @Override public boolean available() { return !closed && packets != null; }
    /** Connect during startup, before the first runtime tick can activate an NPC. */
    @Override public void interactions(InteractionListener<P> listener) { interactions = listener; }
    @Override public void observeInteractions(InteractionObserver observer) { this.observer = observer; }
    /** Who hears of falls that hurt, as the combat module applies them. */
    public void onLanding(BiConsumer<UUID, Double> listener) { landings = Objects.requireNonNull(listener); }
    @Override public void bindTriggers(TriggerDispatcher dispatcher) { owner(); dispatcher.hide(hidden()); triggers = new TriggerBridge(dispatcher, index); }
    @Override public void animationEvents(NpcRig.Events events) { animationEvents = events; }
    @Override public String status() {
        if (!available()) return down();
        owner();
        return report.status();
    }
    @Override public boolean cosmeticAllowed() { return !available() || pressure.cosmetic(tickNumber, tickNumber); }
    /** Owner only: counts for the metrics roll, which runs on this owner's tick thread. */
    RuntimeGauges gauges() {
        if (!available()) return RuntimeGauges.NONE;
        owner();
        return report.gauges();
    }
    @Override public String inspect(String id) {
        if (!available()) return "Runtime " + id + ": " + (closed ? "closed" : unavailable);
        owner();
        return report.inspect(id);
    }
    @Override public List<String> validateActivation(NpcDefinition definition) {
        if (!available()) return List.of(down());
        owner(); Objects.requireNonNull(definition);
        List<String> errors = new ArrayList<>(DefinitionSpec.validate(definition, geometries.keySet(), this::world, host::item));
        errors.addAll(mountRuntime.activationBlockers(definition));
        return List.copyOf(errors);
    }
    @Override public String rideStatus() {
        if (!available()) return "Riding unavailable: " + down();
        owner();
        return mountRuntime.rideStatus();
    }
    @Override public boolean stopRider(String playerName) {
        if (!available()) throw new IllegalStateException(down());
        owner();
        return riders.stop(playerName, "stopped by administrator");
    }
    @Override public Optional<String> selection(UUID viewer) {
        if (!available()) return Optional.empty();
        owner(); String id = selections.get(viewer);
        return id != null && runtime.npcs().snapshot().containsKey(id) ? Optional.of(id) : Optional.empty();
    }
    @Override public void select(UUID viewer, String id) {
        if (!available()) throw new IllegalStateException(down());
        owner();
        if (!runtime.npcs().snapshot().containsKey(id)) throw new IllegalArgumentException("Unknown NPC: " + id);
        if (!selections.containsKey(viewer) && selections.size() >= settings.maximumViewers()) throw new IllegalStateException("Selection capacity reached; raise [npcs] maximumViewers in config.toml");
        selections.put(viewer, id);
    }
    /** On the owner thread: the looked-at or nearest NPC this player sees. */
    @Override public Optional<String> pick(UUID id) { return views.pick(id); }
    /** On the owner thread: viewer-private glowing metadata, ended by the tick after {@code ticks}. */
    @Override public boolean highlight(UUID id, String npc, int ticks) { return views.highlight(id, npc, ticks); }
    @Override public void refresh() { refresh.set(true); }
    /** One owner: work already on the owner thread runs now; anything else is scheduled there. */
    @Override public boolean onOwner(UUID npc, Runnable work, Consumer<String> refused) { return onMain(work, refused); }
    @Override public boolean onOwner(String id, Runnable work, Consumer<String> refused) { return onMain(work, refused); }
    private boolean onMain(Runnable work, Consumer<String> refused) {
        if (!available()) return false;
        if (host.ownerThread()) { work.run(); return true; }
        try { return host.execute(() -> { if (available()) work.run(); else refused.accept(down()); }); }
        catch (RuntimeException rejectedWork) { return false; }
    }
    @Override public PlayerAccess<P> players() { return players; }
    /** Owner only: online players near a point, from an index rebuilt at most once per tick. */
    List<P> playersNear(WorldPosition at, double radius, int limit) { return players.near(at, radius, limit); }
    @Override public ViewerPreviews previews() { return previews; }
    /** Previews, plugin hiding and each NPC's viewers filter, as one check for speech, sounds, particles, triggers and GUIs. */
    @Override public art.arcane.animus.core.behavior.trigger.HiddenNpcs hidden() { return this::hiddenFrom; }
    private boolean hiddenFrom(UUID viewer, String id, UUID npc) { return previews.hides(viewer, id, npc) || filtered(viewer, id, npc); }
    /**
     * The NPC's viewers filter hides it from this online player. The filter comes from the published definitions, so any
     * thread may ask; a filter with a range measures the distance on the owner and applies in full elsewhere.
     */
    private boolean filtered(UUID viewer, String id, UUID npc) {
        NpcDefinition definition = runtime.npcs().snapshot().get(id);
        if (definition == null || !definition.uuid().equals(npc) || definition.viewers() == art.arcane.animus.core.npc.ViewerFilter.NONE) return false;
        Optional<P> online = host.player(viewer);
        if (online.isEmpty()) return false;
        P player = online.get();
        double distance = 0;
        if (definition.viewers().range().isPresent() && host.ownerThread()) {
            Entry entry = byUuid.get(npc);
            WorldPosition at = entry == null || entry.value == null ? null : entry.value.state.snapshot().position(), from = host.position(player);
            distance = at == null || !at.world().equals(from.world()) ? Double.POSITIVE_INFINITY : at.point().distance(from.point());
        }
        return definition.viewers().hides(viewer, distance, (String node) -> host.permitted(player, node), (String node) -> host.granted(player, node));
    }

    // ---- Movement, routes and recordings ----

    @Override public WorldPosition navigationOrigin(String id) { return navigable(id).state.snapshot().position(); }
    @Override public UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) {
        return routes.navigate(navigable(id), destination, mode, blocksPerSecond);
    }
    @Override public boolean stopNavigation(String id) {
        if (!available()) throw new IllegalStateException(down());
        owner(); Value value = value(id);
        return value != null && routes.stop(value);
    }
    @Override public String navigationStatus(String id) {
        if (!available()) return "Navigation " + id + ": " + (closed ? "closed" : unavailable);
        owner(); Value value = value(id);
        return "Navigation " + id + ": " + (value == null ? "not active" : value.navigationStatus + "; position="
                + value.state.snapshot().position().point() + "; tick=" + tickNumber + "; arrivals=" + value.arrivals
                + (value.routeSource == null ? "" : "; route-source=" + value.routeSource)
                + (value.routeError == null ? "" : "; route-error=" + value.routeError) + RecordingPlayer.status(value));
    }
    /** Replaces the current route with a reusable route; its read and any read failure are reported in navigation status. */
    @Override public void followRoute(String id, String route) { routes.follow(navigable(id), route); }
    /** The current route's outcome, for one watcher. Call right after starting the route, in the same owner turn. */
    @Override public CompletableFuture<Void> awaitRoute(String id) { return routes.await(navigable(id)); }
    /**
     * Restarts the definition's own patrol if nothing else moves the NPC: a recording stopped earlier continues from its
     * cursor, a declared recording or reusable route is read again, and inline waypoints start from their first point.
     */
    @Override public boolean resumePatrol(String id) { return routes.resume(navigable(id)); }
    /** Replaces the current route with a recording read from {@code recordings/<id>.json}; the read and any failure show in navigation status. */
    @Override public void playRecording(String id, String recording, Optional<Playback> playback) { recordings.play(navigable(id), recording, playback); }
    /** The NPC is following a route now. */
    public boolean routing(String id) {
        if (!available()) return false;
        owner(); Value value = value(id);
        return value != null && (value.route != null || value.routeTask != null);
    }
    @Override public void lookAt(String id, WorldPosition target) {
        Value value = navigable(id); Objects.requireNonNull(target);
        WorldPosition from = value.state.snapshot().position();
        if (!from.world().equals(target.world()) || from.point().distance(target.point()) > 64)
            throw new IllegalArgumentException("Look target must be within 64 blocks in the same world");
        LookClose.facing(LookClose.eye(from.point(), bodyHeight(value)), target.point())
                .ifPresent((LookClose.Facing facing) -> setPose(value, facing.yaw(), facing.pitch(), value.onGround));
    }
    @Override public void pose(String id, String pose) {
        Value value = navigable(id); Objects.requireNonNull(pose);
        NamedPoses.Facing facing = NamedPoses.parse(value.spec.definition().json()).find(pose)
                .orElseThrow(() -> new IllegalArgumentException("The NPC has no pose named " + pose));
        setPose(value, facing.yaw(), facing.pitch(), value.onGround);
    }
    /** Turns the NPC back to its definition's facing, standing. */
    public void restorePose(String id) {
        Value value = navigable(id);
        setPose(value, value.spec.yaw(), value.spec.pitch(), true);
    }
    /**
     * Where a stationary NPC stands guard: its definition's position, which no fight or route changes. Empty for an NPC
     * that patrols waypoints, a reusable route or a recording, or that is not active.
     */
    public Optional<WorldPosition> post(String id) {
        if (!available()) return Optional.empty();
        owner(); Value value = value(id);
        if (value == null || !value.state.snapshot().active() || value.routeSource != null || value.recordingSource != null
                || WaypointSettings.parse(value.spec.definition()).isPresent())
            return Optional.empty();
        return Optional.of(value.spec.position());
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
        MotionValidator.Result clear = MotionValidator.check(destination.point(), destination.point(), RouteRules.hullWidth(box.max().x() - box.min().x()),
                RouteRules.hullHeight(box.max().y() - box.min().y()), MotionValidator.Medium.GROUND, true, RouteRules.MOTION_CELLS, host.collision(destination.world()));
        if (!clear.clear()) return false;
        binding.get().changed().accept(binding.get().state().move(destination));
        return true;
    }
    /**
     * A block changed in a loaded chunk of this world, or is about to change in this tick. Idle walking NPCs over it check
     * their footing on a later tick and fall when nothing holds them up. Owner thread; reads at most four index buckets.
     */
    public void terrainChanged(String world, BlockPos at) {
        if (!available()) return;
        owner();
        falls.terrainChanged(world, at);
    }
    private Value navigable(String id) {
        if (!available()) throw new IllegalStateException(down());
        owner(); Entry entry = entries.get(id); Value value = entry == null ? null : entry.value;
        if (value == null || !value.state.snapshot().active() || !value.authority.accepts(runtime.npcs().snapshot().get(id)))
            throw new IllegalStateException("The NPC is not active; it becomes active once its chunk loads or a player comes near, then retry");
        if (mountRuntime.vehicleOf(id) != null) throw new IllegalStateException("NPC is riding " + mountRuntime.vehicleOf(id) + "; it moves with its vehicle");
        if (riders.riderOf(value.spec.definition().uuid()) != null) throw new IllegalStateException("A player is riding this NPC");
        return value;
    }
    static double bodyHeight(PersistedNpcService<?, ?>.Value value) { Hitbox box = value.state.snapshot().bounds(); return box.max().y() - box.min().y(); }
    /** Terrain checks use a central hull. A dragon's outstretched wings and tail are presentation, so they may overhang terrain. */
    static double moveWidth(PersistedNpcService<?, ?>.Value value) { Hitbox box = value.state.snapshot().bounds(); return RouteRules.hullWidth(box.max().x() - box.min().x()); }
    static double moveHeight(PersistedNpcService<?, ?>.Value value) { return RouteRules.hullHeight(bodyHeight(value)); }
    static void setPose(PersistedNpcService<?, ?>.Value value, float yaw, float pitch, boolean onGround) {
        if (value.yaw != yaw || value.headYaw != yaw || value.pitch != pitch || value.onGround != onGround) {
            value.yaw = yaw; value.headYaw = yaw; value.pitch = pitch; value.onGround = onGround; value.poseRevision++;
        }
    }
    /** Turns only the head: the body keeps its yaw, as Citizens' head-only looking does. */
    static void setHead(PersistedNpcService<?, ?>.Value value, float headYaw, float pitch) {
        if (value.headYaw != headYaw || value.pitch != pitch) { value.headYaw = headYaw; value.pitch = pitch; value.poseRevision++; }
    }

    // ---- State, combat and looks ----

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
        // A body still lying where it fell is replaced by a fresh one, standing at full health.
        value.dyingUntil = 0;
        views.represent(value);
        if (triggers != null) triggers.activated(npc);
        host.npcSpawned(npc, entry.id, value.state.snapshot().position());
        return true;
    }
    /** Shows who dealt the health change applied next, for its damage and death triggers. Owner thread. */
    public void blame(UUID npc, TriggerEvent.Attacker attacker) {
        if (!available()) return;
        owner(); Entry entry = byUuid.get(npc);
        if (entry != null && entry.value != null) entry.value.attacker = attacker;
    }
    /** The definition's own position, alive or dead: where a health NPC with no respawn points comes back. */
    public Optional<WorldPosition> home(UUID npc) {
        if (!available()) return Optional.empty();
        owner(); Entry entry = byUuid.get(npc);
        return entry == null || entry.value == null ? Optional.empty() : Optional.of(entry.value.spec.position());
    }
    @Override public art.arcane.animus.core.npc.NpcAnchors.Anchor anchor(String id, String name) {
        return art.arcane.animus.core.npc.NpcAnchors.parse(navigable(id).spec.definition().json()).find(name)
                .orElseThrow(() -> new IllegalArgumentException("The NPC has no anchor named " + name));
    }
    @Override public void animate(String id, art.arcane.animus.core.npc.NpcAnimation animation) {
        Value value = navigable(id);
        animation.pose().ifPresent((NpcPose pose) -> {
            if (!pose.availableFor(value.spec.type())) throw new IllegalArgumentException(value.spec.type() + " bodies cannot " + animation.key());
            changeLook(value, pose, value.lookMotion);
        });
        if (animation == art.arcane.animus.core.npc.NpcAnimation.USE_ITEM || animation == art.arcane.animus.core.npc.NpcAnimation.STOP_USING_ITEM)
            changeLook(value, value.lookPose, animation == art.arcane.animus.core.npc.NpcAnimation.USE_ITEM ? value.lookMotion | BodyLook.USING_ITEM : value.lookMotion & ~BodyLook.USING_ITEM);
        if (animation == art.arcane.animus.core.npc.NpcAnimation.SWING) swing(value);
        else if (animation.gesture() != -1) { value.gesture = animation.gesture(); value.gestures++; value.gesturedAt = tickNumber; }
    }
    /** Swings the NPC's arm for everyone who sees it. */
    public void swing(String id) {
        if (!available()) return;
        owner(); Value value = value(id);
        if (value != null) swing(value);
    }
    /** Viewers pick the swing up with their cosmetic work; several swings in a few ticks show as one. */
    void swing(Value value) { value.swings++; value.swungAt = tickNumber; }
    /** Shows one attack to everyone who sees the NPC, sent with its moves rather than after cosmetic work. */
    public void attackSwing(String id) {
        if (!available()) return;
        owner(); Value value = value(id);
        if (value != null) { value.attackSwings++; value.foughtAt = tickNumber; }
    }
    /** Raises or lowers the NPC's bow for everyone who sees it. */
    public void drawing(String id, boolean drawing) {
        if (!available()) return;
        owner(); Value value = value(id);
        if (value == null || value.drawing == drawing) return;
        value.drawing = drawing;
        changeLook(value, value.lookPose, value.lookMotion & ~BodyLook.USING_ITEM);
    }
    /** Shows or hides the NPC burning for everyone who sees it. */
    public void burning(String id, boolean burning) {
        if (!available()) return;
        owner(); Value value = value(id);
        if (value == null || value.burning == burning) return;
        value.burning = burning;
        changeLook(value, value.lookPose, value.lookMotion & ~BodyLook.ON_FIRE);
    }
    void resetLook(Value value) { changeLook(value, value.spec.appearance().pose(), 0); }
    void styleLook(Value value, StyledLeg.Look look) { changeLook(value, look.pose(), look.motion()); }
    /** The playback's pose and motion; a bow drawn in a fight stays drawn across them. */
    void changeLook(Value value, NpcPose pose, int motion) {
        int shown = motion | (value.drawing ? BodyLook.USING_ITEM : 0) | (value.burning ? BodyLook.ON_FIRE : 0);
        if (value.lookPose == pose && value.lookMotion == shown) return;
        value.lookPose = pose; value.lookMotion = shown; value.lookRevision++;
        value.look = BodyLook.of(value.spec.definition().uuid(), value.spec.type(), value.displayName, value.spec.appearance()).moving(pose, shown);
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
            boolean hurt = before != null && snapshot.health() < before.health(), died = hurt && snapshot.health() == 0;
            index.upsert(snapshot);
            Optional<TriggerEvent.Attacker> attacker = Optional.ofNullable(value.attacker);
            value.attacker = null;
            if (triggers != null) triggers.changed(before, snapshot, attacker);
            if (died) host.npcDied(snapshot.npcId(), id, snapshot.position(), attacker);
            if (!snapshot.active() || moved) routes.stopRoute(value, "cancelled by combat lifecycle/position change");
            if (moved) value.poseRevision++;
            if (died) value.dyingUntil = tickNumber + DYING_TICKS;
            for (ViewerPresenter<P, I>.Viewer viewer : views.showing.of(snapshot.npcId())) {
                ViewerPresenter<P, I>.Presented p = viewer.visible.get(snapshot.npcId());
                if (p == null || p.value != value) continue;
                if (died && p.spawnFinished) views.feedback(viewer, p, true);
                else if (!snapshot.active()) views.retire(viewer, p);
                else {
                    if (hurt && p.spawnFinished) views.feedback(viewer, p, false);
                    if (p.bound) viewer.guard.updateSnapshot(p.body.entityId(), snapshot);
                }
            }
        }));
    }
    /** On the owner thread: the drop or swap-hands key, pressed while the player looks at an NPC in interaction reach.
     * The NPC is found along the server ray and validated as a click is, under the same action budget. Only triggers
     * see it. True when a behavior consumes it and the vanilla drop or swap must not happen. */
    @Override public boolean pressed(P player, TriggerEvent.Action key) {
        if (!key.key()) throw new IllegalArgumentException("not a key press: " + key);
        return views.pressed(player, key);
    }

    // ---- Runtime appearance ----

    /** Runtime-only override. Restart or a definition change restores the definition's own appearance. */
    @Override public void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset) {
        Value value = navigable(id);
        NpcPresentation.validateChange(value.spec, name, skin);
        reads.cancelAppearance(value);
        if (reset) {
            value.displayName = value.spec.name();
            value.displaySkin = value.skin;
        } else {
            if (name.isPresent()) value.displayName = name.get();
            if (skin.isPresent()) { reads.readAppearance(value, skin.get()); return; }
        }
        views.represent(value);
    }
    /** Runtime-only, like appearance. A seat comes or goes with sitting, so viewers then receive a fresh body. */
    @Override public void rest(String id, NpcPose pose) {
        Value value = navigable(id);
        if (!pose.availableFor(value.spec.type())) throw new IllegalArgumentException("Only player bodies rest " + pose.key());
        if (pose == NpcPose.SITTING && value.mountReference != null) throw new IllegalArgumentException("A sitting NPC cannot also ride another NPC");
        boolean seated = value.lookPose == NpcPose.SITTING;
        changeLook(value, pose, 0);
        if (seated != (pose == NpcPose.SITTING)) views.represent(value);
    }
    /** The bubble's text goes out in place on the body's top row; the body is never shown again for it. */
    @Override public void bubble(String id, String text, int ticks) {
        Value value = navigable(id);
        value.bubble = text;
        value.bubbleUntil = tickNumber + Math.max(1, ticks);
        value.bubbleRevision++;
        bubbles.add(value);
    }
    /** Bubbles whose time ended leave their bodies. */
    private void endBubbles() {
        for (java.util.Iterator<Value> shown = bubbles.iterator(); shown.hasNext(); ) {
            Value value = shown.next();
            if (value.bubble != null && value.bubbleUntil > tickNumber) continue;
            shown.remove();
            if (value.bubble == null) continue;
            value.bubble = null;
            value.bubbleRevision++;
        }
    }
    /** An empty stack clears the slot. Runtime-only, like appearance. */
    @Override public void setAttribute(String id, String attribute, OptionalDouble next) {
        Value value = navigable(id);
        NpcAttributes updated = RuntimeAttributes.change(value.spec.appearance().traits().attributes(), value.attributes, attribute, next);
        if (updated.equals(value.attributes)) return;
        double scale = value.attributes.scale();
        value.attributes = updated;
        value.attributeRevision++;
        JsonObject definition = RuntimeAttributes.definition(value.spec.definition().json(), updated);
        value.movement = MovementSettings.parse(definition);
        value.falls = MovementSettings.falls(value.spec.type(), definition);
        if (updated.scale() == scale) return;
        Geometry geometry = geometries.get(value.spec.type());
        value.state.resize(geometry.width * updated.scale(), geometry.height * updated.scale());
        index.upsert(value.state.snapshot());
        // Text height and rig bones follow the scale, so the body is presented again; a rig binds again at the new size.
        if (value.rigId != null) rigs.bind(value);
        views.represent(value);
    }
    @Override public void setEquipment(String id, EquipmentSlot slot, I item) {
        Value value = navigable(id);
        if (!packets.equipmentAvailable()) throw new IllegalStateException("Equipment packets are unavailable on this server version");
        if (!BodyRules.equipmentSlots(value.spec.type()).contains(slot)) throw new IllegalArgumentException(BodyRules.slotRefusal(value.spec.type(), slot));
        value.equipment.put(slot, item);
        value.equipmentRevision++;
    }
    @Override public void playAnimation(String id, String animation, double speed, Optional<String> loop, int layer) { rigs.play(rigged(id), animation, speed, loop, layer); }
    @Override public void stopAnimation(String id, OptionalInt layer, int fadeTicks) { rigged(id).rig.stop(layer, fadeTicks, tickNumber); }
    private Value rigged(String id) {
        if (!available()) throw new IllegalStateException(down());
        owner(); Value value = value(id);
        if (value == null || !value.state.snapshot().active()) throw new IllegalStateException("The NPC is not active; it becomes active once its chunk loads or a player comes near");
        if (value.rig == null) throw new IllegalStateException(value.rigId == null ? "NPC " + id + " has no rig" : "Rig " + value.rigId + " is unavailable: " + value.rigProblem);
        return value;
    }

    // ---- Tick and lifecycle ----

    private void tick() {
        if (closed) return;
        owner();
        adoptSettings();
        if (!available()) return;
        tickNumber++;
        budget.simulationLeft = settings.simulationPerTick();
        long mark = metrics.start();
        try {
            // Completion work is bounded independently of connection count and packet acknowledgments.
            views.drainCompletions(settings.packetsPerTick() * 2);
            reads.drain();
            if (!bubbles.isEmpty()) endBubbles();
            boolean forced = refresh.getAndSet(false);
            Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
            if (snapshot.size() > MAX_DEFINITIONS) throw new IllegalStateException("Definition runtime limit exceeded: " + MAX_DEFINITIONS);
            definitions.publish(snapshot, forced);
            if (forced) views.refreshed();
            views.resyncsDue();
            int work = 0;
            while (work < RECONCILE_WORK) {
                if (changes.isEmpty()) {
                    DefinitionQueue.Batch batch = definitions.poll(RECONCILE_WORK - work); work += batch.examined();
                    changes.addAll(batch.changes());
                    if (changes.isEmpty() || work >= RECONCILE_WORK) break;
                }
                DefinitionQueue.Change change = changes.peekFirst();
                if (!definitions.current(change)) { changes.removeFirst(); continue; }
                try { if (!reconcile(change)) break; }
                catch (RuntimeException failure) { quarantine(change.id(), "activation", failure); }
                changes.removeFirst(); work++;
            }
            mountRuntime.resolve();
            mark = metrics.lap(Subsystem.RUNTIME, mark);
            riders.tick();
            routes.tick();
            int cellLimit = navigationSettings.motionCellsPerTick();
            report.navigation(routes.pressure.lastDeferred() > 0 ? 100 : Math.min(100, Math.max(0, cellLimit - budget.motionCells) * 100 / Math.max(1, cellLimit)));
            falls.tick();
            // Routes or behavior runs waiting for their budgets slow cosmetic work first.
            if (routes.pressure.lastDeferred() > 0 || (triggers != null && triggers.backlogged())) pressure.busy(tickNumber);
            mark = metrics.lap(Subsystem.NAVIGATION, mark);
            rigs.tick();
            mark = metrics.lap(Subsystem.RIGS, mark);
            looks.tick();
            homes.tick();
            if (!available()) return; // Arrival listeners may disable the plugin or retire the entire runtime.
            mountRuntime.syncPassengers();
            mountRuntime.tickDragons();
            views.admitPlayers();
            mark = metrics.lap(Subsystem.PRESENTATION, mark);
            if (triggers != null) triggers.proximity(views.viewers.keySet(), views::position);
            mark = metrics.lap(Subsystem.BEHAVIORS, mark);
            int used = views.present();
            metrics.lap(Subsystem.PRESENTATION, mark);
            report.presentation(Math.min(100, used * 100 / Math.max(1, settings.packetsPerTick())));
        } catch (RuntimeException failure) {
            failures.record("tick", "", failure);
            mountRuntime.close();
            // Capability failure must not leave clients able to interact with stale presentation.
            for (ViewerPresenter<P, I>.Viewer viewer : List.copyOf(views.viewers.values())) views.disconnect(viewer.player);
            unavailable = "unavailable after runtime failure: " + failures.last();
            if (triggers != null) triggers.failed("NPC runtime " + unavailable);
            closeQuietly(ticker); packets.close(); packets = null;
            if (navigation != null) navigation.close();
        }
    }

    private void warnActivation(String message) { host.warn(message); }
    void quarantine(Value value, String operation, RuntimeException failure) {
        Entry entry = byUuid.get(value.spec.definition().uuid());
        if (entry != null && entry.value == value) quarantine(entry.id, operation, failure);
        else failures.record(operation, "npc=" + value.spec.definition().id(), failure);
    }
    /**
     * One NPC's work failed: the failure is recorded and that NPC alone stops, as an owner turn does on Folia. Its body is
     * removed and stays down until its definition changes or the NPCs reload.
     */
    void quarantine(String id, String operation, RuntimeException failure) {
        failures.record(operation, "npc=" + id, failure);
        Entry entry = entries.get(id);
        if (entry == null) return;
        if (entry.task != null) { entry.task.cancelled = true; entry.task = null; }
        Value value = entry.value;
        if (value != null) {
            try { removeValue(entry); }
            catch (RuntimeException cleanup) {
                entry.value = null;
                byUuid.remove(value.spec.definition().uuid(), entry); index.remove(value.spec.definition().uuid());
            }
        }
        entry.error = "stopped after a runtime failure in " + operation + ": " + concise(failure);
    }
    private boolean reconcile(DefinitionQueue.Change change) {
        NpcDefinition definition = change.definition(); Entry entry = entries.get(change.id());
        if (!DefinitionSpec.spawned(definition)) { activationWarnings.cleared(change.id()); if (entry != null) remove(entry); return true; }
        if (entry == null) {
            if (entries.size() >= MAX_ACTIVE) { failures.record("activation", "", "Active definition capacity reached: " + MAX_ACTIVE); return true; }
            entry = new Entry(change.id()); entries.put(change.id(), entry);
        }
        if (entry.task != null && entry.task.spec.definition() != definition) { entry.task.cancelled = true; entry.task = null; }
        DefinitionSpec<I> spec;
        try { spec = parse(definition); }
        catch (IllegalArgumentException invalid) { entry.error = invalid.getMessage(); activationWarnings.failed(change.id(), entry.error); return true; }
        if (entry.value != null && entry.value.spec.serialized().equals(spec.serialized())) { entry.error = null; activationWarnings.cleared(change.id()); reads.requestRoute(entry.value); return true; }
        if (entry.task != null) return true;
        if (spec.skin() == null) { activate(entry, spec, null); return true; }
        return reads.readSkin(entry, spec);
    }
    /** The skin an activation waited for was read, or could not be. */
    void skinRead(Entry entry, DefinitionSpec<I> spec, BodySkin skin, String error) {
        if (error != null) { entry.error = "$.skin: " + error; activationWarnings.failed(entry.id, entry.error); return; }
        activate(entry, spec, skin);
    }
    private void activate(Entry entry, DefinitionSpec<I> spec, BodySkin skin) {
        Geometry geometry = geometries.get(spec.type());
        Value previous = entry.value;
        VirtualNpcState state;
        double scale = RouteRules.scale(spec), health = RouteRules.maximumHealth(spec);
        if (previous != null && previous.spec.definition().uuid().equals(spec.definition().uuid())) {
            WorldPosition next = previous.spec.position().equals(spec.position()) ? previous.state.snapshot().position() : spec.position();
            state = previous.state.reconfigured(next, geometry.width * scale, geometry.height * scale, health);
        } else {
            state = new VirtualNpcState(spec.definition().uuid(), spec.position(), geometry.width * scale, geometry.height * scale, health);
            state.spawn();
        }
        boolean sameNpc = previous != null && previous.spec.definition().uuid().equals(spec.definition().uuid());
        if (previous != null) removeValue(entry);
        if (previous != null && !sameNpc) {
            if (triggers != null) triggers.removed(previous.spec.definition().uuid());
            host.npcDespawned(previous.spec.definition().uuid(), previous.spec.definition().id());
        }
        entry.value = new Value(spec, skin, state, geometry.ids, geometry.seat); entry.error = null; activationWarnings.cleared(entry.id);
        byUuid.put(spec.definition().uuid(), entry); index.upsert(state.snapshot());
        mountRuntime.activated(entry.value);
        if (entry.value.rigId != null) rigs.add(entry.value);
        if (entry.value.lookClose != null) looks.add(entry.value);
        if (entry.value.home != null) homes.add(entry.value);
        if (!sameNpc) {
            if (triggers != null) triggers.activated(spec.definition().uuid());
            host.npcSpawned(spec.definition().uuid(), spec.definition().id(), state.snapshot().position());
        }
        if (!routes.startInline(entry.value)) reads.requestRoute(entry.value);
    }
    private void remove(Entry entry) {
        if (entry.task != null) { entry.task.cancelled = true; entry.task = null; }
        if (entry.value != null) {
            UUID uuid = entry.value.spec.definition().uuid();
            removeValue(entry);
            if (triggers != null) triggers.removed(uuid);
            host.npcDespawned(uuid, entry.id);
        }
        entries.remove(entry.id);
    }
    private void removeValue(Entry entry) {
        Value old = entry.value;
        riders.vehicleGone(old.spec.definition().uuid(), "definition-changed");
        entry.value = null;
        mountRuntime.removed(old);
        routes.stopRoute(old, "cancelled by definition replacement/despawn");
        routes.failWatch(old, "cancelled by definition replacement/despawn");
        reads.cancelRoute(old);
        reads.cancelAppearance(old);
        rigs.remove(old);
        looks.remove(old); homes.remove(old);
        UUID uuid = old.spec.definition().uuid(); byUuid.remove(uuid, entry); index.remove(uuid); old.state.despawn();
        views.represent(old);
    }
    boolean valueCurrent(Value value) {
        Entry entry = byUuid.get(value.spec.definition().uuid());
        return available() && entry != null && entry.value == value && value.authority.accepts(runtime.npcs().snapshot().get(entry.id));
    }
    double playerWidth() { return geometries.get(BodyRules.PLAYER).width(); }
    double playerHeight() { return geometries.get(BodyRules.PLAYER).height(); }
    SeatGeometry playerSeat() { return geometries.get(BodyRules.PLAYER).seat(); }
    private String world(String reference) { return host.resolveWorld(reference).orElse(null); }
    public static String concise(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message.substring(0, Math.min(256, message.length())));
    }
    void owner() { if (!host.ownerThread()) throw new IllegalStateException("NPC runtime requires the owner thread"); }
    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) return;
        try { handle.close(); } catch (Exception ignored) { /* The tick checks availability first. */ }
    }

    /** A player joined. Call on the owner thread. */
    public void joined(P player) {
        players.joined(host.id(player));
        if (available()) views.readmit();
    }
    /** A player left the server. */
    public void quit(P player) {
        UUID id = host.id(player);
        players.left(id);
        previews.left(id);
        if (!available()) return;
        riders.playerLeaving(player, "offline");
        views.quit(player); selections.remove(id);
        if (triggers != null) triggers.forget(id);
        views.readmit();
    }
    /** An accepted teleport. The dismount teleport keeps the world and nearby presentation. */
    public void teleported(P player) {
        if (!available()) return;
        // Rebuilding presentation after the dismount teleport would flicker every body.
        if (riders.restoring()) return;
        riders.playerLeaving(player, "teleport");
        views.reset(player);
    }
    public void changedWorld(P player) { if (available()) { riders.playerLeaving(player, "world-changed"); views.reset(player); } }
    public void respawned(P player) { if (available()) { riders.playerLeaving(player, "respawn"); views.reset(player); } }
    public void died(P player) { if (available()) riders.playerLeaving(player, "death"); }
    public void worldLoaded() { if (available()) refresh(); }
    public void worldUnloaded(String world) {
        if (!available()) return;
        routes.worldUnloaded(world);
        refresh();
    }
    @Override public void close() {
        if (closed) return;
        if (mountRuntime != null) mountRuntime.close();
        closed = true;
        closeQuietly(settingsSubscription);
        previews.close();
        closeQuietly(ticker);
        routes.close(); falls.clear(); rigs.clear(); looks.clear(); homes.clear(); if (navigation != null) navigation.close();
        reads.cancelAll();
        views.clear();
        entries.values().forEach((Entry entry) -> { if (entry.value != null) { routes.failWatch(entry.value, "cancelled: runtime shutdown"); entry.value.state.despawn(); index.remove(entry.value.spec.definition().uuid()); } });
        entries.clear(); byUuid.clear(); selections.clear(); changes.clear(); definitions.clear();
        if (packets != null) packets.close();
    }

    final class Entry {
        final String id;
        Value value; DocumentReads<P, I>.SkinRead task; String error;
        Entry(String id) { this.id = id; }
    }
    final class Value {
        final DefinitionSpec<I> spec; final BodySkin skin; final VirtualNpcState state; final int ids;
        final DefinitionAuthority authority;
        float yaw, headYaw, pitch; boolean onGround = true; long poseRevision, arrivals;
        /** A per-player look-close NPC with someone in range: each viewer is shown the NPC looking at them. */
        boolean lookingAtViewers;
        /** The player look-close looked at last, and when a switching NPC may pick another. */
        P lookingAt; long nextSwitch;
        /** Arm swings so far and the tick of the last; each viewer sends one swing for any number since its last. */
        long swings, swungAt;
        /** One-off gestures so far, the last one's animate action (or the hurt flash) and its tick; each viewer sends the latest. */
        long gestures, gesturedAt; int gesture;
        RouteNavigator<P, I>.Route route; WaypointProgress waypoints; String navigationStatus = "idle", routeError;
        /** The log already said terrain blocks one of this NPC's legs. */
        boolean blockedWarned;
        final String routeSource;
        DocumentReads<P, I>.RouteRead routeTask; boolean routeReadQueued;
        /** The definition's own recording; {@link #recording} is the run playing now, {@link #heldRecording} one stopped or finished. */
        final RecordingSource recordingSource;
        RecordingRun recording, heldRecording;
        /** The route leg a step run belongs to, and its route, kept while the step plays or is stopped so it can resume. */
        WaypointProgress.Leg stepLeg; WaypointProgress stepProgress;
        final RecordingDoors recordingDoors;
        /** The pose and motion a playback shows; {@link #look} is the body look with them, sent when {@link #lookRevision} moves. */
        NpcPose lookPose; int lookMotion; long lookRevision; BodyLook look;
        /** Attacks so far, sent with moves; and whether a fight holds the bow drawn, shown through {@link #look}. */
        long attackSwings; boolean drawing, burning;
        /** How far an idle fall has carried the body, for fall damage. */
        final FallTracker fall = new FallTracker();
        /** Who dealt the health change being applied now, for its damage and death triggers; cleared once they fire. */
        TriggerEvent.Attacker attacker;
        /** A body that just died stays shown, lying down, until this tick. */
        long dyingUntil;
        final MountReference mountReference; final RideSettings ride; final SeatGeometry seat;
        boolean supportQueued; double fallSpeed;
        final LookClose lookClose; long nextLook, nextRandomLook;
        /** The navigation module's pause, and when this NPC's route stands still. */
        final RoutePause pause; final RoutePause.Clock pauseClock = new RoutePause.Clock();
        /** The animus:home module, the tick the NPC last stopped being busy or at home, and its next check. */
        final HomeSettings home; long idleSince, nextHome;
        /** A failed return home was warned about; the next one that works clears it. */
        boolean homeWarned;
        /** Each viewer sees its own skin or name on this NPC; null shows the NPC's own. */
        final art.arcane.animus.core.presentation.MirrorSettings mirror;
        /** The NPC guards a player or NPC by its definition, and the tick of its last attack: either keeps it from going home. */
        final boolean guards; long foughtAt = Long.MIN_VALUE / 2;
        MovementSettings movement; boolean falls; final boolean routeSpeedDeclared; final DoorPassage doors;
        /** The definition's attributes with runtime changes on top; {@link #attributeRevision} moves with every runtime change. */
        NpcAttributes attributes; long attributeRevision;
        final DragonMotion dragonMotion = new DragonMotion(); int dragonPhase = DragonBody.PHASE_HOVERING;
        CompletableFuture<Void> routeWatch;
        String displayName; BodySkin displaySkin; DocumentReads<P, I>.AppearanceRead appearanceTask;
        /** The speech bubble shown over the body, or null, and the tick it ends on. */
        String bubble; long bubbleUntil, bubbleRevision;
        final Map<EquipmentSlot, I> equipment = new EnumMap<>(EquipmentSlot.class);
        long equipmentRevision;
        final String rigId; NpcRig<I> rig; String rigProblem; long rigGeneration;
        Value(DefinitionSpec<I> spec, BodySkin skin, VirtualNpcState state, int ids, SeatGeometry seat) {
            this.spec = spec; this.skin = skin; this.state = state; this.ids = ids; this.seat = seat; yaw = spec.yaw(); headYaw = yaw; pitch = spec.pitch();
            displayName = spec.name(); displaySkin = skin;
            equipment.putAll(NpcPresentation.equipment(spec, host::copy)); equipmentRevision = equipment.isEmpty() ? 0 : 1;
            mountReference = MountDeclaration.parse(spec.definition().json()).orElse(null);
            ride = RideSettings.parse(spec.definition().json()).orElse(null);
            lookClose = LookClose.parse(spec.definition().json()).orElse(null);
            pause = RoutePause.parse(spec.definition().json()).orElse(null);
            home = HomeSettings.parse(spec.definition().json()).orElse(null);
            mirror = art.arcane.animus.core.presentation.MirrorSettings.parse(spec.definition().json()).orElse(null);
            guards = home != null && HomeSettings.guards(spec.definition().json());
            attributes = spec.appearance().traits().attributes();
            movement = MovementSettings.parse(spec.definition().json());
            doors = new DoorPassage(spec.definition().uuid());
            falls = MovementSettings.falls(spec.type(), spec.definition().json());
            routeSpeedDeclared = MovementSettings.routeDeclaresSpeed(spec.definition().json());
            routeSource = WaypointSettings.reference(spec.definition());
            recordingSource = RecordingSource.parse(spec.definition().json()).orElse(null);
            recordingDoors = new RecordingDoors(spec.definition().uuid());
            lookPose = spec.appearance().pose();
            authority = new DefinitionAuthority(spec.definition());
            rigId = RigReference.parse(spec.definition().json()).orElse(null);
        }
    }
}
