package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.presentation.NativeEntityIds;
import art.arcane.animus.core.presentation.PassengerGraph;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BoneDisplay;
import art.arcane.animus.core.rig.DisplayTransform;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.control.ControlInput;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.virtual.DragonBody;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Load;
import art.arcane.animus.core.metrics.PacketKind;
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
        private final int seatId;
        private final int[] displayIds;
        private final UUID[] displayUuids;
        private final UUID seatUuid = UUID.randomUUID();
        private final Object[] nameTeam;
        /** This body's traffic, bound on its first write so probe bodies never register. */
        private volatile Load traffic;
        private Body(NativePacketAdapter owner, int entityId, int baseIds, UUID uuid, String typeKey, double width, double height,
                     SeatGeometry seat, Object nativeType, Object playerInfo, BodyLook look, Object[] nameTeam) {
            this.owner = owner; this.entityId = entityId; this.reservedIds = baseIds + look.extraIds(); this.uuid = uuid;
            this.typeKey = typeKey; this.width = width; this.height = height; this.seat = seat; this.nativeType = nativeType; this.playerInfo = playerInfo;
            this.look = look; this.nameTeam = nameTeam;
            displayIds = new int[look.rows().size()];
            displayUuids = new UUID[displayIds.length];
            for (int row = 0; row < displayIds.length; row++) { displayIds[row] = entityId + baseIds + row; displayUuids[row] = UUID.randomUUID(); }
            seatId = look.seated() ? entityId + baseIds + displayIds.length : -1;
        }
        public int entityId() { return entityId; }
        public UUID uuid() { return uuid; }
        public String typeKey() { return typeKey; }
        public double width() { return width; }
        public double height() { return height; }
        public SeatGeometry seat() { return seat; }
        public int[] entityIds() { return java.util.stream.IntStream.range(0, reservedIds).map(offset -> entityId + offset).toArray(); }
        private Load traffic() {
            Load bound = traffic;
            if (bound == null) traffic = bound = owner.metrics.npc(uuid);
            return bound;
        }
    }
    /** A rig's display bones on one body. IDs come from the server allocator in blocks of at most 16. */
    public static final class Bones implements BodyTransport.Bones {
        private final NativePacketAdapter owner;
        private final Body body;
        private final int[] ids;
        private final UUID[] uuids;
        private final Object[] types;
        private final List<List<Object>> content;
        private Bones(NativePacketAdapter owner, Body body, int[] ids, Object[] types, List<List<Object>> content) {
            this.owner = owner; this.body = body; this.ids = ids; this.types = types; this.content = List.copyOf(content);
            uuids = new UUID[ids.length];
            for (int i = 0; i < ids.length; i++) uuids[i] = UUID.randomUUID();
        }
        @Override public int count() { return ids.length; }
        @Override public int[] entityIds() { return ids.clone(); }
    }
    private final Plugin plugin;
    private final NativeBindings nativeApi;
    private final Capabilities capabilities;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Bones a viewer may see at once across every body; the runtime's {@code [rigs] maximumBones} is lower by default. */
    static final int MAX_BONES_PER_VIEWER = 4096;
    private final String handlerName = "animus_virtual_" + UUID.randomUUID().toString().replace("-", "");
    private final String riderUnavailable;
    private final Scheduler owner;
    private final AnimusMetrics metrics = AnimusMetrics.current();
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
        if (nativeApi.bones != null) operations.add("rig-bones");
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
        if (session == null || session.viewer != viewer || !current(session)) throw new BodyTransport.ViewerUnwatched("Viewer is not watched");
        ControlSubscription subscription = new ControlSubscription(session);
        if (!session.control.compareAndSet(null, subscription)) throw new IllegalStateException("Viewer already has a control subscription");
        if (!current(session)) { subscription.close(); throw new BodyTransport.ViewerUnwatched("Viewer session retired"); }
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
        Object playerInfo = typeKey.equals("minecraft:player") ? nativeApi.playerInfo(uuid, look.profileName(), skin, look.traits().listed()) : null;
        int entityId = nativeApi.ids.reserve(reserved + look.extraIds());
        Object[] team = look.team() && nativeApi.presentation != null
                ? nativeApi.presentation.bodyTeam(entityId, typeKey.equals("minecraft:player") ? look.profileName() : uuid.toString(), look) : null;
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
        Session session = new Session(viewer, channel, scheduler, interaction, metrics.viewer(viewer.getUniqueId(), viewer.getName()));
        Session previous = sessions.put(viewer.getUniqueId(), session);
        if (previous != null) retire(previous);
        try {
            channel.eventLoop().execute(() -> {
                if (!current(session) || !channel.isActive()) { session.ready.completeExceptionally(new BodyTransport.ViewerUnwatched("Viewer session retired")); return; }
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
            write(session, body, PacketKind.SPAWN, packets).whenComplete((nothing, failure) -> {
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
                    result.completeExceptionally(failure != null ? failure : new BodyTransport.ViewerUnwatched("Viewer retired during spawn"));
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
            write(session, body, PacketKind.MOVE, packets).whenComplete((nothing, failure) -> complete(result, failure));
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
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
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
            int[] roster = BodyRules.withText(BodyRules.passengerRoster(passengerIds, realRiderId), vehicle.displayIds);
            Object packet;
            synchronized (session) {
                for (Presentation presentation : presentations)
                    if (session.visible.get(presentation.body.entityId) != presentation) throw new CancellationException("Mount presentation retired");
                session.passengers.replace(vehicle.entityId, passengerIds);
                session.rosters.put(vehicle.entityId, roster);
                packet = nativeApi.passengers(vehicle.entityId, withBones(roster, session.bones.get(vehicle.entityId)));
            }
            write(session, vehicle, PacketKind.PASSENGERS, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** Sets the dragon's synced phase. Clients derive its wing motion and head pose from this. */
    @Override public CompletionStage<Void> dragonPhase(Player viewer, BodyTransport.Body presented, int phase) {
        Body dragon = checkBody(presented);
        if (!dragon.typeKey.equals(DragonBody.TYPE)) return failed("Phase applies only to dragon bodies");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(dragon.entityId);
        if (presentation == null || presentation.body != dragon) return failed("Dragon is not visible to this viewer");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(dragon.entityId) != presentation) throw new CancellationException("Dragon was despawned");
            write(session, dragon, PacketKind.METADATA, List.of(nativeApi.dragonPhase(dragon.entityId, phase))).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** The main-arm swing a player shows when placing a block. */
    @Override public CompletionStage<Void> swing(Player viewer, BodyTransport.Body presented) {
        Body body = checkBody(presented);
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, List.of(nativeApi.swing(body.entityId))).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** One metadata packet with the body's shared flags, pose and item use from {@code look}; nothing without presentation support. */
    @Override public CompletionStage<Void> look(Player viewer, BodyTransport.Body presented, BodyLook look) {
        Body body = checkBody(presented);
        Objects.requireNonNull(look);
        if (nativeApi.presentation == null) return CompletableFuture.completedFuture(null);
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        Object packet;
        try { packet = nativeApi.metadataPacket.newInstance(body.entityId, nativeApi.presentation.lookValues(look)); }
        catch (ReflectiveOperationException | RuntimeException failure) { return failed("Body look update failed: " + failure.getClass().getSimpleName()); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public boolean equipmentAvailable() { return nativeApi.equipment != null; }

    /** Viewer-local items on a visible body. An empty stack clears its slot. Call on the viewer's owner: stacks convert here. */
    @Override public CompletionStage<Void> equipment(Player viewer, BodyTransport.Body presented, Map<EquipmentSlot, ItemStack> items) {
        Body body = checkBody(presented);
        if (nativeApi.equipment == null) return failed("Equipment packets are unavailable on this server version");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        Object packet;
        try { packet = nativeApi.equipment.packet(body.entityId, items); }
        catch (ReflectiveOperationException | RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned before equipment");
            write(session, body, PacketKind.EQUIPMENT, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public CompletionStage<Void> attributes(Player viewer, BodyTransport.Body presented, NpcAttributes values) {
        Body body = checkBody(presented);
        if (nativeApi.attributes == null) return CompletableFuture.completedFuture(null);
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        Object packet;
        try { packet = nativeApi.attributes.packet(body.entityId, body.nativeType, values); }
        catch (ReflectiveOperationException | RuntimeException failure) { return failed("Body attribute update failed: " + failure.getClass().getSimpleName()); }
        if (packet == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned before its attributes");
            write(session, body, PacketKind.METADATA, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public boolean bonesAvailable() { return nativeApi.bones != null; }

    @Override public Bones createBones(BodyTransport.Body presented, List<BoneDisplay<ItemStack>> displays) {
        Body body = checkBody(presented);
        if (nativeApi.bones == null) throw new IllegalStateException("Rig display bones are unavailable on this server build");
        if (displays.isEmpty() || displays.size() > art.arcane.animus.core.rig.NpcRig.MAX_DISPLAYS) throw new IllegalArgumentException("A rig shows 1.." + art.arcane.animus.core.rig.NpcRig.MAX_DISPLAYS + " displays");
        List<List<Object>> content = new ArrayList<>(displays.size());
        Object[] types = new Object[displays.size()];
        try {
            for (int i = 0; i < displays.size(); i++) { content.add(nativeApi.bones.content(displays.get(i))); types[i] = nativeApi.bones.type(displays.get(i)); }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot build rig bones: " + failure.getClass().getSimpleName(), failure); }
        int[] ids = new int[displays.size()];
        for (int start = 0; start < ids.length; start += 16) {
            int count = Math.min(16, ids.length - start), first = nativeApi.ids.reserve(count);
            for (int i = 0; i < count; i++) ids[start + i] = first + i;
        }
        return new Bones(this, body, ids, types, content);
    }

    @Override public CompletionStage<Void> showBones(Player viewer, BodyTransport.Body presented, BodyTransport.Bones created, BodyPose pose, List<DisplayTransform> transforms, boolean hideBody) {
        Body body = checkBody(presented);
        Bones bones = checkBones(created, body);
        if (transforms.size() != bones.ids.length) return failed("Every bone needs a transform");
        Session session = session(viewer, pose);
        Presentation presentation;
        synchronized (session) {
            presentation = session.visible.get(body.entityId);
            if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
            if (session.bones.values().stream().mapToInt(value -> value.ids.length).sum() + bones.ids.length > MAX_BONES_PER_VIEWER) return failed("Viewer rig bone limit reached");
            if (session.bones.putIfAbsent(body.entityId, bones) != null) return failed("The body already shows bones");
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation || session.bones.get(body.entityId) != bones) throw new CancellationException("Body was despawned before its bones");
            List<Object> packets = new ArrayList<>(bones.ids.length * 2 + 2);
            for (int i = 0; i < bones.ids.length; i++) {
                packets.add(nativeApi.addEntity.newInstance(bones.ids[i], bones.uuids[i], pose.x(), pose.y(), pose.z(), 0f, 0f, bones.types[i], 0, nativeApi.zeroVelocity, 0d));
                List<Object> values = new ArrayList<>(bones.content.get(i));
                values.addAll(nativeApi.bones.transform(transforms.get(i), 0));
                packets.add(nativeApi.metadataPacket.newInstance(bones.ids[i], values));
            }
            int[] base = session.rosters.getOrDefault(body.entityId, body.displayIds.clone());
            packets.add(nativeApi.passengers(body.entityId, withBones(base, bones)));
            if (hideBody && nativeApi.presentation != null) packets.add(nativeApi.metadataPacket.newInstance(body.entityId, List.of(nativeApi.presentation.visibility(body.look, true))));
            write(session, body, PacketKind.RIG_BONES, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        result.whenComplete((nothing, failure) -> { if (failure != null) session.bones.remove(body.entityId, bones); });
        return result;
    }

    @Override public CompletionStage<Void> moveBones(Player viewer, BodyTransport.Bones moved, int[] indices, DisplayTransform[] transforms, int[] durations) {
        Bones bones = checkBones(moved, null);
        if (indices.length != transforms.length || indices.length != durations.length) return failed("Bone updates need a transform and duration each");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        if (session.bones.get(bones.body.entityId) != bones) return failed("Bones are not shown to this viewer");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.bones.get(bones.body.entityId) != bones) throw new CancellationException("Bones were removed");
            List<Object> packets = new ArrayList<>(indices.length);
            for (int i = 0; i < indices.length; i++) {
                if (indices[i] < 0 || indices[i] >= bones.ids.length) throw new IllegalArgumentException("Unknown bone index " + indices[i]);
                packets.add(nativeApi.metadataPacket.newInstance(bones.ids[indices[i]], nativeApi.bones.transform(transforms[i], Math.max(0, durations[i]))));
            }
            write(session, bones.body, PacketKind.RIG_BONES, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public CompletionStage<Void> restyleBones(Player viewer, BodyTransport.Bones restyled, int[] indices, List<BoneDisplay<ItemStack>> displays) {
        Bones bones = checkBones(restyled, null);
        if (indices.length != displays.size()) return failed("Every restyled bone needs a display");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        if (session.bones.get(bones.body.entityId) != bones) return failed("Bones are not shown to this viewer");
        List<Object> packets = new ArrayList<>(indices.length);
        try {
            for (int i = 0; i < indices.length; i++) {
                if (indices[i] < 0 || indices[i] >= bones.ids.length) return failed("Unknown bone index " + indices[i]);
                if (nativeApi.bones.type(displays.get(i)) != bones.types[indices[i]]) return failed("A restyled bone keeps its kind");
                packets.add(nativeApi.metadataPacket.newInstance(bones.ids[indices[i]], nativeApi.bones.content(displays.get(i))));
            }
        } catch (ReflectiveOperationException failure) { return failed("Cannot build rig bones: " + failure.getClass().getSimpleName()); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.bones.get(bones.body.entityId) != bones) throw new CancellationException("Bones were removed");
            write(session, bones.body, PacketKind.RIG_BONES, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public CompletionStage<Void> hideBones(Player viewer, BodyTransport.Bones shown) {
        Bones bones = checkBones(shown, null);
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !session.bones.remove(bones.body.entityId, bones)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, true, () -> {
            List<Object> packets = new ArrayList<>(3);
            packets.add(nativeApi.removeEntities.newInstance((Object) bones.ids.clone()));
            Presentation presentation = session.visible.get(bones.body.entityId);
            if (presentation != null && presentation.body == bones.body) {
                int[] base = session.rosters.getOrDefault(bones.body.entityId, bones.body.displayIds.clone());
                packets.add(nativeApi.passengers(bones.body.entityId, base));
                if (nativeApi.presentation != null) packets.add(nativeApi.metadataPacket.newInstance(bones.body.entityId, List.of(nativeApi.presentation.visibility(bones.body.look, false))));
            }
            write(session, bones.body, PacketKind.RIG_BONES, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    private Bones checkBones(BodyTransport.Bones presented, Body body) {
        ensureOpen();
        if (!(presented instanceof Bones bones) || bones.owner != this || (body != null && bones.body != body)) throw new IllegalArgumentException("Bones belong to another body or adapter");
        return bones;
    }
    private static int[] withBones(int[] roster, Bones bones) {
        if (bones == null) return roster;
        int[] result = Arrays.copyOf(roster, roster.length + bones.ids.length);
        System.arraycopy(bones.ids, 0, result, roster.length, bones.ids.length);
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
        Bones attached;
        synchronized (session) {
            Presentation visible = session.visible.get(body.entityId);
            Presentation spawning = session.spawning.get(body.entityId);
            present = (visible != null && visible.body == body) || (spawning != null && spawning.body == body);
            for (int id : body.entityIds()) session.visible.computeIfPresent(id, (key, value) -> value.body == body ? null : value);
            session.spawning.computeIfPresent(body.entityId, (key, value) -> value.body == body ? null : value);
            session.passengers.remove(body.entityId);
            session.rosters.remove(body.entityId);
            Bones before = session.bones.get(body.entityId);
            attached = before != null && before.body == body && session.bones.remove(body.entityId, before) ? before : null;
        }
        if (!present) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bones removed = attached;
        try { session.channel.eventLoop().execute(() -> removePackets(session, body, removed).whenComplete((nothing, failure) -> complete(result, failure))); }
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
        if (session == null || session.viewer != viewer || !current(session)) throw new BodyTransport.ViewerUnwatched("Viewer must be watched before sending body packets");
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
    /** The viewer's watch has ended or was never installed: the work is dropped, not sent. */
    private static CompletionStage<Void> unwatched(String reason) { return CompletableFuture.failedFuture(new BodyTransport.ViewerUnwatched(reason)); }
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
                    if (!current(session)) { result.completeExceptionally(new BodyTransport.ViewerUnwatched("Viewer session retired")); return; }
                    try { operation.run(); }
                    catch (ReflectiveOperationException | RuntimeException e) { result.completeExceptionally(e); }
                });
            } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        });
    }

    private CompletionStage<Void> write(Session session, Body body, PacketKind kind, List<Object> packets) {
        if (!session.channel.isActive()) return unwatched("Viewer connection is closed");
        metrics.sent(session.traffic, body == null ? null : body.traffic(), kind, packets.size());
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
    private CompletionStage<Void> removePackets(Session session, Body body) { return removePackets(session, body, null); }
    private CompletionStage<Void> removePackets(Session session, Body body, Bones bones) {
        try {
            List<Object> packets = new ArrayList<>();
            int[] ids = body.entityIds();
            if (bones != null) {
                int[] all = Arrays.copyOf(ids, ids.length + bones.ids.length);
                System.arraycopy(bones.ids, 0, all, ids.length, bones.ids.length);
                ids = all;
            }
            packets.add(nativeApi.removeEntities.newInstance((Object) ids));
            if (body.playerInfo != null) packets.add(nativeApi.removePlayerInfo.newInstance(List.of(body.uuid)));
            if (body.nameTeam != null) packets.add(body.nameTeam[1]);
            return write(session, body, PacketKind.DESPAWN, packets);
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
    private void retire(Session session) { retire(session, "session-retired"); }
    private void retire(Session session, String reason) {
        Set<Body> bodies = new HashSet<>();
        Map<Integer, Bones> retiredBones = new HashMap<>();
        synchronized (session) {
            if (!session.active.compareAndSet(true, false)) return;
            ControlSubscription control = session.control.getAndSet(null);
            if (control != null) control.inbox.close(reason);
            session.visible.values().forEach(value -> bodies.add(value.body));
            session.spawning.values().forEach(value -> bodies.add(value.body));
            for (Bones attached : session.bones.values()) if (attached.body != null) bodies.add(attached.body);
            retiredBones.putAll(session.bones);
            session.visible.clear(); session.spawning.clear(); session.passengers.clear(); session.bones.clear(); session.rosters.clear();
        }
        session.ready.completeExceptionally(new BodyTransport.ViewerUnwatched("Viewer session retired"));
        try {
            session.channel.eventLoop().execute(() -> {
                for (Body body : bodies) {
                    Bones attached = retiredBones.get(body.entityId);
                    removePackets(session, body, attached != null && attached.body == body ? attached : null);
                }
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
                if (now - session.lastInputNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS || !session.inputScheduled.compareAndSet(false, true)) { metrics.inbound(session.traffic, false); return; }
                session.lastInputNanos = now;
                metrics.inbound(session.traffic, true);
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
            if (sessions.remove(session.viewerId, session)) retire(session, ControlInbox.CONNECTION_CLOSED);
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
        /** Bones riding each vehicle, and the last roster sent without them; every roster write appends the bones. */
        private final ConcurrentMap<Integer, Bones> bones = new ConcurrentHashMap<>();
        private final ConcurrentMap<Integer, int[]> rosters = new ConcurrentHashMap<>();
        private final AtomicReference<ControlSubscription> control = new AtomicReference<>();
        private final Load traffic;
        private long lastInputNanos = Long.MIN_VALUE / 2; // Accessed exclusively by the channel event loop.
        private Session(Player viewer, Channel channel, Scheduler scheduler, Consumer<Interaction> interactions, Load traffic) {
            this.viewerId = viewer.getUniqueId(); this.viewer = viewer; this.channel = channel; this.scheduler = scheduler; this.interactions = interactions;
            this.traffic = traffic;
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
        private final NativeBones bones;
        private final NativeAttributes attributes;
        private final NativeBodyVariants variants;
        private final Object textDisplayType, seatType;
        private final NativeEntityIds ids;
        private final Object entityRegistry, zeroVelocity, survival, playerSkinAccessor;
        private final Method identifierParse, registryContains, registryValue, typeWidth, typeHeight;
        private final Constructor<?> addEntity, removeEntities, removePlayerInfo, vector, movement, positionValues;
        private final Method positionPath;
        private final Constructor<?> friendlyBuffer, rotateHead, setPassengers, animate;
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
            animate = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundAnimatePacket", friendly);
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
            NativeBones boundBones = null;
            try { boundBones = new NativeBones(dataValueCreate, craftPackage, entityType("minecraft:item_display"), entityType("minecraft:block_display"), textDisplayType, presentation); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC rig bones unavailable: " + missing.getClass().getSimpleName()); }
            bones = boundBones;
            NativeAttributes boundAttributes = null;
            try { boundAttributes = new NativeAttributes(identifierParse); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC attributes unavailable: " + missing.getClass().getSimpleName()); }
            attributes = boundAttributes;
            NativeBodyVariants boundVariants = null;
            try { boundVariants = new NativeBodyVariants(dataValueCreate, plugin.getServer().getClass().getMethod("getServer").invoke(plugin.getServer())); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC horse, llama, frog and armadillo looks unavailable: " + missing.getClass().getSimpleName()); }
            variants = boundVariants;
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
            if (result == null || !result.isActive()) throw new BodyTransport.ViewerUnwatched("Viewer is not connected");
            return result;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object playerInfo(UUID uuid, String name, BodySkin skin, boolean listed) throws ReflectiveOperationException {
            Object values = multimapCreate.invoke(null);
            if (skin != null) multimapPut.invoke(values, "textures", property.newInstance("textures", skin.textureValue(), skin.signature()));
            Object gameProfile = profile.newInstance(uuid, name, propertyMap.newInstance(values));
            Object entry = playerInfoEntry.newInstance(uuid, gameProfile, listed, 0, survival, null, true, 0, null);
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
            if (presentation != null) values.addAll(presentation.bodyValues(look, body.typeKey));
            if (variants != null) values.addAll(variants.values(body.typeKey, look.traits().variant()));
            if (!values.isEmpty()) packets.add(metadataPacket.newInstance(body.entityId, values));
            Object attributed = attributes == null ? null : attributes.packet(body.entityId, body.nativeType, look.traits().attributes());
            if (attributed != null) packets.add(attributed);
            if (body.displayIds.length > 0) {
                double lift = BodyLook.lift(body.height, body.seat.seatY(), look.aboveNativeNameplate(), look.traits().attributes().scale());
                for (int row = 0; row < body.displayIds.length; row++) {
                    BodyLook.TextRow text = look.rows().get(row);
                    packets.add(addEntity.newInstance(body.displayIds[row], body.displayUuids[row], pose.x(), pose.y() + body.height, pose.z(), 0f, 0f, textDisplayType, 0, zeroVelocity, 0d));
                    packets.add(metadataPacket.newInstance(body.displayIds[row], presentation.textValues(text.text(), lift + text.offset(), look.style())));
                }
                packets.add(passengers(body.entityId, body.displayIds.clone()));
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
        /** Animation 0 swings the main arm. */
        private Object swing(int id) throws ReflectiveOperationException {
            ByteBuf bytes = Unpooled.buffer(8);
            try {
                Object buffer = friendlyBuffer.newInstance(bytes);
                writeVarInt.invoke(buffer, id);
                bytes.writeByte(0);
                return animate.newInstance(buffer);
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
