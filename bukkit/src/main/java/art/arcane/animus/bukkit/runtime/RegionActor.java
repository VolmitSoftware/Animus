package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.navigation.DoorPassage;
import art.arcane.animus.core.navigation.MovementSettings;
import art.arcane.animus.core.runtime.NpcPresentation;
import art.arcane.animus.core.runtime.DefinitionSpec;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.DefinitionAuthority;
import art.arcane.animus.core.runtime.NavigationRefusals;
import art.arcane.animus.core.runtime.RouteDocuments;
import art.arcane.animus.core.runtime.RecordingDoors;
import art.arcane.animus.core.runtime.RecordingSteps;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingDocuments;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingPose;
import art.arcane.animus.core.recording.RecordingRun;
import art.arcane.animus.core.recording.RecordingSource;
import art.arcane.animus.core.recording.RecordingWorlds;
import art.arcane.animus.core.recording.RouteRecordings;
import art.arcane.animus.core.runtime.SkinDocuments;
import art.arcane.animus.core.runtime.WaypointProgress;

import art.arcane.animus.core.navigation.CaptureBounds;
import art.arcane.animus.core.navigation.CaptureProvenance;
import art.arcane.animus.core.navigation.LongLeg;
import art.arcane.animus.core.navigation.ChunkOwnership;
import art.arcane.animus.core.navigation.IdleFall;
import art.arcane.animus.bukkit.navigation.LiveMovementProbe;
import art.arcane.animus.core.navigation.NavigationPlan;
import art.arcane.animus.bukkit.navigation.VirtualNpcWaypointEvent;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.PathExecution;
import art.arcane.animus.core.navigation.PathPlanner;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.navigation.BodyTerrain;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.virtual.DragonMotion;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import com.google.gson.JsonObject;
import art.arcane.animus.core.runtime.RuntimeAttributes;
import java.util.List;
import java.util.ArrayList;
import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.NpcRig;
import art.arcane.animus.core.rig.RigCatalog;
import art.arcane.animus.core.rig.RigPlayback;
import art.arcane.animus.core.rig.RigReference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * One persisted NPC on Folia. Every non-volatile field is confined to the region that currently owns the NPC through
 * {@link RegionOwner}; other threads read only the immutable {@link Publication}. Handoff semantics: routes, waypoint
 * progress, a waiting behavior's watch, kinematic execution and queued owner work move with the NPC; an in-flight
 * terrain capture or plan is cancelled and planned again by the new owner. Suspension keeps the route but ends a
 * behavior's wait, as the single-owner runtime does for unavailable terrain.
 */
final class RegionActor implements RegionOwner.Body {
    /** What the definition asks for. A null spec means the definition was removed or despawned. */
    record Desired(DefinitionSpec<ItemStack> spec, BodySkin skin) {}
    /** Immutable state for viewers, commands and behaviors on other threads. */
    record Publication(boolean live, VirtualNpcState.Snapshot snapshot, DefinitionSpec<ItemStack> spec, String type, String name, BodySkin skin,
                       long appearance, float yaw, float pitch, boolean onGround, long pose,
                       Map<EquipmentSlot, ItemStack> equipment, long equipmentRevision, int ids,
                       String navigation, long arrivals, boolean ride, boolean mount, long revision, NpcRig.Snapshot<ItemStack> rig, int dragonPhase,
                       List<UUID> passengers, Player rider, CombatView combat, BodyLook look, long lookRevision, long swings, long attackSwings,
                       NpcAttributes attributes, long attributeRevision) {}
    /** What other combat engines and status read of this NPC's combat; null when it has none. */
    record CombatView(CombatSettings settings, CombatLoadout loadout, UUID guarded, String status, int helpers) {}
    /** A passenger whose vehicle another region owns steps onto its seat at most this often. */
    private static final int MOUNT_RETRY_TICKS = 20;
    /** A viewer marks nearby NPCs every second; a patrol stays awake this long after the last mark. */
    private static final long WATCHED_NANOS = 3_000_000_000L;
    private final RegionNpcRuntime host;
    final String id;
    final UUID uuid;
    final RegionOwner owner;
    volatile Desired desired;
    volatile Publication publication;
    /** Where a dormant NPC waits to be woken. */
    volatile ChunkPosition resting;
    volatile String error;
    volatile boolean announced;
    /** Set when a dormant NPC is replaced by a fresh one with the same identity: its retirement fires no despawn. */
    volatile boolean replaced;
    /** A reload republished the same definition: read its reusable route file again. */
    volatile boolean reread;
    /** A block under the NPC changed on some region thread: check its footing on the next owner tick. */
    volatile boolean supportWanted;
    /** {@link System#nanoTime()} when a viewer within view distance last saw this NPC; written from viewer regions. */
    volatile long watchedNanos;
    /** The patrol sleeps: no player is near, so it holds no route turn or plan. Read by status. */
    volatile boolean routeSleeping;
    /** Ticks in a row this route wanted a turn and was refused; longest waiting goes first. */
    private int routeWaited;
    /** The log already said terrain blocks one of this NPC's legs. */
    private boolean blockedWarned;

    private Desired applied;
    DefinitionSpec<ItemStack> spec;
    private BodySkin skin;
    VirtualNpcState state;
    private DefinitionAuthority authority;
    private int ids;
    private String routeSource;
    private RideSettings ride;
    private boolean mount;
    private LookClose lookClose;
    private MovementSettings movement = MovementSettings.DEFAULT;
    private DoorPassage doors;
    private boolean falls = true, routeSpeedDeclared = true;
    /** The definition's attributes with runtime changes on top; viewers send them again when the revision moves. */
    private NpcAttributes attributes = NpcAttributes.NONE;
    private long attributeRevision;
    private long nextRandomLook;
    private long nextLook;
    float yaw, pitch;
    private boolean onGround = true;
    private long pose, arrivals, turns, appearance, equipmentRevision, revision, swings, attackSwings;
    private double fallSpeed;
    private Route route;
    private WaypointProgress waypoints;
    private String navigationStatus = "idle", routeError;
    private CompletableFuture<Void> routeWatch;
    private RouteRead routeRead;
    private boolean routeReadWanted;
    /** The definition's own recording; {@link #performance} holds the run playing now and the one kept for resuming. */
    private RecordingSource recordingSource;
    final RegionPlayback performance = new RegionPlayback(new PlaybackActor());
    /** The route leg a step run belongs to, and its route, kept while the step plays or is stopped so it can resume. */
    private WaypointProgress.Leg stepLeg;
    private WaypointProgress stepProgress;
    private RecordingDoors recordingDoors;
    /** The pose and motion a playback shows, published with {@link #lookRevision}; swings count up for viewers to send. */
    private NpcPose lookPose = NpcPose.STANDING;
    private int lookMotion;
    private long lookRevision;
    private BodyLook look;
    /** A fight holds the bow drawn, shown through {@link #look}. */
    private boolean drawing;
    private String displayName;
    private BodySkin displaySkin;
    private AppearanceRead appearanceRead;
    private final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private ChunkPosition anchored;
    private String rigId, rigProblem;
    private NpcRig<ItemStack> rig;
    private long rigGeneration = -1;
    /** The {@code [rigs] enabled} the rig was last bound under; a hotload that changes it binds again. */
    private boolean rigsEnabled;
    private final DragonMotion dragonMotion = new DragonMotion();
    /** This NPC sits on its vehicle's seat; read by its group's owner. */
    volatile boolean seated;
    private long nextMountTry;
    /** A real player riding this NPC; it moves with the NPC on every handoff. */
    private final ActorRide riding;
    private CombatSettings combatSettings;
    private CombatLoadout combatLoadout = CombatLoadout.NONE;
    private ActorCombat combat;
    private int dragonPhase = DragonBody.PHASE_HOVERING;

    RegionActor(RegionNpcRuntime host, Desired desired, boolean announced) {
        this.host = host; this.desired = desired; this.announced = announced;
        spec = desired.spec(); id = spec.definition().id(); uuid = spec.definition().uuid();
        doors = new DoorPassage(uuid);
        recordingDoors = new RecordingDoors(uuid);
        RegionNpcRuntime.Geometry geometry = host.geometry(spec.type());
        state = new VirtualNpcState(uuid, spec.position(), geometry.width() * PersistedNpcService.scale(spec), geometry.height() * PersistedNpcService.scale(spec), PersistedNpcService.maximumHealth(spec));
        state.spawn();
        configure(desired);
        owner = new RegionOwner(uuid, host.regions, host.budget, 32, 8, this);
        riding = new ActorRide(this, host);
        publish(false);
    }

    private void configure(Desired next) {
        applied = next; spec = next.spec(); skin = next.skin();
        authority = new DefinitionAuthority(spec.definition());
        ids = host.geometry(spec.type()).ids();
        routeSource = WaypointSettings.reference(spec.definition());
        ride = RideSettings.parse(spec.definition().json()).orElse(null);
        mount = MountDeclaration.parse(spec.definition().json()).isPresent();
        lookClose = LookClose.parse(spec.definition().json()).orElse(null);
        attributes = spec.appearance().traits().attributes(); attributeRevision++;
        movement = MovementSettings.parse(spec.definition().json());
        falls = MovementSettings.falls(spec.type(), spec.definition().json());
        routeSpeedDeclared = MovementSettings.routeDeclaresSpeed(spec.definition().json());
        yaw = spec.yaw(); pitch = spec.pitch(); onGround = true;
        displayName = spec.name(); displaySkin = skin;
        equipment.clear(); equipment.putAll(NpcPresentation.equipment(spec, ItemStack::clone)); equipmentRevision++; appearance++; pose++;
        waypoints = WaypointSettings.parse(spec.definition()).map((WaypointSettings declared) -> progress(declared, WaypointProgress.INLINE)).orElse(null);
        recordingSource = RecordingSource.parse(spec.definition().json()).orElse(null);
        performance.run = null; performance.held = null; performance.playing = false; stepLeg = null; stepProgress = null;
        lookPose = spec.appearance().pose(); lookMotion = 0; look = null; lookRevision++; drawing = false;
        routeReadWanted = waypoints == null ? routeSource != null || recordingSource != null : !routeReady();
        rigId = RigReference.parse(spec.definition().json()).orElse(null);
        rig = null; rigProblem = null; rigGeneration = -1;
        combatSettings = CombatSettings.active(spec.definition()).orElse(null);
        combatLoadout = CombatLoadout.of(spec.definition());
    }

