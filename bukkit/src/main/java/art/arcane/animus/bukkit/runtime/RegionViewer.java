package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.runtime.NpcPresentation;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PreviewBodies;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.rig.DisplayTransform;
import art.arcane.animus.core.rig.NpcRig;
import art.arcane.animus.core.rig.RigFrame;
import art.arcane.animus.core.rig.RigView;
import org.bukkit.inventory.ItemStack;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * One player's presentation of persisted NPCs on Folia. Runs on the player's own entity scheduler and reads NPCs only
 * through their immutable {@link RegionActor.Publication}. An interaction is honoured only when the player's region
 * also owns the NPC; otherwise it is refused, never answered from another region's live state.
 */
final class RegionViewer {
    static final int MAX_COMPLETIONS = 64, WAKE_INTERVAL = 20, ROSTER_RESEND_TICKS = 20;
    private final RegionNpcRuntime host;
    final Player player;
    final UUID id, world;
    final NativeViewerEpoch.Stamp epoch;
    final ViewerSession guard;
    final PreviewBodies<Player, ItemStack> preview;
    private final Map<UUID, Presented> visible = new HashMap<>(), retiring = new HashMap<>();
    private final Deque<Presented> retirements = new ArrayDeque<>(), presentationOrder = new ArrayDeque<>(), rigOrder = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    final AtomicInteger shown = new AtomicInteger();
    private SpatialNpcIndex.Cursor cursor;
    private ProximityTracker proximity;
    private int nativeIds, pendingSpawns, bones;
    private long ticks;
    private final long phase = java.util.concurrent.ThreadLocalRandom.current().nextLong(1 << 20);
    volatile boolean ready, failed;
    volatile AutoCloseable task;

    RegionViewer(RegionNpcRuntime host, Player player, NativeViewerEpoch.Stamp epoch) {
        this.host = host; this.player = player; this.epoch = epoch;
        id = player.getUniqueId(); world = player.getWorld().getUID();
        preview = new PreviewBodies<>(host.previews, id);
        guard = new ViewerSession(id, UUID.randomUUID(), limits(host.settings().maximumVisible()));
    }

    private static ViewerSession.Limits limits(int maximumVisible) { return new ViewerSession.Limits(maximumVisible, RegionNpcRuntime.MAX_ISSUED_IDS, 100_000_000L, 4.5); }

    /** Packet completions arrive on Netty; they are applied on the next viewer tick, on the player's owner. */
    void enqueue(Runnable completion) {
        if (queued.incrementAndGet() > MAX_COMPLETIONS) { queued.decrementAndGet(); failed = true; return; }
        completions.add(completion);
    }

