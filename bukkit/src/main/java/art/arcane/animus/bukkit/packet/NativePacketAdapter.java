package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.presentation.NativeEntityIds;
import art.arcane.animus.core.presentation.PassengerGraph;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.control.ControlInput;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.virtual.DragonBody;
import io.netty.channel.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Experimental exact-version transport. No method constructs an NMS Entity or registers a body in a world.
 * Call watch/spawn/move/despawn/forget on the viewer's owning scheduler. All native details stay here.
 * Inbound messages are only ownership/rate filtered; the consumer MUST validate authoritative reach,
 * world/chunk visibility, line of sight and current NPC state before doing anything with an interaction.
 */
public final class NativePacketAdapter implements BodyTransport<Player, ItemStack> {
    public record Capabilities(String minecraftVersion, Set<String> entityTypes, Set<String> operations, String implementation) {
        public Capabilities { entityTypes = Set.copyOf(entityTypes); operations = Set.copyOf(operations); }
    }
    @FunctionalInterface public interface Scheduler {
        /** Return false when the viewer retired or the scheduler cannot accept the callback. Never run inline on Netty. */
        boolean execute(Player viewer, Runnable callback);
    }
    public static final class Body implements BodyTransport.Body {
        private final NativePacketAdapter owner;
        private final int entityId;
        private final int reservedIds;
        private final UUID uuid;
        private final String typeKey;
        private final double width, height;
        private final SeatGeometry seat;
        private final Object nativeType, playerInfo;
        private final BodyLook look;
        private final int displayId, seatId;
        private final UUID displayUuid = UUID.randomUUID(), seatUuid = UUID.randomUUID();
        private final Object[] nameTeam;
        private Body(NativePacketAdapter owner, int entityId, int baseIds, UUID uuid, String typeKey, double width, double height,
                     SeatGeometry seat, Object nativeType, Object playerInfo, BodyLook look, Object[] nameTeam) {
            this.owner = owner; this.entityId = entityId; this.reservedIds = baseIds + look.extraIds(); this.uuid = uuid;
            this.typeKey = typeKey; this.width = width; this.height = height; this.seat = seat; this.nativeType = nativeType; this.playerInfo = playerInfo;
            this.look = look; this.nameTeam = nameTeam;
            displayId = look.text().isPresent() ? entityId + baseIds : -1;
            seatId = look.seated() ? entityId + baseIds + (look.text().isPresent() ? 1 : 0) : -1;
        }
        public int entityId() { return entityId; }
        public UUID uuid() { return uuid; }
        public String typeKey() { return typeKey; }
        public double width() { return width; }
        public double height() { return height; }
        public SeatGeometry seat() { return seat; }
        public int[] entityIds() { return java.util.stream.IntStream.range(0, reservedIds).map(offset -> entityId + offset).toArray(); }
    }
    private final Plugin plugin;
    private final NativeBindings nativeApi;
    private final Capabilities capabilities;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final String handlerName = "animus_virtual_" + UUID.randomUUID().toString().replace("-", "");
    private final String riderUnavailable;
    private final Scheduler owner;
    private volatile boolean closed;