    /** Binds the rig after a definition change or catalog reload; a replaced rig re-presents the body. Owner only. */
    private void tickRig() {
        if (rigId == null) return;
        RigCatalog catalog = host.runtime.isRunning() ? host.runtime.rigs().catalog() : null;
        if (catalog == null) return;
        boolean enabled = host.rigs().enabled(), toggled = enabled != rigsEnabled;
        if (catalog.generation() != rigGeneration || toggled) {
            rigGeneration = catalog.generation(); rigsEnabled = enabled;
            if (toggled || rig == null || !rig.refresh(catalog, turns)) {
                boolean had = rig != null;
                rig = null; rigProblem = null;
                if (!enabled) rigProblem = "rigs are disabled in config.toml";
                else if (!host.packets.bonesAvailable()) rigProblem = "display bones are unavailable on this server build";
                else {
                    List<String> problems = new ArrayList<>();
                    rig = NpcRig.bind(rigId, catalog, host.geometry(spec.type()).seat(), PersistedNpcService.scale(spec), DefinitionItems::stack, Optional.of(new ItemStack(org.bukkit.Material.AIR)), problems).orElse(null);
                    if (rig == null) rigProblem = String.join("; ", problems.subList(0, Math.min(3, problems.size())));
                }
                if (had || rig != null) appearance++;
            }
        }
        if (rig == null || !state.snapshot().active() || !host.budget.takeSimulation()) return;
        boolean walking = route != null && route.execution != null && !route.execution.finished();
        rig.equip(equipment, ItemStack::clone);
        NpcRig.Step step = rig.tick(turns, walking);
        for (RigPlayback.Fired fired : step.events()) host.animationEvent(uuid, fired);
        for (RigPlayback.Finished done : step.finished()) host.animationFinished(uuid, done.animation(), done.layer());
        if (rig.due(turns, yaw) && host.budget.cosmeticAllowed() && host.budget.take(RegionBudget.Kind.RIG_PLANS, 1) > 0) rig.plan(turns, yaw, host.rigs().sampleTicks(), System.nanoTime());
    }
    void playAnimation(String animation, double speed, Optional<String> loop, int layer) {
        Optional<AnimationDocument.Loop> mode = loop.map((String text) -> AnimationDocument.Loop.parse(text)
                .orElseThrow(() -> new IllegalArgumentException("loop must be once, loop, pingpong or hold")));
        requireRig().play(animation, speed, mode, layer, turns);
    }
    void stopAnimation(java.util.OptionalInt layer, int fadeTicks) { requireRig().stop(layer, fadeTicks, turns); }
    private NpcRig<ItemStack> requireRig() {
        if (rig == null) throw new IllegalStateException(rigId == null ? "NPC " + id + " has no rig" : "Rig " + rigId + " is unavailable: " + rigProblem);
        return rig;
    }
    /** For inspect, from any thread: a racy but harmless read of the owner's fields. */
    String rigStatus() {
        NpcRig<ItemStack> current = rig;
        if (rigId == null) return "";
        return current != null ? "; " + current.status() : "; rig=" + rigId + " unavailable: " + rigProblem;
    }

    // ---- RegionOwner.Body: owner-confined lifecycle ----

