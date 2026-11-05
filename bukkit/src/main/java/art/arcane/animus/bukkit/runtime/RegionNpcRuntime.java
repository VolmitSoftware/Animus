package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.config.NavigationSettings;
import art.arcane.animus.core.runtime.NavigationPressure;
import art.arcane.animus.core.runtime.NpcPresentation;
import art.arcane.animus.core.runtime.DefinitionSpec;
import art.arcane.animus.core.runtime.ActivationWarnings;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.runtime.RuntimeFailures;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.DefinitionQueue;
import art.arcane.animus.core.runtime.SkinDocuments;
import art.arcane.animus.core.runtime.ViewerPreviews;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.bukkit.navigation.BukkitNavigationBridge;
import art.arcane.animus.bukkit.navigation.TerrainChanges;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.IdleFall;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.bukkit.packet.NativeViewerEpoch;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Persisted NPCs on Folia. Each NPC is owned by the region that owns its chunk ({@link RegionActor}); each viewer runs
 * on its own entity scheduler ({@link RegionViewer}). The global region thread only reconciles definitions, admits
 * file reads, expires lost handoffs and asks loaded chunks to wake dormant NPCs; it never reads a player, block or
 * live NPC state. A dormant NPC starts on its region when its chunk loads or a player nearby is on the same region.
 * Riding, mounted NPCs and combat stay unavailable here.
 */
public final class RegionNpcRuntime implements NpcRuntime<Player, ItemStack>, Listener {
    static final String RIDING_UNAVAILABLE = "riding is unavailable on Folia: the rider and the NPC have separate owners and a ride has no region handoff yet";
    static final String MOUNTS_UNAVAILABLE = "mounted NPCs are unavailable on Folia: a vehicle and its passengers have separate owners and seat coupling has no region handoff yet";
    static final int MAX_ISSUED_IDS = 8192;
    private static final int MAX_DEFINITIONS = 100_000, MAX_ACTIVE = 10_000, RECONCILE_WORK = 64, MAX_READS = 8, WAKES_PER_TICK = 64, ADMISSIONS_PER_PASS = 16;
    record Geometry(double width, double height, int ids, art.arcane.animus.core.mount.SeatGeometry seat) {}
    private static final RegionActor.Desired REMOVED = new RegionActor.Desired(null, null);

    final JavaPlugin plugin;
    final AnimusRuntime runtime;
    final Executor io;
    final PersistedNpcService.Settings settings;
    final FoliaRegions regions;
    final RegionBudget budget;
    final art.arcane.animus.core.config.RigSettings rigs;
    final NavigationSettings navigationSettings;
    /** Global region thread only: whether route turns were refused for a while across regions. */
    private final NavigationPressure navigationPressure = new NavigationPressure();
    private volatile String navigationPressureStatus = "none";
    final SpatialNpcIndex index = new SpatialNpcIndex(MAX_ACTIVE);
    final Map<String, RegionActor> actors = new ConcurrentHashMap<>();
    final Map<UUID, RegionActor> byUuid = new ConcurrentHashMap<>();
    final Set<RegionActor> transfers = ConcurrentHashMap.newKeySet();
    final Map<UUID, String> selections = new ConcurrentHashMap<>();
    final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong();
    final ViewerPreviews previews;
    volatile NativePacketAdapter packets;
    volatile NativeViewerEpoch epochs;
    volatile BukkitNavigationBridge navigation;
    volatile TriggerDispatcher triggers;
    volatile InteractionListener<Player> interactions;
    volatile InteractionObserver observer;
    private final Map<String, Geometry> geometries = new HashMap<>();
    private final Map<ChunkPosition, Set<RegionActor>> resting = new ConcurrentHashMap<>(), anchored = new ConcurrentHashMap<>();
    private final Set<ChunkPosition> loaded = ConcurrentHashMap.newKeySet();
    private final Set<RegionActor> wakeRequests = ConcurrentHashMap.newKeySet();
    private final Set<String> rechecks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RegionViewer> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> online = new ConcurrentHashMap<>();
    private final Map<UUID, String> viewerFailures = new ConcurrentHashMap<>();
    private final Map<String, String> definitionErrors = new ConcurrentHashMap<>();
    private final ActivationWarnings activationWarnings = new ActivationWarnings(this::warnActivation);
    private final Semaphore viewerSlots;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    // Global region thread only.
    private final DefinitionQueue definitions = new DefinitionQueue();
    private final Deque<DefinitionQueue.Change> changes = new ArrayDeque<>();
    private final Map<String, SkinRead> skinReads = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SkinRead> skinCompletions = new ConcurrentLinkedQueue<>();
    private long controlTicks;
    private AutoCloseable control;
    private volatile String unavailable;
    private final RuntimeFailures failures;
    private volatile boolean closed;