    private NativePacketAdapter(Plugin plugin, String version, Scheduler owner) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin);
        this.owner = owner;
        nativeApi = new NativeBindings(plugin, version);
        riderUnavailable = nativeApi.riderUnavailable;
        Set<String> operations = new HashSet<>(Set.of("spawn", "despawn", "move", "signed-player-textures", "attack-interception", "use-interception", "head-rotation", "passengers", "control-input", "dragon-presentation"));
        if (riderUnavailable == null) operations.add("real-rider");
        if (nativeApi.equipment != null) operations.add("equipment");
        if (nativeApi.presentation != null) operations.add("presentation");
        capabilities = new Capabilities(version, BodyRules.TYPES, operations, "explicit-packets/no-native-entity-bodies; signature binding is not gameplay certification");
    }
    /** For callers that always pass their own scheduler to {@link #watch(Player, Scheduler, Consumer)}. */
    public static NativePacketAdapter bind(Plugin plugin, String exactMinecraftVersion) throws ReflectiveOperationException {
        return bind(plugin, exactMinecraftVersion, null);
    }
    /** {@code owner} delivers interactions of {@link #watch(Player, Consumer)} to the viewer's owner. */
    public static NativePacketAdapter bind(Plugin plugin, String exactMinecraftVersion, Scheduler owner) throws ReflectiveOperationException {
        if (!Set.of("26.1.2", "26.2", "26.3").contains(exactMinecraftVersion)) throw new IllegalArgumentException("No packet adapter for " + exactMinecraftVersion);
        return new NativePacketAdapter(plugin, exactMinecraftVersion, owner);
    }
    @Override public Set<String> entityTypes() { return capabilities.entityTypes(); }
    public Capabilities capabilities() { return capabilities; }
    /** Null when a real player can ride on this exact server; otherwise why the capability is unavailable. */
    @Override public String riderUnavailable() { return riderUnavailable; }

    /** One latest-value semantic input subscription per existing viewer session. Poll on the owning scheduler. */
    @Override public ControlSubscription subscribeControl(Player viewer) {
        ensureOpen();
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) throw new IllegalStateException("Viewer is not watched");
        ControlSubscription subscription = new ControlSubscription(session);
        if (!session.control.compareAndSet(null, subscription)) throw new IllegalStateException("Viewer already has a control subscription");
        if (!current(session)) { subscription.close(); throw new IllegalStateException("Viewer session retired"); }
        return subscription;
    }
    public final class ControlSubscription implements BodyTransport.ControlSubscription {
        private final Session session;
        private final ControlInbox inbox = new ControlInbox(System.nanoTime());
        private ControlSubscription(Session session) { this.session = session; }
        @Override public ControlInbox.Snapshot latest() {
            if (!current(session) || session.control.get() != this) inbox.close("session-retired");
            return inbox.snapshot();
        }
        @Override public void close() { inbox.close("subscription-closed"); session.control.compareAndSet(this, null); }
    }

    /** A plain body: the profile name is its nameplate and nothing else is presented. */
    @Override public Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) {
        return createBody(uuid, typeKey, BodyLook.named(profileName), skin);
    }

    /**
     * A body showing its definition's appearance: pose, armor stand parts, and a text display for hologram lines and
     * names a profile cannot carry. Without presentation support only the plain body is shown.
     */
    @Override public Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) {
        BodyLook look = BodyLook.of(uuid, typeKey, name, appearance);
        if (nativeApi.presentation == null) look = BodyLook.named(look.profileName());
        return createBody(uuid, typeKey, look, skin);
    }

    /** Extra native IDs a body with this appearance reserves beyond its type's own. */
    @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) {
        return nativeApi.presentation == null ? 0 : BodyLook.of(uuid, typeKey, name, appearance).extraIds();
    }

    @Override public boolean presentationAvailable() { return nativeApi.presentation != null; }

    private Body createBody(UUID uuid, String typeKey, BodyLook look, BodySkin skin) {
        try { return bindBody(uuid, typeKey, look, skin); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot build a body: " + failure.getClass().getSimpleName(), failure); }
    }
    private Body bindBody(UUID uuid, String typeKey, BodyLook look, BodySkin skin) throws ReflectiveOperationException {
        ensureOpen();
        Objects.requireNonNull(uuid);
        if (!BodyRules.TYPES.contains(typeKey)) throw new IllegalArgumentException("Body type is not enabled by the proof adapter: " + typeKey);
        if (typeKey.equals("minecraft:player")) BodyRules.validateProfileName(look.profileName());
        if (!typeKey.equals("minecraft:player") && skin != null) throw new IllegalArgumentException("Skin textures apply only to player bodies");
        Object type = nativeApi.entityType(typeKey);
        int reserved = BodyRules.reservedIds(typeKey);
        Object playerInfo = typeKey.equals("minecraft:player") ? nativeApi.playerInfo(uuid, look.profileName(), skin) : null;
        int entityId = nativeApi.ids.reserve(reserved + look.extraIds());
        Object[] team = look.hideProfileName() ? nativeApi.presentation.hiddenNameTeam(entityId, look.profileName()) : null;
        return new Body(this, entityId, reserved, uuid, typeKey, ((Number) nativeApi.typeWidth.invoke(type)).doubleValue(),
                ((Number) nativeApi.typeHeight.invoke(type)).doubleValue(), nativeApi.seat(type), type, playerInfo, look, team);
    }

    /** Uses the scheduler given to {@link #bind(Plugin, String, Scheduler)}. */
    @Override public CompletionStage<Void> watch(Player viewer, Consumer<Interaction> interaction) {
        if (owner == null) throw new IllegalStateException("This adapter has no owner scheduler");
        try { return watch(viewer, owner, interaction); }
        catch (ReflectiveOperationException failure) { return CompletableFuture.failedFuture(failure); }
    }
    /** Installing a new watch invalidates the previous session and every pending callback for that UUID. */
    public CompletionStage<Void> watch(Player viewer, Scheduler scheduler, Consumer<Interaction> interaction) throws ReflectiveOperationException {
        ensureOpen();
        Objects.requireNonNull(scheduler); Objects.requireNonNull(interaction);
        Channel channel = nativeApi.channel(viewer);
        Session session = new Session(viewer, channel, scheduler, interaction);
        Session previous = sessions.put(viewer.getUniqueId(), session);
        if (previous != null) retire(previous);
        try {
            channel.eventLoop().execute(() -> {
                if (!current(session) || !channel.isActive()) { session.ready.completeExceptionally(new CancellationException("Viewer session retired")); return; }
                try {
                    if (channel.pipeline().get(handlerName) != null) channel.pipeline().remove(handlerName);
                    if (channel.pipeline().get("packet_handler") == null) throw new IllegalStateException("Vanilla packet_handler is unavailable");
                    channel.pipeline().addBefore("packet_handler", handlerName, new InputHandler(session));
                    session.ready.complete(null);
                } catch (RuntimeException e) { sessions.remove(session.viewerId, session); retire(session); session.ready.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { sessions.remove(session.viewerId, session); retire(session); session.ready.completeExceptionally(e); }
        return session.ready;
    }

    @Override public CompletionStage<Void> spawn(Player viewer, BodyTransport.Body presented, BodyPose pose) {
        Body body = checkBody(presented);
        Session session = session(viewer, pose);
        Presentation presentation = new Presentation(body);
        synchronized (session) {
            int pendingIds = session.spawning.values().stream().mapToInt(value -> value.body.reservedIds).sum();
            if (session.visible.size() + pendingIds + body.reservedIds > BodyRules.MAX_BODIES_PER_VIEWER) return failed("Viewer presentation ID limit reached");
            if (session.visible.values().stream().anyMatch(value -> value.body.uuid.equals(body.uuid))
                    || session.spawning.values().stream().anyMatch(value -> value.body.uuid.equals(body.uuid))) return failed("A body with this UUID is already presented to the viewer");
            if (session.visible.containsKey(body.entityId) || session.spawning.putIfAbsent(body.entityId, presentation) != null) return failed("Body is already visible or spawning");
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.spawning.get(body.entityId) != presentation) throw new CancellationException("Body spawn was cancelled");
            List<Object> packets = nativeApi.spawnPackets(body, pose);
            write(session, packets).whenComplete((nothing, failure) -> {
                boolean published = false;
                synchronized (session) {
                    boolean stillSpawning = session.spawning.remove(body.entityId, presentation);
                    if (failure == null && current(session) && stillSpawning) {
                        for (int id : body.entityIds()) session.visible.put(id, presentation);
                        published = true;
                    }
                }
                if (published) result.complete(null);
                else {
                    // A delayed failure from an older presentation must not remove a newer one of the same body.
                    if (!hasNewerPresentation(session, presentation)) removePackets(session, body);
                    result.completeExceptionally(failure != null ? failure : new CancellationException("Viewer retired during spawn"));
                }
            });
        });
        result.whenComplete((nothing, failure) -> { if (failure != null) session.spawning.remove(body.entityId, presentation); });
        return result;
    }

    @Override public CompletionStage<Void> move(Player viewer, BodyTransport.Body presented, BodyPose pose) {
        Body body = checkBody(presented);
        Session session = session(viewer, pose);
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new IllegalStateException("Body was despawned before movement");
            List<Object> packets = new ArrayList<>(3);
            // A seated body follows its seat on the client, so the seat carries the movement.
            if (body.seatId >= 0) packets.add(nativeApi.seatMove(body, pose));
            packets.add(nativeApi.move(body, pose));
            packets.add(nativeApi.rotateHead(body.entityId, BodyRules.wireYaw(body.typeKey, pose.yaw())));
            write(session, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /**
     * Viewer-local mount presentation only; this does not grant control or mount a real server entity. The client ejects
     * everyone from the vehicle first, so the list is the whole roster. {@code realRider} may be null. An authoritative
     * update bypasses the normal operation limit and, written before a later teleport on the same thread, reaches the client first.
     */
    @Override public CompletionStage<Void> passengers(Player viewer, BodyTransport.Body carrier, List<? extends BodyTransport.Body> passengers, Player realRider, boolean authoritative) {
        Body vehicle = checkBody(carrier);
        List<Body> requested = passengers.stream().map(this::checkBody).toList();
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return failed("Viewer is not watched");
        List<Presentation> presentations = new ArrayList<>();
        synchronized (session) {
            Presentation mount = session.visible.get(vehicle.entityId);
            if (mount == null || mount.body != vehicle) return failed("Vehicle is not visible to this viewer");
            presentations.add(mount);
            for (Body passenger : requested) {
                Presentation rider = session.visible.get(passenger.entityId);
                if (rider == null || rider.body != passenger) return failed("Passenger is not visible to this viewer");
                presentations.add(rider);
            }
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        int realRiderId = realRider == null ? BodyRules.NO_REAL_RIDER : realRider.getEntityId();
        submit(session, result, authoritative, () -> {
            List<Integer> passengerIds = requested.stream().map(Body::entityId).toList();
            Object packet = nativeApi.passengers(vehicle.entityId, BodyRules.withText(BodyRules.passengerRoster(passengerIds, realRiderId), vehicle.displayId));
            synchronized (session) {
                for (Presentation presentation : presentations)
                    if (session.visible.get(presentation.body.entityId) != presentation) throw new CancellationException("Mount presentation retired");
                session.passengers.replace(vehicle.entityId, passengerIds);
            }
            write(session, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** Sets the dragon's synced phase. Clients derive its wing motion and head pose from this. */
    @Override public CompletionStage<Void> dragonPhase(Player viewer, BodyTransport.Body presented, int phase) {
        Body dragon = checkBody(presented);
        if (!dragon.typeKey.equals(DragonBody.TYPE)) return failed("Phase applies only to dragon bodies");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return failed("Viewer is not watched");
        Presentation presentation = session.visible.get(dragon.entityId);
        if (presentation == null || presentation.body != dragon) return failed("Dragon is not visible to this viewer");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(dragon.entityId) != presentation) throw new CancellationException("Dragon was despawned");
            write(session, List.of(nativeApi.dragonPhase(dragon.entityId, phase))).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public boolean equipmentAvailable() { return nativeApi.equipment != null; }

    /** Viewer-local items on a visible body. An empty stack clears its slot. Call on the viewer's owner: stacks convert here. */
    @Override public CompletionStage<Void> equipment(Player viewer, BodyTransport.Body presented, Map<EquipmentSlot, ItemStack> items) {
        Body body = checkBody(presented);
        if (nativeApi.equipment == null) return failed("Equipment packets are unavailable on this server version");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return failed("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        Object packet;
        try { packet = nativeApi.equipment.packet(body.entityId, items); }
        catch (ReflectiveOperationException | RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned before equipment");
            write(session, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /**
     * Owner thread only. Moves the real rider's server position without a client teleport: while its client believes it
     * is a passenger the client follows the vehicle and ignores position packets, so one would only produce stale
     * acknowledgements. The movement baseline is reset so the next rotation packet is not measured against the old place.
     */
    @Override public void placeRider(Player rider, double x, double y, double z) {
        ensureOpen();
        if (riderUnavailable != null) throw new IllegalStateException(riderUnavailable);
        try { nativeApi.placeRider(rider, x, y, z); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot position rider: " + failure.getClass().getSimpleName(), failure); }
    }

    /** Despawn is authoritative: it bypasses the cosmetic/normal outgoing operation admission limit. */
    @Override public CompletionStage<Void> despawn(Player viewer, BodyTransport.Body presented) {
        Body body = checkBody(presented);
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer) return CompletableFuture.completedFuture(null);
        boolean present;
        synchronized (session) {
            Presentation visible = session.visible.get(body.entityId);
            Presentation spawning = session.spawning.get(body.entityId);
            present = (visible != null && visible.body == body) || (spawning != null && spawning.body == body);
            for (int id : body.entityIds()) session.visible.computeIfPresent(id, (key, value) -> value.body == body ? null : value);
            session.spawning.computeIfPresent(body.entityId, (key, value) -> value.body == body ? null : value);
            session.passengers.remove(body.entityId);
        }
        if (!present) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { session.channel.eventLoop().execute(() -> removePackets(session, body).whenComplete((nothing, failure) -> complete(result, failure))); }
        catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        return result;
    }

    @Override public void forget(Player viewer) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session != null && session.viewer == viewer && sessions.remove(session.viewerId, session)) retire(session);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Session session : sessions.values()) if (sessions.remove(session.viewerId, session)) retire(session);
    }

    private Session session(Player viewer, BodyPose pose) {
        ensureOpen();
        if (!viewer.getWorld().getUID().toString().equals(pose.world())) throw new IllegalArgumentException("Viewer and body world differ");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) throw new IllegalStateException("Viewer must be watched before sending body packets");
        return session;
    }
    private Body checkBody(BodyTransport.Body presented) {
        ensureOpen();
        if (!(presented instanceof Body body) || body.owner != this) throw new IllegalArgumentException("Body belongs to another adapter");
        return body;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Packet adapter is closed"); }
    private boolean current(Session session) { return !closed && session.active.get() && sessions.get(session.viewerId) == session; }
    private static CompletionStage<Void> failed(String reason) { return CompletableFuture.failedFuture(new IllegalStateException(reason)); }
    private static void complete(CompletableFuture<Void> result, Throwable failure) { if (failure == null) result.complete(null); else result.completeExceptionally(failure); }
    @FunctionalInterface private interface Operation { void run() throws ReflectiveOperationException; }

    private void submit(Session session, CompletableFuture<Void> result, boolean authoritative, Operation operation) {
        if (!authoritative) {
            if (session.pendingOperations.incrementAndGet() > BodyRules.MAX_OPERATIONS) {
                session.pendingOperations.decrementAndGet(); result.completeExceptionally(new IllegalStateException("Viewer packet queue is full")); return;
            }
            result.whenComplete((nothing, failure) -> session.pendingOperations.decrementAndGet());
        }
        session.ready.whenComplete((nothing, failure) -> {
            if (failure != null) { result.completeExceptionally(failure); return; }
            try {
                session.channel.eventLoop().execute(() -> {
                    if (!current(session)) { result.completeExceptionally(new CancellationException("Viewer session retired")); return; }
                    try { operation.run(); }
                    catch (ReflectiveOperationException | RuntimeException e) { result.completeExceptionally(e); }
                });
            } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        });
    }

    private CompletionStage<Void> write(Session session, List<Object> packets) {
        if (!session.channel.isActive()) return failed("Viewer connection is closed");
        List<CompletableFuture<Void>> results = new ArrayList<>(packets.size());
        try {
            for (Object packet : packets) {
                CompletableFuture<Void> future = new CompletableFuture<>(); results.add(future);
                session.channel.write(packet).addListener(sent -> { if (sent.isSuccess()) future.complete(null); else future.completeExceptionally(sent.cause()); });
            }
            session.channel.flush();
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new));
    }
    private CompletionStage<Void> removePackets(Session session, Body body) {
        try {
            List<Object> packets = new ArrayList<>();
            packets.add(nativeApi.removeEntities.newInstance((Object) body.entityIds()));
            if (body.playerInfo != null) packets.add(nativeApi.removePlayerInfo.newInstance(List.of(body.uuid)));
            if (body.nameTeam != null) packets.add(body.nameTeam[1]);
            return write(session, packets);
        } catch (ReflectiveOperationException e) { return CompletableFuture.failedFuture(e); }
    }
    private boolean hasNewerPresentation(Session original, Presentation presentation) {
        Session current = sessions.get(original.viewerId);
        if (current == null || current.channel != original.channel) return false;
        synchronized (current) {
            Presentation visible = current.visible.get(presentation.body.entityId);
            Presentation spawning = current.spawning.get(presentation.body.entityId);
            return (visible != null && visible != presentation) || (spawning != null && spawning != presentation);
        }
    }
    private void retire(Session session) {
        Set<Body> bodies = new HashSet<>();
        synchronized (session) {
            if (!session.active.compareAndSet(true, false)) return;
            ControlSubscription control = session.control.getAndSet(null);
            if (control != null) control.inbox.close("session-retired");
            session.visible.values().forEach(value -> bodies.add(value.body));
            session.spawning.values().forEach(value -> bodies.add(value.body));
            session.visible.clear(); session.spawning.clear(); session.passengers.clear();
        }
        session.ready.completeExceptionally(new CancellationException("Viewer session retired"));
        try {
            session.channel.eventLoop().execute(() -> {
                for (Body body : bodies) removePackets(session, body);
                ChannelHandler handler = session.channel.pipeline().get(handlerName);
                if (handler instanceof InputHandler input && input.session == session) session.channel.pipeline().remove(handler);
            });
        } catch (RejectedExecutionException ignored) { /* Closed channels have already discarded their client presentation. */ }
    }

    private final class InputHandler extends ChannelInboundHandlerAdapter {
        private final Session session;
        private InputHandler(Session session) { this.session = session; }
        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            ControlSubscription control = session.control.get();
            if (control != null && current(session) && control.inbox.active()) {
                if (nativeApi.controlPacket.isInstance(message)) {
                    try { control.inbox.offer(nativeApi.controlInput(message), System.nanoTime()); }
                    catch (ReflectiveOperationException | RuntimeException error) {
                        control.inbox.close("input-decode-failed");
                        reportInputFailure(session, new IllegalStateException("Cannot decode semantic control input", error));
                    }
                } else if (nativeApi.clientTickEndPacket.isInstance(message)) control.inbox.heartbeat(System.nanoTime());
            }
            boolean attack = nativeApi.attackPacket.isInstance(message);
            if (!attack && !nativeApi.interactPacket.isInstance(message)) { context.fireChannelRead(message); return; }
            int entityId = ((Number) (attack ? nativeApi.attackId : nativeApi.interactId).invoke(message)).intValue();
            Presentation presentation = session.visible.get(entityId);
            if (presentation == null || !current(session)) { context.fireChannelRead(message); return; }
            Body body = presentation.body;
            try {
                long now = System.nanoTime();
                if (now - session.lastInputNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS || !session.inputScheduled.compareAndSet(false, true)) return;
                session.lastInputNanos = now;
                String hand = attack ? "none" : String.valueOf(nativeApi.interactHand.invoke(message));
                boolean secondary = !attack && (boolean) nativeApi.interactSecondary.invoke(message);
                Interaction event = new Interaction(body, attack ? BodyTransport.Action.ATTACK : BodyTransport.Action.INTERACT, hand, secondary, now);
                try {
                    boolean accepted = session.scheduler.execute(session.viewer, () -> {
                        try {
                            if (current(session) && session.visible.get(entityId) == presentation) session.interactions.accept(event);
                        } catch (RuntimeException e) { reportInputFailure(session, e); }
                        finally { session.inputScheduled.set(false); }
                    });
                    if (!accepted) session.inputScheduled.set(false);
                } catch (RuntimeException e) { session.inputScheduled.set(false); reportInputFailure(session, e); }
            } finally { ReferenceCountUtil.release(message); }
        }
        @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
            if (sessions.remove(session.viewerId, session)) retire(session);
            context.fireChannelInactive();
        }
    }
    private void reportInputFailure(Session session, RuntimeException e) {
        if (session.reportedFailure.compareAndSet(false, true)) plugin.getLogger().warning("Virtual interaction callback failed: " + e.getClass().getSimpleName());
    }
    private static final class Presentation {
        private final Body body;
        private Presentation(Body body) { this.body = body; }
    }
    private static final class Session {
        private final UUID viewerId;
        private final Player viewer;
        private final Channel channel;
        private final Scheduler scheduler;
        private final Consumer<Interaction> interactions;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final ConcurrentMap<Integer, Presentation> visible = new ConcurrentHashMap<>(), spawning = new ConcurrentHashMap<>();
        private final AtomicBoolean active = new AtomicBoolean(true), inputScheduled = new AtomicBoolean(), reportedFailure = new AtomicBoolean();
        private final AtomicInteger pendingOperations = new AtomicInteger();
        private final PassengerGraph passengers = new PassengerGraph();
        private final AtomicReference<ControlSubscription> control = new AtomicReference<>();
        private long lastInputNanos = Long.MIN_VALUE / 2; // Accessed exclusively by the channel event loop.
        private Session(Player viewer, Channel channel, Scheduler scheduler, Consumer<Interaction> interactions) {
            this.viewerId = viewer.getUniqueId(); this.viewer = viewer; this.channel = channel; this.scheduler = scheduler; this.interactions = interactions;
        }
    }

    /** Bound separately so a missing cosmetic signature disables equipment alone, not every body. */
    private static final class EquipmentBindings {
        private final Constructor<?> packet;
        private final Method pair, nativeStack;
        private final Class<? extends Enum> slots;

        @SuppressWarnings("unchecked")
        private EquipmentBindings(String craftPackage) throws ReflectiveOperationException {
            packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket").getConstructor(int.class, List.class);
            pair = Class.forName("com.mojang.datafixers.util.Pair").getMethod("of", Object.class, Object.class);
            nativeStack = Class.forName(craftPackage + ".inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
            slots = (Class<? extends Enum>) Class.forName("net.minecraft.world.entity.EquipmentSlot");
            for (EquipmentSlot slot : EquipmentSlot.values()) Enum.valueOf((Class) slots, BodyRules.nativeSlot(slot));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object packet(int entity, Map<EquipmentSlot, ItemStack> items) throws ReflectiveOperationException {
            List<Object> entries = new ArrayList<>(items.size());
            for (Map.Entry<EquipmentSlot, ItemStack> item : items.entrySet())
                entries.add(pair.invoke(null, Enum.valueOf((Class) slots, BodyRules.nativeSlot(item.getKey())), nativeStack.invoke(null, item.getValue())));
            return packet.newInstance(entity, entries);
        }
    }

    private static final class NativeBindings {
        private final EquipmentBindings equipment;
        private final NativePresentation presentation;
        private final Object textDisplayType, seatType;
        private final NativeEntityIds ids;
        private final Object entityRegistry, zeroVelocity, survival, playerSkinAccessor;
        private final Method identifierParse, registryContains, registryValue, typeWidth, typeHeight;
        private final Constructor<?> addEntity, removeEntities, removePlayerInfo, vector, movement, positionValues;
        private final Method positionPath;
        private final Constructor<?> friendlyBuffer, rotateHead, setPassengers;
        private final Method writeVarInt, writeVarIntArray;
        private final Constructor<?> profile, property, propertyMap, playerInfoEntry, playerInfoPacket, metadataPacket;
        private final Method multimapCreate, multimapPut, dataValueCreate;
        private final Field playerInfoEntries;
        private final Class<? extends Enum> playerInfoAction;
        private final Method craftPlayerHandle;
        private final Field playerConnection, networkConnection, channel;
        private final Class<?> attackPacket, interactPacket, controlPacket, clientTickEndPacket;
        private final Method attackId, interactId, interactHand, interactSecondary;
        private final Method controlValue;
        private final Method[] controlFlags;
        private final boolean steppedMovement;
        private final Method typeDimensions, dimensionAttachments, attachmentPoint, vectorX, vectorY, vectorZ;
        private final Object passengerAttachment, vehicleAttachment, dragonPhaseAccessor;
        private final String riderUnavailable;
        private Method riderSetPosition, riderLevel, riderChunkSource, riderChunkMove, riderResetPosition;

        @SuppressWarnings("unchecked")
        private NativeBindings(Plugin plugin, String version) throws ReflectiveOperationException {
            Class<?> entityType = type("net.minecraft.world.entity.EntityType");
            Class<?> identifier = type("net.minecraft.resources.Identifier");
            Class<?> registry = type("net.minecraft.core.Registry");
            entityRegistry = type("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
            identifierParse = identifier.getMethod("parse", String.class);
            registryContains = registry.getMethod("containsKey", identifier);
            registryValue = registry.getMethod("getValue", identifier);
            typeWidth = entityType.getMethod("getWidth"); typeHeight = entityType.getMethod("getHeight");
            Class<?> allocatorOwner = type(version.equals("26.1.2") ? "net.minecraft.world.entity.Entity" : "net.minecraft.server.level.ServerLevel");
            Field counter = field(allocatorOwner, "ENTITY_COUNTER");
            ids = new NativeEntityIds((AtomicInteger) counter.get(null));
            Class<?> vec3 = type("net.minecraft.world.phys.Vec3");
            vector = vec3.getConstructor(double.class, double.class, double.class); zeroVelocity = vec3.getField("ZERO").get(null);
            addEntity = type("net.minecraft.network.protocol.game.ClientboundAddEntityPacket").getConstructor(int.class, UUID.class, double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class);
            removeEntities = type("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
            removePlayerInfo = type("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket").getConstructor(List.class);
            Class<?> friendly = type("net.minecraft.network.FriendlyByteBuf");
            friendlyBuffer = friendly.getConstructor(ByteBuf.class);
            writeVarInt = friendly.getMethod("writeVarInt", int.class);
            writeVarIntArray = friendly.getMethod("writeVarIntArray", int[].class);
            rotateHead = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket", friendly);
            setPassengers = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket", friendly);
            Class<?> positionSync = type("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
            steppedMovement = version.equals("26.3");
            if (steppedMovement) {
                Class<?> path = type("net.minecraft.world.entity.PositionPath");
                positionPath = path.getMethod("of", vec3); positionValues = null;
                movement = positionSync.getConstructor(int.class, path, float.class, float.class, boolean.class);
            } else {
                Class<?> values = type("net.minecraft.world.entity.PositionMoveRotation");
                positionValues = values.getConstructor(vec3, vec3, float.class, float.class); positionPath = null;
                movement = positionSync.getConstructor(int.class, values, boolean.class);
            }
            Class<?> gameProfile = type("com.mojang.authlib.GameProfile");
            Class<?> properties = type("com.mojang.authlib.properties.PropertyMap");
            Class<?> multimap = type("com.google.common.collect.Multimap");
            profile = gameProfile.getConstructor(UUID.class, String.class, properties);
            property = type("com.mojang.authlib.properties.Property").getConstructor(String.class, String.class, String.class);
            propertyMap = properties.getConstructor(multimap);
            multimapCreate = type("com.google.common.collect.ArrayListMultimap").getMethod("create");
            multimapPut = multimap.getMethod("put", Object.class, Object.class);
            Class<?> gameType = type("net.minecraft.world.level.GameType");
            survival = Enum.valueOf((Class<Enum>) gameType, "SURVIVAL");
            Class<?> info = type("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            playerInfoAction = (Class<? extends Enum>) type("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            playerInfoEntry = type("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry").getConstructor(UUID.class, gameProfile, boolean.class, int.class, gameType,
                    type("net.minecraft.network.chat.Component"), boolean.class, int.class, type("net.minecraft.network.chat.RemoteChatSession$Data"));
            Constructor<?> packetConstructor;
            Field entriesField = null;
            try { packetConstructor = info.getConstructor(EnumSet.class, List.class); }
            catch (NoSuchMethodException e) {
                // Vanilla/Spigot expose the collection-of-ServerPlayer overload only. Build an empty packet,
                // then supply immutable explicit entries; no ServerPlayer surrogate is constructed.
                packetConstructor = info.getConstructor(EnumSet.class, Collection.class);
                entriesField = field(info, "entries");
                if (entriesField.getType() != List.class || Modifier.isStatic(entriesField.getModifiers())) throw new NoSuchFieldException("Unexpected player-info entries field");
            }
            playerInfoPacket = packetConstructor; playerInfoEntries = entriesField;
            Class<?> dataAccessor = type("net.minecraft.network.syncher.EntityDataAccessor");
            dataValueCreate = type("net.minecraft.network.syncher.SynchedEntityData$DataValue").getMethod("create", dataAccessor, Object.class);
            playerSkinAccessor = field(type("net.minecraft.world.entity.Avatar"), "DATA_PLAYER_MODE_CUSTOMISATION").get(null);
            metadataPacket = type("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);
            String craftPackage = plugin.getServer().getClass().getPackageName();
            if (!craftPackage.startsWith("org.bukkit.craftbukkit")) throw new ClassNotFoundException("CraftBukkit implementation unavailable");
            craftPlayerHandle = type(craftPackage + ".entity.CraftPlayer").getMethod("getHandle");
            EquipmentBindings boundEquipment = null;
            try { boundEquipment = new EquipmentBindings(craftPackage); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC equipment unavailable: " + missing.getClass().getSimpleName()); }
            equipment = boundEquipment;
            NativePresentation boundPresentation = null;
            try { boundPresentation = new NativePresentation(dataValueCreate); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC poses, text and long names unavailable: " + missing.getClass().getSimpleName()); }
            presentation = boundPresentation;
            textDisplayType = entityType("minecraft:text_display"); seatType = entityType("minecraft:armor_stand");
            playerConnection = type("net.minecraft.server.level.ServerPlayer").getField("connection");
            networkConnection = field(type("net.minecraft.server.network.ServerCommonPacketListenerImpl"), "connection");
            channel = field(type("net.minecraft.network.Connection"), "channel");
            attackPacket = type("net.minecraft.network.protocol.game.ServerboundAttackPacket");
            interactPacket = type("net.minecraft.network.protocol.game.ServerboundInteractPacket");
            attackId = attackPacket.getMethod("entityId"); interactId = interactPacket.getMethod("entityId");
            interactHand = interactPacket.getMethod("hand"); interactSecondary = interactPacket.getMethod("usingSecondaryAction");
            controlPacket = type("net.minecraft.network.protocol.game.ServerboundPlayerInputPacket");
            clientTickEndPacket = type("net.minecraft.network.protocol.game.ServerboundClientTickEndPacket");
            controlValue = controlPacket.getMethod("input");
            Class<?> controlInput = type("net.minecraft.world.entity.player.Input");
            String[] flagNames = {"forward", "backward", "left", "right", "jump", "shift", "sprint"};
            controlFlags = new Method[flagNames.length];
            for (int i = 0; i < flagNames.length; i++) controlFlags[i] = controlInput.getMethod(flagNames[i]);
            typeDimensions = entityType.getMethod("getDimensions");
            Class<?> dimensions = type("net.minecraft.world.entity.EntityDimensions");
            Class<?> attachments = type("net.minecraft.world.entity.EntityAttachments");
            Class<? extends Enum> attachment = (Class<? extends Enum>) type("net.minecraft.world.entity.EntityAttachment");
            dimensionAttachments = dimensions.getMethod("attachments");
            attachmentPoint = attachments.getMethod("getClamped", attachment, int.class, float.class);
            passengerAttachment = Enum.valueOf(attachment, "PASSENGER"); vehicleAttachment = Enum.valueOf(attachment, "VEHICLE");
            vectorX = vec3.getMethod("x"); vectorY = vec3.getMethod("y"); vectorZ = vec3.getMethod("z");
            dragonPhaseAccessor = type("net.minecraft.world.entity.boss.enderdragon.EnderDragon").getField("DATA_PHASE").get(null);
            String missingRider = null;
            try {
                Class<?> serverPlayer = type("net.minecraft.server.level.ServerPlayer");
                riderSetPosition = type("net.minecraft.world.entity.Entity").getMethod("setPos", double.class, double.class, double.class);
                riderLevel = serverPlayer.getMethod("level");
                riderChunkSource = riderLevel.getReturnType().getMethod("getChunkSource");
                riderChunkMove = riderChunkSource.getReturnType().getMethod("move", serverPlayer);
                riderResetPosition = type("net.minecraft.server.network.ServerGamePacketListenerImpl").getMethod("resetPosition");
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                missingRider = "real riders are unavailable on this server build: " + unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage();
            }
            riderUnavailable = missingRider;
        }
        private SeatGeometry seat(Object type) throws ReflectiveOperationException {
            Object points = dimensionAttachments.invoke(typeDimensions.invoke(type));
            Object seat = attachmentPoint.invoke(points, passengerAttachment, 0, 0f), attached = attachmentPoint.invoke(points, vehicleAttachment, 0, 0f);
            return new SeatGeometry((double) vectorX.invoke(seat), (double) vectorY.invoke(seat), (double) vectorZ.invoke(seat), (double) vectorY.invoke(attached));
        }
        private Object dragonPhase(int id, int phase) throws ReflectiveOperationException {
            return metadataPacket.newInstance(id, List.of(dataValueCreate.invoke(null, dragonPhaseAccessor, Integer.valueOf(phase))));
        }
        private void placeRider(Player rider, double x, double y, double z) throws ReflectiveOperationException {
            Object handle = craftPlayerHandle.invoke(rider);
            riderSetPosition.invoke(handle, x, y, z);
            riderResetPosition.invoke(playerConnection.get(handle));
            riderChunkMove.invoke(riderChunkSource.invoke(riderLevel.invoke(handle)), handle);
        }
        private ControlInput controlInput(Object packet) throws ReflectiveOperationException {
            Object value = controlValue.invoke(packet);
            return new ControlInput((boolean) controlFlags[0].invoke(value), (boolean) controlFlags[1].invoke(value),
                    (boolean) controlFlags[2].invoke(value), (boolean) controlFlags[3].invoke(value),
                    (boolean) controlFlags[4].invoke(value), (boolean) controlFlags[5].invoke(value), (boolean) controlFlags[6].invoke(value));
        }
        private Object entityType(String key) throws ReflectiveOperationException {
            Object identifier = identifierParse.invoke(null, key);
            if (!(boolean) registryContains.invoke(entityRegistry, identifier)) throw new IllegalArgumentException("Native entity type is unavailable: " + key);
            return registryValue.invoke(entityRegistry, identifier);
        }
        private Channel channel(Player viewer) throws ReflectiveOperationException {
            Object handle = craftPlayerHandle.invoke(viewer);
            Object connection = networkConnection.get(playerConnection.get(handle));
            Channel result = (Channel) channel.get(connection);
            if (result == null || !result.isActive()) throw new IllegalStateException("Viewer is not connected");
            return result;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object playerInfo(UUID uuid, String name, BodySkin skin) throws ReflectiveOperationException {
            Object values = multimapCreate.invoke(null);
            if (skin != null) multimapPut.invoke(values, "textures", property.newInstance("textures", skin.textureValue(), skin.signature()));
            Object gameProfile = profile.newInstance(uuid, name, propertyMap.newInstance(values));
            Object entry = playerInfoEntry.newInstance(uuid, gameProfile, false, 0, survival, null, true, 0, null);
            EnumSet actions = EnumSet.noneOf(playerInfoAction);
            for (String action : List.of("ADD_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED", "UPDATE_LATENCY", "UPDATE_DISPLAY_NAME", "UPDATE_HAT", "UPDATE_LIST_ORDER"))
                actions.add(Enum.valueOf(playerInfoAction, action));
            if (playerInfoEntries == null) return playerInfoPacket.newInstance(actions, List.of(entry));
            Object packet = playerInfoPacket.newInstance(actions, Collections.emptyList());
            playerInfoEntries.set(packet, List.of(entry));
            return packet;
        }
        private Object spawn(Body body, BodyPose pose) throws ReflectiveOperationException {
            float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
            return addEntity.newInstance(body.entityId, body.uuid, pose.x(), pose.y(), pose.z(), pose.pitch(), yaw, body.nativeType, 0, zeroVelocity, (double) yaw);
        }
        /** Every packet that presents a body, in the order the client must receive them. */
        private List<Object> spawnPackets(Body body, BodyPose pose) throws ReflectiveOperationException {
            List<Object> packets = new ArrayList<>();
            BodyLook look = body.look;
            if (body.playerInfo != null) packets.add(body.playerInfo);
            if (body.nameTeam != null) packets.add(body.nameTeam[0]);
            float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
            if (body.seatId >= 0) {
                packets.add(addEntity.newInstance(body.seatId, body.seatUuid, pose.x(), pose.y(), pose.z(), 0f, yaw, seatType, 0, zeroVelocity, (double) yaw));
                packets.add(metadataPacket.newInstance(body.seatId, presentation.seatValues()));
            }
            packets.add(spawn(body, pose));
            packets.add(rotateHead(body.entityId, yaw));
            List<Object> values = new ArrayList<>();
            if (body.playerInfo != null) values.add(dataValueCreate.invoke(null, playerSkinAccessor, (byte) 0x7f));
            if (presentation != null) values.addAll(presentation.bodyValues(look));
            if (!values.isEmpty()) packets.add(metadataPacket.newInstance(body.entityId, values));
            if (body.displayId >= 0) {
                packets.add(addEntity.newInstance(body.displayId, body.displayUuid, pose.x(), pose.y() + body.height, pose.z(), 0f, 0f, textDisplayType, 0, zeroVelocity, 0d));
                double lift = BodyLook.lift(body.height, body.seat.seatY(), look.aboveNativeNameplate());
                packets.add(metadataPacket.newInstance(body.displayId, presentation.textValues(look.text().orElseThrow(), lift)));
                packets.add(passengers(body.entityId, new int[]{body.displayId}));
            }
            if (body.seatId >= 0) packets.add(passengers(body.seatId, new int[]{body.entityId}));
            return packets;
        }
        private Object seatMove(Body body, BodyPose pose) throws ReflectiveOperationException {
            Object position = vector.newInstance(pose.x(), pose.y(), pose.z());
            float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
            if (steppedMovement) return movement.newInstance(body.seatId, positionPath.invoke(null, position), yaw, 0f, pose.onGround());
            return movement.newInstance(body.seatId, positionValues.newInstance(position, zeroVelocity, yaw, 0f), pose.onGround());
        }
        private Object move(Body body, BodyPose pose) throws ReflectiveOperationException {
            Object position = vector.newInstance(pose.x(), pose.y(), pose.z());
            float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
            if (steppedMovement) return movement.newInstance(body.entityId, positionPath.invoke(null, position), yaw, pose.pitch(), pose.onGround());
            return movement.newInstance(body.entityId, positionValues.newInstance(position, zeroVelocity, yaw, pose.pitch()), pose.onGround());
        }
        private Object rotateHead(int id, float yaw) throws ReflectiveOperationException {
            ByteBuf bytes = Unpooled.buffer(8);
            try {
                Object buffer = friendlyBuffer.newInstance(bytes);
                writeVarInt.invoke(buffer, id);
                bytes.writeByte((byte) (int) Math.floor((yaw % 360.0) * 256.0 / 360.0));
                return rotateHead.newInstance(buffer);
            } finally { bytes.release(); }
        }
        private Object passengers(int vehicle, int[] passengers) throws ReflectiveOperationException {
            ByteBuf bytes = Unpooled.buffer(128);
            try {
                Object buffer = friendlyBuffer.newInstance(bytes);
                writeVarInt.invoke(buffer, vehicle);
                writeVarIntArray.invoke(buffer, (Object) passengers);
                return setPassengers.newInstance(buffer);
            } finally { bytes.release(); }
        }
        private static Constructor<?> decodedPacketConstructor(String className, Class<?> buffer) throws ReflectiveOperationException {
            Constructor<?> constructor = type(className).getDeclaredConstructor(buffer);
            if (!constructor.trySetAccessible()) throw new NoSuchMethodException("Cannot access explicit packet decoder " + className);
            return constructor;
        }
        private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field result = owner.getDeclaredField(name);
            if (!result.trySetAccessible()) throw new NoSuchFieldException("Cannot access " + owner.getName() + "." + name);
            return result;
        }
    }
}
