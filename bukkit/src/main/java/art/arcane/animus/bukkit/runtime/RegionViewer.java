package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.runtime.NpcPresentation;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.bukkit.packet.NativeViewerEpoch;
import art.arcane.animus.core.behavior.trigger.ProximityTracker;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.ViewerSession;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * One player's presentation of persisted NPCs on Folia. Runs on the player's own entity scheduler and reads NPCs only
 * through their immutable {@link RegionActor.Publication}. An interaction is honoured only when the player's region
 * also owns the NPC; otherwise it is refused, never answered from another region's live state.
 */
final class RegionViewer {
    static final int MAX_PENDING_SPAWNS = 8, MAX_COMPLETIONS = 64, PACKETS_PER_REGION = 32, WAKE_INTERVAL = 20;
    private final RegionNpcRuntime host;
    final Player player;
    final UUID id, world;
    final NativeViewerEpoch.Stamp epoch;
    final ViewerSession guard;
    private final Map<UUID, Presented> visible = new HashMap<>(), retiring = new HashMap<>();
    private final Deque<Presented> retirements = new ArrayDeque<>(), presentationOrder = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    final AtomicInteger shown = new AtomicInteger();
    private SpatialNpcIndex.Cursor cursor;
    private ProximityTracker proximity;
    private int nativeIds, pendingSpawns;
    private long ticks;
    private final long phase = java.util.concurrent.ThreadLocalRandom.current().nextLong(1 << 20);
    volatile boolean ready, failed;
    volatile AutoCloseable task;

    RegionViewer(RegionNpcRuntime host, Player player, NativeViewerEpoch.Stamp epoch) {
        this.host = host; this.player = player; this.epoch = epoch;
        id = player.getUniqueId(); world = player.getWorld().getUID();
        guard = new ViewerSession(id, UUID.randomUUID(), new ViewerSession.Limits(host.settings.maximumVisible(), RegionNpcRuntime.MAX_ISSUED_IDS, 100_000_000L, 4.5));
    }

    /** Packet completions arrive on Netty; they are applied on the next viewer tick, on the player's owner. */
    void enqueue(Runnable completion) {
        if (queued.incrementAndGet() > MAX_COMPLETIONS) { queued.decrementAndGet(); failed = true; return; }
        completions.add(completion);
    }

    /** Player owner only. A fault ends this viewer's lease; it never escapes into the region tick. */
    void tick() {
        try { turn(); }
        catch (RuntimeException failure) { host.fail(this, PersistedNpcService.concise(failure)); }
    }
    private void turn() {
        if (!host.current(this)) return;
        if (!host.regions.owns(player)) return;
        if (failed || !player.isOnline()) { host.fail(this, "Packet callback/connection unavailable"); return; }
        if (player.isDead()) { host.disconnect(this); return; }
        if (!epoch.equals(host.epochs.capture(player)) || !world.equals(player.getWorld().getUID())
                || guard.issuedCount() >= RegionNpcRuntime.MAX_ISSUED_IDS - host.settings.maximumVisible()) {
            host.reset(this); return;
        }
        ticks++;
        for (int i = 0; i < MAX_COMPLETIONS; i++) {
            Runnable completion = completions.poll();
            if (completion == null) break;
            queued.decrementAndGet();
            completion.run();
            if (!host.current(this)) return;
        }
        WorldPosition at = position(player.getLocation());
        TriggerDispatcher dispatcher = host.triggers;
        if (dispatcher != null) {
            if (proximity == null) proximity = new ProximityTracker(dispatcher.settings().proximityLimits(), host.index);
            proximity.tick(dispatcher.proximityPlan(), List.of(id), viewer -> Optional.of(at), dispatcher::publish);
        }
        if ((ticks - 1) % WAKE_INTERVAL == 0) host.wakeNear(player, at);
        // Viewers take turns: with more viewers than updatesPerTick, each one updates every stride ticks, offset by its own phase.
        int stride = Math.max(1, (host.viewerCount() + host.settings.updatesPerTick() - 1) / host.settings.updatesPerTick());
        if ((ticks + phase) % stride != 0 || host.budget.take(RegionBudget.Kind.VIEWERS, 1) == 0) return;
        // An equal share of the region's packet work, so the first viewers in scheduler order cannot use all of it.
        int share = Math.max(2, PACKETS_PER_REGION / Math.max(1, Math.min(host.settings.updatesPerTick(), host.viewerCount())));
        int budget = host.budget.take(RegionBudget.Kind.PACKETS, share);
        host.budget.refund(RegionBudget.Kind.PACKETS, budget - update(at, budget));
    }