    public RegionNpcRuntime(JavaPlugin plugin, AnimusRuntime runtime, String minecraftVersion, Executor io, PersistedNpcService.Settings settings, FoliaRegions regions) {
        this.plugin = Objects.requireNonNull(plugin); this.runtime = Objects.requireNonNull(runtime);
        failures = new RuntimeFailures(plugin.getLogger()::warning);
        this.io = Objects.requireNonNull(io); this.settings = Objects.requireNonNull(settings); this.regions = Objects.requireNonNull(regions);
        viewerSlots = new Semaphore(settings.maximumViewers());
        previews = new ViewerPreviews(io, runtime.documents());
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        navigationSettings = runtime.isRunning() ? runtime.navigationSettings() : NavigationSettings.DEFAULT;
        limits.put(RegionBudget.Kind.OWNER_WORK, 64); limits.put(RegionBudget.Kind.ROUTES, navigationSettings.routesPerTick());
        limits.put(RegionBudget.Kind.MOTION_CELLS, navigationSettings.motionCellsPerTick()); limits.put(RegionBudget.Kind.ROUTE_PLANS, navigationSettings.planningStartsPerTick());
        limits.put(RegionBudget.Kind.CAPTURE, navigationSettings.captureReadsPerTick()); limits.put(RegionBudget.Kind.VIEWERS, settings.updatesPerTick());
        limits.put(RegionBudget.Kind.PACKETS, RegionViewer.PACKETS_PER_REGION); limits.put(RegionBudget.Kind.WAKES, 16); limits.put(RegionBudget.Kind.LOOKS, 32);
        rigs = runtime.isRunning() ? runtime.rigSettings() : art.arcane.animus.core.config.RigSettings.DEFAULT;
        limits.put(RegionBudget.Kind.RIG_PLANS, rigs.plansPerTick());
        budget = new RegionBudget(regions, limits);
        if (!settings.enabled()) { unavailable = "disabled in config.toml"; return; }
        try {
            epochs = NativeViewerEpoch.bind(plugin, minecraftVersion);
            packets = NativePacketAdapter.bind(plugin, minecraftVersion);
            navigation = BukkitNavigationBridge.regionOwned(navigationSettings.limits());
            for (String type : packets.capabilities().entityTypes()) {
                NativePacketAdapter.Body body = packets.createBody(UUID.randomUUID(), type, "Animus", null);
                geometries.put(type, new Geometry(body.width(), body.height(), body.entityIds().length, body.seat()));
            }
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getServer().getPluginManager().registerEvents(new TerrainChanges(this::terrainChanged), plugin);
            control = regions.globalTicks(this::controlTick);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                online.put(player.getUniqueId(), player);
                regions.execute(player, () -> admit(player), () -> {});
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unavailable = "unavailable: " + PersistedNpcService.concise(failure);
            if (packets != null) packets.close();
            packets = null;
            if (navigation != null) navigation.close();
            HandlerList.unregisterAll(this);
            plugin.getLogger().warning("NPC runtime " + unavailable);
        }
    }

    @Override public boolean available() { return !closed && packets != null; }
    @Override public void interactions(InteractionListener<Player> listener) { interactions = listener; }
    @Override public void observeInteractions(InteractionObserver observer) { this.observer = observer; }
    @Override public void bindTriggers(TriggerDispatcher dispatcher) { dispatcher.hide(previews); triggers = dispatcher; }
    @Override public void refresh() { refresh.set(true); }
    @Override public PlayerAccess<Player> players() { return players; }
    @Override public ViewerPreviews previews() { return previews; }
    /** A preview candidate checked against this server, on the viewer's region. */
    DefinitionSpec<ItemStack> previewSpec(NpcDefinition definition) { return DefinitionSpec.parse(definition, geometries.keySet(), this::worldKey, DefinitionItems::stack); }

    @Override public String status() {
        if (!available()) return "NPC runtime " + (closed ? "closed" : unavailable);
        int visible = 0;
        for (RegionViewer viewer : viewers.values()) visible += viewer.shown.get();
        int sleeping = 0;
        for (RegionActor actor : actors.values()) if (actor.routeSleeping) sleeping++;
        return "NPC runtime ready on Folia regions | active=" + index.size() + " | dormant=" + Math.max(0, actors.size() - index.size())
                + " | routes-sleeping=" + sleeping + " | navigation-pressure=" + navigationPressureStatus
                + " | viewers=" + viewers.size() + " | visible=" + visible + " | handoffs=" + transfers.size() + " | file-reads=" + reads.get()
                + " | accepted=" + accepted.get() + " | rejected=" + rejected.get() + " | path-jobs=" + navigation.outstandingJobs()
                + " | riding, mounts and combat unavailable on Folia" + (failures.last() == null ? "" : " | last-error=" + failures.last());
    }