    @Override public RegionOwner.Footprint footprint() {
        Hitbox box = state.snapshot().bounds();
        return new RegionOwner.Footprint(state.snapshot().position().world(), Math.floorDiv((int) Math.floor(box.min().x()), 16), Math.floorDiv((int) Math.floor(box.min().z()), 16),
                Math.floorDiv((int) Math.floor(box.max().x()), 16), Math.floorDiv((int) Math.floor(box.max().z()), 16));
    }
    @Override public ChunkPosition anchor() { return state.snapshot().position().chunk(); }
    @Override public boolean loaded(RegionOwner.Footprint footprint) {
        World world = host.world(footprint.world());
        if (world == null || !host.regions.owns(world, footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ())) return false;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) for (int z = footprint.minZ(); z <= footprint.maxZ(); z++)
            if (!world.isChunkLoaded(x, z)) return false;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) for (int z = footprint.minZ(); z <= footprint.maxZ(); z++)
            host.observedLoaded(new ChunkPosition(footprint.world(), x, z));
        return true;
    }
    private boolean loaded(ChunkPosition chunk) { return loaded(new RegionOwner.Footprint(chunk.world(), chunk.x(), chunk.z(), chunk.x(), chunk.z())); }
    /** Known from a load event, or seen loaded right now by this owner, which validated the step into it. */
    @Override public boolean addressable(ChunkPosition destination) { return host.knownLoaded(destination) || loaded(destination); }
    @Override public void handedOff() { host.transfers.add(this); }
    @Override public void rested() { host.rest(this, anchor()); }
    @Override public void owned(boolean woken) {
        host.rest(this, null);
        host.transfers.remove(this);
        if (woken && desired != applied && desired.spec() != null) reconfigure(desired);
        host.anchor(this, anchored, anchor()); anchored = anchor();
        if (state.snapshot().active()) host.index.upsert(state.snapshot());
        if (woken && !announced) { announced = true; host.activated(uuid); }
        if (woken && route == null && performance.run == null && routeReady() && !waypoints.complete()) beginLeg(waypoints.start(state.snapshot().position().point()));
        if (performance.run != null) {
            // The cursor came along; the new owner walks to the pose at the cursor from where the body stands, then plays on.
            cancelPlan();
            route = null;
            performance.adopted();
        }
        publish(true);
    }
    @Override public void released() {
        cancelPlan();
        publish(true);
    }
    @Override public void suspended(String reason) {
        riding.end("npc-dormant", ActorRide.Exit.RESTORE_POSITION);
        closeCombat();
        cancelPlan();
        doors.closeAll(host.doors);
        routeSleeping = false;
        if (routeWatch != null) stopRoute("unavailable: NPC became dormant because " + reason);
        else if (route != null) routeStatus(route, "suspended", "dormant: " + reason);
        host.transfers.remove(this);
        host.index.remove(uuid);
        host.anchor(this, anchored, null); anchored = null;
        publish(false);
    }
    @Override public void retired() {
        riding.end("npc-retired", ActorRide.Exit.RESTORE_POSITION);
        closeCombat();
        seated = false;
        stopRoute("cancelled by definition replacement/despawn");
        failWatch("cancelled by definition replacement/despawn");
        cancelReads();
        host.transfers.remove(this);
        host.index.remove(uuid);
        host.anchor(this, anchored, null); anchored = null;
        host.rest(this, null);
        state.despawn();
        publish(false);
        host.forget(this);
    }

    @Override public ChunkPosition tick() {
        turns++;
        Desired wanted = desired;
        if (wanted != applied) {
            if (wanted.spec() == null) { owner.retire(); return anchor(); }
            reconfigure(wanted);
        }
        // Sections are timed only on sampled ticks, so an idle region pays for a counter check per NPC.
        AnimusMetrics metrics = host.metrics;
        boolean sampled = metrics.sampling();
        long began = sampled ? metrics.start() : 0, mark = began;
        mounts();
        riding.tick();
        if (sampled) mark = metrics.sampledLap(Subsystem.NAVIGATION, mark);
        tickCombat();
        if (sampled) mark = metrics.sampledLap(Subsystem.COMBAT, mark);
        if (reread) { reread = false; routeReadWanted |= routeSource != null || recordingToRead() != null || inlineSteps() != null; }
        if (routeReadWanted) requestRouteRead();
        if (route != null && !seated) { navigate(); supportWanted = false; fallSpeed = 0; }
        else if (!seated && (supportWanted || fallSpeed > 0)) support();
        if (sampled) mark = metrics.sampledLap(Subsystem.NAVIGATION, mark);
        tickRig();
        if (sampled) mark = metrics.sampledLap(Subsystem.RIGS, mark);
        look();
        if (spec.type().equals(DragonBody.TYPE)) dragonPhase = dragonMotion.observe(turns, state.snapshot().position().point(), grounded(),
                DragonBody.restingPhase(spec.appearance().traits().variant()));
        ChunkPosition now = anchor();
        if (!now.equals(anchored)) { host.anchor(this, anchored, now); anchored = now; }
        publish(true);
        if (sampled) {
            long end = metrics.sampledLap(Subsystem.PRESENTATION, mark);
            metrics.npcCost(metrics.npc(uuid), end - began);
            RegionScheduler.Tick tick = host.regions.tick();
            if (tick != null) metrics.regionCost(tick.region(), end - began);
        }
        return now;
    }

    private void reconfigure(Desired next) {
        riding.end("definition-changed", ActorRide.Exit.RESTORE_POSITION);
        WorldPosition position = applied.spec().position().equals(next.spec().position()) ? state.snapshot().position() : next.spec().position();
        stopRoute("cancelled by definition replacement/despawn");
        failWatch("cancelled by definition replacement/despawn");
        cancelReads();
        host.index.remove(uuid);
        RegionNpcRuntime.Geometry geometry = host.geometry(next.spec().type());
        state = state.reconfigured(position, geometry.width() * PersistedNpcService.scale(next.spec()), geometry.height() * PersistedNpcService.scale(next.spec()), PersistedNpcService.maximumHealth(next.spec()));
        configure(next);
        error = null;
        if (state.snapshot().active()) host.index.upsert(state.snapshot());
        if (routeReady()) beginLeg(waypoints.start(state.snapshot().position().point()));
    }

    /** Owner only. The calling thread holds the NPC and its current definition is applied. */
    boolean navigable() {
        return owner.owned() && state.snapshot().active() && authority.accepts(host.runtime.npcs().snapshot().get(id));
    }
    boolean presentable() { return owner.owned() && DefinitionSpec.spawned(host.runtime.npcs().snapshot().get(id)); }

    // ---- Combat: one engine per NPC, on its owner ----

    /** Owner only: this NPC's combat, or null. */
    ActorCombat combat() { return combat; }
    private CombatView combatView() {
        ActorCombat current = combat;
        return current == null ? null : new CombatView(current.settings, current.loadout, current.guarded(), current.status(), current.helpers());
    }
    private void tickCombat() {
        RegionCombat module = host.combat;
        if (module == null || combatSettings == null) { closeCombat(); return; }
        if (combat != null && !combat.matches(combatSettings, combatLoadout)) closeCombat();
        if (combat == null) combat = module.open(this, combatSettings, combatLoadout);
        if (combat != null && !combat.tick()) closeCombat();
    }
    private void closeCombat() {
        ActorCombat current = combat;
        combat = null;
        if (current != null) current.close();
    }
    String displayName() { return displayName; }
    String navigationStatus() { return navigation(); }
    /** Combat movement: a walking leg towards the goal, at most 30 blocks across and 7 up or down per request. */
    CombatWorld.MoveStatus combatMove(WorldPosition goal, double speed) {
        if (seated || riding.active() || !navigable()) return CombatWorld.MoveStatus.REJECTED;
        Vec3 origin = state.snapshot().position().point(), delta = goal.point().subtract(origin);
        Vec3 clamped = new Vec3(origin.x() + clamp(delta.x(), 30), Math.floor(origin.y() + clamp(delta.y(), 7)), origin.z() + clamp(delta.z(), 30));
        try { navigate(new WorldPosition(goal.world(), clamped), MovementProfile.Mode.WALK, speed); return CombatWorld.MoveStatus.ACCEPTED; }
        catch (IllegalArgumentException refused) { return CombatWorld.MoveStatus.REJECTED; }
        catch (IllegalStateException busy) { return CombatWorld.MoveStatus.BUSY; }
    }
    private static double clamp(double value, double limit) { return Math.max(-limit, Math.min(limit, value)); }
    /** Same rules as {@link PersistedNpcService#resumePatrol}: a stopped recording continues from its cursor. */
    boolean resumePatrol() {
        if (!navigable() || route != null || routeRead != null || performance.run != null) return false;
        RecordingRun held = performance.held;
        if (held != null && held.step()) {
            WaypointProgress kept = stepProgress;
            if (!held.playback().finished() && kept != null && kept.current(stepLeg)) {
                waypoints = kept;
                performance.resume();
                publish(true);
                return true;
            }
            performance.held = null; forgetStep();
        }
        if (performance.resume()) { waypoints = null; publish(true); return true; }
        waypoints = WaypointSettings.parse(spec.definition()).map((WaypointSettings declared) -> progress(declared, WaypointProgress.INLINE)).orElse(null);
        if (waypoints != null) {
            if (routeReady()) beginLeg(waypoints.start(state.snapshot().position().point()));
            else routeReadWanted = true;
            publish(true);
            return true;
        }
        if (recordingSource != null) {
            performance.held = null;
            boolean started = startRead(null, null, recordingSource, true);
            publish(true);
            return started;
        }
        if (routeSource != null) routeReadWanted = true;
        return routeSource != null;
    }
    void playRecording(String reference, Optional<Playback> playback) {
        Objects.requireNonNull(playback);
        if (reference == null || !reference.matches(RecordingDocument.ID)) throw new IllegalArgumentException("Invalid recording identifier");
        if (!host.recordings().enabled()) throw new IllegalStateException(RECORDINGS_DISABLED);
        failWatch("cancelled: replaced by a recording");
        cancelRouteRead();
        stopRoute("cancelled: replaced by recording");
        waypoints = null; performance.held = null; forgetStep();
        if (!startRead(null, null, new RecordingSource(reference, playback), true)) throw new IllegalStateException("Recording reads are busy; retry shortly");
        publish(true);
    }
    /**
     * Places the NPC on clear ground at the destination. A destination this region holds is checked here; one another
     * region holds is checked by that region, which hands the result back to this NPC's owner. Moving there then hands
     * the NPC to that region like any chunk crossing. False when the destination is not known to be loaded.
     */
    boolean combatTeleport(WorldPosition destination) {
        if (!placeable(state.snapshot().active(), seated, riding)) return false;
        World world = host.world(destination.world());
        if (world == null) return false;
        ChunkPosition chunk = destination.chunk();
        double width = hullWidth(), height = hullHeight();
        if (host.regions.owns(world, chunk.x(), chunk.z(), chunk.x(), chunk.z())) {
            Optional<WorldPosition> spot = clearSpot(world, destination, width, height);
            spot.ifPresent(this::place);
            return spot.isPresent();
        }
        if (!host.knownLoaded(chunk)) return false;
        long epoch = owner.ownership().state().token() == null ? -1 : owner.ownership().state().token().epoch();
        return host.regions.execute(chunk, () -> clearSpot(world, destination, width, height).ifPresent((WorldPosition spot) -> owner.submit(new OwnerMailbox.Work() {
            @Override public void run() { if (placeable(state.snapshot().active(), seated, riding)) place(spot); }
            @Override public void refuse(String reason) { /* The NPC went dormant; it stays where it was. */ }
        })));
    }
    /** Whether combat may place the NPC somewhere else: it is active, sits on no seat and carries no rider. */
    static boolean placeable(boolean active, boolean seated, ActorRide riding) { return active && !seated && !riding.active(); }
    /** On the owner of the destination: a clear, supported spot in the destination's block column. */
    private Optional<WorldPosition> clearSpot(World world, WorldPosition destination, double width, double height) {
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        MotionValidator.Sampler terrain = LiveMovementProbe.owned(world, owned);
        Vec3 point = destination.point();
        for (int lift : new int[] {0, 1, -1, 2, -2}) {
            Vec3 spot = new Vec3(point.x(), Math.floor(point.y()) + lift, point.z());
            BodyTerrain.Rest rest = new BodyTerrain(width, height, false, 256, terrain).rest(spot.x(), spot.z(), spot.y(), 1 - 0.0001, 0, false);
            Vec3 feet = rest.kind() == BodyTerrain.Kind.GROUND ? new Vec3(spot.x(), rest.feet(), spot.z()) : spot;
            if (MotionValidator.check(feet, feet, width, height, MotionValidator.Medium.GROUND, true, 256, terrain).clear())
                return Optional.of(new WorldPosition(destination.world(), feet));
        }
        return Optional.empty();
    }
    private void place(WorldPosition spot) {
        VirtualNpcState.Snapshot before = state.snapshot();
        stopRoute("cancelled by combat lifecycle/position change");
        combatChanged(before, state.move(spot));
    }
    /** Where this NPC stands guard: its definition's position, unless it patrols waypoints or a reusable route. */
    Optional<WorldPosition> post() {
        if (!state.snapshot().active() || routeSource != null || WaypointSettings.parse(spec.definition()).isPresent()) return Optional.empty();
        return Optional.of(spec.position());
    }
    /** A short step onto clear, supported ground at the point, swept along the way, within ground this region holds. */
    boolean combatStep(WorldPosition to) {
        if (!placeable(state.snapshot().active(), seated, riding)) return false;
        WorldPosition from = state.snapshot().position();
        World world = host.world(to.world());
        if (world == null || !to.world().equals(from.world())) return false;
        ChunkPosition chunk = to.chunk();
        if (!host.regions.owns(world, chunk.x(), chunk.z(), chunk.x(), chunk.z())) return false;
        Optional<WorldPosition> spot = clearSpot(world, to, hullWidth(), hullHeight());
        if (spot.isEmpty()) return false;
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        if (!LiveMovementProbe.check(world, owned, from.point(), spot.get().point(), hullWidth(), hullHeight(), MotionValidator.Medium.GROUND, true, 256).clear()) return false;
        place(spot.get());
        return true;
    }
    /** Back to the definition's facing, standing. */
    void restorePose() { setPose(spec.yaw(), spec.pitch(), true); publish(true); }
    /** Knockback: a short slide onto clear, supported ground this region holds; otherwise the NPC stays put. */
    void combatKnockback(Vec3 direction, double strength) {
        double horizontal = Math.hypot(direction.x(), direction.z());
        if (horizontal < .0001 || !placeable(state.snapshot().active(), seated, riding)) return;
        double distance = Math.min(1.5, strength);
        WorldPosition from = state.snapshot().position();
        World world = host.world(from.world());
        if (world == null) return;
        Vec3 to = from.point().add(new Vec3(direction.x() / horizontal * distance, 0, direction.z() / horizontal * distance));
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        if (!LiveMovementProbe.check(world, owned, from.point(), to, hullWidth(), hullHeight(), MotionValidator.Medium.GROUND, true, 256).clear()) return;
        place(new WorldPosition(from.world(), to));
    }
    void combatHeal(double amount) { VirtualNpcState.Snapshot before = state.snapshot(); combatChanged(before, state.heal(amount)); }
    /** A dead NPC comes back at the point, which may hand it to another region. Fires its spawn trigger again. */
    boolean combatRevive(WorldPosition at) {
        if (state.snapshot().health() > 0 || !DefinitionSpec.spawned(host.runtime.npcs().snapshot().get(id))) return false;
        host.index.upsert(state.respawn(at));
        pose++;
        host.activated(uuid);
        publish(true);
        return true;
    }
    /** Owner only: publishes a combat change of the authoritative state and turns it into damage and death triggers. */
    void combatChanged(VirtualNpcState.Snapshot before, VirtualNpcState.Snapshot after) {
        boolean moved = !before.position().equals(after.position());
        if (after.active()) host.index.upsert(after); else host.index.remove(uuid);
        if (!after.active() || moved) stopRoute("cancelled by combat lifecycle/position change");
        if (moved) pose++;
        if (after.health() < before.health()) host.damaged(uuid, before.health() - after.health(), after.health() == 0);
        publish(true);
    }

    // ---- Mounted NPCs: one owner for each mounted group ----

    /**
     * Owner of this NPC's group only. A passenger whose vehicle this thread also owns boards the vehicle's group and sits
     * on its seat every turn after the vehicle moved. A vehicle another region owns is reached by stepping onto its
     * published seat, which hands this NPC to that region like any chunk crossing; it boards there. When the relation
     * ends the passenger leaves the group and returns to its definition position.
     */
    private void mounts() {
        String vehicleId = host.mounts.vehicleOf().get(id);
        RegionActor vehicle = vehicleId == null ? null : host.actors.get(vehicleId);
        if (vehicle == this) vehicle = null;
        if (owner.carried()) {
            if (vehicle == null || vehicle.owner.root() != owner.root() || !vehicle.state.snapshot().active()) { owner.alight(); return; }
            sit(vehicle);
            return;
        }
        if (vehicleId == null) {
            if (seated) {
                seated = false;
                state.move(spec.position()); host.index.upsert(state.snapshot()); setPose(spec.yaw(), spec.pitch(), true); pose++;
            }
            return;
        }
        if (route != null || waypoints != null || routeRead != null) { cancelRouteRead(); waypoints = null; stopRoute("unavailable: this NPC rides " + vehicleId); }
        riding.end("npc-mounted", ActorRide.Exit.RESTORE_POSITION);
        if (vehicle == null || !state.snapshot().active()) return;
        if (vehicle.owner.owned() && vehicle.state.snapshot().active()) {
            if (owner.board(vehicle.owner)) sit(vehicle);
            return;
        }
        if (turns < nextMountTry) return;
        nextMountTry = turns + MOUNT_RETRY_TICKS;
        Publication view = vehicle.publication;
        if (view == null || !view.live() || !view.snapshot().position().world().equals(state.snapshot().position().world())) return;
        Vec3 seat = host.geometry(view.type()).seat().passengerFeet(view.snapshot().position().point(), view.yaw(), host.geometry(spec.type()).seat().vehicleAttachmentY());
        // The vehicle's chunk is loaded: its owner just published it there. The step hands this NPC to that owner.
        state.move(new WorldPosition(view.snapshot().position().world(), seat)); host.index.upsert(state.snapshot()); pose++;
    }
    /** Group owner only: the passenger takes its seat on the vehicle's live state, which the same thread holds. */
    private void sit(RegionActor vehicle) {
        VirtualNpcState.Snapshot carrier = vehicle.state.snapshot();
        Vec3 seat = host.geometry(vehicle.spec.type()).seat().passengerFeet(carrier.position().point(), vehicle.yaw, host.geometry(spec.type()).seat().vehicleAttachmentY());
        WorldPosition current = state.snapshot().position();
        if (!current.point().equals(seat) || !current.world().equals(carrier.position().world())) {
            state.move(new WorldPosition(carrier.position().world(), seat)); host.index.upsert(state.snapshot()); pose++;
        }
        setPose(vehicle.yaw, pitch, false);
        seated = true;
    }
    /** Passengers seated on this NPC now, in declaration order. Owner of the group only. */
    private List<UUID> passengers() {
        List<String> declared = host.mounts.passengersOf().get(id);
        if (declared == null) return List.of();
        List<UUID> seats = new ArrayList<>(declared.size());
        for (String passenger : declared) {
            RegionActor actor = host.actors.get(passenger);
            if (actor != null && actor.seated && actor.owner.carried() && actor.owner.root() == owner.root()) seats.add(actor.uuid);
        }
        return List.copyOf(seats);
    }
    /** For inspect, from any thread. */
    String mountStatus() {
        StringBuilder summary = new StringBuilder();
        String vehicle = host.mounts.vehicleOf().get(id);
        if (vehicle != null) summary.append("; mounted-on=").append(vehicle).append(seated ? "" : " (not seated yet: the vehicle is not active on a loaded chunk)");
        List<String> passengers = host.mounts.passengersOf().get(id);
        if (passengers != null) summary.append("; passengers=").append(passengers);
        for (art.arcane.animus.core.mount.MountRelations.Diagnostic problem : host.mounts.diagnostics())
            if (problem.id().equals(id)) summary.append("; mount-problem=").append(problem.field()).append(": ").append(problem.message());
        return summary.toString();
    }

    // ---- A real player riding this NPC ----

    /** On the thread that owns both the NPC and the player. Null once admitted, otherwise a message for the player. */
    String requestRide(Player player) { return riding.request(player, ride); }
    void rideAttached(UUID generation, boolean succeeded, String failure) { riding.attached(generation, succeeded, failure); publish(true); }
    void endRide(UUID generation, String reason, ActorRide.Exit exit) { riding.end(generation, reason, exit); }
    String rideSummary() { return riding.summary(); }
    String rideLine() { return riding.line(); }
    /** A ride takes the body from any route. */
    void claimForRide() { cancelRouteRead(); waypoints = null; stopRoute("cancelled: a player is riding"); publish(true); }
    /** One accepted ride step, published before any packet is sent. */
    void rideStep(Vec3 position, float nextYaw) {
        WorldPosition at = state.snapshot().position();
        if (!position.equals(at.point())) { state.move(new WorldPosition(at.world(), position)); host.index.upsert(state.snapshot()); }
        pose++; setPose(nextYaw, 0, true);
    }
    /** The ride ended: every viewer is sent the authoritative position again. */
    void rideReleased() { pose++; publish(state.snapshot().active()); }

    // ---- Navigation, on the owner ----

    UUID navigate(WorldPosition destination, MovementProfile.Mode mode, double speed) {
        PersistedNpcService.validateLeg(state.snapshot().position(), destination, mode, speed, hullWidth(), hullHeight());
        failWatch("cancelled: replaced by a new route");
        stopRoute("cancelled: replaced by manual route");
        cancelRouteRead();
        waypoints = null;
        route = route(destination, mode, speed, null);
        navigationStatus = "queued; route=" + route.id;
        publish(true);
        return route.id;
    }
    boolean stopNavigation() {
        boolean running = route != null || routeReadWanted || routeRead != null || performance.run != null || (waypoints != null && !waypoints.complete());
        cancelRouteRead(); waypoints = null; stopRoute("cancelled by administrator"); failWatch("cancelled: navigation stopped");
        publish(true);
        return running;
    }
    void pose(String name) {
        art.arcane.animus.core.npc.NamedPoses.Facing facing = art.arcane.animus.core.npc.NamedPoses.parse(spec.definition().json()).find(name)
                .orElseThrow(() -> new IllegalArgumentException("The NPC has no pose named " + name));
        setPose(facing.yaw(), facing.pitch(), onGround);
        publish(true);
    }
    void lookAt(WorldPosition target) {
        WorldPosition from = state.snapshot().position();
        if (!from.world().equals(target.world()) || from.point().distance(target.point()) > 64)
            throw new IllegalArgumentException("Look target must be within64 blocks in the same world");
        Optional<LookClose.Facing> facing = LookClose.facing(LookClose.eye(from.point(), bodyHeight()), target.point());
        if (facing.isEmpty()) return;
        setPose(facing.get().yaw(), facing.get().pitch(), onGround);
        publish(true);
    }
    /**
     * animus:look-close on the owner: at most every few ticks, charged to the region's look budget. Only players this region
     * owns are candidates; a player in view distance counts as viewing, since viewer state belongs to the player's region.
     */
    private void look() {
        LookClose look = lookClose;
        if (look == null || turns < nextLook || mount || seated || riding.active() || !navigable()) return;
        if (!look.whileNavigating() && (routeRead != null || (route != null && (route.plan != null || route.execution != null)))) return;
        // A playing recording faces where the recorder looked.
        if (performance.run != null) return;
        if (!host.budget.cosmeticAllowed() || host.budget.take(RegionBudget.Kind.LOOKS, 1) == 0) return;
        nextLook = turns + LookClose.INTERVAL_TICKS;
        WorldPosition at = state.snapshot().position();
        World world = host.world(at.world());
        if (world == null) return;
        Vec3 eye = LookClose.eye(at.point(), bodyHeight());
        List<LookClose.Candidate<Player>> candidates = new ArrayList<>();
        for (Player player : host.players().ownedNear(new WorldPosition(at.world(), eye), look.range() + 2, 32)) {
            Location location = player.getEyeLocation();
            Vec3 playerEye = new Vec3(location.getX(), location.getY(), location.getZ());
            candidates.add(new LookClose.Candidate<>(player, playerEye, playerEye.distance(at.point()) <= host.settings().viewDistance()));
        }
        boolean looked = false;
        for (LookClose.Candidate<Player> candidate : look.ranked(eye, candidates)) {
            if (look.lineOfSight() && !clearSight(world, candidate.eye(), eye)) continue;
            LookClose.facing(eye, candidate.eye()).ifPresent((LookClose.Facing facing) -> setPose(facing.yaw(), facing.pitch(), onGround));
            looked = true;
            break;
        }
        if (!looked && look.npcs()) looked = lookAtNpc(at, eye, look.range());
        if (!looked && look.randomLook().isPresent() && look.randomLook().get().active() && turns >= nextRandomLook) {
            LookClose.RandomLook random = look.randomLook().get();
            LookClose.Facing facing = random.pick(java.util.concurrent.ThreadLocalRandom.current());
            setPose(facing.yaw(), facing.pitch(), onGround);
            nextRandomLook = turns + random.delayTicks();
        }
    }
    /** NPCs scanned for a look-close NPC target: a few nearby chunks and candidates at most. */
    private static final SpatialNpcIndex.Budget NPC_LOOK_BUDGET = new SpatialNpcIndex.Budget(16, 32);
    /** Turns toward the nearest other active NPC in range; false when there is none. Other NPCs use the standard body height. */
    private boolean lookAtNpc(WorldPosition at, Vec3 eye, double range) {
        Vec3 nearest = null;
        double best = range;
        SpatialNpcIndex.Scan scan = host.index.scan(new SpatialNpcIndex.Query(at, range), null, NPC_LOOK_BUDGET, (ChunkPosition chunk) -> true);
        for (VirtualNpcState.Snapshot other : scan.candidates()) {
            if (other.npcId().equals(uuid) || !other.active() || !other.position().world().equals(at.world())) continue;
            Hitbox box = other.bounds();
            Vec3 target = LookClose.eye(other.position().point(), box.max().y() - box.min().y());
            double distance = target.distance(eye);
            if (distance <= best) { best = distance; nearest = target; }
        }
        if (nearest == null) return false;
        LookClose.facing(eye, nearest).ifPresent((LookClose.Facing facing) -> setPose(facing.yaw(), facing.pitch(), onGround));
        return true;
    }
    /** Same rule as interaction checks: every chunk on the way is owned here and loaded, and no collidable block lies between. */
    private boolean clearSight(World world, Vec3 from, Vec3 to) {
        int minX = ((int) Math.floor(Math.min(from.x(), to.x()))) >> 4, maxX = ((int) Math.floor(Math.max(from.x(), to.x()))) >> 4;
        int minZ = ((int) Math.floor(Math.min(from.z(), to.z()))) >> 4, maxZ = ((int) Math.floor(Math.max(from.z(), to.z()))) >> 4;
        if (!host.regions.owns(world, minX, minZ, maxX, maxZ)) return false;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!world.isChunkLoaded(x, z)) return false;
        double distance = from.distance(to);
        if (distance <= .000001) return true;
        Vec3 direction = to.subtract(from).normalized();
        return world.rayTraceBlocks(new Location(world, from.x(), from.y(), from.z()), new Vector(direction.x(), direction.y(), direction.z()),
                Math.max(0, distance - .0001), FluidCollisionMode.NEVER, true) == null;
    }
    void followRoute(String reference) {
        if (reference == null || !reference.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid reusable route identifier");
        failWatch("cancelled: replaced by a new route");
        cancelRouteRead();
        stopRoute("cancelled: replaced by reusable route");
        waypoints = null; forgetStep();
        if (!startRead(reference, null, null, true)) throw new IllegalStateException("Reusable route reads are busy; retry shortly");
        navigationStatus = "loading reusable route " + reference;
        publish(true);
    }
    CompletableFuture<Void> awaitRoute() {
        if (route == null && routeRead == null) throw new IllegalStateException("The NPC has no route to wait for");
        failWatch("cancelled: replaced by a new wait");
        routeWatch = new CompletableFuture<>();
        return routeWatch;
    }
    void setAppearance(Optional<String> name, Optional<String> reference, boolean reset) {
        NpcPresentation.validateChange(spec, name, reference);
        cancelAppearanceRead();
        if (reset) { displayName = spec.name(); displaySkin = skin; }
        else {
            if (name.isPresent()) displayName = name.get();
            if (reference.isPresent()) { readAppearance(reference.get()); return; }
        }
        appearance++;
        publish(true);
    }
    void setEquipment(EquipmentSlot slot, ItemStack item) {
        if (!host.packets.equipmentAvailable()) throw new IllegalStateException("Equipment packets are unavailable on this server version");
        equipment.put(slot, item.clone());
        equipmentRevision++;
        publish(true);
    }
    /** A runtime attribute change; see {@link RuntimeAttributes}. A new scale resizes the hitbox and presents the body and its rig again. */
    void setAttribute(String attribute, OptionalDouble next) {
        NpcAttributes updated = RuntimeAttributes.change(spec.appearance().traits().attributes(), attributes, attribute, next);
        if (updated.equals(attributes)) return;
        double scale = attributes.scale();
        attributes = updated;
        attributeRevision++;
        JsonObject definition = RuntimeAttributes.definition(spec.definition().json(), updated);
        movement = MovementSettings.parse(definition);
        falls = MovementSettings.falls(spec.type(), definition);
        if (updated.scale() != scale) {
            RegionNpcRuntime.Geometry geometry = host.geometry(spec.type());
            state.resize(geometry.width() * updated.scale(), geometry.height() * updated.scale());
            host.index.upsert(state.snapshot());
            rig = null; rigGeneration = -1;
            appearance++;
        }
        publish(true);
    }
    WorldPosition position() { return state.snapshot().position(); }

    // ---- Builds, on the owner ----

    /** A route is running or about to be planned. */
    boolean routing() { return route != null || routeRead != null; }
    /** Swings the arm for every viewer, which sees the new count on its own region. */
    void swing() { swings++; publish(true); }
    /** Shows one attack; viewers send it with moves, not after cosmetic work. */
    void attackSwing() { attackSwings++; publish(true); }
    /**
     * Moves the NPC at once onto clear, supported ground this region holds and has loaded, as a build's last resort;
     * false leaves it where it is. A move into another chunk hands the NPC on at the end of its next turn.
     */
    boolean relocate(WorldPosition destination) {
        if (seated || mount || riding.active() || !owner.riders().isEmpty() || !state.snapshot().active() || !destination.world().equals(state.snapshot().position().world())) return false;
        World world = host.world(destination.world());
        if (world == null) return false;
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        ChunkPosition chunk = destination.chunk();
        if (!owned.owns(chunk.x(), chunk.z()) || !world.isChunkLoaded(chunk.x(), chunk.z())) return false;
        if (!LiveMovementProbe.check(world, owned, destination.point(), destination.point(), hullWidth(), hullHeight(), MotionValidator.Medium.GROUND, true, 256).clear())
            return false;
        place(destination);
        return true;
    }

    private void navigate() {
        Route current = route;
        if (turns < current.nextAttempt) return;
        if (!state.snapshot().active()) { stopRoute("cancelled by definition/lifecycle change"); return; }
        // A patrol with no player near sleeps: no turn, plan or budget until a viewer marks it again.
        if ((current.leg != null || current.recording != null) && routeWatch == null && !watched()) {
            if (!routeSleeping) {
                routeSleeping = true; cancelPlan(); doors.closeAll(host.doors);
                if (current.recording != null) current.recording.paused(RecordingRun.Phase.SLEEPING);
                routeStatus(current, "sleeping", "no player within " + (int) host.settings().viewDistance() + " blocks");
            }
            routeWaited = 0;
            return;
        }
        if (routeSleeping) {
            routeSleeping = false; current.progressTurn = turns;
            if (current.recording != null) current.recording.retry();
            if (current.recording != null && current.walk) current.deadline = turns + RecordingSteps.walkTicks(state.snapshot().position().point().distance(current.goal.point()), current.speed);
            routeStatus(current, "queued", "a player is near");
        }
        if (current.leg != null && current.leg.pause()) {
            route = null;
            if (waypoints != null) beginLeg(waypoints.resume(current.leg, state.snapshot().position().point()));
            return;
        }
        if (current.leg != null && current.leg.step() != null && current.recording == null) {
            route = null;
            startStep(current.leg);
            return;
        }
        World world = host.world(current.goal.world());
        if (world == null) { stopRoute("cancelled: world unavailable"); return; }
        if (current.recording != null && !host.recordings().enabled()) {
            routeError = RECORDINGS_DISABLED; performance.end("unavailable: " + RECORDINGS_DISABLED); return;
        }
        if (current.recording != null && current.walk && turns > current.deadline) {
            failLeg(current, "unreachable: the walk to the recording took longer than " + (current.deadline - current.startTurn) + " ticks"); return;
        }
        if ((current.leg != null || current.recording != null) && movement.stuck(turns - current.progressTurn)) { failLeg(current, "stuck: no progress for " + (turns - current.progressTurn) + " ticks"); return; }
        int waited = routeWaited;
        if (!host.budget.takeRoute(waited)) { routeWaited = waited + 1; return; }
        routeWaited = 0;
        if (current.recording != null && !current.walk) {
            try { if (!performance.turn(current.recording)) { host.budget.routeRefused(waited); routeWaited = waited + 1; } }
            catch (RuntimeException failure) { stopRoute("failed: " + PersistedNpcService.concise(failure)); }
            return;
        }
        ChunkOwnership owned = (x, z) -> host.regions.owns(world, x, z, x, z);
        try {
            if (current.execution == null) {
                if (current.plan == null) {
                    BlockPos feet = BlockPos.containing(state.snapshot().position().point()), goal = BlockPos.containing(current.goal.point());
                    LongLeg.Request request = current.longLeg.request(state.snapshot().position().point());
                    if (!loaded(new ChunkPosition(current.goal.world(), Math.floorDiv(feet.x(), 16), Math.floorDiv(feet.z(), 16)))
                            || (request.direct() && !loaded(new ChunkPosition(current.goal.world(), Math.floorDiv(goal.x(), 16), Math.floorDiv(goal.z(), 16))))) {
                        suspendRoute("unavailable terrain; no chunk loading requested"); return;
                    }
                    if (fallBeforeLeg(current, world, owned)) return;
                    CaptureBounds bounds = request.bounds();
                    // A long leg's segment reads only the chunks this region owns; the rest is unavailable, so it walks to
                    // the region's edge and the handoff moves it on. A whole leg must lie in this region.
                    if (request.direct() && !host.regions.owns(current.goal.world(), Math.floorDiv(bounds.minX(), 16), Math.floorDiv(bounds.minZ(), 16),
                            Math.floorDiv(bounds.minX() + bounds.sizeX() - 1, 16), Math.floorDiv(bounds.minZ() + bounds.sizeZ() - 1, 16))) {
                        suspendRoute("route terrain belongs to another region; retrying"); return;
                    }
                    if (host.budget.take(RegionBudget.Kind.ROUTE_PLANS, 1) == 0) {
                        host.budget.routeRefused(waited); routeWaited = waited + 1;
                        routeStatus(current, "queued", "waiting for a planning turn"); return;
                    }
                    current.plan = host.navigation.plan(world.getUID().toString(), bounds, feet, request.aim(), profile(current.mode), request.limits());
                    current.result = current.plan.future().toCompletableFuture();
                    routeStatus(current, "planning", "");
                }
                if (!current.result.isDone()) {
                    int units = host.budget.take(RegionBudget.Kind.CAPTURE, 256);
                    host.budget.refund(RegionBudget.Kind.CAPTURE, units - host.navigation.capture(current.plan, world, owned, turns, units));
                }
                if (current.result.isDone()) {
                    PathPlanner.Result planned = current.result.join();
                    current.capture = current.plan.capture().orElse(null); current.plan = null; current.result = null;
                    if (planned.status() == PathPlanner.Status.UNAVAILABLE_TERRAIN) suspendRoute("unavailable captured terrain");
                    else if (planned.status() == PathPlanner.Status.CANCELLED) { /* released for a handoff; the new owner plans again */ }
                    else {
                        LongLeg.Next next = current.longLeg.planned(planned);
                        if (next == LongLeg.Next.BLOCKED) blockLeg(current, "failed: " + planned.status());
                        else if (next == LongLeg.Next.RETRY) routeStatus(current, "replanning", "attempt " + current.longLeg.attempts() + " after " + planned.status());
                        else {
                            current.segment = next == LongLeg.Next.SEGMENT;
                            Vec3 end = next == LongLeg.Next.FINAL ? current.goal.point() : planned.path().getLast().point();
                            current.execution = new PathExecution(state.snapshot().position().point(), planned.path(), end, profile(current.mode));
                            routeStatus(current, "moving", PersistedNpcService.moving(current.segment) + "; region owner");
                        }
                    }
                }
            }
            if (route == current && current.execution != null && !step(current, world, owned)) { host.budget.routeRefused(waited); routeWaited = waited + 1; }
        } catch (RejectedExecutionException full) { current.nextAttempt = turns + 20; routeStatus(current, "queued", "path capacity"); }
        catch (RuntimeException failure) { if (route == current) failLeg(current, "failed: " + PersistedNpcService.concise(failure)); else stopRoute("failed: " + PersistedNpcService.concise(failure)); }
    }
    /** One kinematic step. False when the region had too few collision cells left this tick, so the route waits. */
    private boolean step(Route current, World world, ChunkOwnership owned) {
        if (!current.execution.position().equals(state.snapshot().position().point())) { stopRoute("cancelled: authoritative position changed"); return true; }
        if (current.execution.finished()) { executed(current); return true; }
        PathExecution.Proposal step = current.execution.next(current.speed / 20).orElseThrow();
        int sweep = PersistedNpcService.motionCells(hullWidth(), hullHeight(), step.from().distance(step.to()));
        int settle = PersistedNpcService.motionCells(hullWidth(), hullHeight(), 0);
        int wanted = Math.min(host.navigationSettings().motionCellsPerTick(), sweep + (step.landing() ? settle : 0));
        int granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); return false; }
        if (current.mode == MovementProfile.Mode.WALK && movement.openDoors()) {
            Optional<BlockPos> door = doors.prepare(current.goal.world(), step.from(), step.to(), hullWidth(), hullHeight(), LiveMovementProbe.owned(world, owned), host.doors);
            if (door.isPresent()) {
                host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted);
                failLeg(current, "blocked: the door at " + door.get().x() + " " + door.get().y() + " " + door.get().z() + " could not be opened");
                return true;
            }
        }
        MotionValidator.Result validation = LiveMovementProbe.check(world, owned, step.from(), step.to(), hullWidth(), hullHeight(), step.medium(), step.requireSupport(), sweep);
        int used = validation.sampledCells();
        if (validation.clear() && step.landing()) {
            validation = LiveMovementProbe.check(world, owned, step.to(), step.to(), hullWidth(), hullHeight(),
                    current.mode == MovementProfile.Mode.SWIM ? MotionValidator.Medium.WATER : MotionValidator.Medium.GROUND,
                    current.mode != MovementProfile.Mode.SWIM, settle);
            used += validation.sampledCells();
        }
        host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted - used);
        if (validation.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) suspendRoute(validation.detail());
        else if (!validation.clear()) blockedStep(current, "blocked: " + validation.status() + "; " + validation.detail());
        else {
            current.execution.commit(step);
            Vec3 delta = step.to().subtract(step.from());
            float facing = Math.hypot(delta.x(), delta.z()) > .000001 ? (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())) : yaw;
            state.move(new WorldPosition(current.goal.world(), step.to()));
            host.index.upsert(state.snapshot());
            pose++; setPose(facing, 0, step.onGround());
            doors.passed(step.to(), hullWidth(), host.doors);
            current.progressTurn = turns;
            routeStatus(current, "moving", PersistedNpcService.moving(current.segment) + "; region owner");
            if (movement.arrived(step.to(), current.goal.point())) arrive(current);
            else if (current.execution.finished()) executed(current);
        }
        return true;
    }
    /** Same long leg rules as {@link PersistedNpcService}: a walked segment plans the next one, the last one arrives. */
    private void executed(Route current) {
        if (!current.segment) { arrive(current); return; }
        current.execution = null; current.segment = false;
        routeStatus(current, "queued", "next long leg segment");
    }
    private void blockedStep(Route current, String reason) {
        if (current.longLeg.blocked() == LongLeg.Next.BLOCKED) { blockLeg(current, reason); return; }
        current.execution = null; current.segment = false;
        routeStatus(current, "replanning", reason);
    }
    /** Terrain blocks the leg for good: logged once per NPC with the leg, then the route fails as any failed leg does. */
    private void blockLeg(Route current, String reason) {
        if (current.recording != null) { failLeg(current, reason); return; }
        if (exactPoint(current, reason)) return;
        boolean once = AnimusLog.shared().debug();
        if (!once || !blockedWarned) {
            if (once) blockedWarned = true;
            AnimusLog.shared().routine(PersistedNpcService.BLOCKED_LEGS, id, PersistedNpcService.blockedLeg(id, current.waypoint, current.origin, current.goal, reason,
                    current.leg != null && movement.teleportsWhenStuck()), host.plugin.getLogger()::warning);
        }
        failLeg(current, reason);
    }
    /** Owner only: a solid block, in a chunk this region holds, lies under the feet. */
    private boolean grounded() {
        WorldPosition at = state.snapshot().position();
        World world = host.world(at.world());
        int x = (int) Math.floor(at.point().x()), y = (int) Math.floor(at.point().y() - 0.000001), z = (int) Math.floor(at.point().z());
        if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight() || !host.regions.owns(world, x >> 4, z >> 4, x >> 4, z >> 4) || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
        return world.getBlockAt(x, y, z).getType().isSolid();
    }
    /** True while a viewer within view distance marked this NPC recently. Any thread. */
    boolean watched() {
        long seen = watchedNanos;
        return seen != 0 && System.nanoTime() - seen < WATCHED_NANOS;
    }
    /**
     * A walking body standing in the air falls one step per turn before it plans a leg, so the leg starts on the ground
     * instead of failing from the air. True while it falls; the step is charged to the region's motion cells.
     */
    private boolean fallBeforeLeg(Route current, World world, ChunkOwnership owned) {
        if (current.mode != MovementProfile.Mode.WALK || mount || !falls) return false;
        int wanted = IdleFall.cells(hullWidth(), hullHeight()), granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); return false; }
        boolean swims = BodyMobility.forBody(spec.type(), hullWidth(), hullHeight()).supports(MovementProfile.Mode.SWIM);
        WorldPosition at = state.snapshot().position();
        IdleFall.Step step = IdleFall.step(at.point(), fallSpeed, movement.gravity(), hullWidth(), hullHeight(), swims, LiveMovementProbe.owned(world, owned));
        host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted - step.sampled());
        fallSpeed = step.speed();
        boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
        if (falling || step.outcome() == IdleFall.Outcome.LANDED) {
            state.move(new WorldPosition(at.world(), step.to()));
            host.index.upsert(state.snapshot());
            pose++; setPose(yaw, pitch, !falling);
        }
        if (falling) routeStatus(current, "falling", "to the ground before the leg starts");
        return falling;
    }
    /** Idle gravity: one bounded step per owner tick while the NPC falls, charged to the region's simulation turns and motion cells. */
    private void support() {
        supportWanted = false;
        World world = mount || !navigable() || !falls ? null : host.world(state.snapshot().position().world());
        if (world == null) { fallSpeed = 0; return; }
        if (!host.budget.takeSimulation()) { supportWanted = true; return; }
        int wanted = IdleFall.cells(hullWidth(), hullHeight()), granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); supportWanted = true; return; }
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        boolean swims = BodyMobility.forBody(spec.type(), hullWidth(), hullHeight()).supports(MovementProfile.Mode.SWIM);
        WorldPosition at = state.snapshot().position();
        IdleFall.Step step = IdleFall.step(at.point(), fallSpeed, movement.gravity(), hullWidth(), hullHeight(), swims, LiveMovementProbe.owned(world, owned));
        host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted - step.sampled());
        fallSpeed = step.speed();
        boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
        if (falling || step.outcome() == IdleFall.Outcome.LANDED) {
            state.move(new WorldPosition(at.world(), step.to()));
            host.index.upsert(state.snapshot());
            pose++; setPose(yaw, pitch, !falling);
        }
    }
    /** Arrival keeps doors the NPC still stands in only while another leg follows; a finished route closes them all. */
    private void arrive(Route current) {
        arriveAt(current);
        if (route == null) doors.closeAll(host.doors);
        else doors.passed(state.snapshot().position().point(), hullWidth(), host.doors);
    }
    private void arriveAt(Route current) {
        if (route != current) return;
        if (current.recording != null) { route = null; performance.arrived(current.recording); return; }
        WaypointProgress progress = waypoints;
        route = null; routeStatus(current, "arrived", ""); arrivals++;
        host.plugin.getServer().getPluginManager().callEvent(new VirtualNpcWaypointEvent(uuid, id, current.id, current.waypoint, state.snapshot().position()));
        host.waypoint(uuid, current.waypoint);
        if (progress == null) { finishWatch(); return; }
        if (!navigable() || route != null || waypoints != progress) return;
        Optional<WaypointProgress.Leg> next = current.leg == null ? Optional.empty() : progress.arrived(current.leg, state.snapshot().position().point());
        if (next.isPresent()) beginLeg(next);
        else finishWatch();
    }
    private WaypointProgress progress(WaypointSettings declared, String route) { return WaypointProgress.of(declared, spec.position().point(), uuid, route); }
    /** Same leg rules as {@link PersistedNpcService}: a pause leg holds the route until its wait ends. */
    private void beginLeg(Optional<WaypointProgress.Leg> next) {
        WaypointProgress progress = waypoints;
        if (progress == null || next.isEmpty() || !state.snapshot().active()) return;
        WaypointProgress.Leg leg = next.get();
        WaypointSettings settings = progress.settings();
        WorldPosition at = state.snapshot().position();
        WorldPosition goal = leg.goal() == null ? at : new WorldPosition(at.world(), leg.goal());
        double speed = movement.routeSpeed(routeSpeedDeclared, settings.speed());
        if (leg.goal() != null) {
            try { PersistedNpcService.validateLeg(at, goal, settings.mode(), speed, hullWidth(), hullHeight()); }
            catch (IllegalArgumentException invalid) {
                if (leg.jittered()) { beginLeg(progress.exact(leg)); return; }
                navigationStatus = "invalid waypoint " + leg.arrival() + ": " + invalid.getMessage(); failWatch(navigationStatus);
                Optional<WaypointProgress.Leg> retry = progress.failed(leg);
                if (retry.isPresent()) beginLeg(retry);
                return;
            }
        }
        route = route(goal, settings.mode(), speed, leg);
        route.nextAttempt = turns + Math.max(1, leg.waitTicks());
        route.progressTurn = turns;
        routeStatus(route, leg.pause() ? "waiting to retry" : "queued", "");
    }
    /** The waypoints can start: they have no recording steps, or the recordings those steps play were read. */
    private boolean routeReady() { return waypoints != null && (waypoints.recordings() != null || waypoints.settings().recordings().isEmpty()); }
    /** The definition's inline route when it has recording steps, whose recordings a route load reads; otherwise null. */
    private WaypointSettings inlineSteps() {
        try {
            WaypointSettings inline = WaypointSettings.parse(spec.definition()).orElse(null);
            return inline == null || inline.recordings().isEmpty() ? null : inline;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static final String STEP_ERROR = "recording step ";
    /** Same rules as {@link PersistedNpcService}: walk in to the segment's first frame and play it, or block the leg. */
    private void startStep(WaypointProgress.Leg leg) {
        WaypointProgress progress = waypoints;
        if (progress == null || !progress.current(leg) || !state.snapshot().active()) return;
        if (!host.recordings().enabled()) { stepFailed(leg, RECORDINGS_DISABLED); return; }
        RouteRecordings recordings = progress.recordings() == null ? RouteRecordings.NONE : progress.recordings();
        RouteRecordings.Start start = recordings.start(leg.arrival(), leg.step(), state.snapshot().position().world(),
                (String reference) -> Optional.ofNullable(host.worldKey(reference)));
        if (start.run() == null) { stepFailed(leg, start.problem()); return; }
        if (routeError != null && routeError.startsWith(STEP_ERROR)) routeError = null;
        stepLeg = leg; stepProgress = progress; performance.held = null;
        startRecording(start.run());
    }
    /** A step that cannot play or finish is a blocked leg under the route's failure rules. */
    private void stepFailed(WaypointProgress.Leg leg, String reason) {
        WaypointProgress progress = waypoints;
        stepLeg = null; stepProgress = null;
        routeError = STEP_ERROR + (leg == null ? "" : leg.arrival() + ": ") + reason;
        navigationStatus = "blocked: " + routeError;
        failWatch(navigationStatus);
        if (progress == null || leg == null || !progress.current(leg)) return;
        String failure = navigationStatus;
        beginLeg(progress.failed(leg));
        if (route != null) routeStatus(route, "waiting to retry", "after " + failure);
    }
    /** A step played to toTick: its waypoint is reached where the segment ended, and the route goes on from there. */
    private void stepReached(UUID routeId) {
        WaypointProgress progress = waypoints; WaypointProgress.Leg leg = stepLeg;
        stepLeg = null; stepProgress = null;
        if (leg == null) return;
        arrivals++;
        navigationStatus = "arrived; route=" + routeId + "; waypoint=" + leg.arrival() + "; recording step";
        host.plugin.getServer().getPluginManager().callEvent(new VirtualNpcWaypointEvent(uuid, id, routeId, leg.arrival(), state.snapshot().position()));
        host.waypoint(uuid, leg.arrival());
        if (progress == null) { finishWatch(); return; }
        if (!navigable() || route != null || waypoints != progress || !progress.current(leg)) return;
        Optional<WaypointProgress.Leg> next = progress.arrived(leg, state.snapshot().position().point());
        if (next.isPresent()) beginLeg(next);
        else finishWatch();
    }
    /** Drops a stopped step and its route, so a later resumePatrol starts the declared route rather than the old step. */
    private void forgetStep() {
        if (performance.held != null && performance.held.step()) performance.held = null;
        if (performance.run == null || !performance.run.step()) { stepLeg = null; stepProgress = null; }
    }
    /** An unchanged route whose step recording changed: a step playing it restarts, a stopped one forgets its cursor. */
    private void recordingsReloaded(RouteRecordings before, RouteRecordings after) {
        RecordingRun playing = performance.run;
        if (playing != null && playing.step() && after.changed(before, playing.source().recording())) {
            WaypointProgress.Leg leg = stepLeg;
            stopRoute("recording step restarted: recording changed");
            performance.held = null;
            startStep(leg);
            return;
        }
        RecordingRun held = performance.held;
        if (held != null && held.step() && after.changed(before, held.source().recording())) performance.held = null;
    }
    /** A leg that cannot finish: random and wander routes pause and choose again, every other route halts. */
    private void failLeg(Route current, String reason) {
        if (current.recording != null) {
            if (current.plan != null) current.plan.cancel();
            if (current.execution != null) current.execution.cancel();
            if (route == current) route = null;
            performance.walkFailed(current.recording, reason);
            return;
        }
        if (exactPoint(current, reason) || teleportStuck(current)) return;
        WaypointProgress progress = waypoints;
        stopRoute(reason);
        if (progress == null || current.leg == null || !progress.retries() || route != null || !navigable()) return;
        String failure = navigationStatus;
        beginLeg(progress.failed(current.leg));
        if (route != null) routeStatus(route, "waiting to retry", "after " + failure);
    }
    /** Same jitter fallback as {@link PersistedNpcService}: a jittered goal that cannot finish walks to the exact point once. */
    private boolean exactPoint(Route current, String reason) {
        WaypointProgress progress = waypoints;
        if (current.leg == null || !current.leg.jittered() || progress == null || route != current || !state.snapshot().active()) return false;
        Optional<WaypointProgress.Leg> exact = progress.exact(current.leg);
        if (exact.isEmpty()) return false;
        if (current.plan != null) current.plan.cancel();
        if (current.execution != null) current.execution.cancel();
        route = null;
        beginLeg(exact);
        if (route != null) routeStatus(route, "queued", "exact point after " + reason);
        return true;
    }
    /** Same stuck rule as {@link PersistedNpcService}: a waypoint leg ends at its point and the route goes on. */
    private boolean teleportStuck(Route current) {
        if (current.leg == null || current.leg.pause() || !movement.teleportsWhenStuck() || route != current || !state.snapshot().active()) return false;
        if (current.plan != null) current.plan.cancel();
        if (current.execution != null) current.execution.cancel();
        current.plan = null; current.result = null; current.execution = null;
        World world = host.world(current.goal.world());
        // Only terrain this region owns is read; a waypoint elsewhere is used as it is.
        state.move(world == null ? current.goal : PersistedNpcService.stuckTarget(current.goal, current.mode, hullWidth(), hullHeight(),
                LiveMovementProbe.owned(world, (int x, int z) -> host.regions.owns(world, x, z, x, z))));
        host.index.upsert(state.snapshot());
        pose++; setPose(yaw, 0, true);
        arrive(current);
        return true;
    }
    private void suspendRoute(String reason) {
        if (routeWatch != null) { stopRoute("unavailable: " + reason); return; }
        cancelPlan();
        route.nextAttempt = turns + 40; routeStatus(route, "suspended", reason);
    }
    private void cancelPlan() {
        if (route == null) return;
        if (route.plan != null) route.plan.cancel();
        route.plan = null; route.result = null;
    }
    private void stopRoute(String reason) {
        Route current = route; route = null; routeSleeping = false; routeWaited = 0;
        if (doors != null) doors.closeAll(host.doors);
        if (performance.run != null) {
            // The cursor stays with the actor, so resumePatrol continues from it.
            performance.stopped();
            recordingDoors.closeAll(host.doors);
            resetLook();
        }
        if (current == null) { navigationStatus = reason; return; }
        if (current.plan != null) current.plan.cancel();
        if (current.execution != null) current.execution.cancel();
        routeStatus(current, reason, "");
        failWatch(reason);
    }
    private void routeStatus(Route current, String phase, String detail) {
        navigationStatus = phase + "; route=" + current.id + "; waypoint=" + current.waypoint + "; goal=" + current.goal.point()
                + (current.capture == null ? "" : "; capture=" + current.capture.firstTick() + ".." + current.capture.lastTick()
                + "; sampled=" + current.capture.sampledVoxels() + "; unavailable=" + current.capture.unavailableVoxels())
                + (detail.isEmpty() ? "" : "; " + detail);
    }
    private void failWatch(String reason) {
        CompletableFuture<Void> watch = routeWatch;
        if (watch == null) return;
        routeWatch = null;
        watch.completeExceptionally(NavigationRefusals.of(reason));
    }
    private void finishWatch() {
        CompletableFuture<Void> watch = routeWatch;
        if (watch == null) return;
        routeWatch = null;
        watch.complete(null);
    }
    private void setPose(float nextYaw, float nextPitch, boolean ground) {
        if (yaw != nextYaw || pitch != nextPitch || onGround != ground) { yaw = nextYaw; pitch = nextPitch; onGround = ground; pose++; }
    }
    private MovementProfile profile(MovementProfile.Mode mode) { return movement.apply(BodyMobility.forRoute(spec.type(), hullWidth(), hullHeight(), mode), spec.type()); }
    private double bodyWidth() { Hitbox box = state.snapshot().bounds(); return box.max().x() - box.min().x(); }
    private double bodyHeight() { Hitbox box = state.snapshot().bounds(); return box.max().y() - box.min().y(); }
    double hullWidth() { return PersistedNpcService.hullWidth(bodyWidth()); }
    double hullHeight() { return PersistedNpcService.hullHeight(bodyHeight()); }

    // ---- Recording playback, on the owner, inside the route slot ----

    private static final String RECORDINGS_DISABLED = "recordings are disabled in config.toml";

    /** The actor's side of {@link RegionPlayback}: routes, the body, terrain and cells of this region. */
    private final class PlaybackActor implements RegionPlayback.Actor {
        @Override public WorldPosition position() { return state.snapshot().position(); }
        @Override public boolean active() { return state.snapshot().active(); }
        @Override public long turns() { return turns; }
        @Override public double hullWidth() { return RegionActor.this.hullWidth(); }
        @Override public double hullHeight() { return RegionActor.this.hullHeight(); }
        @Override public double walkSpeed() { return movement.routeSpeed(false, RegionPlayback.WALK_SPEED); }
        @Override public boolean loaded(String world, int chunkX, int chunkZ) { return RegionActor.this.loaded(new ChunkPosition(world, chunkX, chunkZ)); }
        @Override public int takeCells(int wanted) { return host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted); }
        @Override public void refundCells(int unused) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, unused); }
        @Override public int cellLimit() { return host.navigationSettings().motionCellsPerTick(); }
        @Override public MotionValidator.Sampler terrain(String world) {
            World loaded = host.world(world);
            return loaded == null ? (int x, int y, int z) -> null : LiveMovementProbe.owned(loaded, (int x, int z) -> host.regions.owns(loaded, x, z, x, z));
        }
        @Override public void walk(RecordingRun run, WorldPosition goal, double speed, long deadline) {
            PersistedNpcService.validateLeg(state.snapshot().position(), goal, MovementProfile.Mode.WALK, speed, hullWidth(), hullHeight());
            cancelPlan();
            route = route(goal, MovementProfile.Mode.WALK, speed, null);
            route.recording = run; route.walk = true; route.progressTurn = turns; route.startTurn = turns; route.deadline = deadline;
            routeStatus(route, "queued", "");
        }
        @Override public void play(RecordingRun run, int delay) {
            cancelPlan();
            route = route(state.snapshot().position(), MovementProfile.Mode.WALK, RegionPlayback.WALK_SPEED, null);
            route.recording = run; route.walk = false; route.progressTurn = turns; route.startTurn = turns;
            route.nextAttempt = turns + Math.max(1, delay);
            routeStatus(route, run.phase() == RecordingRun.Phase.BLOCKED ? "blocked" : "playing", "");
        }
        @Override public void step(WorldPosition at, RecordingPose pose, boolean moved) {
            if (moved) { state.move(at); host.index.upsert(state.snapshot()); RegionActor.this.pose++; }
            setPose((float) pose.yaw(), (float) pose.pitch(), pose.onGround());
            applyLook(pose);
            if (route != null) route.progressTurn = turns;
        }
        @Override public void event(String world, RecordingEvent event) { playEvent(world, event); }
        @Override public boolean teleportsWhenStuck() { return movement.teleportsWhenStuck(); }
        @Override public void teleport(RecordingPose pose) {
            WorldPosition goal = new WorldPosition(state.snapshot().position().world(), new Vec3(pose.x(), pose.y(), pose.z()));
            World world = host.world(goal.world());
            // Only terrain this region owns is read; a goal elsewhere is used as it is.
            state.move(world == null ? goal : PersistedNpcService.stuckTarget(goal, MovementProfile.Mode.WALK, hullWidth(), hullHeight(),
                    LiveMovementProbe.owned(world, (int x, int z) -> host.regions.owns(world, x, z, x, z))));
            host.index.upsert(state.snapshot());
            RegionActor.this.pose++; setPose((float) pose.yaw(), (float) pose.pitch(), true);
        }
        @Override public void status(String phase, String detail) { if (route != null) routeStatus(route, phase, detail); }
        @Override public void retryIn(int ticks) { if (route != null) route.nextAttempt = turns + ticks; }
        @Override public boolean watched() { return routeWatch != null; }
        @Override public void ended(String status, boolean failed) {
            cancelPlan();
            if (route != null && route.execution != null) route.execution.cancel();
            UUID routeId = route == null ? UUID.randomUUID() : route.id;
            route = null;
            recordingDoors.closeAll(host.doors);
            if (failed) doors.closeAll(host.doors);
            resetLook();
            RecordingRun over = performance.held;
            if (over != null && over.step()) {
                performance.held = null;
                if (failed) stepFailed(stepLeg, status); else stepReached(routeId);
                return;
            }
            navigationStatus = status;
            if (failed) failWatch(status); else finishWatch();
        }
        @Override public void stop(String reason) { stopRoute(reason); }
    }
    private void startRecording(RecordingRun run) {
        navigationStatus = "starting";
        performance.start(run);
    }
    private void playEvent(String world, RecordingEvent event) {
        switch (event) {
            case RecordingEvent.Swing ignored -> swings++;
            case RecordingEvent.Equipment change -> {
                if (!host.packets.equipmentAvailable()) return;
                if (change.item().isEmpty()) { equipment.put(change.slot(), new ItemStack(org.bukkit.Material.AIR)); equipmentRevision++; return; }
                ItemStack stack = DefinitionItems.stack(change.item().get(), "$.events", new ArrayList<>());
                if (stack == null) return;
                equipment.put(change.slot(), stack);
                equipmentRevision++;
            }
            case RecordingEvent.Use use -> recordingDoors.use(world, new BlockPos(use.x(), use.y(), use.z()), host.doors);
        }
    }
    private void applyLook(RecordingPose shown) {
        if (!BodyRules.PLAYER.equals(spec.type())) return;
        changeLook(RecordingSteps.pose(shown, spec.appearance().pose()), RecordingSteps.motion(shown));
    }
    private void resetLook() { changeLook(spec.appearance().pose(), 0); }
    /** The playback's pose and motion; a bow drawn in a fight stays drawn across them. */
    private void changeLook(NpcPose nextPose, int motion) {
        int shown = motion | (drawing ? BodyLook.USING_ITEM : 0);
        if (lookPose == nextPose && lookMotion == shown) return;
        lookPose = nextPose; lookMotion = shown; lookRevision++;
        look = BodyLook.of(uuid, spec.type(), displayName, spec.appearance()).moving(nextPose, shown);
    }
    /** Raises or lowers the bow for every viewer. */
    void drawing(boolean drawn) {
        if (drawing == drawn) return;
        drawing = drawn;
        changeLook(lookPose, lookMotion & ~BodyLook.USING_ITEM);
        publish(true);
    }
    /** The recording a hotload re-reads: the one playing or kept, else the declared one. */
    private RecordingSource recordingToRead() {
        if (performance.run != null && !performance.run.step()) return performance.run.source();
        if (performance.run == null && performance.held != null && !performance.held.step()) return performance.held.source();
        return recordingSource;
    }
    /** Same rules as {@link PersistedNpcService}: a hotload re-read keeps an unchanged recording and restarts a changed one. */
    private void finishRecordingRead(RouteRead read, RecordingDocument document, String failure) {
        RecordingRun current = performance.run != null ? performance.run : performance.held;
        String error = failure == null && !host.recordings().enabled() ? RECORDINGS_DISABLED : failure;
        if (error == null)
            error = RecordingWorlds.refusal(document, state.snapshot().position().world(), (String reference) -> Optional.ofNullable(host.worldKey(reference))).orElse(null);
        if (error != null) {
            routeError = error + "; last-valid-recording-retained=" + (current != null);
            failWatch("unavailable: " + error);
            if (route == null && performance.run == null) navigationStatus = "unavailable: recording " + read.recording.recording();
            return;
        }
        routeError = null;
        if (routeWatch != null && read.recording.mode(document) != Playback.ONCE) { navigationStatus = "invalid: a looping recording never finishes"; failWatch(navigationStatus); return; }
        boolean same = current != null && current.source().equals(read.recording) && current.document().equals(document);
        if (!read.start) {
            if (same) return;
            if (performance.run == null && performance.held != null) { performance.held = null; return; }
            if (performance.run == null && route != null) return;
        }
        stopRoute("replaced by recording reload");
        performance.held = null; waypoints = null;
        startRecording(new RecordingRun(read.recording, document));
    }

    // ---- File reads: admitted on the owner, completed back through the mailbox ----

    private void requestRouteRead() {
        RecordingSource recorded = (performance.run != null && !performance.run.step()) || routeSource == null ? recordingToRead() : null;
        WaypointSettings inline = recorded == null && routeSource == null ? inlineSteps() : null;
        if ((routeSource == null && recorded == null && inline == null) || routeRead != null) { routeReadWanted = false; return; }
        if (startRead(recorded == null ? routeSource : null, inline, recorded, false)) {
            routeReadWanted = false;
            if (route == null) navigationStatus = recorded == null ? "loading reusable route" : "loading recording";
        }
    }
    /**
     * Reads a reusable route and its step recordings, the step recordings of the {@code inline} route, or a recording;
     * {@code start} plays the recording whatever was playing.
     */
    private boolean startRead(String reference, WaypointSettings inline, RecordingSource recorded, boolean start) {
        if (!host.admitRead()) return false;
        RouteRead read = new RouteRead(reference, inline, recorded, start, host.recordings().maximumFileBytes());
        routeRead = read;
        try { host.io.execute(read); return true; }
        catch (RejectedExecutionException full) { routeRead = null; host.releaseRead(); routeError = "I/O queue full; retry reload"; return false; }
    }
    private void cancelRouteRead() { routeReadWanted = false; if (routeRead != null) { routeRead.cancelled = true; routeRead = null; } }
    private void finishRouteRead(RouteRead read, WaypointSettings settings, RouteRecordings recordings, RecordingDocument document, String failure) {
        if (routeRead != read || read.cancelled) return;
        routeRead = null;
        if (read.recording != null) { finishRecordingRead(read, document, failure); return; }
        if (failure != null) {
            routeError = failure + "; last-valid-route-retained=" + (waypoints != null);
            failWatch("unavailable: " + failure);
            if (route == null && waypoints == null) navigationStatus = "unavailable: reusable route";
            return;
        }
        routeError = null;
        if (routeWatch != null && settings.endless()) { navigationStatus = "invalid: a looping route never finishes"; failWatch(navigationStatus); return; }
        if (read.inline != null && waypoints == null) return;
        if (waypoints != null && !waypoints.reload(settings)) {
            RouteRecordings before = waypoints.recordings();
            waypoints.recordings(recordings);
            if (before != null) { recordingsReloaded(before, recordings); return; }
            if (route != null || performance.run != null) return;
        } else {
            stopRoute("replaced by reusable route reload");
            forgetStep();
            if (waypoints == null) waypoints = progress(settings, read.inline != null ? WaypointProgress.INLINE : read.reference);
            waypoints.recordings(recordings);
        }
        beginLeg(waypoints.start(state.snapshot().position().point()));
    }
    private void readAppearance(String reference) {
        if (!host.admitRead()) throw new IllegalStateException("Skin reads are busy; retry shortly");
        AppearanceRead read = new AppearanceRead(reference);
        appearanceRead = read;
        try { host.io.execute(read); }
        catch (RejectedExecutionException full) { appearanceRead = null; host.releaseRead(); throw new IllegalStateException("I/O queue is full; retry shortly"); }
    }
    private void cancelAppearanceRead() { if (appearanceRead != null) { appearanceRead.cancelled = true; appearanceRead = null; } }
    private void cancelReads() { cancelRouteRead(); cancelAppearanceRead(); }
    private void finishAppearance(AppearanceRead read, BodySkin loaded, String failure) {
        if (appearanceRead != read || read.cancelled) return;
        appearanceRead = null;
        if (failure != null) { host.noteFailure("skin read", "npc=" + id, "$.skin: " + failure); return; }
        displaySkin = loaded; appearance++;
    }
    /** Read results return through the mailbox, so whichever region owns the NPC by then applies them. */
    private void complete(Runnable apply) {
        owner.submit(new OwnerMailbox.Work() {
            @Override public void run() { apply.run(); publish(true); }
            @Override public void refuse(String reason) { /* A dormant or retired NPC no longer wants this read. */ }
        });
    }
    /**
     * One reusable route and the recordings its steps play, the step recordings of an inline route, or one recording;
     * every recording within the live {@code [recordings] maximumFileBytes}.
     */
    private final class RouteRead implements Runnable {
        final String reference; final WaypointSettings inline; final RecordingSource recording; final boolean start; final int maximumBytes; volatile boolean cancelled;
        RouteRead(String reference, WaypointSettings inline, RecordingSource recording, boolean start, int maximumBytes) {
            this.reference = reference; this.inline = inline; this.recording = recording; this.start = start; this.maximumBytes = maximumBytes;
        }
        @Override public void run() {
            WaypointSettings settings = null; RouteRecordings steps = null; RecordingDocument document = null; String failure = null;
            try {
                if (!cancelled) {
                    if (recording != null) document = RecordingDocuments.read(host.runtime.documents(), recording.recording(), maximumBytes);
                    else {
                        settings = inline != null ? inline : RouteDocuments.read(host.runtime.documents(), reference);
                        steps = RouteRecordings.read(settings, (String id) -> RecordingDocuments.read(host.runtime.documents(), id, maximumBytes));
                    }
                }
            }
            catch (Exception problem) { failure = PersistedNpcService.concise(problem); }
            finally { host.releaseRead(); }
            WaypointSettings result = settings; RouteRecordings loaded = steps; RecordingDocument played = document; String reason = failure;
            if (!cancelled) complete(() -> finishRouteRead(this, result, loaded, played, reason));
        }
    }
    private final class AppearanceRead implements Runnable {
        final String reference; volatile boolean cancelled;
        AppearanceRead(String reference) { this.reference = reference; }
        @Override public void run() {
            BodySkin loaded = null; String failure = null;
            try { if (!cancelled) loaded = SkinDocuments.read(host.runtime.documents(), reference); }
            catch (Exception problem) { failure = PersistedNpcService.concise(problem); }
            finally { host.releaseRead(); }
            BodySkin result = loaded; String reason = failure;
            if (!cancelled) complete(() -> finishAppearance(this, result, reason));
        }
    }

    /** Owner only, or the thread that just ended ownership. Publishes a new immutable view only when something changed. */
    private void publish(boolean live) {
        Publication previous = publication;
        VirtualNpcState.Snapshot snapshot = state.snapshot();
        boolean presented = live && snapshot.active();
        if (previous != null && previous.live() == presented && previous.snapshot().equals(snapshot) && previous.appearance() == appearance
                && previous.pose() == pose && previous.equipmentRevision() == equipmentRevision && previous.arrivals() == arrivals
                && Objects.equals(previous.navigation(), navigation()) && previous.rig() == rigSnapshot() && previous.dragonPhase() == dragonPhase
                && previous.passengers().equals(passengers()) && previous.rider() == rider() && Objects.equals(previous.combat(), combatView())
                && previous.lookRevision() == lookRevision && previous.swings() == swings && previous.attackSwings() == attackSwings && previous.attributeRevision() == attributeRevision) return;
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        (rig == null ? equipment : rig.bodyEquipment(equipment)).forEach((slot, item) -> items.put(slot, item.clone()));
        publication = new Publication(presented, snapshot, spec, spec.type(), displayName, displaySkin, appearance, yaw, pitch, onGround, pose,
                Map.copyOf(items), equipmentRevision, NpcPresentation.nativeIds(host.packets, spec, displayName, ids), navigation(), arrivals, ride != null, mount, ++revision, rigSnapshot(), dragonPhase, passengers(), rider(), combatView(), look, lookRevision, swings, attackSwings,
                attributes, attributeRevision);
    }
    private Player rider() { return riding.rider(); }
    private NpcRig.Snapshot<ItemStack> rigSnapshot() {
        NpcRig<ItemStack> current = rig;
        return current == null ? null : current.snapshot();
    }
    private String navigation() {
        return navigationStatus + (routeSource == null ? "" : "; route-source=" + routeSource) + (routeError == null ? "" : "; route-error=" + routeError)
                + performance.describe(recordingSource == null ? null : recordingSource.recording());
    }

    private static final class Route {
        final UUID id = UUID.randomUUID(); final WorldPosition goal; final MovementProfile.Mode mode; final double speed; final int waypoint; final WaypointProgress.Leg leg;
        final Vec3 origin; final LongLeg longLeg;
        NavigationPlan plan; CompletableFuture<PathPlanner.Result> result; PathExecution execution; CaptureProvenance capture;
        long nextAttempt, progressTurn;
        /** The path being walked ends part way along a long leg. */
        boolean segment;
        /** A recording's turn: a planned walk to its goal pose when {@link #walk}, otherwise its frames; a walk fails after {@link #deadline}. */
        RecordingRun recording; boolean walk; long deadline = Long.MAX_VALUE, startTurn;
        Route(WorldPosition goal, MovementProfile.Mode mode, double speed, WaypointProgress.Leg leg, Vec3 origin, LongLeg longLeg) {
            this.goal = goal; this.mode = mode; this.speed = speed; this.leg = leg; this.waypoint = leg == null ? -1 : leg.arrival();
            this.origin = origin; this.longLeg = longLeg;
        }
    }
    /** A route from where the body stands, planned with this NPC's hull and path range. */
    private Route route(WorldPosition goal, MovementProfile.Mode mode, double speed, WaypointProgress.Leg leg) {
        Vec3 origin = state.snapshot().position().point();
        return new Route(goal, mode, speed, leg, origin, new LongLeg(origin, goal.point(), hullWidth(), hullHeight(), movement.pathRange()));
    }
}