    private int update(WorldPosition at, int packetBudget) {
        int sent = 0;
        Map<Long, Boolean> delivered = new HashMap<>();
        Predicate<ChunkPosition> sentChunk = chunk -> chunk.world().equals(at.world())
                && delivered.computeIfAbsent(ChunkPosition.key(chunk.x(), chunk.z()), ignored -> host.regions.sent(player, chunk.x(), chunk.z()));
        for (Presented body : List.copyOf(visible.values())) if (!eligible(body.actor.publication, at, sentChunk)) retire(body);
        while (!retirements.isEmpty() && sent < packetBudget) {
            Presented body = retirements.removeFirst(); sent++;
            host.packets.despawn(player, body.body).whenComplete((nothing, failure) -> enqueue(() -> {
                if (!host.current(this)) return;
                body.despawnFinished = true;
                if (failure != null) host.fail(this, PersistedNpcService.concise(failure)); else release(body);
            }));
        }
        if (ready && retirements.isEmpty()) {
            for (int i = 0, count = presentationOrder.size(); i < count && sent < packetBudget; i++) {
                Presented body = presentationOrder.removeFirst();
                if (body.retired) continue;
                presentationOrder.addLast(body);
                RegionActor.Publication now = body.actor.publication;
                if (now.appearance() != body.appearance) { retire(body); continue; }
                if (body.spawnFinished && !body.moving && body.pose != now.pose()) { move(body, now); sent++; if (!host.current(this)) return sent; }
                if (body.spawnFinished && !body.equipping && sent < packetBudget && body.equipment != now.equipmentRevision()) { equip(body, now); sent++; if (!host.current(this)) return sent; }
            }
        }
        if (!ready || !retirements.isEmpty() || sent >= packetBudget || pendingSpawns >= MAX_PENDING_SPAWNS || visible.size() >= host.settings.maximumVisible()) return sent;
        int capacity = Math.min(Math.min(8, packetBudget - sent), Math.min(host.settings.maximumVisible() - visible.size(), MAX_PENDING_SPAWNS - pendingSpawns));
        SpatialNpcIndex.Scan scan = host.index.scan(new SpatialNpcIndex.Query(at, host.settings.viewDistance()), cursor, new SpatialNpcIndex.Budget(32, Math.max(1, capacity * 2)), sentChunk);
        cursor = scan.next().orElse(null);
        for (VirtualNpcState.Snapshot candidate : scan.candidates()) {
            if (capacity == 0) break;
            RegionActor actor = host.byUuid.get(candidate.npcId());
            RegionActor.Publication now = actor == null ? null : actor.publication;
            if (now == null || visible.containsKey(candidate.npcId()) || retiring.containsKey(candidate.npcId())
                    || nativeIds + now.ids() > 256 || !eligible(now, at, sentChunk)) continue;
            spawn(actor, now); sent++; capacity--;
            if (!host.current(this)) break;
        }
        return sent;
    }
    private boolean eligible(RegionActor.Publication now, WorldPosition at, Predicate<ChunkPosition> sentChunk) {
        return now.live() && now.snapshot().position().world().equals(at.world())
                && now.snapshot().position().point().distance(at.point()) <= host.settings.viewDistance() && sentChunk.test(now.snapshot().position().chunk());
    }
    private boolean eligibleNow(RegionActor actor) {
        if (!host.current(this) || !player.isOnline() || player.isDead()) return false;
        WorldPosition at = position(player.getLocation());
        return eligible(actor.publication, at, chunk -> chunk.world().equals(at.world()) && host.regions.sent(player, chunk.x(), chunk.z()));
    }
    private void spawn(RegionActor actor, RegionActor.Publication now) {
        try {
            NativePacketAdapter.Body body = host.packets.createBody(now.spec().definition().uuid(), now.spec().type(), now.name(), now.spec().appearance(), now.skin());
            Presented presented = new Presented(actor, body, now);
            visible.put(actor.uuid, presented); presentationOrder.addLast(presented);
            nativeIds += presented.ids; pendingSpawns++; shown.incrementAndGet();
            host.packets.spawn(player, body, pose(now)).whenComplete((nothing, failure) -> enqueue(() -> {
                if (!host.current(this)) return;
                presented.spawnFinished = true; pendingSpawns--;
                if (presented.retired) { release(presented); return; }
                if (failure != null) { host.fail(this, PersistedNpcService.concise(failure)); return; }
                if (!eligibleNow(actor)) { retire(presented); return; }
                try { guard.bind(body.entityId(), actor.publication.snapshot()); presented.bound = true; }
                catch (RuntimeException invalid) { host.fail(this, PersistedNpcService.concise(invalid)); }
            }));
        } catch (RuntimeException failure) { host.fail(this, PersistedNpcService.concise(failure)); }
    }
    private void move(Presented body, RegionActor.Publication now) {
        body.moving = true;
        try {
            host.packets.move(player, body.body, pose(now)).whenComplete((nothing, failure) -> enqueue(() -> {
                if (!host.current(this) || body.retired || visible.get(body.actor.uuid) != body) return;
                body.moving = false;
                if (failure != null) { host.fail(this, PersistedNpcService.concise(failure)); return; }
                if (!eligibleNow(body.actor)) { retire(body); return; }
                body.pose = now.pose();
                if (body.bound) guard.updateSnapshot(body.body.entityId(), body.actor.publication.snapshot());
            }));
        } catch (RuntimeException failure) { host.fail(this, PersistedNpcService.concise(failure)); }
    }
    /** Cosmetic: a failed send is recorded and not retried, and never costs the viewer their session. */
    private void equip(Presented body, RegionActor.Publication now) {
        body.equipping = true;
        try {
            host.packets.equipment(player, body.body, now.equipment()).whenComplete((nothing, failure) -> enqueue(() -> {
                body.equipping = false; body.equipment = now.equipmentRevision();
                if (failure != null && host.current(this) && !body.retired) host.noteFailure(PersistedNpcService.concise(failure));
            }));
        } catch (RuntimeException failure) { body.equipping = false; body.equipment = now.equipmentRevision(); host.noteFailure(PersistedNpcService.concise(failure)); }
    }
    private void retire(Presented body) {
        if (body.retired) return;
        body.retired = true; visible.remove(body.actor.uuid, body); guard.unbind(body.body.entityId());
        presentationOrder.remove(body); shown.decrementAndGet();
        retiring.put(body.actor.uuid, body); retirements.addLast(body);
    }
    private void release(Presented body) {
        if (body.spawnFinished && body.despawnFinished && retiring.remove(body.actor.uuid, body)) nativeIds -= body.ids;
    }
    void stop() {
        AutoCloseable running = task;
        if (running != null) try { running.close(); } catch (Exception ignored) { /* Folia already retired the task. */ }
    }
    /** Network and pure state only: callable from a retirement callback without world access. */
    void close() {
        guard.close(); visible.clear(); retiring.clear(); retirements.clear(); presentationOrder.clear(); completions.clear(); shown.set(0);
        if (proximity != null) proximity.forget(id);
        AutoCloseable running = task;
        if (running != null) try { running.close(); } catch (Exception ignored) { /* Folia already retired the task. */ }
    }