    /** Player owner only. A fault ends this viewer's lease; it never escapes into the region tick. */
    void tick() {
        long started = host.metrics.start();
        try { turn(); }
        catch (RuntimeException failure) { host.fail(this, "viewer tick", null, failure); }
        finally { host.metrics.stop(Subsystem.PRESENTATION, started); }
    }
    private void turn() {
        if (!host.current(this)) return;
        if (!host.regions.owns(player)) return;
        if (failed) { host.fail(this, "packet callbacks", null, "Packet callback queue full"); return; }
        if (!player.isOnline()) { host.disconnect(this); return; }
        if (player.isDead()) { host.disconnect(this); return; }
        if (!epoch.equals(host.epochs.capture(player)) || !world.equals(player.getWorld().getUID())
                || guard.issuedCount() >= RegionNpcRuntime.MAX_ISSUED_IDS - host.settings().maximumVisible()) {
            host.reset(this); return;
        }
        ticks++;
        // A hotloaded maximumVisible: bodies already shown stay; new spawns wait until under it.
        int maximumVisible = host.settings().maximumVisible();
        if (guard.limits().maxVisible() != maximumVisible) guard.limits(limits(maximumVisible));
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
            if (proximity == null) proximity = new ProximityTracker(dispatcher::proximityLimits, host.index, dispatcher::hides);
            proximity.tick(dispatcher.proximityPlan(), List.of(id), viewer -> Optional.of(at), dispatcher::publish);
        }
        if ((ticks - 1) % WAKE_INTERVAL == 0) host.wakeNear(player, at);
        // Viewers take turns: with more viewers than updatesPerTick, each one updates every stride ticks, offset by its own phase.
        int stride = Math.max(1, (host.viewerCount() + host.settings().updatesPerTick() - 1) / host.settings().updatesPerTick());
        if ((ticks + phase) % stride != 0 || host.budget.take(RegionBudget.Kind.VIEWERS, 1) == 0) return;
        // An equal share of the region's packet work, so the first viewers in scheduler order cannot use all of it.
        int share = Math.max(2, host.settings().packetsPerTick() / Math.max(1, Math.min(host.settings().updatesPerTick(), host.viewerCount())));
        int budget = host.budget.take(RegionBudget.Kind.PACKETS, share);
        int used = update(at, budget);
        if (used >= budget) host.budget.shortOfPackets();
        // Cosmetic work spends only what this viewer's despawns, moves and spawns left, and pauses while the region is short.
        if (host.budget.cosmeticAllowed()) used += presentCosmetics(budget - used);
        host.budget.refund(RegionBudget.Kind.PACKETS, budget - used);
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
                if (failure != null) host.fail(this, "despawn", body.actor, failure); else release(body);
            }));
        }
        if (!retirements.isEmpty()) return sent;
        if (ready) {
            for (int i = 0, count = presentationOrder.size(); i < count && sent < packetBudget; i++) {
                Presented body = presentationOrder.removeFirst();
                if (body.retired) continue;
                presentationOrder.addLast(body);
                RegionActor.Publication now = body.actor.publication;
                if (now.appearance() != body.appearance || rigReplaced(body, now)) { retire(body); continue; }
                if (body.spawnFinished && !body.moving && body.pose != now.pose()) { move(body, now); sent++; if (!host.current(this)) return sent; }
                if (body.spawnFinished && !body.equipping && sent < packetBudget && body.equipment != now.equipmentRevision()) { equip(body, now); sent++; if (!host.current(this)) return sent; }
                if (body.spawnFinished && sent < packetBudget && body.dragonPhase != now.dragonPhase() && now.type().equals(DragonBody.TYPE)) { phase(body, now.dragonPhase()); sent++; if (!host.current(this)) return sent; }
            }
        }
        if (ready && retirements.isEmpty() && sent < packetBudget) { sent += rosters(packetBudget - sent); if (!host.current(this)) return sent; }
        if (ready && sent < packetBudget)
            sent += preview.update(player, host.packets, at, host.settings().viewDistance(), sentChunk, host::previewSpec,
                    (Runnable completion) -> enqueue(() -> { if (host.current(this)) completion.run(); }), nativeIds, packetBudget - sent);
        if (!ready || !retirements.isEmpty() || sent >= packetBudget || pendingSpawns >= host.settings().pendingSpawnsPerViewer() || visible.size() >= host.settings().maximumVisible()) return sent;
        int capacity = Math.min(Math.min(8, packetBudget - sent), Math.min(host.settings().maximumVisible() - visible.size(), host.settings().pendingSpawnsPerViewer() - pendingSpawns));
        SpatialNpcIndex.Scan scan = host.index.scan(new SpatialNpcIndex.Query(at, host.settings().viewDistance()), cursor, new SpatialNpcIndex.Budget(32, Math.max(1, capacity * 2)), sentChunk);
        cursor = scan.next().orElse(null);
        for (VirtualNpcState.Snapshot candidate : scan.candidates()) {
            if (capacity == 0) break;
            RegionActor actor = host.byUuid.get(candidate.npcId());
            RegionActor.Publication now = actor == null ? null : actor.publication;
            if (now == null || visible.containsKey(candidate.npcId()) || retiring.containsKey(candidate.npcId())
                    || nativeIds + preview.reservedIds() + now.ids() > 256 || !eligible(now, at, sentChunk)) continue;
            spawn(actor, now); sent++; capacity--;
            if (!host.current(this)) break;
        }
        return sent;
    }
    /** A live NPC a preview stands in for stays hidden from this viewer only. */
    private boolean eligible(RegionActor.Publication now, WorldPosition at, Predicate<ChunkPosition> sentChunk) {
        return now.live() && !host.previews.hides(id, now.spec().definition().id(), now.spec().definition().uuid()) && now.snapshot().position().world().equals(at.world())
                && now.snapshot().position().point().distance(at.point()) <= host.settings().viewDistance() && sentChunk.test(now.snapshot().position().chunk());
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
            visible.put(actor.uuid, presented); presentationOrder.addLast(presented); rigOrder.addLast(presented);
            nativeIds += presented.ids; pendingSpawns++; shown.incrementAndGet();
            host.packets.spawn(player, body, pose(now)).whenComplete((nothing, failure) -> enqueue(() -> {
                if (!host.current(this)) return;
                presented.spawnFinished = true; pendingSpawns--;
                if (presented.retired) { release(presented); return; }
                if (failure != null) { host.fail(this, "spawn", actor, failure); return; }
                if (!eligibleNow(actor)) { retire(presented); return; }
                try { guard.bind(body.entityId(), actor.publication.snapshot()); presented.bound = true; }
                catch (RuntimeException invalid) { host.fail(this, "spawn", actor, invalid); }
            }));
        } catch (RuntimeException failure) { host.fail(this, "spawn", actor, failure); }
    }
    private void move(Presented body, RegionActor.Publication now) {
        body.moving = true;
        try {
            host.packets.move(player, body.body, pose(now)).whenComplete((nothing, failure) -> enqueue(() -> {
                if (!host.current(this) || body.retired || visible.get(body.actor.uuid) != body) return;
                body.moving = false;
                if (failure != null) { host.fail(this, "move", body.actor, failure); return; }
                if (!eligibleNow(body.actor)) { retire(body); return; }
                body.pose = now.pose();
                if (body.bound) guard.updateSnapshot(body.body.entityId(), body.actor.publication.snapshot());
            }));
        } catch (RuntimeException failure) { host.fail(this, "move", body.actor, failure); }
    }
    /** Cosmetic: a failed send is recorded and not retried, and never costs the viewer their session. */
    private void equip(Presented body, RegionActor.Publication now) {
        body.equipping = true;
        try {
            host.packets.equipment(player, body.body, now.equipment()).whenComplete((nothing, failure) -> enqueue(() -> {
                body.equipping = false; body.equipment = now.equipmentRevision();
                if (failure != null && host.current(this) && !body.retired) host.cosmeticFailed(this, "equipment", body.actor, failure);
            }));
        } catch (RuntimeException failure) { body.equipping = false; body.equipment = now.equipmentRevision(); host.cosmeticFailed(this, "equipment", body.actor, failure); }
    }
    /** Cosmetic: the dragon's wing phase from its owner's publication. A failed send is tried again on a later update. */
    private void phase(Presented body, int phase) {
        body.dragonPhase = phase;
        try {
            host.packets.dragonPhase(player, body.body, phase).whenComplete((nothing, failure) -> {
                if (failure != null) enqueue(() -> { if (host.current(this) && !body.retired && !host.cosmeticFailed(this, "dragon phase", body.actor, failure)) body.dragonPhase = -1; });
            });
        } catch (RuntimeException failure) { if (!host.cosmeticFailed(this, "dragon phase", body.actor, failure)) body.dragonPhase = -1; }
    }
    /** The body shows bones of a rig the NPC no longer has: it is respawned, which is authoritative work. */
    private static boolean rigReplaced(Presented body, RegionActor.Publication now) {
        return body.rigIdentity != 0 && (now.rig() == null || now.rig().identity() != body.rigIdentity);
    }
    /** Rig bones for this viewer, taking turns so its rig budget reaches every body over a few ticks. Returns packet budget used. */
    private int presentCosmetics(int packetBudget) {
        if (!ready || !retirements.isEmpty()) return 0;
        int sent = 0;
        int[] rigBudget = {host.rigs().updatesPerTick()};
        for (int i = 0, count = rigOrder.size(); i < count && (sent < packetBudget || rigBudget[0] > 0); i++) {
            Presented body = rigOrder.removeFirst();
            if (body.retired) continue;
            rigOrder.addLast(body);
            RegionActor.Publication now = body.actor.publication;
            if (now.rig() != null || body.rigIdentity != 0) sent += presentRig(body, now, packetBudget - sent, rigBudget);
            if (!host.current(this)) return sent;
        }
        return sent;
    }
    /** Shows the body's bones once it is spawned, then sends changed bone transforms within this viewer's rig budget. */
    private int presentRig(Presented body, RegionActor.Publication now, int packetBudget, int[] rigBudget) {
        NpcRig.Snapshot<ItemStack> shown = now.rig();
        if (rigReplaced(body, now) || shown == null || shown.frame() == null || !body.spawnFinished || body.retired || body.rigFailed) return 0;
        RigFrame frame = shown.frame();
        if (body.bones == null) {
            if (packetBudget <= 0 || bones + shown.displays().size() > host.rigs().maximumBones()) return 0;
            try { body.bones = host.packets.createBones(body.body, shown.displays()); }
            catch (RuntimeException invalid) { body.rigFailed = true; host.cosmeticFailed(this, "rig bones", body.actor, invalid); return 0; }
            body.rigIdentity = shown.identity(); body.view = new RigView(body.bones.count()); bones += body.bones.count();
            try {
                host.packets.showBones(player, body.body, body.bones, pose(now), List.of(frame.targets()), shown.hideBody()).whenComplete((nothing, failure) -> enqueue(() -> {
                    if (failure != null) { if (host.current(this) && !body.retired && !host.cosmeticFailed(this, "show bones", body.actor, failure)) body.rigFailed = true; return; }
                    body.view.shown(frame); body.bonesShown = true;
                }));
            } catch (RuntimeException failure) { if (!host.cosmeticFailed(this, "show bones", body.actor, failure)) body.rigFailed = true; }
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
            host.packets.moveBones(player, body.bones, due, targets, durations).whenComplete((nothing, failure) -> {
                if (failure != null) enqueue(() -> { if (host.current(this) && !body.retired) { body.view.unsent(due); host.cosmeticFailed(this, "move bones", body.actor, failure); } });
            });
        } catch (RuntimeException failure) { body.view.unsent(due); host.cosmeticFailed(this, "move bones", body.actor, failure); }
        return 0;
    }
    private void retire(Presented body) {
        if (body.retired) return;
        if (body.bones != null) { bones -= body.bones.count(); body.bones = null; }
        body.retired = true; visible.remove(body.actor.uuid, body); guard.unbind(body.body.entityId());
        presentationOrder.remove(body); rigOrder.remove(body); shown.decrementAndGet();
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
        guard.close(); visible.clear(); retiring.clear(); retirements.clear(); presentationOrder.clear(); rigOrder.clear(); completions.clear(); shown.set(0);
        if (proximity != null) proximity.forget(id);
        AutoCloseable running = task;
        if (running != null) try { running.close(); } catch (Exception ignored) { /* Folia already retired the task. */ }
    }

    /** Player owner only, from the packet adapter's scheduler. */
    void interact(NativePacketAdapter.Interaction input) {
        if (!host.current(this) || !host.regions.owns(player) || player.isDead()) return;
        // Preview bodies are inert: no event, trigger, listener, selection, GUI, shop or damage.
        if (preview.owns(input.body().uuid())) return;
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
        boolean consumed = dispatcher != null && dispatcher.click(new TriggerEvent.Interaction(actor.uuid, id,
                action == VirtualNpcInteractionEvent.Action.ATTACK ? TriggerEvent.Action.ATTACK : TriggerEvent.Action.INTERACT,
                input.secondaryAction(), TriggerEvent.Interaction.hand(input.hand())));
        NpcRuntime.InteractionObserver observer = host.observer;
        if (observer != null) observer.accepted(actor.uuid, id, action == VirtualNpcInteractionEvent.Action.ATTACK, validated.distance(), false);
        // A behavior that consumes the click owns it: modules, riding and selection never see it.
        if (consumed) return;
        NpcRuntime.InteractionListener<Player> listener = host.interactions;
        if (listener != null && listener.interacted(player, actor.uuid, action == VirtualNpcInteractionEvent.Action.ATTACK, input.secondaryAction())) return;
        // A sneaking interaction keeps its administrative meaning, so a rideable NPC can still be selected.
        if (actor.publication.ride() && action == VirtualNpcInteractionEvent.Action.INTERACT && !input.secondaryAction() && "MAIN_HAND".equals(input.hand())) {
            String refusal = actor.requestRide(player);
            if (refusal != null) player.sendMessage(RichText.colorize("&cCannot ride &r" + actor.spec.name() + "&c: " + refusal));
            else attach(body, actor);
            return;
        }
        // Click selection tells an administrator once; clicking the selected NPC again, as play does, stays quiet.
        if (player.hasPermission("animus.admin") && !actor.id.equals(host.selections.put(id, actor.id))) player.sendMessage("Selected " + actor.id + " via " + action);
    }

    /** Player owner only: this viewer presents the NPC now. */
    boolean presents(UUID npc) {
        Presented body = visible.get(npc);
        return body != null && body.spawnFinished && !body.retired;
    }
    /**
     * The rider's own attach goes out at once and authoritatively; the ride starts on the NPC's owner when the client has it.
     * The callback carries the ride's epoch, so it never starts a later ride.
     */
    private void attach(Presented body, RegionActor actor) {
        RegionRides.Lease<RegionActor> lease = host.rides.ofRider(id);
        if (lease == null || lease.actor() != actor) return;
        UUID generation = lease.generation();
        java.util.function.BiConsumer<Boolean, String> settle = (Boolean succeeded, String failure) -> actor.owner.submit(new OwnerMailbox.Work() {
            @Override public void run() { actor.rideAttached(generation, succeeded, failure); }
            @Override public void refuse(String reason) { host.rides.release(lease); }
        });
        List<NativePacketAdapter.Body> passengers = passengers(actor.publication);
        body.sentRoster = roster(passengers, player);
        try {
            host.packets.passengers(player, body.body, passengers, player, true).whenComplete((nothing, failure) -> enqueue(() ->
                    settle.accept(failure == null, failure == null ? "" : art.arcane.animus.core.runtime.PersistedNpcService.concise(failure))));
        } catch (RuntimeException failure) { settle.accept(false, art.arcane.animus.core.runtime.PersistedNpcService.concise(failure)); }
    }
    /** Player owner only, after this player's ride on the NPC ended: the detach reaches this client before any teleport. */
    void detach(RegionActor actor) {
        Presented body = visible.get(actor.uuid);
        if (body == null || !body.spawnFinished || body.retired) return;
        List<NativePacketAdapter.Body> passengers = passengers(actor.publication);
        body.sentRoster = roster(passengers, null);
        try {
            host.packets.passengers(player, body.body, passengers, null, true).whenComplete((nothing, failure) -> {
                if (failure != null) enqueue(() -> { if (host.current(this) && !body.retired) { body.sentRoster = null; host.cosmeticFailed(this, "passengers", body.actor, failure); } });
            });
        } catch (RuntimeException failure) { body.sentRoster = null; host.cosmeticFailed(this, "passengers", body.actor, failure); }
    }
    /** The seated passengers this viewer has spawned, from the vehicle owner's publication. */
    private List<NativePacketAdapter.Body> passengers(RegionActor.Publication vehicle) {
        if (vehicle.passengers().isEmpty()) return List.of();
        List<NativePacketAdapter.Body> bodies = new java.util.ArrayList<>(vehicle.passengers().size());
        for (UUID passenger : vehicle.passengers()) {
            Presented seated = visible.get(passenger);
            if (seated != null && seated.spawnFinished && !seated.retired) bodies.add(seated.body);
        }
        return bodies;
    }
    private static List<Integer> roster(List<NativePacketAdapter.Body> passengers, Player rider) {
        List<Integer> roster = new java.util.ArrayList<>(passengers.size() + 1);
        for (NativePacketAdapter.Body passenger : passengers) roster.add(passenger.entityId());
        if (rider != null) roster.add(rider.getEntityId());
        return List.copyOf(roster);
    }
    /**
     * Client passenger lists replace the whole roster and need every ID known, so they are re-derived from what this
     * viewer has. Vehicles losing a passenger go first: the client refuses a body that still sits on another vehicle.
     * Other clients know a real rider only while vanilla tracks it, so a roster with a real rider repeats every second.
     */
    private int rosters(int packetBudget) {
        int sent = 0;
        boolean resend = ticks % ROSTER_RESEND_TICKS == 0;
        for (int pass = 0; pass < 2; pass++) {
            for (Presented vehicle : presentationOrder) {
                if (sent >= packetBudget) return sent;
                if (!vehicle.spawnFinished || vehicle.retired) continue;
                RegionActor.Publication now = vehicle.actor.publication;
                Player rider = now.rider();
                // Most bodies carry nobody and never did: nothing to work out.
                if (rider == null && now.passengers().isEmpty() && vehicle.sentRoster != null && vehicle.sentRoster.isEmpty()) continue;
                List<NativePacketAdapter.Body> passengers = passengers(now);
                List<Integer> roster = roster(passengers, rider);
                List<Integer> previous = vehicle.sentRoster == null ? List.of(-1) : vehicle.sentRoster;
                boolean changed = !roster.equals(previous), detaching = !roster.containsAll(previous);
                boolean reveal = resend && rider != null && rider != player;
                if (pass == 0 ? !(changed && detaching) : !(changed || reveal)) continue;
                if (vehicle.sentRoster == null && roster.isEmpty()) { vehicle.sentRoster = roster; continue; }
                vehicle.sentRoster = roster;
                sent++;
                try {
                    host.packets.passengers(player, vehicle.body, passengers, rider, false).whenComplete((nothing, failure) -> {
                        if (failure != null) enqueue(() -> { if (host.current(this) && !vehicle.retired) { vehicle.sentRoster = null; host.cosmeticFailed(this, "passengers", vehicle.actor, failure); } });
                    });
                } catch (RuntimeException failure) { vehicle.sentRoster = null; if (host.cosmeticFailed(this, "passengers", vehicle.actor, failure)) return sent; }
                if (!host.current(this)) return sent;
            }
        }
        return sent;
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
        boolean bound, retired, spawnFinished, despawnFinished, moving, equipping, bonesShown, rigFailed;
        long pose, equipment, rigIdentity;
        int dragonPhase = DragonBody.PHASE_HOVERING;
        /** The passenger roster last sent for this vehicle; null when unknown, so the next update sends it again. */
        List<Integer> sentRoster;
        BodyTransport.Bones bones; RigView view;
        Presented(RegionActor actor, NativePacketAdapter.Body body, RegionActor.Publication now) {
            this.actor = actor; this.body = body; appearance = now.appearance(); ids = body.entityIds().length; pose = now.pose();
            equipment = now.equipment().isEmpty() ? now.equipmentRevision() : -1;
        }
    }
}
