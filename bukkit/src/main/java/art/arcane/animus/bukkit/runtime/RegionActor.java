package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.runtime.NpcPresentation;
import art.arcane.animus.core.runtime.DefinitionSpec;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.DefinitionAuthority;
import art.arcane.animus.core.runtime.NavigationRefusals;
import art.arcane.animus.core.runtime.RouteDocuments;
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
import art.arcane.animus.core.virtual.DragonMotion;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
                       List<UUID> passengers, Player rider, CombatView combat) {}
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
    private long nextLook;
    float yaw, pitch;
    private boolean onGround = true;
    private long pose, arrivals, turns, appearance, equipmentRevision, revision;
    private double fallSpeed;
    private Route route;
    private WaypointProgress waypoints;
    private String navigationStatus = "idle", routeError;
    private CompletableFuture<Void> routeWatch;
    private RouteRead routeRead;
    private boolean routeReadWanted;
    private String displayName;
    private BodySkin displaySkin;
    private AppearanceRead appearanceRead;
    private final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private ChunkPosition anchored;
    private String rigId, rigProblem;
    private NpcRig<ItemStack> rig;
    private long rigGeneration = -1;
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
        RegionNpcRuntime.Geometry geometry = host.geometry(spec.type());
        state = new VirtualNpcState(uuid, spec.position(), geometry.width(), geometry.height(), PersistedNpcService.maximumHealth(spec));
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
        yaw = spec.yaw(); pitch = spec.pitch(); onGround = true;
        displayName = spec.name(); displaySkin = skin;
        equipment.clear(); equipment.putAll(NpcPresentation.equipment(spec, ItemStack::clone)); equipmentRevision++; appearance++; pose++;
        waypoints = WaypointSettings.parse(spec.definition()).map(this::progress).orElse(null);
        routeReadWanted = waypoints == null && routeSource != null;
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
        if (catalog.generation() != rigGeneration) {
            rigGeneration = catalog.generation();
            if (rig == null || !rig.refresh(catalog, turns)) {
                boolean had = rig != null;
                rig = null; rigProblem = null;
                if (!host.rigs.enabled()) rigProblem = "rigs are disabled in config.toml";
                else if (!host.packets.bonesAvailable()) rigProblem = "display bones are unavailable on this server build";
                else {
                    List<String> problems = new ArrayList<>();
                    rig = NpcRig.bind(rigId, catalog, host.geometry(spec.type()).seat(), DefinitionItems::stack, problems).orElse(null);
                    if (rig == null) rigProblem = String.join("; ", problems.subList(0, Math.min(3, problems.size())));
                }
                if (had || rig != null) appearance++;
            }
        }
        if (rig == null || !state.snapshot().active()) return;
        boolean walking = route != null && route.execution != null && !route.execution.finished();
        for (RigPlayback.Finished done : rig.tick(turns, walking)) host.animationFinished(uuid, done.animation(), done.layer());
        if (rig.due(turns, yaw) && host.budget.cosmeticAllowed() && host.budget.take(RegionBudget.Kind.RIG_PLANS, 1) > 0) rig.plan(turns, yaw, host.rigs.sampleTicks(), System.nanoTime());
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
        if (woken && route == null && waypoints != null && !waypoints.complete()) beginLeg(waypoints.start(state.snapshot().position().point()));
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
        mounts();
        riding.tick();
        tickCombat();
        if (reread) { reread = false; routeReadWanted |= routeSource != null; }
        if (routeReadWanted) requestRouteRead();
        if (route != null && !seated) { navigate(); supportWanted = false; fallSpeed = 0; }
        else if (!seated && (supportWanted || fallSpeed > 0)) support();
        tickRig();
        look();
        if (spec.type().equals(DragonBody.TYPE)) dragonPhase = dragonMotion.observe(turns, state.snapshot().position().point(), grounded());
        ChunkPosition now = anchor();
        if (!now.equals(anchored)) { host.anchor(this, anchored, now); anchored = now; }
        publish(true);
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
        state = state.reconfigured(position, geometry.width(), geometry.height(), PersistedNpcService.maximumHealth(next.spec()));
        configure(next);
        error = null;
        if (state.snapshot().active()) host.index.upsert(state.snapshot());
        if (waypoints != null) beginLeg(waypoints.start(state.snapshot().position().point()));
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
        if (combat != null) combat.tick();
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
    /** Restarts the definition's own patrol, if nothing else moves the NPC. */
    void resumePatrol() {
        if (!navigable() || route != null || routeRead != null) return;
        waypoints = WaypointSettings.parse(spec.definition()).map(this::progress).orElse(null);
        if (waypoints != null) beginLeg(waypoints.start(state.snapshot().position().point()));
        else if (routeSource != null) routeReadWanted = true;
    }
    /**
     * Places the NPC on clear ground at the destination. A destination this region holds is checked here; one another
     * region holds is checked by that region, which hands the result back to this NPC's owner. Moving there then hands
     * the NPC to that region like any chunk crossing. False when the destination is not known to be loaded.
     */
    boolean combatTeleport(WorldPosition destination) {
        if (seated || riding.active() || !state.snapshot().active()) return false;
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
            @Override public void run() { if (state.snapshot().active() && !seated && riding == null) place(spot); }
            @Override public void refuse(String reason) { /* The NPC went dormant; it stays where it was. */ }
        })));
    }
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
    /** Knockback: a short slide onto clear, supported ground this region holds; otherwise the NPC stays put. */
    void combatKnockback(Vec3 direction, double strength) {
        double horizontal = Math.hypot(direction.x(), direction.z());
        if (horizontal < .0001 || seated || riding.active() || !state.snapshot().active()) return;
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
        route = new Route(destination, mode, speed, null, state.snapshot().position().point(), hullWidth(), hullHeight());
        navigationStatus = "queued; route=" + route.id;
        publish(true);
        return route.id;
    }
    boolean stopNavigation() {
        boolean running = route != null || routeReadWanted || routeRead != null || (waypoints != null && !waypoints.complete());
        cancelRouteRead(); waypoints = null; stopRoute("cancelled by administrator"); failWatch("cancelled: navigation stopped");
        publish(true);
        return running;
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
            candidates.add(new LookClose.Candidate<>(player, playerEye, playerEye.distance(at.point()) <= host.settings.viewDistance()));
        }
        for (LookClose.Candidate<Player> candidate : look.ranked(eye, candidates)) {
            if (look.lineOfSight() && !clearSight(world, candidate.eye(), eye)) continue;
            LookClose.facing(eye, candidate.eye()).ifPresent((LookClose.Facing facing) -> setPose(facing.yaw(), facing.pitch(), onGround));
            break;
        }
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
        waypoints = null;
        if (!startRouteRead(reference)) throw new IllegalStateException("Reusable route reads are busy; retry shortly");
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
    WorldPosition position() { return state.snapshot().position(); }

    private void navigate() {
        Route current = route;
        if (turns < current.nextAttempt) return;
        if (!state.snapshot().active()) { stopRoute("cancelled by definition/lifecycle change"); return; }
        // A patrol with no player near sleeps: no turn, plan or budget until a viewer marks it again.
        if (current.leg != null && routeWatch == null && !watched()) {
            if (!routeSleeping) { routeSleeping = true; cancelPlan(); routeStatus(current, "sleeping", "no player within " + (int) host.settings.viewDistance() + " blocks"); }
            routeWaited = 0;
            return;
        }
        if (routeSleeping) { routeSleeping = false; routeStatus(current, "queued", "a player is near"); }
        if (current.leg != null && current.leg.pause()) {
            route = null;
            if (waypoints != null) beginLeg(waypoints.resume(current.leg, state.snapshot().position().point()));
            return;
        }
        World world = host.world(current.goal.world());
        if (world == null) { stopRoute("cancelled: world unavailable"); return; }
        int waited = routeWaited;
        if (!host.budget.takeRoute(waited)) { routeWaited = waited + 1; return; }
        routeWaited = 0;
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
                    current.plan = host.navigation.plan(world.getUID().toString(), bounds, feet, goal, profile(current.mode), request.limits());
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
                            Vec3 end = current.segment ? planned.path().getLast().point() : current.goal.point();
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
        int wanted = Math.min(host.navigationSettings.motionCellsPerTick(), sweep + (step.landing() ? settle : 0));
        int granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); return false; }
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
            routeStatus(current, "moving", PersistedNpcService.moving(current.segment) + "; region owner");
            if (current.execution.finished()) executed(current);
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
        if (!blockedWarned) {
            blockedWarned = true;
            host.plugin.getLogger().warning(PersistedNpcService.blockedLeg(id, current.waypoint, current.origin, current.goal, reason));
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
        if (current.mode != MovementProfile.Mode.WALK || mount || !BodyMobility.falls(spec.type())) return false;
        int wanted = IdleFall.cells(hullWidth(), hullHeight()), granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); return false; }
        boolean swims = BodyMobility.forBody(spec.type(), hullWidth(), hullHeight()).supports(MovementProfile.Mode.SWIM);
        WorldPosition at = state.snapshot().position();
        IdleFall.Step step = IdleFall.step(at.point(), fallSpeed, hullWidth(), hullHeight(), swims, LiveMovementProbe.owned(world, owned));
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
    /** Idle gravity: one bounded step per owner tick while the NPC falls, charged to the region's motion cells. */
    private void support() {
        supportWanted = false;
        World world = mount || !navigable() || !BodyMobility.falls(spec.type()) ? null : host.world(state.snapshot().position().world());
        if (world == null) { fallSpeed = 0; return; }
        int wanted = IdleFall.cells(hullWidth(), hullHeight()), granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); supportWanted = true; return; }
        ChunkOwnership owned = (int x, int z) -> host.regions.owns(world, x, z, x, z);
        boolean swims = BodyMobility.forBody(spec.type(), hullWidth(), hullHeight()).supports(MovementProfile.Mode.SWIM);
        WorldPosition at = state.snapshot().position();
        IdleFall.Step step = IdleFall.step(at.point(), fallSpeed, hullWidth(), hullHeight(), swims, LiveMovementProbe.owned(world, owned));
        host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted - step.sampled());
        fallSpeed = step.speed();
        boolean falling = step.outcome() == IdleFall.Outcome.FALLING;
        if (falling || step.outcome() == IdleFall.Outcome.LANDED) {
            state.move(new WorldPosition(at.world(), step.to()));
            host.index.upsert(state.snapshot());
            pose++; setPose(yaw, pitch, !falling);
        }
    }
    private void arrive(Route current) {
        if (route != current) return;
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
    private WaypointProgress progress(WaypointSettings declared) { return WaypointProgress.of(declared, spec.position().point(), uuid); }
    /** Same leg rules as {@link PersistedNpcService}: a pause leg holds the route until its wait ends. */
    private void beginLeg(Optional<WaypointProgress.Leg> next) {
        WaypointProgress progress = waypoints;
        if (progress == null || next.isEmpty() || !state.snapshot().active()) return;
        WaypointProgress.Leg leg = next.get();
        WaypointSettings settings = progress.settings();
        WorldPosition at = state.snapshot().position();
        WorldPosition goal = leg.pause() ? at : new WorldPosition(at.world(), leg.goal());
        if (!leg.pause()) {
            try { PersistedNpcService.validateLeg(at, goal, settings.mode(), settings.speed(), hullWidth(), hullHeight()); }
            catch (IllegalArgumentException invalid) {
                navigationStatus = "invalid waypoint " + leg.arrival() + ": " + invalid.getMessage(); failWatch(navigationStatus);
                Optional<WaypointProgress.Leg> retry = progress.failed(leg);
                if (retry.isPresent()) beginLeg(retry);
                return;
            }
        }
        route = new Route(goal, settings.mode(), settings.speed(), leg, at.point(), hullWidth(), hullHeight());
        route.nextAttempt = turns + Math.max(1, leg.waitTicks());
        routeStatus(route, leg.pause() ? "waiting to retry" : "queued", "");
    }
    /** A leg that cannot finish: random and wander routes pause and choose again, every other route halts. */
    private void failLeg(Route current, String reason) {
        WaypointProgress progress = waypoints;
        stopRoute(reason);
        if (progress == null || current.leg == null || !progress.retries() || route != null || !navigable()) return;
        String failure = navigationStatus;
        beginLeg(progress.failed(current.leg));
        if (route != null) routeStatus(route, "waiting to retry", "after " + failure);
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
    private MovementProfile profile(MovementProfile.Mode mode) { return BodyMobility.forRoute(spec.type(), hullWidth(), hullHeight(), mode); }
    private double bodyWidth() { Hitbox box = state.snapshot().bounds(); return box.max().x() - box.min().x(); }
    private double bodyHeight() { Hitbox box = state.snapshot().bounds(); return box.max().y() - box.min().y(); }
    double hullWidth() { return PersistedNpcService.hullWidth(bodyWidth()); }
    double hullHeight() { return PersistedNpcService.hullHeight(bodyHeight()); }

    // ---- File reads: admitted on the owner, completed back through the mailbox ----

    private void requestRouteRead() {
        if (routeSource == null || routeRead != null) { routeReadWanted = false; return; }
        if (startRouteRead(routeSource)) { routeReadWanted = false; if (route == null) navigationStatus = "loading reusable route"; }
    }
    private boolean startRouteRead(String reference) {
        if (!host.admitRead()) return false;
        RouteRead read = new RouteRead(reference);
        routeRead = read;
        try { host.io.execute(read); return true; }
        catch (RejectedExecutionException full) { routeRead = null; host.releaseRead(); routeError = "I/O queue full; retry reload"; return false; }
    }
    private void cancelRouteRead() { routeReadWanted = false; if (routeRead != null) { routeRead.cancelled = true; routeRead = null; } }
    private void finishRouteRead(RouteRead read, WaypointSettings settings, String failure) {
        if (routeRead != read || read.cancelled) return;
        routeRead = null;
        if (failure != null) {
            routeError = failure + "; last-valid-route-retained=" + (waypoints != null);
            failWatch("unavailable: " + failure);
            if (route == null && waypoints == null) navigationStatus = "unavailable: reusable route";
            return;
        }
        routeError = null;
        if (routeWatch != null && settings.endless()) { navigationStatus = "invalid: a looping route never finishes"; failWatch(navigationStatus); return; }
        if (waypoints != null && !waypoints.reload(settings)) return;
        stopRoute("replaced by reusable route reload");
        if (waypoints == null) waypoints = progress(settings);
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
    private final class RouteRead implements Runnable {
        final String reference; volatile boolean cancelled;
        RouteRead(String reference) { this.reference = reference; }
        @Override public void run() {
            WaypointSettings settings = null; String failure = null;
            try { if (!cancelled) settings = RouteDocuments.read(host.runtime.documents(), reference); }
            catch (Exception problem) { failure = PersistedNpcService.concise(problem); }
            finally { host.releaseRead(); }
            WaypointSettings result = settings; String reason = failure;
            if (!cancelled) complete(() -> finishRouteRead(this, result, reason));
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
                && previous.passengers().equals(passengers()) && previous.rider() == rider() && Objects.equals(previous.combat(), combatView())) return;
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        equipment.forEach((slot, item) -> items.put(slot, item.clone()));
        publication = new Publication(presented, snapshot, spec, spec.type(), displayName, displaySkin, appearance, yaw, pitch, onGround, pose,
                Map.copyOf(items), equipmentRevision, NpcPresentation.nativeIds(host.packets, spec, displayName, ids), navigation(), arrivals, ride != null, mount, ++revision, rigSnapshot(), dragonPhase, passengers(), rider(), combatView());
    }
    private Player rider() { return riding.rider(); }
    private NpcRig.Snapshot<ItemStack> rigSnapshot() {
        NpcRig<ItemStack> current = rig;
        return current == null ? null : current.snapshot();
    }
    private String navigation() {
        return navigationStatus + (routeSource == null ? "" : "; route-source=" + routeSource) + (routeError == null ? "" : "; route-error=" + routeError);
    }

    private static final class Route {
        final UUID id = UUID.randomUUID(); final WorldPosition goal; final MovementProfile.Mode mode; final double speed; final int waypoint; final WaypointProgress.Leg leg;
        final Vec3 origin; final LongLeg longLeg;
        NavigationPlan plan; CompletableFuture<PathPlanner.Result> result; PathExecution execution; CaptureProvenance capture;
        long nextAttempt;
        /** The path being walked ends part way along a long leg. */
        boolean segment;
        Route(WorldPosition goal, MovementProfile.Mode mode, double speed, WaypointProgress.Leg leg, Vec3 origin, double hullWidth, double hullHeight) {
            this.goal = goal; this.mode = mode; this.speed = speed; this.leg = leg; this.waypoint = leg == null ? -1 : leg.arrival();
            this.origin = origin; longLeg = new LongLeg(origin, goal.point(), hullWidth, hullHeight);
        }
    }
}