    /** Player owner only, from the packet adapter's scheduler. */
    void interact(NativePacketAdapter.Interaction input) {
        if (!host.current(this) || !host.regions.owns(player) || player.isDead()) return;
        Presented body = visible.get(input.body().uuid());
        if (body == null || body.body != input.body() || !body.bound || body.retired || !eligibleNow(body.actor)) { host.rejected.incrementAndGet(); return; }
        RegionActor actor = body.actor;
        // The NPC's mutable state may be read only by its owner: the player's region must hold the NPC as well.
        if (!actor.owner.owned()) { host.rejected.incrementAndGet(); return; }
        if (!actor.presentable()) { host.rejected.incrementAndGet(); retire(body); return; }
        guard.updateSnapshot(body.body.entityId(), actor.state.snapshot());
        Location eye = player.getEyeLocation(); Vector direction = eye.getDirection();
        World viewerWorld = player.getWorld();
        ViewerSession.HitShape shape = actor.spec.type().equals(DragonBody.TYPE) ? DragonBody.hitShape(actor.yaw) : ViewerSession.HitShape.BOUNDS;
        ViewerSession.Validation validated = guard.validate(new ViewerSession.Request(id, guard.sessionId(), input.body().entityId(),
                position(eye), new Vec3(direction.getX(), direction.getY(), direction.getZ()), System.nanoTime()), (from, to, distance) -> {
            int minX = ((int) Math.floor(Math.min(eye.getX(), to.x()))) >> 4, maxX = ((int) Math.floor(Math.max(eye.getX(), to.x()))) >> 4;
            int minZ = ((int) Math.floor(Math.min(eye.getZ(), to.z()))) >> 4, maxZ = ((int) Math.floor(Math.max(eye.getZ(), to.z()))) >> 4;
            if (!host.regions.owns(viewerWorld, minX, minZ, maxX, maxZ)) return false;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!viewerWorld.isChunkLoaded(x, z)) return false;
            return distance <= 0.000001 || viewerWorld.rayTraceBlocks(eye, direction, Math.max(0, distance - 0.0001), FluidCollisionMode.NEVER, true) == null;
        }, shape);
        if (!validated.accepted()) { host.rejected.incrementAndGet(); return; }
        VirtualNpcInteractionEvent.Action action = input.action() == NativePacketAdapter.Action.ATTACK ? VirtualNpcInteractionEvent.Action.ATTACK : VirtualNpcInteractionEvent.Action.INTERACT;
        VirtualNpcInteractionEvent event = new VirtualNpcInteractionEvent(player, actor.uuid, action, validated.distance(), false);
        host.plugin.getServer().getPluginManager().callEvent(event);
        // Listeners run on this region and may teleport, clear or hand the NPC elsewhere: authority is read again.
        if (event.isCancelled() || !host.current(this) || player.isDead() || visible.get(actor.uuid) != body || !eligibleNow(actor)
                || !actor.owner.owned() || actor.state.snapshot().revision() != validated.npc().revision() || !actor.presentable()) return;
        host.accepted.incrementAndGet();
        TriggerDispatcher dispatcher = host.triggers;
        if (dispatcher != null) dispatcher.publish(new TriggerEvent.Interaction(actor.uuid, id,
                action == VirtualNpcInteractionEvent.Action.ATTACK ? TriggerEvent.Action.ATTACK : TriggerEvent.Action.INTERACT));
        NpcRuntime.InteractionListener<Player> listener = host.interactions;
        if (listener != null && listener.interacted(player, actor.uuid, action == VirtualNpcInteractionEvent.Action.ATTACK, input.secondaryAction())) return;
        if (actor.publication.ride() && action == VirtualNpcInteractionEvent.Action.INTERACT && !input.secondaryAction() && "MAIN_HAND".equals(input.hand())) {
            player.sendMessage("Cannot ride " + actor.spec.name() + ": " + RegionNpcRuntime.RIDING_UNAVAILABLE);
            return;
        }
        if (player.hasPermission("animus.admin")) {
            host.selections.put(id, actor.id);
            player.sendMessage("Selected " + actor.id + " via " + action);
        }
    }

    private static BodyPose pose(RegionActor.Publication now) {
        WorldPosition position = now.snapshot().position(); Vec3 point = position.point();
        return new BodyPose(position.world(), point.x(), point.y(), point.z(), now.yaw(), now.pitch(), now.onGround());
    }
    static WorldPosition position(Location location) {
        return new WorldPosition(location.getWorld().getUID().toString(), new Vec3(location.getX(), location.getY(), location.getZ()));
    }

    private static final class Presented {
        final RegionActor actor; final NativePacketAdapter.Body body; final long appearance; final int ids;
        boolean bound, retired, spawnFinished, despawnFinished, moving, equipping;
        long pose, equipment;
        Presented(RegionActor actor, NativePacketAdapter.Body body, RegionActor.Publication now) {
            this.actor = actor; this.body = body; appearance = now.appearance(); ids = body.entityIds().length; pose = now.pose();
            equipment = now.equipment().isEmpty() ? now.equipmentRevision() : -1;
        }
    }
}
