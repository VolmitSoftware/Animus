package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.navigation.CaptureProvenance;
import art.arcane.animus.bukkit.navigation.ChunkOwnership;
import art.arcane.animus.bukkit.navigation.LiveMovementProbe;
import art.arcane.animus.bukkit.navigation.NavigationPlan;
import art.arcane.animus.bukkit.navigation.VirtualNpcWaypointEvent;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
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
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/**
 * One persisted NPC on Folia. Every non-volatile field is confined to the region that currently owns the NPC through
 * {@link RegionOwner}; other threads read only the immutable {@link Publication}. Handoff semantics: routes, waypoint
 * progress, a waiting behavior's watch, kinematic execution and queued owner work move with the NPC; an in-flight
 * terrain capture or plan is cancelled and planned again by the new owner. Suspension keeps the route but ends a
 * behavior's wait, as the single-owner runtime does for unavailable terrain.
 */
final class RegionActor implements RegionOwner.Body {
    /** What the definition asks for. A null spec means the definition was removed or despawned. */
    record Desired(DefinitionSpec spec, NativePacketAdapter.Skin skin) {}
    /** Immutable state for viewers, commands and behaviors on other threads. */
    record Publication(boolean live, VirtualNpcState.Snapshot snapshot, String type, String name, NativePacketAdapter.Skin skin,
                       long appearance, float yaw, float pitch, boolean onGround, long pose,
                       Map<BehaviorPlatform.EquipmentSlot, ItemStack> equipment, long equipmentRevision, int ids,
                       String navigation, long arrivals, boolean ride, boolean mount, long revision) {}
    private static final int NAVIGATION_CELLS = 2048;
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

    private Desired applied;
    DefinitionSpec spec;
    private NativePacketAdapter.Skin skin;
    VirtualNpcState state;
    private DefinitionAuthority authority;
    private int ids;
    private String routeSource;
    private RideSettings ride;
    private boolean mount;
    float yaw, pitch;
    private boolean onGround = true;
    private long pose, arrivals, turns, appearance, equipmentRevision, revision;
    private Route route;
    private WaypointProgress waypoints;
    private String navigationStatus = "idle", routeError;
    private CompletableFuture<Void> routeWatch;
    private RouteRead routeRead;
    private boolean routeReadWanted;
    private String displayName;
    private NativePacketAdapter.Skin displaySkin;
    private AppearanceRead appearanceRead;
    private final EnumMap<BehaviorPlatform.EquipmentSlot, ItemStack> equipment = new EnumMap<>(BehaviorPlatform.EquipmentSlot.class);
    private ChunkPosition anchored;

    RegionActor(RegionNpcRuntime host, Desired desired, boolean announced) {
        this.host = host; this.desired = desired; this.announced = announced;
        spec = desired.spec(); id = spec.definition().id(); uuid = spec.definition().uuid();
        RegionNpcRuntime.Geometry geometry = host.geometry(spec.type());
        state = new VirtualNpcState(uuid, spec.position(), geometry.width(), geometry.height(), PersistedNpcService.maximumHealth(spec));
        state.spawn();
        configure(desired);
        owner = new RegionOwner(uuid, host.regions, host.budget, 32, 8, this);
        publish(false);
    }

    private void configure(Desired next) {
        applied = next; spec = next.spec(); skin = next.skin();
        authority = new DefinitionAuthority(spec.definition());
        ids = host.geometry(spec.type()).ids();
        routeSource = WaypointSettings.reference(spec.definition());
        ride = RideSettings.parse(spec.definition().json()).orElse(null);
        mount = MountDeclaration.parse(spec.definition().json()).isPresent();
        yaw = spec.yaw(); pitch = spec.pitch(); onGround = true;
        displayName = spec.name(); displaySkin = skin;
        equipment.clear(); equipmentRevision++; appearance++; pose++;
        waypoints = WaypointSettings.parse(spec.definition()).map(WaypointProgress::new).orElse(null);
        routeReadWanted = waypoints == null && routeSource != null;
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
        if (woken && route == null && waypoints != null && !waypoints.complete()) beginWaypoint(0);
        publish(true);
    }
    @Override public void released() {
        cancelPlan();
        publish(true);
    }
    @Override public void suspended(String reason) {
        cancelPlan();
        if (routeWatch != null) stopRoute("unavailable: NPC became dormant because " + reason);
        else if (route != null) routeStatus(route, "suspended", "dormant: " + reason);
        host.transfers.remove(this);
        host.index.remove(uuid);
        host.anchor(this, anchored, null); anchored = null;
        publish(false);
    }
    @Override public void retired() {
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
        if (reread) { reread = false; routeReadWanted |= routeSource != null; }
        if (routeReadWanted) requestRouteRead();
        if (route != null) navigate();
        ChunkPosition now = anchor();
        if (!now.equals(anchored)) { host.anchor(this, anchored, now); anchored = now; }
        publish(true);
        return now;
    }