    @Override public String inspect(String id) {
        if (!available()) return "Runtime " + id + ": " + (closed ? "closed" : unavailable);
        NpcDefinition definition = runtime.npcs().snapshot().get(id);
        if (definition == null) return "Runtime " + id + ": unknown";
        if (!DefinitionSpec.spawned(definition)) return "Runtime " + id + ": dormant";
        RegionActor actor = actors.get(id);
        String error = definitionErrors.get(id);
        if (actor == null) return "Runtime " + id + ": " + (skinReads.containsKey(id) ? "loading skin" : error == null ? "activation pending (active capacity " + MAX_ACTIVE + ")" : "unavailable; " + error);
        RegionActor.Publication view = actor.publication;
        NpcOwnership.Phase phase = actor.owner.ownership().state().phase();
        String state = view.live() ? "active" : phase == NpcOwnership.Phase.DORMANT ? "waiting for its chunk to load or a player nearby" : phase.name().toLowerCase(java.util.Locale.ROOT);
        return "Runtime " + id + ": " + state + "; uuid=" + actor.uuid + "; revision=" + definition.revision() + "; owner=" + phase.name().toLowerCase(java.util.Locale.ROOT)
                + "; health=" + view.snapshot().health() + "; position=" + view.snapshot().position().point() + "; navigation=" + view.navigation()
                + NpcPresentation.describe(view.spec(), view.equipment().size(), packets.presentationAvailable())
                + (view.mount() ? "; mount=" + MOUNTS_UNAVAILABLE : "") + (view.ride() ? "; ride=" + RIDING_UNAVAILABLE : "")
                + actor.rigStatus()
                + (actor.error == null ? "" : "; last-valid-retained=true; " + actor.error);
    }
    @Override public List<String> validateActivation(NpcDefinition definition) {
        if (!available()) return List.of("NPC runtime " + (closed ? "closed" : unavailable));
        return DefinitionSpec.validate(Objects.requireNonNull(definition), geometries.keySet(), this::worldKey, DefinitionItems::stack);
    }
    @Override public String rideStatus() { return "Riding unavailable: " + RIDING_UNAVAILABLE; }
    @Override public boolean stopRider(String playerName) { throw new IllegalStateException(RIDING_UNAVAILABLE); }
    @Override public Optional<String> selection(UUID viewer) {
        String id = selections.get(viewer);
        return id != null && runtime.npcs().snapshot().containsKey(id) ? Optional.of(id) : Optional.empty();
    }
    @Override public void select(UUID viewer, String id) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        if (!runtime.npcs().snapshot().containsKey(id)) throw new IllegalArgumentException("Unknown NPC: " + id);
        if (!selections.containsKey(viewer) && selections.size() >= settings.maximumViewers()) throw new IllegalStateException("Selection capacity reached");
        selections.put(viewer, id);
    }
    @Override public Optional<ActiveNpc> active(UUID npc) {
        RegionActor actor = available() ? byUuid.get(npc) : null;
        RegionActor.Publication view = actor == null ? null : actor.publication;
        return view == null || !view.live() ? Optional.empty() : Optional.of(new ActiveNpc(actor.id, view.name(), view.snapshot()));
    }
    @Override public String navigationStatus(String id) {
        if (!available()) return "Navigation " + id + ": " + (closed ? "closed" : unavailable);
        RegionActor actor = actors.get(id);
        RegionActor.Publication view = actor == null ? null : actor.publication;
        return "Navigation " + id + ": " + (view == null || !view.live() ? "inactive" : view.navigation() + "; position=" + view.snapshot().position().point() + "; arrivals=" + view.arrivals());
    }

    @Override public boolean onOwner(UUID npc, Runnable work, Consumer<String> refused) { return submit(available() ? byUuid.get(npc) : null, work, refused); }
    @Override public boolean onOwner(String id, Runnable work, Consumer<String> refused) { return submit(available() ? actors.get(id) : null, work, refused); }
    private static boolean submit(RegionActor actor, Runnable work, Consumer<String> refused) {
        return actor != null && actor.owner.submit(new OwnerMailbox.Work() {
            @Override public void run() { work.run(); }
            @Override public void refuse(String reason) { refused.accept(reason); }
        });
    }

    @Override public WorldPosition navigationOrigin(String id) { return navigable(id).position(); }
    @Override public UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) {
        RegionActor actor = navigable(id); Objects.requireNonNull(destination); Objects.requireNonNull(mode);
        return actor.navigate(destination, mode, blocksPerSecond);
    }
    @Override public boolean stopNavigation(String id) {
        RegionActor actor = owned(id);
        return actor != null && actor.stopNavigation();
    }
    @Override public void lookAt(String id, WorldPosition target) { navigable(id).lookAt(Objects.requireNonNull(target)); }
    @Override public void followRoute(String id, String route) { navigable(id).followRoute(route); }
    @Override public CompletableFuture<Void> awaitRoute(String id) { return navigable(id).awaitRoute(); }
    @Override public void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset) { navigable(id).setAppearance(name, skin, reset); }
    @Override public void setEquipment(String id, EquipmentSlot slot, ItemStack item) { navigable(id).setEquipment(slot, item); }
    @Override public void playAnimation(String id, String animation, double speed, Optional<String> loop, int layer) { navigable(id).playAnimation(animation, speed, loop, layer); }
    @Override public void stopAnimation(String id, java.util.OptionalInt layer, int fadeTicks) { navigable(id).stopAnimation(layer, fadeTicks); }

    private RegionActor navigable(String id) {
        RegionActor actor = owned(id);
        if (actor == null || !actor.navigable()) throw new IllegalStateException("NPC must be active with its current definition applied");
        return actor;
    }
    /** The NPC, when the calling thread is its owner. */
    private RegionActor owned(String id) {
        if (!available()) throw new IllegalStateException("NPC runtime " + (closed ? "closed" : unavailable));
        RegionActor actor = actors.get(id);
        if (actor == null) return null;
        if (!actor.owner.owned()) throw new IllegalStateException("NPC " + id + " is owned by another region; run this on its owner");
        return actor;
    }

    // ---- Shared bookkeeping used by actors and viewers ----

    Geometry geometry(String type) { return geometries.get(type); }
    World world(String id) { return regions.world(id); }
    /** On the changed block's region thread: marks the NPCs standing on it, each checks its footing on its own owner tick. */
    private void terrainChanged(String world, BlockPos at) {
        if (!available()) return;
        for (UUID npc : IdleFall.standingOn(index, world, at)) {
            RegionActor actor = byUuid.get(npc);
            if (actor != null) actor.supportWanted = true;
        }
    }
    private String worldKey(String name) {
        UUID id = worldId(name);
        return id == null ? null : id.toString();
    }
    private UUID worldId(String name) {
        World world;
        try { world = plugin.getServer().getWorld(UUID.fromString(name)); }
        catch (IllegalArgumentException notUuid) { world = plugin.getServer().getWorld(name); }
        return world == null ? null : world.getUID();
    }
    /** A failure outside any viewer's session: shown in status and logged once per cause. */
    void noteFailure(String operation, String context, String error) { failures.record(operation, context, error); }
    /** A chunk load event reported this chunk and no unload has followed. */
    boolean knownLoaded(ChunkPosition chunk) { return loaded.contains(chunk); }
    /** An owner saw the chunk loaded: chunks loaded before Animus enabled become known this way. */
    void observedLoaded(ChunkPosition chunk) { loaded.add(chunk); }
    boolean admitRead() {
        while (true) {
            int now = reads.get();
            if (now >= MAX_READS) return false;
            if (reads.compareAndSet(now, now + 1)) return true;
        }
    }
    void releaseRead() { reads.decrementAndGet(); }
    void activated(UUID npc) { TriggerDispatcher dispatcher = triggers; if (dispatcher != null) dispatcher.activated(npc); }
    void waypoint(UUID npc, int waypoint) { TriggerDispatcher dispatcher = triggers; if (dispatcher != null) dispatcher.publish(new TriggerEvent.Waypoint(npc, waypoint)); }
    void animationFinished(UUID npc, String animation, int layer) {
        TriggerDispatcher dispatcher = triggers;
        if (dispatcher != null) dispatcher.publish(new TriggerEvent.AnimationFinished(npc, animation, layer));
    }
    /** Registers where a dormant NPC waits, or clears it. Removed NPCs that went dormant retire here. */
    void rest(RegionActor actor, ChunkPosition chunk) {
        ChunkPosition previous = actor.resting;
        if (previous != null) leave(resting, previous, actor);
        actor.resting = chunk;
        if (chunk == null) return;
        join(resting, chunk, actor);
        if (actor.desired.spec() == null) actor.owner.retireUnowned();
        else if (!closed && loaded.contains(chunk)) wakeRequests.add(actor);
    }
    /** Owner bookkeeping for chunk-unload lookups. */
    void anchor(RegionActor actor, ChunkPosition from, ChunkPosition to) {
        if (from != null) leave(anchored, from, actor);
        if (to != null) join(anchored, to, actor);
    }
    void forget(RegionActor actor) {
        actors.remove(actor.id, actor);
        byUuid.remove(actor.uuid, actor);
        wakeRequests.remove(actor);
        if (actor.replaced || closed) return;
        // A newer definition may have arrived while the owner retired the old one; the control tick checks again.
        if (actor.desired.spec() != null) { rechecks.add(actor.id); return; }
        TriggerDispatcher dispatcher = triggers;
        if (actor.announced && dispatcher != null) dispatcher.deactivated(actor.uuid);
    }
    private static void join(Map<ChunkPosition, Set<RegionActor>> map, ChunkPosition chunk, RegionActor actor) {
        map.compute(chunk, (key, set) -> { Set<RegionActor> bucket = set == null ? ConcurrentHashMap.newKeySet() : set; bucket.add(actor); return bucket; });
    }
    private static void leave(Map<ChunkPosition, Set<RegionActor>> map, ChunkPosition chunk, RegionActor actor) {
        map.computeIfPresent(chunk, (key, set) -> { set.remove(actor); return set.isEmpty() ? null : set; });
    }

    /**
     * Viewer owner only: wakes dormant NPCs in loaded chunks that this player's region owns, and marks every active NPC
     * within view distance as watched, on any region, so its patrol keeps walking (see {@link RegionActor#watched()}).
     */
    void wakeNear(Player player, WorldPosition at) {
        World world = player.getWorld();
        int radius = (int) Math.ceil(settings.viewDistance() / 16), centerX = Math.floorDiv((int) Math.floor(at.point().x()), 16), centerZ = Math.floorDiv((int) Math.floor(at.point().z()), 16);
        long now = System.nanoTime();
        for (int x = centerX - radius; x <= centerX + radius; x++) for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            Set<RegionActor> active = anchored.get(new ChunkPosition(at.world(), x, z));
            if (active != null) for (RegionActor actor : active) actor.watchedNanos = now;
        }
        for (int x = centerX - radius; x <= centerX + radius; x++) for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            Set<RegionActor> waiting = resting.get(new ChunkPosition(at.world(), x, z));
            if (waiting == null || !regions.owns(world, x, z, x, z) || !world.isChunkLoaded(x, z)) continue;
            for (RegionActor actor : List.copyOf(waiting)) {
                if (budget.take(RegionBudget.Kind.WAKES, 1) == 0) return;
                actor.owner.wake();
            }
        }
    }

    // ---- Viewers, on each player's own scheduler ----

    int viewerCount() { return viewers.size(); }
    boolean current(RegionViewer viewer) { return !closed && viewers.get(viewer.id) == viewer; }
    /** Player owner only. */
    private void admit(Player player) {
        if (!available() || !regions.owns(player) || !player.isOnline() || player.isDead()) return;
        UUID id = player.getUniqueId();
        if (viewers.containsKey(id) || viewerFailures.containsKey(id) || !viewerSlots.tryAcquire()) return;
        RegionViewer viewer;
        try { viewer = new RegionViewer(this, player, epochs.capture(player)); }
        catch (RuntimeException failure) { viewerSlots.release(); failures.record("viewer admission", "viewer=" + player.getName(), failure); return; }
        if (viewers.putIfAbsent(id, viewer) != null) { viewerSlots.release(); return; }
        try {
            packets.watch(player, (target, callback) -> regions.execute(target, callback, () -> {}), viewer::interact)
                    .whenComplete((nothing, failure) -> viewer.enqueue(() -> {
                        if (!current(viewer)) return;
                        if (failure == null) viewer.ready = true; else watchFailed(viewer, failure);
                    }));
        } catch (ReflectiveOperationException | RuntimeException failure) { watchFailed(viewer, failure); return; }
        viewer.task = regions.repeat(player, viewer::tick, () -> retire(viewer));
        if (!current(viewer)) viewer.close();
    }
    private static String context(RegionViewer viewer, RegionActor actor) {
        return "viewer=" + viewer.player.getName() + (actor == null ? "" : ", npc=" + actor.id);
    }
    /** Player owner only. A watch that ended (the connection closed or was re-watched) is not an error: the viewer is dropped and watched again. */
    void fail(RegionViewer viewer, String operation, RegionActor actor, Throwable failure) {
        if (!current(viewer)) return;
        if (RuntimeFailures.viewerGone(failure)) { lost(viewer); return; }
        fail(viewer, operation, actor, PersistedNpcService.concise(failure));
    }
    void fail(RegionViewer viewer, String operation, RegionActor actor, String error) {
        if (!current(viewer)) return;
        failures.record(operation, context(viewer, actor), error);
        if (viewerFailures.size() < settings.maximumViewers()) viewerFailures.put(viewer.id, error);
        disconnect(viewer);
    }
    /** Player owner only. Cosmetic work failed: an error is recorded and the viewer keeps its session. True when the watch had ended and the viewer was dropped. */
    boolean cosmeticFailed(RegionViewer viewer, String operation, RegionActor actor, Throwable failure) {
        if (RuntimeFailures.viewerGone(failure)) { if (current(viewer)) lost(viewer); return true; }
        failures.record(operation, context(viewer, actor), failure);
        return false;
    }
    /** Player owner only: drops a viewer whose watch ended without counting it as a failure, and watches it again. */
    private void lost(RegionViewer viewer) {
        disconnect(viewer);
        admit(viewer.player);
    }
    /** A closed connection waits quietly for the quit, rejoin, teleport or respawn that follows it. */
    private void watchFailed(RegionViewer viewer, Throwable failure) {
        if (!current(viewer)) return;
        if (!RuntimeFailures.viewerGone(failure)) { fail(viewer, "watch", null, failure); return; }
        if (viewerFailures.size() < settings.maximumViewers()) viewerFailures.put(viewer.id, "connection closed");
        disconnect(viewer);
    }
    /** Network and pure state only, so a scheduler retirement callback may call it. */
    void disconnect(RegionViewer viewer) {
        if (!viewers.remove(viewer.id, viewer)) return;
        viewerSlots.release();
        viewer.close();
        NativePacketAdapter adapter = packets;
        if (adapter != null) adapter.forget(viewer.player);
    }
    /** Player owner only: a fresh lease with a fresh epoch, after a teleport, world change or respawn. */
    void reset(RegionViewer viewer) {
        disconnect(viewer);
        viewerFailures.remove(viewer.id);
        admit(viewer.player);
    }
    private void retire(RegionViewer viewer) { disconnect(viewer); }
    private void resetLater(Player player) {
        RegionViewer viewer = viewers.get(player.getUniqueId());
        if (viewer != null && viewer.player == player) disconnect(viewer);
        viewerFailures.remove(player.getUniqueId());
        if (available()) regions.execute(player, () -> admit(player), () -> {});
    }

    private final PlayerAccess<Player> players = new PlayerAccess<Player>() {
        @Override public boolean online(UUID player) { return online.containsKey(player); }
        @Override public Optional<Player> owned(UUID player) {
            Player online = RegionNpcRuntime.this.online.get(player);
            return online != null && regions.owns(online) ? Optional.of(online) : Optional.empty();
        }
        @Override public List<Player> ownedNear(WorldPosition at, double radius, int limit) {
            List<Player> result = new ArrayList<>();
            for (Player candidate : online.values()) {
                if (result.size() >= limit) break;
                if (!regions.owns(candidate) || !candidate.getWorld().getUID().toString().equals(at.world())) continue;
                if (RegionViewer.position(candidate.getLocation()).point().distance(at.point()) <= radius) result.add(candidate);
            }
            return result;
        }
        @Override public boolean deliver(UUID player, Consumer<Player> action) {
            Player target = online.get(player);
            if (target == null) return false;
            if (regions.owns(target)) { action.accept(target); return true; }
            return regions.execute(target, () -> action.accept(target), () -> {});
        }
        @Override public boolean console(Runnable action) { return regions.global(action); }
    };

    // ---- Control plane on the global region thread ----

    private void controlTick() {
        if (!available()) return;
        controlTicks++;
        try {
            navigationPressure.observe(controlTicks, budget.drainRefusedRoutes(), "routesPerTick=" + navigationSettings.routesPerTick()
                    + ", motionCellsPerTick=" + navigationSettings.motionCellsPerTick() + ", planningStartsPerTick=" + navigationSettings.planningStartsPerTick()
                    + " per region in [navigation] of config.toml").ifPresent(plugin.getLogger()::warning);
            navigationPressureStatus = navigationPressure.describe();
            if (controlTicks % 1200 == 0) budget.forgetIdleRegions(System.nanoTime());
            for (int i = 0; i < MAX_READS; i++) { SkinRead read = skinCompletions.poll(); if (read == null) break; finishSkin(read); }
            boolean forced = refresh.getAndSet(false);
            Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
            if (snapshot.size() > MAX_DEFINITIONS) throw new IllegalStateException("Definition runtime limit exceeded: " + MAX_DEFINITIONS);
            definitions.publish(snapshot, forced);
            if (forced) viewerFailures.clear();
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
            for (Iterator<String> pending = rechecks.iterator(); pending.hasNext(); ) {
                String id = pending.next(); pending.remove();
                NpcDefinition definition = snapshot.get(id);
                if (definition != null && !actors.containsKey(id)) reconcile(new DefinitionQueue.Change(id, definition));
            }
            long now = System.nanoTime();
            for (RegionActor actor : List.copyOf(transfers)) if (actor.owner.expireTransfer(now) || !actor.owner.transferring()) transfers.remove(actor);
            int wakes = 0;
            for (Iterator<RegionActor> pending = wakeRequests.iterator(); pending.hasNext() && wakes < WAKES_PER_TICK; ) {
                RegionActor actor = pending.next(); pending.remove(); wakes++;
                ChunkPosition at = actor.resting;
                // Only chunks a load event reported are addressed; scheduling never asks for an absent chunk.
                if (at != null && loaded.contains(at)) regions.execute(at, actor.owner::wake);
            }
            if (controlTicks % 100 == 0) {
                int admitted = 0;
                for (Player player : online.values()) {
                    if (admitted >= ADMISSIONS_PER_PASS || viewers.size() >= settings.maximumViewers()) break;
                    if (viewers.containsKey(player.getUniqueId()) || viewerFailures.containsKey(player.getUniqueId())) continue;
                    admitted++;
                    regions.execute(player, () -> admit(player), () -> {});
                }
            }
        } catch (RuntimeException failure) {
            failures.record("control tick", "", failure);
        }
    }
    private void warnActivation(String message) { plugin.getLogger().warning(message); }
    private boolean reconcile(DefinitionQueue.Change change) {
        NpcDefinition definition = change.definition();
        RegionActor actor = actors.get(change.id());
        if (!DefinitionSpec.spawned(definition)) {
            definitionErrors.remove(change.id()); activationWarnings.cleared(change.id());
            SkinRead read = skinReads.remove(change.id()); if (read != null) read.cancelled = true;
            if (actor != null) remove(actor);
            return true;
        }
        SkinRead pending = skinReads.get(change.id());
        if (pending != null && pending.spec.definition() != definition) { pending.cancelled = true; skinReads.remove(change.id()); pending = null; }
        DefinitionSpec<ItemStack> spec;
        try { spec = DefinitionSpec.parse(definition, geometries.keySet(), this::worldKey, DefinitionItems::stack); }
        catch (IllegalArgumentException invalid) {
            if (actor != null) actor.error = invalid.getMessage(); else definitionErrors.put(change.id(), invalid.getMessage());
            activationWarnings.failed(change.id(), invalid.getMessage());
            return true;
        }
        RegionActor.Desired current = actor == null ? null : actor.desired;
        if (current != null && current.spec() != null && current.spec().serialized().equals(spec.serialized())) { actor.error = null; actor.reread = true; activationWarnings.cleared(change.id()); return true; }
        if (pending != null) return true;
        if (spec.skin() == null) { apply(spec, null); return true; }
        if (skinReads.size() >= MAX_READS) return false;
        SkinRead read = new SkinRead(spec);
        skinReads.put(change.id(), read);
        try { io.execute(read); }
        catch (RejectedExecutionException full) { skinReads.remove(change.id()); definitionErrors.put(change.id(), "$.skin: I/O queue is full; retry reload"); }
        return true;
    }
    private void finishSkin(SkinRead read) {
        if (skinReads.get(read.spec.definition().id()) != read || read.cancelled) return;
        skinReads.remove(read.spec.definition().id());
        if (runtime.npcs().snapshot().get(read.spec.definition().id()) != read.spec.definition()) return;
        if (read.failure != null) {
            RegionActor actor = actors.get(read.spec.definition().id());
            if (actor != null) actor.error = "$.skin: " + read.failure; else definitionErrors.put(read.spec.definition().id(), "$.skin: " + read.failure);
            activationWarnings.failed(read.spec.definition().id(), "$.skin: " + read.failure);
            return;
        }
        apply(read.spec, read.skin);
    }
    private void apply(DefinitionSpec<ItemStack> spec, BodySkin skin) {
        String id = spec.definition().id();
        definitionErrors.remove(id); activationWarnings.cleared(id);
        RegionActor.Desired desired = new RegionActor.Desired(spec, skin);
        RegionActor actor = actors.get(id);
        if (actor != null && !actor.uuid.equals(spec.definition().uuid())) { remove(actor); actor = null; }
        if (actor != null) {
            RegionActor.Desired previous = actor.desired;
            boolean moved = previous.spec() == null || !previous.spec().position().equals(spec.position());
            // A dormant NPC has no owner to apply a new position, so a fresh one starts at the new place.
            if (moved) {
                actor.replaced = true;
                if (actor.owner.retireUnowned()) { create(desired, actor.announced); return; }
                actor.replaced = false;
            }
            actor.desired = desired;
            NpcOwnership.Phase phase = actor.owner.ownership().state().phase();
            if (phase == NpcOwnership.Phase.DORMANT) wakeRequests.add(actor);
            // The owner may have retired it just before this write: look again on the next control tick.
            if (phase == NpcOwnership.Phase.RETIRED) rechecks.add(id);
            return;
        }
        if (actors.size() >= MAX_ACTIVE) { failures.record("activation", "", "Active definition capacity reached: " + MAX_ACTIVE); return; }
        create(desired, false);
    }
    private void create(RegionActor.Desired desired, boolean announced) {
        RegionActor actor = new RegionActor(this, desired, announced);
        actors.put(actor.id, actor); byUuid.put(actor.uuid, actor);
        rest(actor, desired.spec().position().chunk());
        wakeRequests.add(actor);
    }
    private void remove(RegionActor actor) {
        actor.desired = REMOVED;
        actor.owner.retireUnowned();
    }

    // ---- Events, each on the thread that owns its subject ----

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        online.put(player.getUniqueId(), player);
        viewerFailures.remove(player.getUniqueId());
        admit(player);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        online.remove(player.getUniqueId(), player);
        previews.left(player.getUniqueId());
        RegionViewer viewer = viewers.get(player.getUniqueId());
        if (viewer != null && viewer.player == player) disconnect(viewer);
        selections.remove(player.getUniqueId());
        viewerFailures.remove(player.getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) { resetLater(event.getPlayer()); }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event) { resetLater(event.getPlayer()); }
    @EventHandler public void respawn(PlayerRespawnEvent event) { resetLater(event.getPlayer()); }
    @EventHandler public void chunkLoaded(ChunkLoadEvent event) {
        ChunkPosition chunk = new ChunkPosition(event.getWorld().getUID().toString(), event.getChunk().getX(), event.getChunk().getZ());
        loaded.add(chunk);
        Set<RegionActor> waiting = resting.get(chunk);
        if (waiting == null) return;
        // A wake that misses this event, for budget or because the chunk is not yet ready, is retried once next tick.
        for (RegionActor actor : List.copyOf(waiting))
            if (budget.take(RegionBudget.Kind.WAKES, 1) == 0 || !actor.owner.wake()) wakeRequests.add(actor);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void chunkUnloaded(ChunkUnloadEvent event) {
        ChunkPosition chunk = new ChunkPosition(event.getWorld().getUID().toString(), event.getChunk().getX(), event.getChunk().getZ());
        loaded.remove(chunk);
        Set<RegionActor> here = anchored.get(chunk);
        if (here != null) for (RegionActor actor : List.copyOf(here)) actor.owner.unloading(chunk);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        previews.close();
        if (control != null) try { control.close(); } catch (Exception ignored) { /* The global scheduler already stopped. */ }
        HandlerList.unregisterAll(this);
        for (RegionActor actor : actors.values()) actor.owner.shutdown();
        // Viewer state belongs to each player's region; shutdown only stops the tasks and closes the network sessions.
        for (RegionViewer viewer : viewers.values()) viewer.stop();
        viewers.clear(); actors.clear(); byUuid.clear(); transfers.clear(); wakeRequests.clear(); resting.clear(); anchored.clear();
        for (SkinRead read : skinReads.values()) read.cancelled = true;
        skinReads.clear(); skinCompletions.clear(); changes.clear(); definitions.clear(); selections.clear();
        if (navigation != null) navigation.close();
        if (packets != null) packets.close();
    }

    private final class SkinRead implements Runnable {
        final DefinitionSpec<ItemStack> spec; volatile boolean cancelled; BodySkin skin; String failure;
        SkinRead(DefinitionSpec<ItemStack> spec) { this.spec = spec; }
        @Override public void run() {
            try { if (!cancelled && !closed) skin = SkinDocuments.read(runtime.documents(), spec.skin()); }
            catch (Exception problem) { failure = PersistedNpcService.concise(problem); }
            if (!closed) skinCompletions.add(this);
        }
    }
}
