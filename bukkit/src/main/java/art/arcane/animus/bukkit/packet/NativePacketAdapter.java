package art.arcane.animus.bukkit.packet;

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
import art.arcane.animus.core.presentation.VariantWire;
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

import static art.arcane.animus.bukkit.packet.NativeBindings.field;
import static art.arcane.animus.bukkit.packet.NativeBindings.type;

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
    private final NativeBindings shared;
    private final BodyBindings nativeApi;
    private final Capabilities capabilities;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Bones a viewer may see at once across every body; the runtime's {@code [rigs] maximumBones} is lower by default. */
    static final int MAX_BONES_PER_VIEWER = 4096;
    private final String riderUnavailable;
    private final Scheduler owner;
    private final AnimusMetrics metrics = AnimusMetrics.current();
    private volatile boolean closed;
    /** Every adapter on this server shares one set of body bindings. */
    private static BodyBindings boundBodies;

    private NativePacketAdapter(Plugin plugin, String version, Scheduler owner) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin);
        this.owner = owner;
        shared = NativeBindings.bind(plugin, version);
        nativeApi = bodies(plugin, shared);
        riderUnavailable = nativeApi.riderUnavailable;
        Set<String> operations = new HashSet<>(Set.of("spawn", "despawn", "move", "signed-player-textures", "attack-interception", "use-interception", "head-rotation", "passengers", "control-input", "dragon-presentation"));
        if (riderUnavailable == null) operations.add("real-rider");
        if (nativeApi.equipment != null) operations.add("equipment");
        if (nativeApi.presentation != null) operations.add("presentation");
        if (nativeApi.bones != null) operations.add("rig-bones");
        capabilities = new Capabilities(version, bodyTypes(shared), operations, "explicit-packets/no-native-entity-bodies; signature binding is not gameplay certification");
    }

    private static synchronized BodyBindings bodies(Plugin plugin, NativeBindings shared) throws ReflectiveOperationException {
        if (boundBodies == null || boundBodies.shared != shared) boundBodies = new BodyBindings(plugin, shared);
        return boundBodies;
    }

    /** The shared body types this server registers. */
    private static Set<String> bodyTypes(NativeBindings bindings) {
        Set<String> types = new HashSet<>();
        for (String key : BodyRules.TYPES) {
            try { bindings.entityType(key); types.add(key); }
            catch (ReflectiveOperationException | RuntimeException unavailable) { /* A type the server does not register is not offered. */ }
        }
        return types;
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
        if (!capabilities.entityTypes().contains(typeKey)) throw new IllegalArgumentException("Body type is not enabled by the proof adapter: " + typeKey);
        if (typeKey.equals("minecraft:player")) BodyRules.validateProfileName(look.profileName());
        if (!typeKey.equals("minecraft:player") && skin != null) throw new IllegalArgumentException("Skin textures apply only to player bodies");
        Object type = shared.entityType(typeKey);
        int reserved = BodyRules.reservedIds(typeKey);
        Object playerInfo = typeKey.equals("minecraft:player") ? nativeApi.playerInfo(uuid, look.profileName(), skin, look.traits().listed(), look.traits().variant().choices().getOrDefault("gameMode", "survival")) : null;
        int entityId = shared.ids.reserve(reserved + look.extraIds());
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
        Channel channel = shared.channel(viewer);
        Session session = new Session(viewer, channel, scheduler, interaction, metrics.viewer(viewer.getUniqueId(), viewer.getName()));
        Session previous = sessions.put(viewer.getUniqueId(), session);
        if (previous != null) retire(previous);
        try {
            channel.eventLoop().execute(() -> {
                if (!current(session) || !channel.isActive()) { session.ready.completeExceptionally(new BodyTransport.ViewerUnwatched("Viewer session retired")); return; }
                try {
                    ViewerPipeline.attach(channel, this, new InputHandler(session));
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
            packets.add(nativeApi.rotateHead(body.entityId, BodyRules.wireYaw(body.typeKey, pose.headYaw())));
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
            List<Object> packets = new ArrayList<>(2);
            synchronized (session) {
                for (Presentation presentation : presentations)
                    if (session.visible.get(presentation.body.entityId) != presentation) throw new CancellationException("Mount presentation retired");
                List<Integer> before = session.passengers.passengers(vehicle.entityId);
                session.passengers.replace(vehicle.entityId, passengerIds);
                session.rosters.put(vehicle.entityId, roster);
                packets.add(nativeApi.passengers(vehicle.entityId, withBones(roster, session.bones.get(vehicle.entityId))));
                reseat(session, before, passengerIds, packets);
            }
            write(session, vehicle, PacketKind.PASSENGERS, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    @Override public boolean carryAvailable() { return nativeApi.carried != null; }
    /** The server passenger lists this viewer receives for {@code entity} keep the extras; see {@link CarriedPackets}. */
    @Override public CompletionStage<Void> carry(Player viewer, int entity, List<Integer> own, List<? extends BodyTransport.Body> passengers, List<Integer> riders) {
        if (nativeApi.carried == null) return failed("Real entities cannot carry NPCs on this server build");
        List<Body> requested = passengers.stream().map(this::checkBody).toList();
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        List<Presentation> presentations = new ArrayList<>();
        synchronized (session) {
            if (session.visible.containsKey(entity)) return failed("A virtual body is not a real entity");
            for (Body passenger : requested) {
                Presentation rider = session.visible.get(passenger.entityId);
                if (rider == null || rider.body != passenger) return failed("Passenger is not visible to this viewer");
                presentations.add(rider);
            }
        }
        List<Integer> ownIds = List.copyOf(own), riderIds = List.copyOf(riders);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, true, () -> {
            List<Integer> bodyIds = requested.stream().map(Body::entityId).toList();
            int[] extras = new int[bodyIds.size() + riderIds.size()];
            for (int i = 0; i < bodyIds.size(); i++) extras[i] = bodyIds.get(i);
            for (int i = 0; i < riderIds.size(); i++) extras[bodyIds.size() + i] = riderIds.get(i);
            List<Object> packets = new ArrayList<>(2);
            synchronized (session) {
                for (Presentation presentation : presentations)
                    if (session.visible.get(presentation.body.entityId) != presentation) throw new CancellationException("Mount presentation retired");
                List<Integer> before = session.passengers.passengers(entity);
                session.passengers.replace(entity, bodyIds);
                if (extras.length == 0) session.carried.remove(entity); else session.carried.put(entity, extras);
                packets.add(nativeApi.passengers(entity, CarriedPackets.union(ownIds.stream().mapToInt(Integer::intValue).toArray(), extras)));
                reseat(session, before, bodyIds, packets);
            }
            write(session, null, PacketKind.PASSENGERS, packets).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }
    /** Bodies that left a vehicle sit on their own seat again. Session lock held. */
    private void reseat(Session session, List<Integer> before, List<Integer> after, List<Object> packets) throws ReflectiveOperationException {
        for (int id : before) {
            if (after.contains(id)) continue;
            Presentation left = session.visible.get(id);
            if (left != null && left.body.entityId == id && left.body.seatId >= 0) packets.add(nativeApi.passengers(left.body.seatId, new int[]{id}));
        }
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
    @Override public CompletionStage<Void> swing(Player viewer, BodyTransport.Body presented) { return gesture(viewer, presented, 0); }

    /** One melee attack: the main-arm swing, or the body type's own attack animation. */
    @Override public CompletionStage<Void> attack(Player viewer, BodyTransport.Body presented) { return animate(viewer, checkBody(presented), 0, true); }

    /** One vanilla gesture: an arm swing, waking up or a critical hit. */
    @Override public CompletionStage<Void> gesture(Player viewer, BodyTransport.Body presented, int action) { return animate(viewer, checkBody(presented), action, false); }
    /** Sends a gesture, or an attack, for a visible body; nothing when this server has no packet for it. */
    private CompletionStage<Void> animate(Player viewer, Body body, int code, boolean attack) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        Object packet;
        try {
            packet = attack && BodyRules.attacksWithEvent(body.typeKey) && nativeApi.entityEvent != null ? nativeApi.entityEvent(body.entityId, BodyRules.ATTACK_EVENT)
                    : nativeApi.gesture(body.entityId, code, presentation.mainHand, attack);
        } catch (ReflectiveOperationException | RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        if (packet == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** A damage event: the client flashes the body red and plays its own type's hurt sound. Nothing when unbound. */
    @Override public CompletionStage<Void> hurt(Player viewer, BodyTransport.Body presented) {
        return feedback(viewer, presented, "hurt", (int id) -> nativeApi.hurt(id));
    }
    /** The death event and zero health: the body falls over and plays its own type's death sound. */
    @Override public CompletionStage<Void> die(Player viewer, BodyTransport.Body presented) {
        boolean living = presented instanceof Body body && VariantWire.living(body.typeKey);
        return feedback(viewer, presented, "death", (int id) -> nativeApi.die(id, living));
    }
    @FunctionalInterface private interface Feedback { List<Object> packets(int id) throws ReflectiveOperationException; }
    private CompletionStage<Void> feedback(Player viewer, BodyTransport.Body presented, String label, Feedback feedback) {
        Body body = checkBody(presented);
        if (nativeApi.damageType == null) return CompletableFuture.completedFuture(null);
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        List<Object> packets;
        try { packets = feedback.packets(body.entityId); }
        catch (ReflectiveOperationException | RuntimeException failure) { return failed("Body " + label + " failed: " + failure.getClass().getSimpleName()); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, packets).whenComplete((nothing, failure) -> complete(result, failure));
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
        try { packet = shared.metadataPacket.newInstance(body.entityId, nativeApi.presentation.lookValues(look, body.typeKey)); }
        catch (ReflectiveOperationException | RuntimeException failure) { return failed("Body look update failed: " + failure.getClass().getSimpleName()); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** One metadata packet with the body's shared flags, invisible or as its look has them. */
    @Override public CompletionStage<Void> conceal(Player viewer, BodyTransport.Body presented, boolean hidden) {
        Body body = checkBody(presented);
        if (nativeApi.presentation == null) return failed("Bodies cannot be hidden on this server build");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        Object packet;
        try { packet = shared.metadataPacket.newInstance(body.entityId, List.of(nativeApi.presentation.visibility(body.look, hidden))); }
        catch (ReflectiveOperationException | RuntimeException failure) { return failed("Body visibility update failed: " + failure.getClass().getSimpleName()); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, true, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, List.of(packet)).whenComplete((nothing, failure) -> complete(result, failure));
        });
        return result;
    }

    /** New text on each of the body's text display rows; refused when the row count differs from the body's. */
    @Override public CompletionStage<Void> relabel(Player viewer, BodyTransport.Body presented, List<String> rows) {
        Body body = checkBody(presented);
        if (nativeApi.presentation == null || rows.size() != body.displayIds.length) return failed("Body text rows changed shape");
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || session.viewer != viewer || !current(session)) return unwatched("Viewer is not watched");
        Presentation presentation = session.visible.get(body.entityId);
        if (presentation == null || presentation.body != body) return failed("Body is not visible to this viewer");
        List<Object> packets = new ArrayList<>(rows.size());
        try { for (int row = 0; row < rows.size(); row++) packets.add(shared.metadataPacket.newInstance(body.displayIds[row], nativeApi.presentation.textValue(rows.get(row)))); }
        catch (ReflectiveOperationException | RuntimeException failure) { return failed("Body relabel failed: " + failure.getClass().getSimpleName()); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(session, result, false, () -> {
            if (session.visible.get(body.entityId) != presentation) throw new CancellationException("Body was despawned");
            write(session, body, PacketKind.METADATA, packets).whenComplete((nothing, failure) -> complete(result, failure));
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
        Map<EquipmentSlot, ItemStack> shown = BodyRules.shownEquipment(body.typeKey(), items);
        if (shown.isEmpty()) return CompletableFuture.completedFuture(null);
        Object packet;
        ItemStack held = shown.get(EquipmentSlot.MAIN_HAND);
        try {
            packet = nativeApi.equipment.packet(body.entityId, shown);
            if (held != null) presentation.mainHand = nativeApi.equipment.nativeItem(held);
        }
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
            int count = Math.min(16, ids.length - start), first = shared.ids.reserve(count);
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
                packets.add(shared.addEntity.newInstance(bones.ids[i], bones.uuids[i], pose.x(), pose.y(), pose.z(), 0f, 0f, bones.types[i], 0, shared.zeroVelocity, 0d));
                List<Object> values = new ArrayList<>(bones.content.get(i));
                values.addAll(nativeApi.bones.transform(transforms.get(i), 0));
                packets.add(shared.metadataPacket.newInstance(bones.ids[i], values));
            }
            int[] base = session.rosters.getOrDefault(body.entityId, body.displayIds.clone());
            packets.add(nativeApi.passengers(body.entityId, withBones(base, bones)));
            if (hideBody && nativeApi.presentation != null) packets.add(shared.metadataPacket.newInstance(body.entityId, List.of(nativeApi.presentation.visibility(body.look, true))));
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
                packets.add(shared.metadataPacket.newInstance(bones.ids[indices[i]], nativeApi.bones.transform(transforms[i], Math.max(0, durations[i]))));
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
                packets.add(shared.metadataPacket.newInstance(bones.ids[indices[i]], nativeApi.bones.content(displays.get(i))));
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
            packets.add(shared.removeEntities.newInstance((Object) bones.ids.clone()));
            Presentation presentation = session.visible.get(bones.body.entityId);
            if (presentation != null && presentation.body == bones.body) {
                int[] base = session.rosters.getOrDefault(bones.body.entityId, bones.body.displayIds.clone());
                packets.add(nativeApi.passengers(bones.body.entityId, base));
                if (nativeApi.presentation != null) packets.add(shared.metadataPacket.newInstance(bones.body.entityId, List.of(nativeApi.presentation.visibility(bones.body.look, false))));
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

    /**
     * Moves a server entity to this position without a teleport, so the entity stays in the world and mobs keep it as
     * their target. Folia allows only teleportAsync through Bukkit, which removes and re-adds the entity. Owner thread of
     * the entity, and of the chunk it moves into.
     */
    public void place(org.bukkit.entity.Entity entity, double x, double y, double z) {
        try { nativeApi.entitySetPosition.invoke(nativeApi.craftEntityHandle.invoke(entity), x, y, z); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot move " + entity.getType() + ": " + failure.getClass().getSimpleName(), failure); }
    }
    /**
     * Where a real server entity carries its passenger at {@code index}, at its current size and pose, and where a body of
     * its kind would attach as a passenger, in body-local coordinates. Owner thread of the entity.
     */
    public SeatGeometry seatOf(org.bukkit.entity.Entity entity, int index) {
        try { return nativeApi.seatOf(entity, index); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read the seat of " + entity.getType() + ": " + failure.getClass().getSimpleName(), failure); }
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
            packets.add(shared.removeEntities.newInstance((Object) ids));
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
            session.visible.clear(); session.spawning.clear(); session.passengers.clear(); session.bones.clear(); session.rosters.clear(); session.carried.clear();
        }
        session.ready.completeExceptionally(new BodyTransport.ViewerUnwatched("Viewer session retired"));
        try {
            session.channel.eventLoop().execute(() -> {
                for (Body body : bodies) {
                    Bones attached = retiredBones.get(body.entityId);
                    removePackets(session, body, attached != null && attached.body == body ? attached : null);
                }
                ViewerPipeline.detach(session.channel, (ViewerPipeline.Tap tap) -> tap instanceof InputHandler input && input.session == session);
            });
        } catch (RejectedExecutionException ignored) { /* Closed channels have already discarded their client presentation. */ }
    }

    /** This adapter's tap on the viewer's connection: clicks on its bodies, control input, and carried passengers. */
    private final class InputHandler implements ViewerPipeline.Tap {
        private final Session session;
        private InputHandler(Session session) { this.session = session; }
        /** Passenger lists the server sends for an entity that carries NPCs or seated players for this viewer keep them. */
        @Override public Object write(Object message) {
            CarriedPackets carried = nativeApi.carried;
            if (carried == null || session.carried.isEmpty() || !current(session)) return message;
            try { return carried.rewrite(message, session.carried::get); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                reportInputFailure(session, new IllegalStateException("Cannot keep carried passengers", failure));
                return message;
            }
        }
        @Override public boolean read(Object message) throws Exception {
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
            boolean attack = shared.attackPacket.isInstance(message);
            if (!attack && !shared.interactPacket.isInstance(message)) return false;
            int entityId = ((Number) (attack ? shared.attackId : shared.interactId).invoke(message)).intValue();
            Presentation presentation = session.visible.get(entityId);
            if (presentation == null || !current(session)) return false;
            Body body = presentation.body;
            try {
                long now = System.nanoTime();
                if (now - session.lastInputNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS || !session.inputScheduled.compareAndSet(false, true)) { metrics.inbound(session.traffic, false); return true; }
                session.lastInputNanos = now;
                metrics.inbound(session.traffic, true);
                String hand = attack ? "none" : String.valueOf(shared.interactHand.invoke(message));
                boolean secondary = !attack && (boolean) shared.interactSecondary.invoke(message);
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
            return true;
        }
        @Override public void closed() {
            if (sessions.remove(session.viewerId, session)) retire(session, ControlInbox.CONNECTION_CLOSED);
        }
    }
    private void reportInputFailure(Session session, RuntimeException e) {
        if (session.reportedFailure.compareAndSet(false, true)) plugin.getLogger().warning("Virtual interaction callback failed: " + e.getClass().getSimpleName());
    }
    private static final class Presentation {
        private final Body body;
        /** The native main-hand item last shown to this viewer, whose animation the body's swings use; null before any. */
        private volatile Object mainHand;
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
        /** Real server entities that carry bodies or players for this viewer, and the IDs added after their own passengers. */
        private final ConcurrentMap<Integer, int[]> carried = new ConcurrentHashMap<>();
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
        private EquipmentBindings(NativeBindings shared) throws ReflectiveOperationException {
            packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket").getConstructor(int.class, List.class);
            pair = Class.forName("com.mojang.datafixers.util.Pair").getMethod("of", Object.class, Object.class);
            nativeStack = shared.nativeStack();
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

        private Object nativeItem(ItemStack item) throws ReflectiveOperationException { return nativeStack.invoke(null, item); }
    }

    /** What only bodies bind, on top of the shared handles; one set serves every adapter on the server. */
    private static final class BodyBindings {
        /** The animate packet's constant for each gesture code, by name; 26.3 drops the swings and renumbers the rest. */
        private static final String[] GESTURES = {"SWING_MAIN_HAND", null, "WAKE_UP", "SWING_OFF_HAND", "CRITICAL_HIT", "MAGIC_CRITICAL_HIT"};
        private final EquipmentBindings equipment;
        private final NativePresentation presentation;
        private final NativeBones bones;
        private final NativeAttributes attributes;
        private final art.arcane.animus.core.presentation.BodyVariantValues variants;
        /** Keeps carried passengers on the server's own passenger packets; null when this server's packets cannot be read. */
        private final CarriedPackets carried;
        private final NativeBindings shared;
        private final Object textDisplayType, seatType;
        private final Object survival, playerSkinAccessor;
        private final Method typeWidth, typeHeight;
        private final Constructor<?> removePlayerInfo, friendlyBuffer, rotateHead, setPassengers, animate, entityEvent, damageEvent;
        /** This server's animate action for each gesture code (the animate actions before 26.3); -1 for one it sends otherwise or not at all. */
        private final int[] animateActions;
        /**
         * From 26.3 an arm swing has its own packet, carrying the hand and the held item's swing animation as vanilla sends
         * it; null where the animate packet swings, or where this server's swing packet could not be bound.
         */
        private final Constructor<?> swingPacket;
        private final Object mainHand, offHand, defaultSwing;
        private final Method attackAnimation, interactAnimation;
        /** The generic damage type a hurt event names, or null when this server's registries could not be read. */
        private final Object damageType, healthAccessor;
        private final Method writeVarInt, writeVarIntArray;
        private final Constructor<?> profile, property, propertyMap, playerInfoEntry, playerInfoPacket;
        private final Method multimapCreate, multimapPut;
        private final Field playerInfoEntries;
        private final Class<? extends Enum> playerInfoAction;
        private final Method craftEntityHandle, entityDimensions, entityPose, entitySetPosition;
        private final Class<?> controlPacket, clientTickEndPacket;
        private final Method controlValue;
        private final Method[] controlFlags;
        private final Method typeDimensions, dimensionAttachments, attachmentPoint, vectorX, vectorY, vectorZ;
        private final Object passengerAttachment, vehicleAttachment, dragonPhaseAccessor;
        private final String riderUnavailable;
        private Method riderLevel, riderChunkSource, riderChunkMove, riderResetPosition;

        @SuppressWarnings("unchecked")
        private BodyBindings(Plugin plugin, NativeBindings shared) throws ReflectiveOperationException {
            this.shared = shared;
            Class<?> entityType = type("net.minecraft.world.entity.EntityType");
            typeWidth = entityType.getMethod("getWidth"); typeHeight = entityType.getMethod("getHeight");
            removePlayerInfo = type("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket").getConstructor(List.class);
            Class<?> friendly = type("net.minecraft.network.FriendlyByteBuf");
            friendlyBuffer = friendly.getConstructor(ByteBuf.class);
            writeVarInt = friendly.getMethod("writeVarInt", int.class);
            writeVarIntArray = friendly.getMethod("writeVarIntArray", int[].class);
            rotateHead = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket", friendly);
            setPassengers = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket", friendly);
            CarriedPackets boundCarried = null;
            try { boundCarried = new CarriedPackets(this::passengers); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPCs on real entities unavailable: " + missing.getClass().getSimpleName()); }
            carried = boundCarried;
            animate = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundAnimatePacket", friendly);
            Class<?> animatePacket = type("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
            animateActions = new int[GESTURES.length];
            for (int code = 0; code < GESTURES.length; code++) animateActions[code] = constant(animatePacket, GESTURES[code]);
            Constructor<?> boundSwing = null;
            Object boundMain = null, boundOff = null, boundDefault = null;
            Method boundAttack = null, boundInteract = null;
            if (animateActions[0] < 0) {
                try {
                    Class<?> hand = type("net.minecraft.world.InteractionHand"), animation = type("net.minecraft.world.item.component.SwingAnimation");
                    Class<?> stack = type("net.minecraft.world.item.ItemStack");
                    boundMain = Enum.valueOf((Class<Enum>) hand, "MAIN_HAND"); boundOff = Enum.valueOf((Class<Enum>) hand, "OFF_HAND");
                    boundDefault = animation.getField("DEFAULT").get(null);
                    boundAttack = stack.getMethod("getAttackAnimation"); boundInteract = stack.getMethod("getInteractAnimation");
                    boundSwing = type("net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket").getConstructor(int.class, hand, animation);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                    boundSwing = null;
                    plugin.getLogger().warning("NPC arm swings unavailable: " + missing.getClass().getSimpleName());
                }
            }
            swingPacket = boundSwing; mainHand = boundMain; offHand = boundOff; defaultSwing = boundDefault;
            attackAnimation = boundAttack; interactAnimation = boundInteract;
            Constructor<?> boundEvent = null, boundDamageEvent = null;
            Object boundDamage = null, boundHealth = null;
            try {
                Class<?> resourceKey = type("net.minecraft.resources.ResourceKey");
                boundEvent = decodedPacketConstructor("net.minecraft.network.protocol.game.ClientboundEntityEventPacket", friendly);
                boundDamageEvent = type("net.minecraft.network.protocol.game.ClientboundDamageEventPacket").getConstructor(int.class, type("net.minecraft.core.Holder"), int.class, int.class, Optional.class);
                boundHealth = field(type("net.minecraft.world.entity.LivingEntity"), "DATA_HEALTH_ID").get(null);
                Object server = plugin.getServer().getClass().getMethod("getServer").invoke(plugin.getServer());
                Object registries = server.getClass().getMethod("registryAccess").invoke(server);
                Object lookup = type("net.minecraft.core.HolderLookup$Provider").getMethod("lookupOrThrow", resourceKey)
                        .invoke(registries, type("net.minecraft.core.registries.Registries").getField("DAMAGE_TYPE").get(null));
                boundDamage = type("net.minecraft.core.HolderGetter").getMethod("getOrThrow", resourceKey)
                        .invoke(lookup, type("net.minecraft.world.damagesource.DamageTypes").getField("GENERIC").get(null));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                boundDamage = null;
                plugin.getLogger().warning("NPC hurt and death animations unavailable: " + missing.getClass().getSimpleName());
            }
            entityEvent = boundEvent; damageEvent = boundDamageEvent; damageType = boundDamage; healthAccessor = boundHealth;
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
            playerSkinAccessor = field(type("net.minecraft.world.entity.Avatar"), "DATA_PLAYER_MODE_CUSTOMISATION").get(null);
            String craftPackage = shared.craftPackage;
            craftEntityHandle = type(craftPackage + ".entity.CraftEntity").getMethod("getHandle");
            Class<?> entity = type("net.minecraft.world.entity.Entity");
            entityPose = entity.getMethod("getPose");
            entitySetPosition = entity.getMethod("setPos", double.class, double.class, double.class);
            entityDimensions = entity.getMethod("getDimensions", type("net.minecraft.world.entity.Pose"));
            EquipmentBindings boundEquipment = null;
            try { boundEquipment = new EquipmentBindings(shared); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC equipment unavailable: " + missing.getClass().getSimpleName()); }
            equipment = boundEquipment;
            NativePresentation boundPresentation = null;
            try { boundPresentation = new NativePresentation(shared.dataValue); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC poses, text and long names unavailable: " + missing.getClass().getSimpleName()); }
            presentation = boundPresentation;
            textDisplayType = shared.entityType("minecraft:text_display"); seatType = shared.entityType("minecraft:armor_stand");
            NativeBones boundBones = null;
            try { boundBones = new NativeBones(shared, shared.entityType("minecraft:item_display"), shared.entityType("minecraft:block_display"), textDisplayType, presentation); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC rig bones unavailable: " + missing.getClass().getSimpleName()); }
            bones = boundBones;
            NativeAttributes boundAttributes = null;
            try { boundAttributes = new NativeAttributes(shared.identifierParse); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC attributes unavailable: " + missing.getClass().getSimpleName()); }
            attributes = boundAttributes;
            art.arcane.animus.core.presentation.BodyVariantValues boundVariants = null;
            try {
                NativePresentation texts = presentation;
                Method nativeStack = shared.nativeStack();
                Method blockState = Class.forName(craftPackage + ".block.data.CraftBlockData").getMethod("getState");
                Method create = shared.dataValue;
                Object server = plugin.getServer().getClass().getMethod("getServer").invoke(plugin.getServer());
                boundVariants = new art.arcane.animus.core.presentation.BodyVariantValues(new art.arcane.animus.core.presentation.BodyVariantValues.Natives() {
                    @Override public Object value(Object accessor, Object value) throws ReflectiveOperationException { return create.invoke(null, accessor, value); }
                    @Override public Object item(art.arcane.animus.core.npc.NpcItem item) throws ReflectiveOperationException {
                        List<String> errors = new ArrayList<>();
                        ItemStack stack = art.arcane.animus.bukkit.runtime.DefinitionItems.stack(item, "$.item", errors);
                        return stack == null || !errors.isEmpty() ? null : nativeStack.invoke(null, stack);
                    }
                    @Override public Object blockState(String state) throws ReflectiveOperationException {
                        try { return blockState.invoke(org.bukkit.Bukkit.createBlockData(state)); }
                        catch (IllegalArgumentException unknown) { return null; }
                    }
                    @Override public Object component(String text) throws ReflectiveOperationException { return texts == null ? null : texts.component(text); }
                }, server.getClass().getMethod("registryAccess").invoke(server));
                boundVariants.report(plugin.getLogger()::warning);
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { plugin.getLogger().warning("NPC body settings unavailable: " + missing.getClass().getSimpleName()); }
            variants = boundVariants;
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
            Class<?> vec3 = type("net.minecraft.world.phys.Vec3");
            vectorX = vec3.getMethod("x"); vectorY = vec3.getMethod("y"); vectorZ = vec3.getMethod("z");
            dragonPhaseAccessor = type("net.minecraft.world.entity.boss.enderdragon.EnderDragon").getField("DATA_PHASE").get(null);
            String missingRider = null;
            try {
                Class<?> serverPlayer = type("net.minecraft.server.level.ServerPlayer");
                riderLevel = serverPlayer.getMethod("level");
                riderChunkSource = riderLevel.getReturnType().getMethod("getChunkSource");
                riderChunkMove = riderChunkSource.getReturnType().getMethod("move", serverPlayer);
                riderResetPosition = type("net.minecraft.server.network.ServerGamePacketListenerImpl").getMethod("resetPosition");
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                missingRider = "real riders are unavailable on this server build: " + unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage();
            }
            riderUnavailable = missingRider;
        }
        private SeatGeometry seatOf(org.bukkit.entity.Entity entity, int index) throws ReflectiveOperationException {
            Object handle = craftEntityHandle.invoke(entity);
            Object points = dimensionAttachments.invoke(entityDimensions.invoke(handle, entityPose.invoke(handle)));
            Object seat = attachmentPoint.invoke(points, passengerAttachment, index, 0f), attached = attachmentPoint.invoke(points, vehicleAttachment, 0, 0f);
            return new SeatGeometry((double) vectorX.invoke(seat), (double) vectorY.invoke(seat), (double) vectorZ.invoke(seat), (double) vectorY.invoke(attached));
        }
        private SeatGeometry seat(Object type) throws ReflectiveOperationException {
            Object points = dimensionAttachments.invoke(typeDimensions.invoke(type));
            Object seat = attachmentPoint.invoke(points, passengerAttachment, 0, 0f), attached = attachmentPoint.invoke(points, vehicleAttachment, 0, 0f);
            return new SeatGeometry((double) vectorX.invoke(seat), (double) vectorY.invoke(seat), (double) vectorZ.invoke(seat), (double) vectorY.invoke(attached));
        }
        private Object dragonPhase(int id, int phase) throws ReflectiveOperationException {
            return shared.metadataPacket.newInstance(id, List.of(shared.dataValue.invoke(null, dragonPhaseAccessor, Integer.valueOf(phase))));
        }
        private void placeRider(Player rider, double x, double y, double z) throws ReflectiveOperationException {
            Object handle = shared.craftPlayerHandle.invoke(rider);
            entitySetPosition.invoke(handle, x, y, z);
            riderResetPosition.invoke(shared.playerConnection.get(handle));
            riderChunkMove.invoke(riderChunkSource.invoke(riderLevel.invoke(handle)), handle);
        }
        private ControlInput controlInput(Object packet) throws ReflectiveOperationException {
            Object value = controlValue.invoke(packet);
            return new ControlInput((boolean) controlFlags[0].invoke(value), (boolean) controlFlags[1].invoke(value),
                    (boolean) controlFlags[2].invoke(value), (boolean) controlFlags[3].invoke(value),
                    (boolean) controlFlags[4].invoke(value), (boolean) controlFlags[5].invoke(value), (boolean) controlFlags[6].invoke(value));
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object playerInfo(UUID uuid, String name, BodySkin skin, boolean listed, String gameMode) throws ReflectiveOperationException {
            Object values = multimapCreate.invoke(null);
            if (skin != null) multimapPut.invoke(values, "textures", property.newInstance("textures", skin.textureValue(), skin.signature()));
            Object gameProfile = profile.newInstance(uuid, name, propertyMap.newInstance(values));
            Object entry = playerInfoEntry.newInstance(uuid, gameProfile, listed, 0, Enum.valueOf((Class<Enum>) survival.getClass(), gameMode.toUpperCase(java.util.Locale.ROOT)), null, true, 0, null);
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
            int data = variants == null ? 0 : variants.spawnData(body.typeKey, body.look.traits().variant());
            return shared.addEntity.newInstance(body.entityId, body.uuid, pose.x(), pose.y(), pose.z(), pose.pitch(), yaw, body.nativeType, data, shared.zeroVelocity,
                    (double) BodyRules.wireYaw(body.typeKey, pose.headYaw()));
        }
        /** Every packet that presents a body, in the order the client must receive them. */
        private List<Object> spawnPackets(Body body, BodyPose pose) throws ReflectiveOperationException {
            List<Object> packets = new ArrayList<>();
            BodyLook look = body.look;
            if (body.playerInfo != null) packets.add(body.playerInfo);
            if (body.nameTeam != null) packets.add(body.nameTeam[0]);
            float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
            if (body.seatId >= 0) {
                packets.add(shared.addEntity.newInstance(body.seatId, body.seatUuid, pose.x(), pose.y(), pose.z(), 0f, yaw, seatType, 0, shared.zeroVelocity, (double) yaw));
                packets.add(shared.metadataPacket.newInstance(body.seatId, presentation.seatValues()));
            }
            packets.add(spawn(body, pose));
            packets.add(rotateHead(body.entityId, BodyRules.wireYaw(body.typeKey, pose.headYaw())));
            List<Object> values = new ArrayList<>();
            if (body.playerInfo != null) values.add(shared.dataValue.invoke(null, playerSkinAccessor, VariantWire.skinParts(look.traits().variant())));
            if (presentation != null) values.addAll(presentation.bodyValues(look, body.typeKey));
            if (variants != null) values.addAll(variants.values(body.typeKey, look.traits().variant()));
            if (!values.isEmpty()) packets.add(shared.metadataPacket.newInstance(body.entityId, values));
            Object attributed = attributes == null ? null : attributes.packet(body.entityId, body.nativeType, look.traits().attributes());
            if (attributed != null) packets.add(attributed);
            if (body.displayIds.length > 0) {
                double lift = BodyLook.lift(body.typeKey, body.height, body.seat.seatY(), look.aboveNativeNameplate(), look.traits().attributes().scale());
                for (int row = 0; row < body.displayIds.length; row++) {
                    BodyLook.TextRow text = look.rows().get(row);
                    packets.add(shared.addEntity.newInstance(body.displayIds[row], body.displayUuids[row], pose.x(), pose.y() + body.height, pose.z(), 0f, 0f, textDisplayType, 0, shared.zeroVelocity, 0d));
                    packets.add(shared.metadataPacket.newInstance(body.displayIds[row], presentation.textValues(text.text(), lift + text.offset(), look.style())));
                }
                packets.add(passengers(body.entityId, body.displayIds.clone()));
            }
            if (body.seatId >= 0) packets.add(passengers(body.seatId, new int[]{body.entityId}));
            return packets;
        }
        private Object seatMove(Body body, BodyPose pose) throws ReflectiveOperationException {
            return shared.move(body.seatId, pose.x(), pose.y(), pose.z(), BodyRules.wireYaw(body.typeKey, pose.yaw()), 0f, pose.onGround());
        }
        private Object move(Body body, BodyPose pose) throws ReflectiveOperationException {
            return shared.move(body.entityId, pose.x(), pose.y(), pose.z(), BodyRules.wireYaw(body.typeKey, pose.yaw()), pose.pitch(), pose.onGround());
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
        /** A damage event from no entity: the client's own hurt handling flashes the body and plays its hurt sound. */
        private List<Object> hurt(int id) throws ReflectiveOperationException {
            return List.of(damageEvent.newInstance(id, damageType, -1, -1, Optional.empty()));
        }
        /** Entity event 3 plays the death sound and, for every body but a player's, the fall; zero health makes a player fall too. */
        private List<Object> die(int id, boolean living) throws ReflectiveOperationException {
            Object event = entityEvent(id, (byte) 3);
            // A body that is not a living entity has no health for the client to set.
            return living ? List.of(event, shared.metadataPacket.newInstance(id, List.of(shared.dataValue.invoke(null, healthAccessor, 0f)))) : List.of(event);
        }
        private Object entityEvent(int id, byte event) throws ReflectiveOperationException {
            ByteBuf bytes = Unpooled.buffer(8);
            try {
                Object buffer = friendlyBuffer.newInstance(bytes);
                bytes.writeInt(id);
                bytes.writeByte(event);
                return entityEvent.newInstance(buffer);
            } finally { bytes.release(); }
        }
        /**
         * The packet that shows a gesture code on this server, or null where it has none. A swing carries the attack or
         * interact animation of {@code held}, the body's native main-hand item, as vanilla does; the default without one.
         */
        private Object gesture(int id, int code, Object held, boolean attack) throws ReflectiveOperationException {
            if (swingPacket != null && (code == 0 || code == 3)) {
                Object animation = code == 3 || held == null ? defaultSwing : (attack ? attackAnimation : interactAnimation).invoke(held);
                return swingPacket.newInstance(id, code == 0 ? mainHand : offHand, animation);
            }
            int action = code >= 0 && code < animateActions.length ? animateActions[code] : -1;
            if (action < 0) return null;
            ByteBuf bytes = Unpooled.buffer(8);
            try {
                Object buffer = friendlyBuffer.newInstance(bytes);
                writeVarInt.invoke(buffer, id);
                bytes.writeByte(action);
                return animate.newInstance(buffer);
            } finally { bytes.release(); }
        }
        /** The int constant of this name on the packet class, or -1 where this version has none. */
        private static int constant(Class<?> packet, String name) throws ReflectiveOperationException {
            if (name == null) return -1;
            try { return packet.getField(name).getInt(null); }
            catch (NoSuchFieldException absent) { return -1; }
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
    }
}