    private void reconfigure(Desired next) {
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
        if (waypoints != null) beginWaypoint(0);
    }

    /** Owner only. The calling thread holds the NPC and its current definition is applied. */
    boolean navigable() {
        return owner.owned() && state.snapshot().active() && authority.accepts(host.runtime.npcs().snapshot().get(id));
    }
    boolean presentable() { return owner.owned() && DefinitionSpec.spawned(host.runtime.npcs().snapshot().get(id)); }

    // ---- Navigation, on the owner ----

    UUID navigate(WorldPosition destination, MovementProfile.Mode mode, double speed) {
        PersistedNpcService.validateLeg(state.snapshot().position(), destination, mode, speed, hullWidth(), hullHeight());
        failWatch("cancelled: replaced by a new route");
        stopRoute("cancelled: replaced by manual route");
        cancelRouteRead();
        waypoints = null;
        route = new Route(destination, mode, speed, -1);
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
        Vec3 delta = target.point().subtract(from.point().add(new Vec3(0, bodyHeight() * .8, 0)));
        if (delta.distance(Vec3.ZERO) < .000001) return;
        setPose((float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())), (float) -Math.toDegrees(Math.atan2(delta.y(), Math.hypot(delta.x(), delta.z()))), onGround);
        publish(true);
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
        boolean player = spec.type().equals("minecraft:player");
        if ((name.isPresent() || reference.isPresent()) && !player) throw new IllegalArgumentException("Names and skins display only on player bodies");
        if (name.isPresent()) NativePacketAdapter.validateProfileName(name.get());
        cancelAppearanceRead();
        if (reset) { displayName = spec.name(); displaySkin = skin; }
        else {
            if (name.isPresent()) displayName = name.get();
            if (reference.isPresent()) { readAppearance(reference.get()); return; }
        }
        appearance++;
        publish(true);
    }
    void setEquipment(BehaviorPlatform.EquipmentSlot slot, ItemStack item) {
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
        World world = host.world(current.goal.world());
        if (world == null) { stopRoute("cancelled: world unavailable"); return; }
        if (host.budget.take(RegionBudget.Kind.ROUTES, 1) == 0) return;
        ChunkOwnership owned = (x, z) -> host.regions.owns(world, x, z, x, z);
        try {
            if (current.execution == null) {
                if (current.plan == null) {
                    BlockPos feet = BlockPos.containing(state.snapshot().position().point()), goal = BlockPos.containing(current.goal.point());
                    if (!loaded(new ChunkPosition(current.goal.world(), Math.floorDiv(feet.x(), 16), Math.floorDiv(feet.z(), 16)))
                            || !loaded(new ChunkPosition(current.goal.world(), Math.floorDiv(goal.x(), 16), Math.floorDiv(goal.z(), 16)))) {
                        suspendRoute("unavailable terrain; no chunk loading requested"); return;
                    }
                    var bounds = PersistedNpcService.captureBounds(state.snapshot().position(), current.goal, hullWidth(), hullHeight());
                    if (!host.regions.owns(current.goal.world(), Math.floorDiv(bounds.minX(), 16), Math.floorDiv(bounds.minZ(), 16),
                            Math.floorDiv(bounds.minX() + bounds.sizeX() - 1, 16), Math.floorDiv(bounds.minZ() + bounds.sizeZ() - 1, 16))) {
                        suspendRoute("route terrain belongs to another region; retrying"); return;
                    }
                    current.plan = host.navigation.plan(world.getUID(), bounds, feet, goal, profile(current.mode), new PathPlanner.Limits(4096, 4096, 256));
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
                    else if (planned.status() != PathPlanner.Status.FOUND) stopRoute("failed: " + planned.status());
                    else {
                        current.execution = new PathExecution(state.snapshot().position().point(), planned.path(), current.goal.point(), profile(current.mode));
                        routeStatus(current, "moving", "kinematic; live collision revalidation; region owner");
                    }
                }
            }
            if (route == current && current.execution != null) step(current, world, owned);
        } catch (RejectedExecutionException full) { current.nextAttempt = turns + 20; routeStatus(current, "queued", "path capacity"); }
        catch (RuntimeException failure) { stopRoute("failed: " + PersistedNpcService.concise(failure)); }
    }
    private void step(Route current, World world, ChunkOwnership owned) {
        if (!current.execution.position().equals(state.snapshot().position().point())) { stopRoute("cancelled: authoritative position changed"); return; }
        if (current.execution.finished()) { arrive(current); return; }
        PathExecution.Proposal step = current.execution.next(current.speed / 20).orElseThrow();
        int sweep = PersistedNpcService.motionCells(hullWidth(), hullHeight(), step.from().distance(step.to()));
        int settle = PersistedNpcService.motionCells(hullWidth(), hullHeight(), 0);
        int wanted = Math.min(NAVIGATION_CELLS, sweep + (step.landing() ? settle : 0));
        int granted = host.budget.take(RegionBudget.Kind.MOTION_CELLS, wanted);
        if (granted < wanted) { host.budget.refund(RegionBudget.Kind.MOTION_CELLS, granted); return; }
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
        else if (!validation.clear()) stopRoute("blocked: " + validation.status() + "; " + validation.detail());
        else {
            current.execution.commit(step);
            Vec3 delta = step.to().subtract(step.from());
            float facing = Math.hypot(delta.x(), delta.z()) > .000001 ? (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())) : yaw;
            state.move(new WorldPosition(current.goal.world(), step.to()));
            host.index.upsert(state.snapshot());
            pose++; setPose(facing, 0, step.onGround());
            routeStatus(current, "moving", "kinematic; live collision revalidation; region owner");
            if (current.execution.finished()) arrive(current);
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
        OptionalInt next = progress.arrived(current.waypoint);
        if (next.isPresent()) beginWaypoint(next.getAsInt());
        else finishWatch();
    }
    private void beginWaypoint(int waypoint) {
        WaypointProgress progress = waypoints;
        if (progress == null || progress.complete() || !state.snapshot().active()) return;
        WaypointSettings settings = progress.settings();
        WorldPosition goal = new WorldPosition(state.snapshot().position().world(), settings.points().get(waypoint));
        try { PersistedNpcService.validateLeg(state.snapshot().position(), goal, settings.mode(), settings.speed(), hullWidth(), hullHeight()); }
        catch (IllegalArgumentException invalid) { navigationStatus = "invalid waypoint " + waypoint + ": " + invalid.getMessage(); failWatch(navigationStatus); return; }
        route = new Route(goal, settings.mode(), settings.speed(), waypoint);
        route.nextAttempt = turns + Math.max(1, settings.waitTicks());
        routeStatus(route, "queued", "");
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
        Route current = route; route = null;
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
    private double hullWidth() { return PersistedNpcService.hullWidth(bodyWidth()); }
    private double hullHeight() { return PersistedNpcService.hullHeight(bodyHeight()); }

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
        if (routeWatch != null && settings.loop()) { navigationStatus = "invalid: a looping route never finishes"; failWatch(navigationStatus); return; }
        if (waypoints != null && !waypoints.reload(settings)) return;
        stopRoute("replaced by reusable route reload");
        if (waypoints == null) waypoints = new WaypointProgress(settings);
        beginWaypoint(0);
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
    private void finishAppearance(AppearanceRead read, NativePacketAdapter.Skin loaded, String failure) {
        if (appearanceRead != read || read.cancelled) return;
        appearanceRead = null;
        if (failure != null) { host.noteFailure("$.skin: " + failure); return; }
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
            NativePacketAdapter.Skin loaded = null; String failure = null;
            try { if (!cancelled) loaded = SkinDocuments.read(host.runtime.documents(), reference); }
            catch (Exception problem) { failure = PersistedNpcService.concise(problem); }
            finally { host.releaseRead(); }
            NativePacketAdapter.Skin result = loaded; String reason = failure;
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
                && Objects.equals(previous.navigation(), navigation())) return;
        Map<BehaviorPlatform.EquipmentSlot, ItemStack> items = new EnumMap<>(BehaviorPlatform.EquipmentSlot.class);
        equipment.forEach((slot, item) -> items.put(slot, item.clone()));
        publication = new Publication(presented, snapshot, spec.type(), displayName, displaySkin, appearance, yaw, pitch, onGround, pose,
                Map.copyOf(items), equipmentRevision, ids, navigation(), arrivals, ride != null, mount, ++revision);
    }
    private String navigation() {
        return navigationStatus + (routeSource == null ? "" : "; route-source=" + routeSource) + (routeError == null ? "" : "; route-error=" + routeError);
    }

    private static final class Route {
        final UUID id = UUID.randomUUID(); final WorldPosition goal; final MovementProfile.Mode mode; final double speed; final int waypoint;
        NavigationPlan plan; CompletableFuture<PathPlanner.Result> result; PathExecution execution; CaptureProvenance capture;
        long nextAttempt;
        Route(WorldPosition goal, MovementProfile.Mode mode, double speed, int waypoint) { this.goal = goal; this.mode = mode; this.speed = speed; this.waypoint = waypoint; }
    }
}
