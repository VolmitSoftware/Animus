package art.arcane.animus.core.runtime;

import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.rig.DisplayTransform;
import art.arcane.animus.core.rig.NpcRig;
import art.arcane.animus.core.rig.RigFrame;
import art.arcane.animus.core.rig.RigView;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.ViewerSession;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.animus.core.control.LookClose;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Viewers of one runtime and what each is shown: admission, spawns, moves, despawns, cosmetic work, packet completions
 * and validated clicks. Every send is admitted into the viewer's bounded outbox first and its completion comes back
 * through one bounded queue the owner drains. Owner thread, except {@link #enqueue}.
 */
final class ViewerPresenter<P, I> {
    /** How long a viewer whose packet callbacks overflowed waits for the backlog to drain before it is shown every NPC again. */
    static final int RESYNC_TICKS = 20;
    /** A swing shown later than this would not match what the NPC did. */
    private static final int STALE_SWING_TICKS = 10;
    private final PersistedNpcService<P, I> service;
    final Map<UUID, Viewer> viewers = new HashMap<>();
    /** Viewers that show each NPC, kept as bodies spawn and retire. */
    final NpcViewers<Viewer> showing = new NpcViewers<>();
    private final Deque<Viewer> viewerOrder = new ArrayDeque<>();
    private final Map<UUID, String> viewerFailures = new HashMap<>();
    /** Viewers dropped after a callback overflow, and the tick from which they may be admitted again. */
    private final Map<UUID, Long> resyncs = new HashMap<>();
    /** Packet callbacks from network threads; at most {@link #completionCapacity} wait, a bound a hotload may change. */
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger completionCount = new AtomicInteger();
    private volatile int completionCapacity;
    private Iterator<? extends P> admissions = Collections.emptyIterator();
    private final ViewerCapNotice capNotice = new ViewerCapNotice();
    long accepted, rejected;

    ViewerPresenter(PersistedNpcService<P, I> service) { this.service = service; }

    void limits(PersistedNpcService.Settings settings) { completionCapacity = settings.maximumViewers() * (settings.pendingSpawnsPerViewer() + 2); }
    int visible() {
        int visible = 0;
        for (Viewer viewer : viewers.values()) visible += viewer.visible.size();
        return visible;
    }
    /** Every online player is considered for admission again, from the start. */
    void readmit() { admissions = service.host.onlinePlayers().iterator(); }
    /** A forced refresh: viewers that failed may be admitted again. */
    void refreshed() { viewerFailures.clear(); readmit(); }
    /** Readmits viewers whose resync wait is over. */
    void resyncsDue() { if (!resyncs.isEmpty() && resyncs.values().removeIf((Long due) -> due <= service.tickNumber)) readmit(); }
    /** The player's failures and resync wait are forgotten and every player is considered again. */
    void reset(P player) { UUID id = service.host.id(player); disconnect(player); viewerFailures.remove(id); resyncs.remove(id); readmit(); }
    /** Forgets every viewer; their bodies leave their clients. Each viewer's triggers are forgotten too. */
    void forgetAll() {
        for (Viewer viewer : List.copyOf(viewers.values())) {
            disconnect(viewer.player);
            if (service.triggers != null) service.triggers.forget(viewer.id);
        }
    }
    /** Shutdown: no viewer, failure, resync or completion is kept. */
    void clear() {
        viewers.values().forEach((Viewer viewer) -> viewer.guard.close()); viewers.clear(); viewerOrder.clear(); showing.clear();
        forgetPending();
    }
    /** Suspension, after every viewer was forgotten: no failure, resync, completion or pending admission is kept. */
    void forgetPending() {
        viewerFailures.clear(); resyncs.clear(); admissions = Collections.emptyIterator();
        while (completions.poll() != null) completionCount.decrementAndGet();
    }
    /** Packet callbacks, at most {@code limit}, independently of connection count and acknowledgments. */
    void drainCompletions(int limit) {
        for (int i = 0; i < limit; i++) {
            Runnable completion = completions.poll(); if (completion == null) break;
            completionCount.decrementAndGet(); completion.run();
        }
    }
    void enqueue(Viewer viewer, Runnable action) {
        if (service.closed) return;
        if (completionCount.incrementAndGet() > completionCapacity) { completionCount.decrementAndGet(); viewer.failed = true; return; }
        completions.add(action);
    }
    boolean current(Viewer viewer) { return !service.closed && viewers.get(service.host.id(viewer.player)) == viewer; }
    Optional<WorldPosition> position(UUID id) {
        Viewer viewer = viewers.get(id);
        return viewer == null || !service.host.online(viewer.player) || service.host.dead(viewer.player) ? Optional.empty() : Optional.of(service.host.position(viewer.player));
    }

    // ---- Admission and each viewer's turn ----

    void admitPlayers() {
        PersistedNpcService.Settings settings = service.settings;
        for (int i = 0; i < settings.updatesPerTick() && viewers.size() < settings.maximumViewers(); i++) {
            P player;
            try { if (!admissions.hasNext()) return; player = admissions.next(); }
            catch (ConcurrentModificationException changed) { readmit(); return; }
            UUID id = service.host.id(player);
            if (!service.host.online(player) || service.host.dead(player) || viewers.containsKey(id) || viewerFailures.containsKey(id) || resyncs.containsKey(id)) continue;
            Viewer viewer = new Viewer(player); viewers.put(id, viewer); viewerOrder.addLast(viewer);
            try {
                service.packets.watch(player, (BodyTransport.Interaction input) -> interact(viewer, input)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                    if (!current(viewer)) return;
                    if (failure == null) viewer.ready = true; else watchFailed(viewer, failure);
                }));
            } catch (RuntimeException failure) { watchFailed(viewer, failure); }
        }
    }
    /** Every viewer's despawns, moves and spawns, then cosmetic work with what they left. Returns NPC packet budget used. */
    int present() {
        PersistedNpcService.Settings settings = service.settings;
        long tickNumber = service.tickNumber;
        for (Viewer viewer : viewers.values()) if (viewer.glowing != null && tickNumber >= viewer.glowEnds && unglow(viewer)) viewer.glowing = null;
        List<Viewer> updated = new ArrayList<>();
        int used = FairViewerWork.run(viewerOrder, settings.updatesPerTick(), settings.packetsPerTick(), this::current, (Viewer viewer, int remaining) -> {
            if (viewer.failed) { resync(viewer); return 0; }
            if (!service.host.online(viewer.player)) { disconnect(viewer.player); return 0; }
            if (service.host.dead(viewer.player)) { disconnect(viewer.player); return 0; }
            if (!viewer.world.equals(service.host.position(viewer.player).world())) { disconnect(viewer.player); readmit(); return 0; }
            if (viewer.guard.issuedCount() >= PersistedNpcService.MAX_ISSUED_IDS - settings.maximumVisible()) {
                disconnect(viewer.player); readmit(); return 0;
            }
            updated.add(viewer);
            return update(viewer, remaining);
        });
        if (used >= settings.packetsPerTick()) service.pressure.shortOfBudget(tickNumber);
        // Cosmetic work spends what the despawns, moves and spawns left, split evenly; rig bones have their own budget, so every viewer is visited.
        for (int i = 0; i < updated.size(); i++) {
            Viewer viewer = updated.get(i);
            if (current(viewer)) used += presentCosmetics(viewer, Math.ceilDiv(Math.max(0, settings.packetsPerTick() - used), updated.size() - i));
        }
        return used;
    }
    private int update(Viewer viewer, int packetBudget) {
        PersistedNpcService.Settings settings = service.settings;
        RuntimeHost<P, I> host = service.host;
        int sent = 0;
        viewer.turns++;
        WorldPosition at = host.position(viewer.player);
        Map<Long, Boolean> delivered = viewer.deliveredChunks; delivered.clear();
        Predicate<ChunkPosition> visibleChunk = (ChunkPosition chunk) -> chunk.world().equals(at.world()) && delivered.computeIfAbsent(ChunkPosition.key(chunk.x(), chunk.z()),
                (Long ignored) -> host.chunkLoaded(at.world(), chunk.x(), chunk.z()) && host.chunkSent(viewer.player, chunk.x(), chunk.z()));
        List<Presented> leaving = null;
        for (Presented body : viewer.visible.values())
            if (!eligible(viewer, body.value, at, visibleChunk)) { if (leaving == null) leaving = new ArrayList<>(); leaving.add(body); }
        if (leaving != null) for (Presented body : leaving) retire(viewer, body);
        while (!viewer.retirements.isEmpty() && sent < packetBudget && admit(viewer, PresentationPressure.Tier.DESPAWN)) {
            Presented body = viewer.retirements.removeFirst(); sent++;
            handOver(viewer, () -> service.packets.despawn(viewer.player, body.body)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                if (!current(viewer)) return;
                body.despawnFinished = true;
                if (failure != null) fail(viewer, "despawn", body.value, failure); else releaseRetired(viewer, body);
            }));
        }
        // Despawns still waiting: moves and spawns wait behind them, so this viewer is short of budget.
        if (!viewer.retirements.isEmpty()) { service.pressure.shortOfBudget(service.tickNumber); return sent; }
        // One outstanding move per body; rotating the body queue prevents a busy subset starving other updates.
        if (viewer.ready) {
            for (int i = 0, count = viewer.presentationOrder.size(); i < count && sent < packetBudget; i++) {
                Presented body = viewer.presentationOrder.removeFirst();
                if (body.retired) continue;
                viewer.presentationOrder.addLast(body);
                if (rigReplaced(body)) { retire(viewer, body); continue; }
                if (body.spawnFinished && !body.moving && body.deliveredPoseRevision != body.value.poseRevision && moveDue(viewer, body, at)) {
                    if (!move(viewer, body)) return sent;
                    sent++;
                    if (!current(viewer)) return sent;
                }
                if (body.spawnFinished && !body.equipping && sent < packetBudget && body.deliveredEquipmentRevision != body.value.equipmentRevision) {
                    if (!equip(viewer, body)) return sent;
                    sent++;
                    if (!current(viewer)) return sent;
                }
                if (body.spawnFinished && body.bound && sent < packetBudget && body.deliveredAttackSwings != body.value.attackSwings) {
                    if (!attackSwing(viewer, body)) return sent;
                    sent++;
                    if (!current(viewer)) return sent;
                }
                if (body.spawnFinished && !body.attributing && sent < packetBudget && body.deliveredAttributeRevision != body.value.attributeRevision) {
                    if (!attribute(viewer, body)) return sent;
                    sent++;
                    if (!current(viewer)) return sent;
                }
            }
            sent += service.mountRuntime.updatePassengers(viewer, packetBudget - sent);
        }
        if (viewer.ready && sent < packetBudget)
            sent += viewer.preview.update(viewer.player, service.packets, at, settings.viewDistance(), visibleChunk,
                    (NpcDefinition definition) -> service.parse(definition),
                    (Runnable completion) -> enqueue(viewer, () -> { if (current(viewer)) completion.run(); }), viewer.nativeIds, packetBudget - sent);
        if (viewer.ready && viewer.visible.size() >= settings.maximumVisible() && capNotice.due(service.tickNumber) && hiddenByCap(viewer, at, visibleChunk))
            host.warn(capNotice.warn(service.tickNumber, host.name(viewer.player), settings.maximumVisible()));
        if (!viewer.ready || !viewer.retirements.isEmpty() || sent >= packetBudget || viewer.pendingSpawns >= settings.pendingSpawnsPerViewer()
                || viewer.visible.size() >= settings.maximumVisible()) return sent;
        int capacity = Math.min(Math.min(8, packetBudget - sent), Math.min(settings.maximumVisible() - viewer.visible.size(), settings.pendingSpawnsPerViewer() - viewer.pendingSpawns));
        SpatialNpcIndex.Scan scan = service.index.scan(new SpatialNpcIndex.Query(at, settings.viewDistance()), viewer.cursor, new SpatialNpcIndex.Budget(32, Math.max(1, capacity * 2)), visibleChunk);
        viewer.cursor = scan.next().orElse(null);
        for (VirtualNpcState.Snapshot snapshot : scan.candidates()) {
            if (capacity == 0) break;
            PersistedNpcService<P, I>.Entry entry = service.byUuid.get(snapshot.npcId()); PersistedNpcService<P, I>.Value value = entry == null ? null : entry.value;
            if (value == null || viewer.visible.containsKey(snapshot.npcId()) || viewer.retiring.containsKey(snapshot.npcId())
                    || viewer.nativeIds + viewer.preview.reservedIds() + nativeIds(value) > 256 || !eligible(viewer, value, at, visibleChunk)) continue;
            if (!admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) break;
            spawn(viewer, value); sent++; capacity--;
            if (!current(viewer)) break;
        }
        return sent;
    }
    /** An NPC in range the viewer would see but for the {@code maximumVisible} cap; one bounded scan from the start. */
    private boolean hiddenByCap(Viewer viewer, WorldPosition at, Predicate<ChunkPosition> visibleChunk) {
        SpatialNpcIndex.Scan scan = service.index.scan(new SpatialNpcIndex.Query(at, service.settings.viewDistance()), null, new SpatialNpcIndex.Budget(256, service.settings.maximumVisible() + 1), visibleChunk);
        for (VirtualNpcState.Snapshot snapshot : scan.candidates()) {
            PersistedNpcService<P, I>.Entry entry = service.byUuid.get(snapshot.npcId());
            if (entry != null && entry.value != null && !viewer.visible.containsKey(snapshot.npcId()) && !viewer.retiring.containsKey(snapshot.npcId()) && eligible(viewer, entry.value, at, visibleChunk)) return true;
        }
        return false;
    }
    /** A live NPC a preview stands in for stays hidden from that viewer only, as does one whose viewers filter hides it from them. */
    private boolean eligible(Viewer viewer, PersistedNpcService<P, I>.Value value, WorldPosition at, Predicate<ChunkPosition> delivered) {
        PersistedNpcService<P, I>.Entry entry = service.byUuid.get(value.spec.definition().uuid());
        VirtualNpcState.Snapshot snapshot = value.state.snapshot();
        if (entry == null || entry.value != value || !(snapshot.active() || value.dyingUntil > service.tickNumber) || service.previews.hides(viewer.id, entry.id, value.spec.definition().uuid())
                || !snapshot.position().world().equals(at.world())) return false;
        double distance = snapshot.position().point().distance(at.point());
        return distance <= service.settings.viewDistance() && delivered.test(snapshot.position().chunk())
                && !value.spec.viewers().hides(viewer.id, distance, (String node) -> service.host.permitted(viewer.player, node), (String node) -> service.host.granted(viewer.player, node));
    }
    private boolean eligibleNow(Viewer viewer, PersistedNpcService<P, I>.Value value) {
        RuntimeHost<P, I> host = service.host;
        if (!current(viewer) || !host.online(viewer.player) || host.dead(viewer.player)) return false;
        WorldPosition at = host.position(viewer.player);
        return eligible(viewer, value, at, (ChunkPosition chunk) -> host.chunkLoaded(at.world(), chunk.x(), chunk.z()) && host.chunkSent(viewer.player, chunk.x(), chunk.z()));
    }

    // ---- Authoritative work ----

    private void spawn(Viewer viewer, PersistedNpcService<P, I>.Value value) {
        try {
            art.arcane.animus.core.presentation.MirrorSettings mirror = value.mirror;
            String name = shownName(value, viewer);
            NpcPresentation.Label label = NpcPresentation.label(value.spec, name, value.bubble, value.attributes, service.runtime.placeholders(), viewer.id);
            // A mirrored skin shows only on a player body; a viewer without a readable skin sees the NPC's own.
            BodySkin skin = mirror != null && mirror.skin() && value.spec.type().equals(art.arcane.animus.core.presentation.BodyRules.PLAYER)
                    ? service.host.skin(viewer.player).orElse(value.displaySkin) : value.displaySkin;
            BodyTransport.Body body = NpcPresentation.body(service.packets, label, skin);
            Presented presented = new Presented(value, body); viewer.visible.put(body.uuid(), presented); showing.shown(body.uuid(), viewer);
            presented.labels = label.rows(); presented.labelledAt = service.tickNumber; presented.deliveredBubbleRevision = value.bubbleRevision;
            presented.placeholdered = service.runtime.placeholders().active() && NpcPresentation.placeholdered(value.spec, name);
            if (mirror != null && mirror.equipment()) presented.deliveredEquipmentRevision = -1;
            presented.deliveredSwings = value.swings; presented.deliveredGestures = value.gestures; presented.deliveredAttackSwings = value.attackSwings; presented.deliveredAttributeRevision = value.attributeRevision;
            viewer.presentationOrder.addLast(presented); viewer.cosmeticOrder.addLast(presented);
            viewer.nativeIds += body.entityIds().length; viewer.pendingSpawns++;
            presented.deliveredPoseRevision = value.poseRevision;
            // A body spawns with its definition's look, so a playback pose shown now is sent once the spawn lands.
            presented.deliveredLookRevision = value.lookPose == value.spec.appearance().pose() && value.lookMotion == 0 ? value.lookRevision : value.lookRevision - 1;
            BodyPose pose = pose(value, viewer);
            handOver(viewer, () -> service.packets.spawn(viewer.player, body, pose)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
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
    /** The name this viewer sees: its own on an NPC that mirrors names, else the NPC's. */
    private String shownName(PersistedNpcService<P, I>.Value value, Viewer viewer) {
        return value.mirror != null && value.mirror.name() ? service.host.name(viewer.player) : value.displayName;
    }
    /** Native IDs a body of this NPC reserves, including its text display and seat. */
    private int nativeIds(PersistedNpcService<P, I>.Value value) {
        return NpcPresentation.nativeIds(service.packets, value.spec, value.displayName, value.ids);
    }
    /** The body's pose for this viewer: a per-player look-close NPC looks at a viewer in its range who may be looked at. */
    private BodyPose pose(PersistedNpcService<P, I>.Value value, Viewer viewer) {
        WorldPosition position = value.state.snapshot().position(); Vec3 p = position.point();
        LookClose look = value.lookClose;
        if (value.lookingAtViewers && look != null && look.perPlayer()) {
            Vec3 eye = LookClose.eye(p, PersistedNpcService.bodyHeight(value)), target = service.host.eye(viewer.player);
            Optional<LookClose.Facing> facing = target.distance(eye) <= look.range() && look.admits((String node) -> service.host.permitted(viewer.player, node))
                    ? LookClose.facing(eye, target) : Optional.empty();
            if (facing.isPresent())
                return new BodyPose(position.world(), p.x(), p.y(), p.z(), look.headOnly() ? value.yaw : facing.get().yaw(), facing.get().yaw(), facing.get().pitch(), value.onGround);
        }
        return new BodyPose(position.world(), p.x(), p.y(), p.z(), value.yaw, value.headYaw, value.pitch, value.onGround);
    }
    /** A body farther than half the view distance moves on fewer turns while any budget is short, always to its latest pose. */
    private boolean moveDue(Viewer viewer, Presented body, WorldPosition at) {
        return body.value.state.snapshot().position().point().distance(at.point()) <= service.settings.viewDistance() / 2
                || service.pressure.distantMove(service.tickNumber, viewer.turns, body.body.uuid().hashCode());
    }
    /** False when the viewer's outbox is full; the latest pose goes on a later turn. */
    private boolean move(Viewer viewer, Presented body) {
        if (!admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) return false;
        long revision = body.value.poseRevision;
        body.moving = true;
        try {
            handOver(viewer, () -> service.packets.move(viewer.player, body.body, pose(body.value, viewer))).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                if (!current(viewer) || body.retired || viewer.visible.get(body.body.uuid()) != body) return;
                body.moving = false;
                if (failure != null) { fail(viewer, "move", body.value, failure); return; }
                if (!eligibleNow(viewer, body.value)) { retire(viewer, body); return; }
                body.deliveredPoseRevision = revision;
                if (body.bound) viewer.guard.updateSnapshot(body.body.entityId(), body.value.state.snapshot());
            }));
        } catch (RuntimeException failure) { fail(viewer, "move", body.value, failure); }
        return true;
    }
    /** Sent with moves, but a failed send is recorded and not retried, and never costs the viewer their session. False when the outbox is full. */
    private boolean equip(Viewer viewer, Presented body) {
        if (!admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) return false;
        long revision = body.value.equipmentRevision;
        body.equipping = true;
        try {
            NpcRig<I> rig = body.value.rig;
            Map<EquipmentSlot, I> worn = body.value.mirror != null && body.value.mirror.equipment() ? service.host.equipment(viewer.player) : body.value.equipment;
            handOver(viewer, () -> service.packets.equipment(viewer.player, body.body, Map.copyOf(rig == null ? worn : rig.bodyEquipment(worn)))).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                body.equipping = false;
                body.deliveredEquipmentRevision = revision;
                if (failure != null && current(viewer) && !body.retired) cosmeticFailed(viewer, "equipment", body.value, failure);
            }));
        } catch (RuntimeException failure) {
            body.equipping = false;
            body.deliveredEquipmentRevision = revision;
            cosmeticFailed(viewer, "equipment", body.value, failure);
        }
        return true;
    }
    /** Authoritative: a runtime attribute change on a visible body. False when the viewer's outbox is full; the latest attributes go on a later turn. */
    private boolean attribute(Viewer viewer, Presented body) {
        if (!admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) return false;
        long revision = body.value.attributeRevision;
        NpcAttributes attributes = body.value.attributes;
        body.attributing = true;
        try {
            handOver(viewer, () -> service.packets.attributes(viewer.player, body.body, attributes)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                if (!current(viewer) || body.retired || viewer.visible.get(body.body.uuid()) != body) return;
                body.attributing = false;
                if (failure != null) { fail(viewer, "attributes", body.value, failure); return; }
                body.deliveredAttributeRevision = revision;
            }));
        } catch (RuntimeException failure) { fail(viewer, "attributes", body.value, failure); }
        return true;
    }
    /**
     * One swing for every attack since this viewer's last, admitted like a move: an attack is feedback for a hit the
     * viewer may feel, so it neither waits for cosmetic budget nor goes stale. A failed send is recorded and not retried.
     */
    private boolean attackSwing(Viewer viewer, Presented body) {
        if (!admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) return false;
        body.deliveredAttackSwings = body.value.attackSwings;
        sendCosmetic(viewer, body, "attack swing", () -> service.packets.swing(viewer.player, body.body));
        return true;
    }
    /**
     * The hurt flash and the body's own hurt sound, or its death fall and death sound, for one viewer. Admitted like an
     * attack swing: the viewer may have dealt the hit, so it neither waits for cosmetic budget nor goes stale.
     */
    void feedback(Viewer viewer, Presented body, boolean death) {
        if (!admit(viewer, PresentationPressure.Tier.AUTHORITATIVE)) return;
        sendCosmetic(viewer, body, death ? "death" : "hurt", () -> death ? service.packets.die(viewer.player, body.body) : service.packets.hurt(viewer.player, body.body));
    }
    void retire(Viewer viewer, Presented body) {
        if (body.retired) return;
        body.retired = true; viewer.visible.remove(body.body.uuid(), body); showing.hidden(body.body.uuid(), viewer); viewer.guard.unbind(body.body.entityId());
        if (body.bones != null) { viewer.bones -= body.bones.count(); body.bones = null; }
        viewer.rosterDirty = true;
        viewer.presentationOrder.remove(body); viewer.cosmeticOrder.remove(body);
        viewer.retiring.put(body.body.uuid(), body); viewer.retirements.addLast(body);
    }
    private void releaseRetired(Viewer viewer, Presented body) {
        if (body.spawnFinished && body.despawnFinished && viewer.retiring.remove(body.body.uuid(), body)) viewer.nativeIds -= body.body.entityIds().length;
    }
    /** Every body of the NPC is retired; viewers receive a fresh body through the normal spawn path. */
    void represent(PersistedNpcService<P, I>.Value value) {
        UUID uuid = value.spec.definition().uuid();
        for (Viewer viewer : showing.of(uuid)) {
            Presented body = viewer.visible.get(uuid);
            if (body != null && body.value == value) retire(viewer, body);
        }
    }

    // ---- Cosmetic work ----

    /**
     * Cosmetic work for one viewer after every viewer's authoritative work: playback poses, arm swings, dragon wing phases
     * and rig bones, taking turns so the budget reaches every body over a few ticks. Each sends the latest state, so work
     * that waited is coalesced, not queued. A swing older than {@link #STALE_SWING_TICKS} is skipped. Returns NPC packet
     * budget used.
     */
    private int presentCosmetics(Viewer viewer, int packetBudget) {
        long tickNumber = service.tickNumber;
        if (!viewer.ready || !viewer.retirements.isEmpty() || !service.pressure.allows(PresentationPressure.Tier.COSMETIC, tickNumber, viewer.turns)) return 0;
        int sent = 0;
        int[] rigBudget = {service.rigSettings.updatesPerTick()};
        int refresh = service.runtime.placeholders().refreshTicks();
        for (int i = 0, count = viewer.cosmeticOrder.size(); i < count && (sent < packetBudget || rigBudget[0] > 0); i++) {
            Presented body = viewer.cosmeticOrder.removeFirst();
            if (body.retired) continue;
            viewer.cosmeticOrder.addLast(body);
            if (body.spawnFinished && !body.looking && sent < packetBudget && body.deliveredLookRevision != body.value.lookRevision) {
                if (!look(viewer, body)) return sent;
                sent++;
                if (!current(viewer)) return sent;
            }
            if (body.spawnFinished && body.bound && body.deliveredGestures != body.value.gestures) {
                if (tickNumber - body.value.gesturedAt > STALE_SWING_TICKS) body.deliveredGestures = body.value.gestures;
                else if (sent < packetBudget) {
                    body.deliveredGestures = body.value.gestures;
                    int gesture = body.value.gesture;
                    sendCosmetic(viewer, body, "gesture", () -> gesture == art.arcane.animus.core.npc.NpcAnimation.HURT_FLASH
                            ? service.packets.hurt(viewer.player, body.body) : service.packets.gesture(viewer.player, body.body, gesture));
                    sent++;
                    if (!current(viewer)) return sent;
                }
            }
            if (body.spawnFinished && body.bound && body.deliveredSwings != body.value.swings) {
                if (tickNumber - body.value.swungAt > STALE_SWING_TICKS) body.deliveredSwings = body.value.swings;
                else if (sent < packetBudget) {
                    if (!swing(viewer, body)) return sent;
                    sent++;
                }
            }
            if (body.spawnFinished && sent < packetBudget && (body.deliveredBubbleRevision != body.value.bubbleRevision || body.placeholdered && refresh > 0 && tickNumber - body.labelledAt >= refresh)) {
                if (!relabel(viewer, body)) return sent;
                sent++;
                if (!current(viewer)) return sent;
                if (body.retired) continue;
            }
            sent += presentRig(viewer, body, packetBudget - sent, rigBudget);
            if (!current(viewer)) return sent;
        }
        return sent + service.mountRuntime.updateDragonPhases(viewer, packetBudget - sent);
    }
    /**
     * Cosmetic, when the speech bubble changes and every {@code [placeholders] refreshTicks}: the body's text filled again for
     * this viewer. Changed text goes out in place; a body whose rows changed shape, or whose transport cannot relabel, respawns.
     */
    private boolean relabel(Viewer viewer, Presented body) {
        body.labelledAt = service.tickNumber;
        long bubble = body.value.bubbleRevision;
        List<String> rows = NpcPresentation.label(body.value.spec, shownName(body.value, viewer), body.value.bubble, body.value.attributes, service.runtime.placeholders(), viewer.id).rows();
        if (rows.equals(body.labels)) { body.deliveredBubbleRevision = bubble; return true; }
        if (rows.size() != body.labels.size()) { retire(viewer, body); return true; }
        if (!admit(viewer, PresentationPressure.Tier.COSMETIC)) return false;
        body.labels = rows; body.deliveredBubbleRevision = bubble;
        try {
            handOver(viewer, () -> service.packets.relabel(viewer.player, body.body, rows)).whenComplete((Void nothing, Throwable failure) -> {
                if (failure != null) enqueue(viewer, () -> { if (current(viewer) && !body.retired) retire(viewer, body); });
            });
        } catch (RuntimeException failure) { retire(viewer, body); }
        return true;
    }
    /** Cosmetic: one arm swing for any number since this viewer's last; a failed send is not retried. */
    private boolean swing(Viewer viewer, Presented body) {
        if (!admit(viewer, PresentationPressure.Tier.COSMETIC)) return false;
        body.deliveredSwings = body.value.swings;
        sendCosmetic(viewer, body, "swing", () -> service.packets.swing(viewer.player, body.body));
        return true;
    }
    /** Cosmetic: the playback pose of a body, sent as metadata. False when the outbox has no room for cosmetic work. */
    private boolean look(Viewer viewer, Presented body) {
        long revision = body.value.lookRevision;
        BodyLook look = body.value.look;
        if (look == null || !service.packets.presentationAvailable()) { body.deliveredLookRevision = revision; return true; }
        if (!admit(viewer, PresentationPressure.Tier.COSMETIC)) return false;
        body.looking = true;
        try {
            handOver(viewer, () -> service.packets.look(viewer.player, body.body, look)).whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                body.looking = false;
                body.deliveredLookRevision = revision;
                if (failure != null && current(viewer) && !body.retired) cosmeticFailed(viewer, "look", body.value, failure);
            }));
        } catch (RuntimeException failure) {
            body.looking = false;
            body.deliveredLookRevision = revision;
            cosmeticFailed(viewer, "look", body.value, failure);
        }
        return true;
    }
    /** The body shows bones of a rig the NPC no longer has: it is respawned, which is authoritative work. */
    private boolean rigReplaced(Presented body) {
        if (body.rigIdentity == 0) return false;
        NpcRig<I> rig = body.value.rig;
        NpcRig.Snapshot<I> shown = rig == null ? null : rig.snapshot();
        return shown == null || shown.identity() != body.rigIdentity;
    }
    /**
     * Shows the body's bones once it is spawned, then sends changed bone transforms within the viewer's rig budget.
     * Returns NPC packet budget used: only showing bones costs it, because transforms have their own budget.
     */
    private int presentRig(Viewer viewer, Presented body, int packetBudget, int[] rigBudget) {
        BodyTransport<P, I> packets = service.packets;
        NpcRig<I> rig = body.value.rig;
        NpcRig.Snapshot<I> shown = rig == null ? null : rig.snapshot();
        if (rigReplaced(body) || shown == null || shown.frame() == null || !body.spawnFinished || body.retired || body.rigFailed) return 0;
        RigFrame frame = shown.frame();
        if (body.bones == null) {
            if (packetBudget <= 0 || viewer.bones + shown.displays().size() > service.rigSettings.maximumBones() || !admit(viewer, PresentationPressure.Tier.COSMETIC)) return 0;
            try { body.bones = packets.createBones(body.body, shown.displays()); }
            catch (RuntimeException invalid) { viewer.outbox.abandon(); body.rigFailed = true; body.value.rigProblem = PersistedNpcService.concise(invalid); service.failures.record("rig bones", context(viewer, body.value), invalid); return 0; }
            body.rigIdentity = shown.identity(); body.socketRevision = shown.socketRevision(); body.view = new RigView(body.bones.count()); viewer.bones += body.bones.count();
            try {
                handOver(viewer, () -> packets.showBones(viewer.player, body.body, body.bones, pose(body.value, viewer), List.of(frame.targets()), shown.hideBody()))
                        .whenComplete((Void nothing, Throwable failure) -> enqueue(viewer, () -> {
                            if (failure != null) { if (current(viewer) && !body.retired && !cosmeticFailed(viewer, "show bones", body.value, failure)) body.rigFailed = true; return; }
                            body.view.shown(frame); body.bonesShown = true;
                        }));
            } catch (RuntimeException failure) { if (!cosmeticFailed(viewer, "show bones", body.value, failure)) body.rigFailed = true; }
            return 1;
        }
        if (!body.bonesShown || rigBudget[0] <= 0) return 0;
        if (body.socketRevision != shown.socketRevision()) {
            int[] sockets = shown.socketIndices();
            body.socketRevision = shown.socketRevision();
            rigBudget[0] -= sockets.length;
            sendCosmetic(viewer, body, "socket items", () -> packets.restyleBones(viewer.player, body.bones, sockets, shown.socketDisplays()));
            if (rigBudget[0] <= 0) return 0;
        }
        int[] due = body.view.due(frame, rigBudget[0]);
        if (due.length == 0 || !admit(viewer, PresentationPressure.Tier.COSMETIC)) return 0;
        long nanos = System.nanoTime();
        DisplayTransform[] targets = new DisplayTransform[due.length];
        int[] durations = new int[due.length];
        for (int i = 0; i < due.length; i++) { targets[i] = frame.target(due[i]); durations[i] = frame.remaining(nanos, due[i]); }
        body.view.sent(frame, due);
        rigBudget[0] -= due.length;
        try {
            handOver(viewer, () -> packets.moveBones(viewer.player, body.bones, due, targets, durations)).whenComplete((Void nothing, Throwable failure) -> {
                if (failure != null) enqueue(viewer, () -> { if (current(viewer) && !body.retired) { body.view.unsent(due); cosmeticFailed(viewer, "move bones", body.value, failure); } });
            });
        } catch (RuntimeException failure) { body.view.unsent(due); cosmeticFailed(viewer, "move bones", body.value, failure); }
        return 0;
    }

    // ---- Selection glow ----

    /** On the owner thread: the looked-at or nearest NPC this player sees. */
    Optional<String> pick(UUID id) {
        Viewer viewer = viewers.get(id);
        if (viewer == null || !service.available()) return Optional.empty();
        service.owner();
        List<SelectionPick.Candidate> candidates = new ArrayList<>();
        for (Presented body : viewer.visible.values())
            if (body.spawnFinished && !body.retired && !viewer.preview.owns(body.value.spec.definition().uuid()))
                candidates.add(new SelectionPick.Candidate(body.value.spec.definition().id(), body.value.state.snapshot(), shape(body.value)));
        WorldPosition at = service.host.position(viewer.player);
        return SelectionPick.choose(at.world(), service.host.eye(viewer.player), service.host.direction(viewer.player), at.point(), candidates);
    }
    /** On the owner thread: viewer-private glowing metadata, ended by the tick after {@code ticks}. */
    boolean highlight(UUID id, String npc, int ticks) {
        Viewer viewer = viewers.get(id);
        if (viewer == null || !service.available() || !service.packets.presentationAvailable()) return false;
        service.owner();
        Presented body = null;
        for (Presented seen : viewer.visible.values()) if (seen.value.spec.definition().id().equals(npc)) { body = seen; break; }
        if (body == null || !body.spawnFinished || body.retired) return false;
        if (viewer.glowing != null && viewer.glowing != body && !unglow(viewer)) return false;
        if (!glow(viewer, body, base(body.value).glowing())) return false;
        viewer.glowing = body;
        viewer.glowEnds = service.tickNumber + ticks;
        return true;
    }
    /** Shows the glowing body's own look again; false while the outbox has no room, so the next tick tries again. */
    private boolean unglow(Viewer viewer) {
        Presented body = viewer.glowing;
        return body.retired || viewer.visible.get(body.value.spec.definition().uuid()) != body || glow(viewer, body, base(body.value));
    }
    private BodyLook base(PersistedNpcService<P, I>.Value value) {
        return value.look != null ? value.look : BodyLook.of(value.spec.definition().uuid(), value.spec.type(), value.displayName, value.spec.appearance().withAttributes(value.attributes));
    }
    /** Cosmetic: one look sent to this player; a failed send is recorded and not retried. */
    private boolean glow(Viewer viewer, Presented body, BodyLook look) {
        if (!admit(viewer, PresentationPressure.Tier.COSMETIC)) return false;
        sendCosmetic(viewer, body, "glow", () -> service.packets.look(viewer.player, body.body, look));
        return true;
    }

    // ---- Outbox ----

    /** Reserves a place in the viewer's outbox; a full queue counts as a short budget, so cosmetic work yields while it drains. */
    boolean admit(Viewer viewer, PresentationPressure.Tier tier) {
        if (viewer.outbox.admit(tier, service.settings.queuePerViewer())) return true;
        if (tier != PresentationPressure.Tier.COSMETIC) service.pressure.shortOfBudget(service.tickNumber);
        return false;
    }
    /** Hands one admitted operation to the transport; a send that throws first gives its place back. */
    CompletionStage<Void> handOver(Viewer viewer, Supplier<CompletionStage<Void>> send) {
        CompletionStage<Void> sent;
        try { sent = send.get(); }
        catch (RuntimeException failure) { viewer.outbox.abandon(); throw failure; }
        return viewer.outbox.track(sent);
    }
    /** Hands an admitted cosmetic send to the transport; a failure is recorded against the body while it is shown, never retried. */
    private void sendCosmetic(Viewer viewer, Presented body, String operation, Supplier<CompletionStage<Void>> send) {
        try {
            handOver(viewer, send).whenComplete((Void nothing, Throwable failure) -> {
                if (failure != null) enqueue(viewer, () -> { if (current(viewer) && !body.retired) cosmeticFailed(viewer, operation, body.value, failure); });
            });
        } catch (RuntimeException failure) { cosmeticFailed(viewer, operation, body.value, failure); }
    }
    /** Cosmetic work of one viewer failed: an error is recorded and the viewer keeps its session. True when the watch had ended and the viewer was dropped. */
    boolean cosmeticFailed(Viewer viewer, String operation, PersistedNpcService<P, I>.Value value, Throwable failure) {
        if (RuntimeFailures.viewerGone(failure)) { if (current(viewer)) lost(viewer); return true; }
        service.failures.record(operation, context(viewer, value), failure);
        return false;
    }

    // ---- Clicks and key presses ----

    private void interact(Viewer viewer, BodyTransport.Interaction input) {
        RuntimeHost<P, I> host = service.host;
        if (!current(viewer) || host.dead(viewer.player)) return;
        // Preview bodies are inert: no trigger, listener, selection, GUI, shop, ride or damage.
        if (viewer.preview.owns(input.body().uuid())) return;
        Presented body = viewer.visible.get(input.body().uuid());
        if (body == null || body.body != input.body()) { rejected++; return; }
        ViewerSession.Validation validated = hit(viewer, body);
        if (validated == null) return;
        RuntimeHost.Interaction action = input.action() == BodyTransport.Action.ATTACK ? RuntimeHost.Interaction.ATTACK : RuntimeHost.Interaction.INTERACT;
        // A click on an NPC that redirects its clicks counts as a click on the target, while that target is active and shown to
        // this player. An attack still lands on the NPC that was hit: the interaction event, which deals combat damage, names it.
        UUID target = redirect(viewer, body.value);
        boolean allowed = host.interactionAllowed(viewer.player, action == RuntimeHost.Interaction.ATTACK ? body.body.uuid() : target, action, validated.distance());
        // A combat listener hurts and knocks the NPC back during that event; it is still this click unless the NPC left the world.
        VirtualNpcState.Snapshot now = body.value.state.snapshot();
        if (!allowed || !current(viewer) || host.dead(viewer.player) || viewer.visible.get(body.body.uuid()) != body || !eligibleNow(viewer, body.value)
                || !now.active() || !now.position().world().equals(validated.npc().position().world())
                || !DefinitionSpec.spawned(service.runtime.npcs().snapshot().get(body.value.spec.definition().id()))) return;
        accepted++;
        PersistedNpcService<P, I>.Value clicked = body.value;
        if (action == RuntimeHost.Interaction.INTERACT && clicked.pause != null && clicked.pause.onClick()) clicked.pauseClock.pause(clicked.pause, service.tickNumber);
        TriggerBridge triggers = service.triggers;
        // An attack packet carries no sneaking flag, so a left click reads the player's own state.
        boolean consumed = triggers != null && triggers.interaction(target, host.id(viewer.player),
                action == RuntimeHost.Interaction.ATTACK ? TriggerEvent.Action.ATTACK : TriggerEvent.Action.INTERACT,
                action == RuntimeHost.Interaction.ATTACK ? host.sneaking(viewer.player) : input.secondaryAction(), TriggerEvent.Interaction.hand(input.hand()), false);
        NpcRuntime.InteractionObserver observed = service.observer;
        if (observed != null) observed.accepted(target, host.id(viewer.player), action == RuntimeHost.Interaction.ATTACK, validated.distance(), false);
        // A behavior that consumes the click owns it: modules, riding and selection never see it.
        if (consumed) return;
        NpcRuntime.InteractionListener<P> listener = service.interactions;
        if (listener != null && listener.interacted(viewer.player, target, action == RuntimeHost.Interaction.ATTACK, input.secondaryAction())) return;
        // Only the wand selects, through the listener above; a plain click is play.
        if (target.equals(body.body.uuid()) && body.value.ride != null && action == RuntimeHost.Interaction.INTERACT && !input.secondaryAction() && "MAIN_HAND".equals(input.hand())) {
            String refusal = service.riders.request(viewer.player, service.mountRuntime.vehicle(body.value));
            if (refusal != null) host.tell(viewer.player, Palette.error("Cannot ride " + body.value.spec.name() + ": " + refusal));
        }
    }
    /** The NPC that receives clicks on {@code value}: its redirect target when that NPC is active and not hidden from the viewer, otherwise itself. */
    private UUID redirect(Viewer viewer, PersistedNpcService<P, I>.Value value) {
        UUID self = value.spec.definition().uuid();
        java.util.Optional<UUID> target = value.spec.definition().clickRedirect();
        if (target.isEmpty()) return self;
        PersistedNpcService<P, I>.Entry entry = service.byUuid.get(target.get());
        return entry != null && entry.value != null && entry.value.state.snapshot().active() && !service.hidden().hides(viewer.id, entry.id, target.get()) ? target.get() : self;
    }
    /** A key press aimed at an NPC in interaction reach, validated as a click is; see {@link NpcRuntime#pressed}. */
    boolean pressed(P player, TriggerEvent.Action key) {
        RuntimeHost<P, I> host = service.host;
        Viewer viewer = viewers.get(host.id(player));
        if (viewer == null || service.triggers == null || !current(viewer) || host.dead(player)) return false;
        ViewerSession.HitShape inert = (VirtualNpcState.Snapshot npc, Vec3 origin, Vec3 direction, double reach) -> OptionalDouble.empty();
        OptionalInt aimed = viewer.guard.aim(new WorldPosition(host.position(player).world(), host.eye(player)), host.direction(player), (VirtualNpcState.Snapshot npc) -> {
            Presented seen = viewer.visible.get(npc.npcId());
            return seen == null || viewer.preview.owns(npc.npcId()) ? inert : shape(seen.value);
        });
        if (aimed.isEmpty()) return false;
        Presented body = null;
        for (Presented seen : viewer.visible.values()) if (seen.body.entityId() == aimed.getAsInt()) { body = seen; break; }
        if (body == null || hit(viewer, body) == null) return false;
        accepted++;
        return service.triggers.interaction(body.body.uuid(), host.id(player), key, host.sneaking(player), TriggerEvent.Hand.MAIN_HAND, false);
    }
    /** Validates the player's current server ray against a presented body; null after counting the rejection. */
    private ViewerSession.Validation hit(Viewer viewer, Presented body) {
        RuntimeHost<P, I> host = service.host;
        if (!body.bound || body.retired || !eligibleNow(viewer, body.value)) { rejected++; return null; }
        // A command publication can precede the bounded reconciliation pass: deletion/despawn still revokes hits immediately.
        if (!DefinitionSpec.spawned(service.runtime.npcs().snapshot().get(body.value.spec.definition().id()))) { rejected++; retire(viewer, body); return null; }
        // Transport acknowledgment is presentation bookkeeping; hits always use the current authoritative position.
        viewer.guard.updateSnapshot(body.body.entityId(), body.value.state.snapshot());
        Vec3 eye = host.eye(viewer.player), direction = host.direction(viewer.player);
        String world = host.position(viewer.player).world();
        ViewerSession.Validation validated = viewer.guard.validate(new ViewerSession.Request(host.id(viewer.player), viewer.guard.sessionId(), body.body.entityId(),
                new WorldPosition(world, eye), direction, System.nanoTime()), (WorldPosition from, Vec3 to, double distance) -> {
            int minX = ((int) Math.floor(Math.min(eye.x(), to.x()))) >> 4, maxX = ((int) Math.floor(Math.max(eye.x(), to.x()))) >> 4;
            int minZ = ((int) Math.floor(Math.min(eye.z(), to.z()))) >> 4, maxZ = ((int) Math.floor(Math.max(eye.z(), to.z()))) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!host.chunkLoaded(world, x, z)) return false;
            return distance <= 0.000001 || host.clearSight(viewer.player, eye, direction, Math.max(0, distance - 0.0001));
        }, service.packets.hitShape(body.body.uuid()).orElseGet(() -> shape(body.value)));
        if (!validated.accepted()) { rejected++; return null; }
        return validated;
    }
    private static ViewerSession.HitShape shape(PersistedNpcService<?, ?>.Value value) {
        return value.spec.type().equals(DragonBody.TYPE) ? DragonBody.hitShape(value.yaw) : ViewerSession.HitShape.BOUNDS;
    }

    // ---- Failures ----

    private String context(Viewer viewer, PersistedNpcService<P, I>.Value value) {
        return "viewer=" + service.host.name(viewer.player) + (value == null ? "" : ", npc=" + value.spec.definition().id());
    }
    /** Body work of one viewer failed. A watch that ended (the connection closed or was re-watched) is not an error: the viewer is dropped and watched again. */
    private void fail(Viewer viewer, String operation, PersistedNpcService<P, I>.Value value, Throwable failure) {
        if (!current(viewer)) return;
        if (RuntimeFailures.viewerGone(failure)) { lost(viewer); return; }
        fail(viewer, operation, value, PersistedNpcService.concise(failure));
    }
    private void fail(Viewer viewer, String operation, PersistedNpcService<P, I>.Value value, String error) {
        if (!current(viewer)) return;
        service.failures.record(operation, context(viewer, value), error);
        if (viewerFailures.size() < service.settings.maximumViewers()) viewerFailures.put(service.host.id(viewer.player), error);
        disconnect(viewer.player);
    }
    /** The viewer's packet callbacks overflowed: it is dropped and shown every NPC again once the backlog has drained. */
    private void resync(Viewer viewer) {
        service.failures.record("packet callbacks", context(viewer, null), "Packet callback queue full; the viewer is shown its NPCs again");
        disconnect(viewer.player);
        resyncs.put(service.host.id(viewer.player), service.tickNumber + RESYNC_TICKS);
    }
    /** The transport could not watch the viewer. A closed connection waits quietly for the quit, rejoin, teleport or respawn that follows it. */
    private void watchFailed(Viewer viewer, Throwable failure) {
        if (!current(viewer)) return;
        if (!RuntimeFailures.viewerGone(failure)) { fail(viewer, "watch", null, failure); return; }
        service.failures.watchEnded(service.host.name(viewer.player), failure);
        if (viewerFailures.size() < service.settings.maximumViewers()) viewerFailures.put(service.host.id(viewer.player), "connection closed");
        disconnect(viewer.player);
    }
    /** Drops a viewer whose watch ended without counting it as a failure; the next admission watches it again. */
    private void lost(Viewer viewer) { disconnect(viewer.player); readmit(); }
    void disconnect(P player) {
        if (service.riders != null) service.riders.viewerLost(player);
        Viewer old = viewers.remove(service.host.id(player));
        if (old != null) { old.guard.close(); viewerOrder.remove(old); showing.forget(old, old.visible.keySet()); old.visible.clear(); old.retiring.clear(); old.retirements.clear(); old.presentationOrder.clear(); old.cosmeticOrder.clear(); }
        if (service.packets != null) service.packets.forget(player);
    }
    /** A player left the server: their session, failures and resync wait are forgotten. */
    void quit(P player) {
        UUID id = service.host.id(player);
        disconnect(player);
        viewerFailures.remove(id); resyncs.remove(id);
    }

    final class Presented {
        final PersistedNpcService<P, I>.Value value; final BodyTransport.Body body;
        boolean bound, retired, spawnFinished, despawnFinished, moving, equipping, looking;
        long deliveredPoseRevision, deliveredEquipmentRevision, deliveredLookRevision, deliveredSwings, deliveredGestures, deliveredAttackSwings, deliveredAttributeRevision;
        boolean attributing;
        List<Integer> sentRoster = List.of();
        int sentDragonPhase = DragonBody.PHASE_HOVERING;
        BodyTransport.Bones bones; RigView view; long rigIdentity, socketRevision; boolean bonesShown, rigFailed;
        /** The filled text rows this viewer was sent, and whether the name or a hologram line holds a placeholder that refreshes. */
        List<String> labels; long labelledAt, deliveredBubbleRevision; boolean placeholdered;
        Presented(PersistedNpcService<P, I>.Value value, BodyTransport.Body body) { this.value = value; this.body = body; }
    }
    final class Viewer {
        final P player; final UUID id; final String world; final ViewerSession guard; final PreviewBodies<P, I> preview;
        final Map<UUID, Presented> visible = new HashMap<>(), retiring = new HashMap<>();
        /** Whether each chunk reached this viewer, remembered for one update only. */
        final Map<Long, Boolean> deliveredChunks = new HashMap<>();
        /** The body glowing for this player alone after a selection, and the tick its glow ends on. */
        Presented glowing; long glowEnds;
        final Deque<Presented> retirements = new ArrayDeque<>();
        final Deque<Presented> presentationOrder = new ArrayDeque<>();
        /** The same bodies in their own rotation for cosmetic work, which runs after every viewer's authoritative work. */
        final Deque<Presented> cosmeticOrder = new ArrayDeque<>();
        /** Packet operations handed to the transport and not yet finished, bounded by {@code [npcs] queuePerViewer}. */
        final ViewerOutbox outbox = new ViewerOutbox();
        /** Presentation updates this viewer had; spaces its cosmetic work and distant moves under pressure. */
        long turns;
        SpatialNpcIndex.Cursor cursor;
        int nativeIds, pendingSpawns, bones;
        boolean ready, rosterDirty; volatile boolean failed;
        long rosterRevision = -1;
        Viewer(P player) {
            this.player = player; id = service.host.id(player); world = service.host.position(player).world();
            preview = new PreviewBodies<>(service.previews, id);
            guard = new ViewerSession(service.host.id(player), UUID.randomUUID(), service.viewerLimits());
        }
    }
}
