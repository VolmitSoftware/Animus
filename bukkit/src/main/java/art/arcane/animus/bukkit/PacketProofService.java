package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.navigation.*;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.navigation.*;
import art.arcane.animus.core.control.*;
import art.arcane.animus.core.virtual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Opt-in mechanical proof, isolated to each viewer. Never runs real commands or damages real entities. */
final class PacketProofService implements Listener, AutoCloseable {
    private static final int MAX_VIEWERS = 128;
    private static final int MAX_BODIES = 8;
    private static final int MAX_CONTROLLERS = 8;
    private static final long LIFETIME_NANOS = 120_000_000_000L;
    private final JavaPlugin plugin;
    private final ReplyScheduler scheduler;
    private final NativePacketAdapter packets;
    private final String unavailable;
    private final Map<UUID, ProofSession> sessions = new HashMap<>();
    private final Map<UUID, SkinRequest> skinRequests = new HashMap<>();
    private BukkitTask cleanup;
    private BukkitTask controlTicker;
    private BukkitNavigationBridge navigation;
    private boolean closed;

    PacketProofService(JavaPlugin plugin, ReplyScheduler scheduler, String version, boolean enabled) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        NativePacketAdapter bound = null;
        String reason = null;
        if (!enabled) reason = "disabled; set packetProofEnabled = true in config.toml and restart";
        else if (scheduler.isFolia()) reason = "unavailable on Folia until region-safe world snapshot validation is proven";
        else try { bound = NativePacketAdapter.bind(plugin, version); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reason = "native binding failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Packet proof unavailable for " + version, e);
        }
        packets = bound;
        unavailable = reason;
        if (packets != null) {
            navigation = new BukkitNavigationBridge(plugin, NavigationLimits.DEFAULT);
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            cleanup = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20, 20);
        }
    }

    boolean available() { return packets != null && !closed; }
    record SkinRequest(UUID world, UUID requestId) {}
    SkinRequest beginSkinLoad(Player viewer) {
        requireAvailable();
        UUID id = viewer.getUniqueId();
        if (!skinRequests.containsKey(id) && skinRequests.size() >= MAX_VIEWERS)
            throw new IllegalStateException("Proof skin request limit reached");
        SkinRequest request = new SkinRequest(viewer.getWorld().getUID(), UUID.randomUUID());
        skinRequests.put(id, request);
        return request;
    }
    boolean finishSkinLoad(Player viewer, SkinRequest request) {
        if (skinRequests.get(viewer.getUniqueId()) != request) return false;
        skinRequests.remove(viewer.getUniqueId());
        return available() && viewer.isOnline() && !viewer.isDead() && viewer.hasPermission("animus.admin")
                && request.world().equals(viewer.getWorld().getUID());
    }
    String status(Player viewer) {
        if (!available()) return "Packet proof " + (closed ? "closed" : unavailable);
        ProofSession session = viewer == null ? null : sessions.get(viewer.getUniqueId());
        int count = session == null ? 0 : session.bodies.size();
        long registered = session == null ? 0 : session.bodies.values().stream()
                .filter(body -> plugin.getServer().getEntity(body.body.uuid()) != null).count();
        return "Packet proof ready | Minecraft " + packets.capabilities().minecraftVersion()
                + " | viewer bodies=" + count + " | world-registered=" + registered
                + " | accepted=" + (session == null ? 0 : session.accepted)
                + " | rejected=" + (session == null ? 0 : session.rejected)
                + " | viewer-isolated preview";
    }

    void spawn(Player viewer, String type, String texture, String signature) {
        requireAvailable();
        if (!viewer.isOnline() || viewer.isDead() || !viewer.hasPermission("animus.admin")) throw new IllegalStateException("Viewer is not active");
        ProofSession session = sessions.get(viewer.getUniqueId());
        try {
            if (session == null) {
                if (sessions.size() >= MAX_VIEWERS) throw new IllegalStateException("Proof viewer limit reached");
                session = new ProofSession(viewer);
                sessions.put(viewer.getUniqueId(), session);
                ProofSession watched = session;
                packets.watch(viewer, (player, task) -> {
                    if (closed || !plugin.isEnabled()) return false;
                    return scheduler.reply(player, task);
                }, input -> interact(watched, input)).whenComplete((nothing, failure) -> {
                    if (failure != null) scheduler.reply(viewer, () -> fail(watched, failure));
                });
            }
            if (session.bodies.size() >= MAX_BODIES || session.admittedBodies >= 64)
                throw new IllegalStateException("Proof limit reached; clear before creating more bodies");
            var skin = texture == null ? null : new NativePacketAdapter.Skin(texture, signature);
            var body = packets.createBody(UUID.randomUUID(), type, "AnimusProof", skin);
            Location origin = viewer.getLocation();
            Vector facing = origin.getDirection().setY(0);
            if (facing.lengthSquared() < 0.000001) facing = new Vector(0, 0, 1);
            Location location = origin.clone().add(facing.normalize().multiply(Math.max(3, body.width() / 2 + 2)));
            location.setYaw(origin.getYaw() + 180); location.setPitch(0);
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
                throw new IllegalStateException("Target chunk is unavailable; proof does not load terrain");
            VirtualNpcState state = new VirtualNpcState(body.uuid(), position(location), body.width(), body.height(), 20);
            ProofBody presented = new ProofBody(body, state, NativePacketAdapter.Pose.at(location, true));
            session.admittedBodies++;
            session.bodies.put(body.entityId(), presented);
            ProofSession owner = session;
            packets.spawn(viewer, body, presented.pose).whenComplete((nothing, failure) -> scheduler.reply(viewer, () -> {
                if (!current(owner) || owner.bodies.get(body.entityId()) != presented) return;
                if (failure != null) { owner.bodies.remove(body.entityId()); fail(owner, failure); return; }
                try { owner.guard.bind(body.entityId(), state.spawn()); }
                catch (RuntimeException e) { fail(owner, e); return; }
                owner.expiresAt = System.nanoTime() + LIFETIME_NANOS;
                viewer.sendMessage("Proof spawned id=" + body.entityId() + " uuid=" + body.uuid() + " type=" + type
                        + " x=" + location.getX() + " y=" + location.getY() + " z=" + location.getZ()
                        + " height=" + body.height() + " world-registered=" + (plugin.getServer().getEntity(body.uuid()) == null ? 0 : 1));
            }));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot create proof body: " + e.getMessage(), e); }
    }

    void move(Player viewer, int id, double dx, double dy, double dz) {
        requireAvailable();
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz) || Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) > 8)
            throw new IllegalArgumentException("Proof move offsets must be finite and within ±8 blocks");
        ProofSession session = session(viewer);
        ProofBody body = body(session, id);
        if (session.control != null) throw new IllegalStateException("Stop remote control before moving proof bodies manually");
        if (mounted(session, id) || session.mountPending.contains(id)) throw new IllegalStateException("Detach proof mounts before moving them");
        if (body.moving) throw new IllegalStateException("Movement update already pending");
        var old = body.pose;
        var next = new NativePacketAdapter.Pose(old.world(), old.x() + dx, old.y() + dy, old.z() + dz, old.yaw(), old.pitch(), old.onGround());
        if (!viewer.getWorld().getUID().equals(next.world()) || !viewer.getWorld().isChunkLoaded(((int) Math.floor(next.x())) >> 4, ((int) Math.floor(next.z())) >> 4)
                || viewer.getLocation().toVector().distanceSquared(new Vector(next.x(), next.y(), next.z())) > 64 * 64)
            throw new IllegalArgumentException("Target is outside visible loaded terrain");
        body.moving = true;
        cancelPlan(session);
        // Withdraw interaction authority while the client position update is pending.
        packets.move(viewer, body.body, next).whenComplete((nothing, failure) -> scheduler.reply(viewer, () -> {
            if (!current(session) || session.bodies.get(id) != body) return;
            body.moving = false;
            if (failure != null) { fail(session, failure); return; }
            body.pose = next;
            session.guard.updateSnapshot(id, body.state.move(new WorldPosition(next.world().toString(), new Vec3(next.x(), next.y(), next.z()))));
            viewer.sendMessage("Proof moved id=" + id + " x=" + next.x() + " y=" + next.y() + " z=" + next.z());
        }));
    }

    void control(Player viewer, int id) {
        requireAvailable();
        if (!viewer.isOnline() || viewer.isDead() || !viewer.hasPermission("animus.admin") || viewer.isInsideVehicle())
            throw new IllegalStateException("Remote control requires an active operator outside a real vehicle");
        ProofSession session = session(viewer); ProofBody body = body(session, id);
        if (session.control != null) throw new IllegalStateException("Stop the current remote control lease first");
        if (sessions.values().stream().filter(value -> value.control != null).count() >= MAX_CONTROLLERS)
            throw new IllegalStateException("Remote control capacity reached");
        if (!body.body.typeKey().equals("minecraft:player") || mounted(session, id) || session.mountPending.contains(id)
                || body.moving && !body.controlMove)
            throw new IllegalStateException("Control requires a detached player proof body without a manual movement pending");
        Vec3 point = body.state.snapshot().position().point();
        if (!viewer.getWorld().getUID().equals(body.pose.world()) || point.distance(position(viewer.getLocation()).point()) > 32)
            throw new IllegalStateException("Control body must be in this world within 32 blocks");
        var terrain = LiveMovementProbe.check(viewer.getWorld(), point, point, body.body.width(), body.body.height(), MotionValidator.Medium.GROUND, true, 128);
        if (!terrain.clear()) throw new IllegalStateException("Control requires supported flat loaded terrain: " + terrain.status());
        cancelPlan(session);
        ControlSession control = new ControlSession(body, packets.subscribeControl(viewer), point, System.nanoTime());
        session.control = control; session.lastControl = control;
        if (controlTicker == null) controlTicker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickControls, 1, 1);
        viewer.sendMessage("Proof control started id=" + id + " generation=" + control.loop.generation()
                + " speed=2.0 leash=8.0 lifetime-seconds=30 | unmounted remote control; awaiting fresh input");
    }

    void stopControl(Player viewer) {
        requireAvailable();
        ProofSession session = sessions.get(viewer.getUniqueId());
        if (session == null || session.control == null) { viewer.sendMessage("Proof control stopped reason=idle"); return; }
        stopControl(session, "command", true);
    }

    String controlStatus(Player viewer) {
        requireAvailable();
        ProofSession session = sessions.get(viewer.getUniqueId());
        ControlSession control = session == null ? null : session.lastControl;
        if (control == null) return "Proof control idle | unmounted remote control";
        Vec3 point = control.body.state.snapshot().position().point();
        return "Proof control id=" + control.body.body.entityId() + " generation=" + control.loop.generation()
                + " state=" + control.state + " x=" + point.x() + " y=" + point.y() + " z=" + point.z()
                + " tick=" + control.loop.ticks() + " steps=" + control.steps + " input-sequence=" + control.sequence
                + " reason=" + control.reason + " | unmounted remote control";
    }

    private void tickControls() {
        for (ProofSession session : List.copyOf(sessions.values())) {
            ControlSession control = session.control;
            if (control == null) continue;
            Player viewer = session.viewer; ProofBody body = control.body;
            try {
                if (body.controlDeliveryFailed) { clear(viewer); continue; }
                if (!current(session) || viewer.isDead() || !viewer.hasPermission("animus.admin") || viewer.isInsideVehicle()
                        || session.bodies.get(body.body.entityId()) != body || !body.state.snapshot().active()
                        || mounted(session, body.body.entityId()) || session.mountPending.contains(body.body.entityId())
                        || !viewer.getWorld().getUID().equals(body.pose.world())) {
                    stopControl(session, "authority-retired", true); continue;
                }
                Vec3 point = body.state.snapshot().position().point();
                if (point.distance(position(viewer.getLocation()).point()) > 32) { stopControl(session, "viewer-range", true); continue; }
                var input = control.subscription.latest();
                if (input.latest() != null) control.sequence = input.latest().sequence();
                var proposed = control.loop.propose(System.nanoTime(), point, viewer.getLocation().getYaw(), input);
                if (proposed.state() == ControlLoop.State.STOPPED) { stopControl(session, proposed.reason(), true); continue; }
                if (proposed.state() == ControlLoop.State.THROTTLED) continue;
                if (body.moving) { control.state = "PENDING"; continue; }
                control.state = proposed.state().name(); control.reason = proposed.reason();
                var terrain = LiveMovementProbe.check(viewer.getWorld(), point, proposed.position(), body.body.width(), body.body.height(),
                        MotionValidator.Medium.GROUND, true, 128);
                if (terrain.status() == MotionValidator.Status.UNAVAILABLE_TERRAIN) { stopControl(session, "terrain-unavailable", true); continue; }
                if (!terrain.clear()) { control.state = "BLOCKED"; control.reason = terrain.status().name(); continue; }
                if (proposed.state() != ControlLoop.State.PROPOSED) continue;
                Vec3 next = proposed.position();
                var pose = new NativePacketAdapter.Pose(body.pose.world(), next.x(), next.y(), next.z(), viewer.getLocation().getYaw(), 0, true);
                // Publish the accepted step before egress. A completion never advances coordinates after a lease ends.
                body.moving = true; body.controlMove = true; long movement = ++body.movementGeneration;
                body.pose = pose;
                session.guard.updateSnapshot(body.body.entityId(), body.state.move(new WorldPosition(pose.world().toString(), next)));
                control.steps++; control.state = "PENDING"; control.reason = "packet-pending";
                packets.move(viewer, body.body, pose).whenComplete((nothing, failure) -> {
                    boolean delivered = scheduler.reply(viewer, () -> {
                        if (!current(session) || session.bodies.get(body.body.entityId()) != body || body.movementGeneration != movement) return;
                        body.moving = false; body.controlMove = false;
                        if (failure != null) { fail(session, failure); return; }
                        if (session.control == control) { control.state = "ACTIVE"; control.reason = "clear"; }
                    });
                    if (!delivered) body.controlDeliveryFailed = true;
                });
            } catch (RuntimeException failure) { fail(session, failure); }
        }
        stopControlTickerWhenIdle();
    }

    private void stopControl(ProofSession session, String reason, boolean notify) {
        ControlSession control = session.control;
        if (control == null) return;
        session.control = null; control.loop.close(reason); control.subscription.close();
        control.state = "STOPPED"; control.reason = reason;
        if (notify && session.viewer.isOnline()) session.viewer.sendMessage("Proof control stopped reason=" + reason);
        stopControlTickerWhenIdle();
    }
    private void stopControlTickerWhenIdle() {
        if (controlTicker != null && sessions.values().stream().noneMatch(session -> session.control != null)) {
            controlTicker.cancel(); controlTicker = null;
        }
    }

    /** Diagnostic only: captured terrain can change; executing a route requires live revalidation. */
    void path(Player viewer, int id, int dx, int dy, int dz) {
        requireAvailable();
        if (Math.max(Math.abs((long) dx), Math.max(Math.abs((long) dy), Math.abs((long) dz))) > 8)
            throw new IllegalArgumentException("Proof path offsets must be within ±8 blocks");
        ProofSession session = session(viewer);
        ProofBody body = body(session, id);
        if (session.control != null) throw new IllegalStateException("Stop remote control before planning a proof route");
        if (body.moving || mounted(session, id) || session.mountPending.contains(id))
            throw new IllegalStateException("Proof body must be stationary and detached for planning");
        if (body.body.width() > 5) throw new IllegalArgumentException("Body exceeds the voxel planner width limit");
        cancelPlan(session);
        var snapshot = body.state.snapshot();
        BlockPos start = BlockPos.containing(snapshot.position().point());
        BlockPos goal = new BlockPos(start.x() + dx, start.y() + dy, start.z() + dz);
        int height = Math.max(1, (int) Math.ceil(body.body.height()));
        int radius = Math.max(0, (int) Math.ceil((body.body.width() - 1) / 2));
        int padding = radius + 2;
        int minX = Math.min(start.x(), goal.x()) - padding, minY = Math.min(start.y(), goal.y()) - 4;
        int minZ = Math.min(start.z(), goal.z()) - padding;
        CaptureBounds bounds = new CaptureBounds(minX, minY, minZ,
                Math.abs(dx) + padding * 2 + 1, Math.abs(dy) + height + 6, Math.abs(dz) + padding * 2 + 1);
        MovementProfile profile = new MovementProfile(Set.of(MovementProfile.Mode.WALK), height, radius, 0, 1, 3, true);
        NavigationPlan plan = navigation.plan(body.pose.world(), bounds, start, goal, profile, new PathPlanner.Limits(2048, 2048, 128));
        session.plan = plan;
        viewer.sendMessage("Proof path queued id=" + id + " request=" + plan.id());
        plan.future().whenComplete((result, failure) -> scheduler.reply(viewer, () -> {
            if (!current(session) || session.plan != plan) return;
            session.plan = null;
            if (session.bodies.get(id) != body || body.state.snapshot().revision() != snapshot.revision()) return;
            if (failure != null) { viewer.sendMessage("Proof path failed: " + failure.getClass().getSimpleName()); return; }
            String capture = plan.capture().map(p -> " sampled=" + p.sampledVoxels() + " unavailable=" + p.unavailableVoxels()
                    + " capture-ticks=" + p.firstTick() + ".." + p.lastTick()).orElse("");
            viewer.sendMessage("Proof path id=" + id + " status=" + result.status() + " steps=" + result.path().size()
                    + " visited=" + result.visitedNodes() + " peak-frontier=" + result.peakFrontier() + capture
                    + " | planning only; terrain requires revalidation before movement");
        }));
    }

    private static void cancelPlan(ProofSession session) {
        NavigationPlan plan = session.plan;
        session.plan = null;
        if (plan != null) plan.cancel();
    }

    void mount(Player viewer, int vehicleId, List<Integer> riderIds) {
        requireAvailable();
        ProofSession session = session(viewer);
        if (session.control != null) throw new IllegalStateException("Stop remote control before mounting proof bodies");
        ProofBody vehicle = body(session, vehicleId);
        List<NativePacketAdapter.Body> riders = new ArrayList<>();
        Set<Integer> affected = new HashSet<>(riderIds); affected.add(vehicleId);
        affected.addAll(session.mounts.getOrDefault(vehicleId, Set.of()));
        for (int id : affected) if (body(session, id).moving || session.mountPending.contains(id))
            throw new IllegalStateException("A mount operation or movement is already pending");
        for (int id : riderIds) riders.add(body(session, id).body);
        session.mountPending.addAll(affected);
        cancelPlan(session);
        try {
            packets.passengers(viewer, vehicle.body, riders, null, false).whenComplete((nothing, failure) -> scheduler.reply(viewer, () -> {
                if (!current(session)) return;
                session.mountPending.removeAll(affected);
                if (failure != null) { fail(session, failure); return; }
                if (riderIds.isEmpty()) session.mounts.remove(vehicleId);
                else session.mounts.put(vehicleId, Set.copyOf(riderIds));
                viewer.sendMessage("Proof passengers vehicle=" + vehicleId + " count=" + riderIds.size()
                        + " | static presentation only; detach before moving or interacting");
            }));
        } catch (RuntimeException e) { session.mountPending.removeAll(affected); throw e; }
    }

    private static boolean mounted(ProofSession session, int id) {
        return session.mounts.containsKey(id) || session.mounts.values().stream().anyMatch(riders -> riders.contains(id));
    }

    void clear(Player viewer) {
        skinRequests.remove(viewer.getUniqueId());
        ProofSession session = sessions.remove(viewer.getUniqueId());
        if (session != null) { stopControl(session, "cleared", false); cancelPlan(session); session.guard.close(); session.bodies.values().forEach(body -> body.state.despawn()); session.bodies.clear(); }
        if (packets != null) packets.forget(viewer);
    }

    private void interact(ProofSession session, NativePacketAdapter.Interaction input) {
        if (!current(session)) return;
        Player viewer = session.viewer;
        if (viewer.isDead() || !viewer.hasPermission("animus.admin")) { clear(viewer); return; }
        ProofBody body = session.bodies.get(input.body().entityId());
        if (body == null || body.moving || mounted(session, body.body.entityId()) || session.mountPending.contains(body.body.entityId())) return;
        Location eye = viewer.getEyeLocation();
        Vector direction = eye.getDirection();
        var result = session.guard.validate(new ViewerSession.Request(viewer.getUniqueId(), session.guard.sessionId(),
                input.body().entityId(), position(eye), new Vec3(direction.getX(), direction.getY(), direction.getZ()), System.nanoTime()),
                (serverEye, point, distance) -> {
                    // Bukkit block access is only used on the owner/main scheduler; this proof is explicitly disabled on Folia.
                    int minX = ((int) Math.floor(Math.min(eye.getX(), point.x()))) >> 4;
                    int maxX = ((int) Math.floor(Math.max(eye.getX(), point.x()))) >> 4;
                    int minZ = ((int) Math.floor(Math.min(eye.getZ(), point.z()))) >> 4;
                    int maxZ = ((int) Math.floor(Math.max(eye.getZ(), point.z()))) >> 4;
                    for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                        if (!viewer.getWorld().isChunkLoaded(x, z)) return false;
                    return distance <= 0.000001 || viewer.getWorld().rayTraceBlocks(eye, direction, Math.max(0, distance - 0.0001), FluidCollisionMode.NEVER, true) == null;
                }, body.body.typeKey().equals(DragonBody.TYPE) ? DragonBody.hitShape(body.pose.yaw()) : ViewerSession.HitShape.BOUNDS);
        if (!result.accepted()) {
            session.rejected++;
            viewer.sendMessage("Proof rejected id=" + input.body().entityId() + " reason=" + result.rejection());
            return;
        }
        var action = input.action() == NativePacketAdapter.Action.ATTACK ? VirtualNpcInteractionEvent.Action.ATTACK : VirtualNpcInteractionEvent.Action.INTERACT;
        var event = new VirtualNpcInteractionEvent(viewer, body.body.uuid(), action, result.distance(), true);
        plugin.getServer().getPluginManager().callEvent(event);
        if (!current(session) || session.bodies.get(body.body.entityId()) != body || body.moving || viewer.isDead()
                || mounted(session, body.body.entityId()) || session.mountPending.contains(body.body.entityId())
                || !viewer.hasPermission("animus.admin") || body.state.snapshot().revision() != result.npc().revision()) return;
        if (event.isCancelled()) { viewer.sendMessage("Proof interaction cancelled id=" + body.body.entityId()); return; }
        session.accepted++;
        if (action == VirtualNpcInteractionEvent.Action.ATTACK) {
            cancelPlan(session);
            session.guard.updateSnapshot(body.body.entityId(), body.state.damage(1));
        }
        viewer.sendMessage("Proof accepted id=" + body.body.entityId() + " action=" + action + " health=" + body.state.snapshot().health());
        if (!body.state.snapshot().active()) {
            if (session.control != null && session.control.body == body) stopControl(session, "body-retired", true);
            session.bodies.remove(body.body.entityId());
            packets.despawn(viewer, body.body);
        }
    }

    private void expire() {
        long now = System.nanoTime();
        for (ProofSession session : List.copyOf(sessions.values())) {
            if (!session.viewer.isOnline() || session.viewer.isDead() || now - session.expiresAt >= 0) { clear(session.viewer); continue; }
            for (ProofBody body : List.copyOf(session.bodies.values())) {
                if (body.controlDeliveryFailed) { clear(session.viewer); break; }
                var pose = body.pose;
                if (!session.viewer.getWorld().getUID().equals(pose.world())
                        || !session.viewer.getWorld().isChunkLoaded(((int) Math.floor(pose.x())) >> 4, ((int) Math.floor(pose.z())) >> 4)
                        || session.viewer.getLocation().toVector().distanceSquared(new Vector(pose.x(), pose.y(), pose.z())) > 64 * 64) {
                    // A mount is one presentation unit: invalidate the whole proof rather than leave
                    // passenger state (or an in-flight mount completion) pointing at a removed body.
                    if (mounted(session, body.body.entityId()) || session.mountPending.contains(body.body.entityId())) {
                        clear(session.viewer);
                        break;
                    }
                    cancelPlan(session);
                    if (session.control != null && session.control.body == body) stopControl(session, "body-retired", true);
                    session.bodies.remove(body.body.entityId()); session.guard.unbind(body.body.entityId()); body.state.despawn();
                    packets.despawn(session.viewer, body.body);
                }
            }
        }
    }

    private boolean current(ProofSession session) { return !closed && sessions.get(session.viewer.getUniqueId()) == session && session.viewer.isOnline(); }
    private void fail(ProofSession session, Throwable failure) {
        if (!current(session)) return;
        clear(session.viewer);
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        session.viewer.sendMessage("Packet proof failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Packet proof operation failed", failure);
    }
    private void requireAvailable() { if (!available()) throw new IllegalStateException(status(null)); }
    private ProofSession session(Player viewer) {
        ProofSession session = sessions.get(viewer.getUniqueId());
        if (session == null) throw new IllegalArgumentException("No proof bodies for this viewer");
        return session;
    }
    private static ProofBody body(ProofSession session, int id) {
        ProofBody body = session.bodies.get(id);
        if (body == null || !body.state.snapshot().active()) throw new IllegalArgumentException("Proof body is not active for this viewer");
        return body;
    }
    private static WorldPosition position(Location location) {
        return new WorldPosition(location.getWorld().getUID().toString(), new Vec3(location.getX(), location.getY(), location.getZ()));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) { clear(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { clear(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { clear(event.getPlayer()); }
    @Override public void close() {
        if (closed) return;
        for (ProofSession session : List.copyOf(sessions.values())) clear(session.viewer);
        skinRequests.clear();
        closed = true;
        if (cleanup != null) cleanup.cancel();
        if (controlTicker != null) controlTicker.cancel();
        if (navigation != null) navigation.close();
        HandlerList.unregisterAll(this);
        if (packets != null) packets.close();
    }
    private static final class ProofSession {
        final Player viewer;
        final ViewerSession guard;
        final Map<Integer, ProofBody> bodies = new LinkedHashMap<>();
        long expiresAt = System.nanoTime() + LIFETIME_NANOS;
        final Map<Integer, Set<Integer>> mounts = new HashMap<>();
        final Set<Integer> mountPending = new HashSet<>();
        NavigationPlan plan;
        ControlSession control, lastControl;
        int admittedBodies;
        long accepted, rejected;
        ProofSession(Player viewer) {
            this.viewer = viewer;
            guard = new ViewerSession(viewer.getUniqueId(), UUID.randomUUID(), new ViewerSession.Limits(MAX_BODIES, 64, 100_000_000L, 4.5));
        }
    }
    private static final class ProofBody {
        final NativePacketAdapter.Body body;
        final VirtualNpcState state;
        NativePacketAdapter.Pose pose;
        boolean moving;
        boolean controlMove;
        volatile boolean controlDeliveryFailed;
        long movementGeneration;
        ProofBody(NativePacketAdapter.Body body, VirtualNpcState state, NativePacketAdapter.Pose pose) { this.body = body; this.state = state; this.pose = pose; }
    }
    private static final class ControlSession {
        final ProofBody body;
        final NativePacketAdapter.ControlSubscription subscription;
        final ControlLoop loop;
        long steps, sequence;
        String state = "WAITING_INPUT", reason = "fresh-input-required";
        ControlSession(ProofBody body, NativePacketAdapter.ControlSubscription subscription, Vec3 anchor, long now) {
            this.body = body; this.subscription = subscription; loop = new ControlLoop(anchor, now, ControlLoop.Limits.PREVIEW);
        }
    }
}
