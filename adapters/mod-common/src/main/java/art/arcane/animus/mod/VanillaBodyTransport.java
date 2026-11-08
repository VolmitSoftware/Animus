package art.arcane.animus.mod;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Load;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.control.ControlInbox;
import art.arcane.animus.core.control.ControlInput;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BoneDisplay;
import art.arcane.animus.core.rig.DisplayTransform;
import art.arcane.animus.mod.mixin.LivingDataAccess;
import art.arcane.animus.mod.mixin.ZoglinDataAccess;
import art.arcane.animus.mod.mixin.PiglinDataAccess;
import art.arcane.animus.mod.mixin.ZombieDataAccess;
import art.arcane.animus.mod.mixin.AgeableDataAccess;
import art.arcane.animus.mod.mixin.BlockDisplayDataAccess;
import art.arcane.animus.mod.mixin.ItemDisplayDataAccess;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.presentation.NativeEntityIds;
import art.arcane.animus.core.presentation.PassengerGraph;
import art.arcane.animus.mod.mixin.AvatarDataAccess;
import art.arcane.animus.mod.mixin.DisplayDataAccess;
import art.arcane.animus.mod.mixin.EntityCounterAccessor;
import art.arcane.animus.mod.mixin.EntityDataAccess;
import art.arcane.animus.mod.mixin.PlayerInfoUpdateAccess;
import art.arcane.animus.mod.mixin.TextDisplayDataAccess;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Rotations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Packet presentation built from vanilla classes on the server thread. Packets are written straight to each viewer's
 * connection, so every operation settles before it returns. No server entity is constructed or registered.
 */
final class VanillaBodyTransport implements BodyTransport<ServerPlayer, ItemStack> {
    static final class Body implements BodyTransport.Body {
        private final VanillaBodyTransport owner;
        private final int entityId, reservedIds, seatId;
        private final int[] displayIds;
        private final UUID uuid, seatUuid = UUID.randomUUID();
        private final UUID[] displayUuids;
        private final String typeKey;
        private final double width, height;
        private final SeatGeometry seat;
        private final EntityType<?> type;
        private final ClientboundPlayerInfoUpdatePacket playerInfo;
        private final BodyLook look;
        private final List<Packet<ClientGamePacketListener>> nameTeam;
        /** Bound on the first write so probe bodies never register. Server thread only. */
        private Load traffic;

        private Body(VanillaBodyTransport owner, int entityId, int baseIds, UUID uuid, String typeKey, EntityType<?> type, SeatGeometry seat,
                     ClientboundPlayerInfoUpdatePacket playerInfo, BodyLook look, List<Packet<ClientGamePacketListener>> nameTeam) {
            this.owner = owner; this.entityId = entityId; this.reservedIds = baseIds + look.extraIds(); this.uuid = uuid; this.typeKey = typeKey;
            this.type = type; this.width = type.getWidth(); this.height = type.getHeight(); this.seat = seat; this.playerInfo = playerInfo;
            this.look = look; this.nameTeam = nameTeam;
            displayIds = IntStream.range(0, look.rows().size()).map((int row) -> entityId + baseIds + row).toArray();
            displayUuids = new UUID[displayIds.length];
            for (int row = 0; row < displayUuids.length; row++) displayUuids[row] = UUID.randomUUID();
            seatId = look.seated() ? entityId + baseIds + displayIds.length : -1;
        }
        @Override public int entityId() { return entityId; }
        @Override public UUID uuid() { return uuid; }
        @Override public String typeKey() { return typeKey; }
        @Override public double width() { return width; }
        @Override public double height() { return height; }
        @Override public SeatGeometry seat() { return seat; }
        @Override public int[] entityIds() { return IntStream.range(0, reservedIds).map((int offset) -> entityId + offset).toArray(); }
        private Load traffic() {
            if (traffic == null) traffic = owner.metrics.npc(uuid);
            return traffic;
        }
    }

    /** A rig's display bones on one body. */
    static final class Bones implements BodyTransport.Bones {
        private final VanillaBodyTransport owner;
        private final Body body;
        private final int[] ids;
        private final UUID[] uuids;
        private final EntityType<?>[] types;
        private final List<List<SynchedEntityData.DataValue<?>>> content;
        private Bones(VanillaBodyTransport owner, Body body, int[] ids, EntityType<?>[] types, List<List<SynchedEntityData.DataValue<?>>> content) {
            this.owner = owner; this.body = body; this.ids = ids; this.types = types; this.content = List.copyOf(content);
            uuids = new UUID[ids.length];
            for (int i = 0; i < ids.length; i++) uuids[i] = UUID.randomUUID();
        }
        @Override public int count() { return ids.length; }
        @Override public int[] entityIds() { return ids.clone(); }
    }

    private final class Session {
        final ServerPlayer viewer;
        final Consumer<Interaction> interactions;
        final Map<Integer, Body> visible = new HashMap<>();
        final PassengerGraph passengers = new PassengerGraph();
        /** Bones riding each vehicle, and the last roster sent without them; every roster write appends the bones. */
        final Map<Integer, Bones> bones = new HashMap<>();
        final Map<Integer, int[]> rosters = new HashMap<>();
        ControlSubscription control;
        long lastInputNanos = Long.MIN_VALUE / 2;
        boolean reportedFailure;
        final Load traffic;
        Session(ServerPlayer viewer, Consumer<Interaction> interactions) {
            this.viewer = viewer; this.interactions = interactions;
            traffic = metrics.viewer(viewer.getUUID(), viewer.getGameProfile().name());
        }
    }

    final class ControlSubscription implements BodyTransport.ControlSubscription {
        private final Session session;
        private final ControlInbox inbox = new ControlInbox(System.nanoTime());
        private ControlSubscription(Session session) { this.session = session; }
        @Override public ControlInbox.Snapshot latest() {
            if (!session.viewer.connection.isAcceptingMessages()) inbox.close(ControlInbox.CONNECTION_CLOSED);
            else if (!current(session) || session.control != this) inbox.close("session-retired");
            return inbox.snapshot();
        }
        @Override public void close() { inbox.close("subscription-closed"); if (session.control == this) session.control = null; }
    }

    private final NativeEntityIds ids = new NativeEntityIds(EntityCounterAccessor.animus$counter());
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<String> types;
    private final EntityType<?> textDisplayType, seatType, itemDisplayType, blockDisplayType;
    static final int MAX_BONES_PER_VIEWER = 4096;
    private final java.util.function.Consumer<String> warnings;
    private final AnimusMetrics metrics = AnimusMetrics.current();
    private boolean closed;

    VanillaBodyTransport(java.util.function.Consumer<String> warnings) {
        this.warnings = Objects.requireNonNull(warnings);
        Set<String> available = new HashSet<>();
        for (String key : BodyRules.TYPES) if (BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(key))) available.add(key);
        types = Set.copyOf(available);
        textDisplayType = entityType("minecraft:text_display");
        seatType = entityType("minecraft:armor_stand");
        itemDisplayType = entityType("minecraft:item_display");
        blockDisplayType = entityType("minecraft:block_display");
    }

    private static EntityType<?> entityType(String key) {
        Identifier id = Identifier.parse(key);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw new IllegalArgumentException("Native entity type is unavailable: " + key);
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    @Override public Set<String> entityTypes() { return types; }
    @Override public String riderUnavailable() { return null; }
    @Override public boolean presentationAvailable() { return true; }
    @Override public boolean equipmentAvailable() { return true; }

    @Override public Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) {
        return createBody(uuid, typeKey, BodyLook.named(profileName), skin);
    }
    @Override public Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) {
        return createBody(uuid, typeKey, BodyLook.of(uuid, typeKey, name, appearance), skin);
    }
    @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) {
        return BodyLook.of(uuid, typeKey, name, appearance).extraIds();
    }
    private Body createBody(UUID uuid, String typeKey, BodyLook look, BodySkin skin) {
        ensureOpen();
        Objects.requireNonNull(uuid);
        if (!types.contains(typeKey)) throw new IllegalArgumentException("Body type is not available: " + typeKey);
        boolean player = typeKey.equals(BodyRules.PLAYER);
        if (player) BodyRules.validateProfileName(look.profileName());
        if (!player && skin != null) throw new IllegalArgumentException("Skin textures apply only to player bodies");
        EntityType<?> type = entityType(typeKey);
        int reserved = BodyRules.reservedIds(typeKey);
        ClientboundPlayerInfoUpdatePacket info = player ? playerInfo(uuid, look.profileName(), skin, look.traits().listed()) : null;
        int entityId = ids.reserve(reserved + look.extraIds());
        List<Packet<ClientGamePacketListener>> team = look.team() ? bodyTeam(entityId, player ? look.profileName() : uuid.toString(), look) : null;
        return new Body(this, entityId, reserved, uuid, typeKey, type, seat(type), info, look, team);
    }
    private static SeatGeometry seat(EntityType<?> type) {
        EntityAttachments points = type.getDimensions().attachments();
        Vec3 seat = points.getClamped(EntityAttachment.PASSENGER, 0, 0f), attached = points.getClamped(EntityAttachment.VEHICLE, 0, 0f);
        return new SeatGeometry(seat.x(), seat.y(), seat.z(), attached.y());
    }
    private static ClientboundPlayerInfoUpdatePacket playerInfo(UUID uuid, String name, BodySkin skin, boolean listed) {
        Multimap<String, Property> values = ArrayListMultimap.create();
        if (skin != null) values.put("textures", new Property("textures", skin.textureValue(), skin.signature()));
        GameProfile profile = new GameProfile(uuid, name, new PropertyMap(values));
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(uuid, profile, listed, 0, GameType.SURVIVAL, null, true, 0, null);
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER);
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(actions, List.of());
        ((PlayerInfoUpdateAccess) packet).animus$entries(List.of(entry));
        return packet;
    }
    /**
     * A team of one body that hides its profile name tag when the look asks, and colours its name and glow with the
     * team colour. Its own name never collides with a real team.
     */
    private static List<Packet<ClientGamePacketListener>> bodyTeam(int entityId, String member, BodyLook look) {
        Scoreboard board = new Scoreboard();
        PlayerTeam team = new PlayerTeam(board, "animus-" + Integer.toHexString(entityId));
        team.setNameTagVisibility(look.hideProfileName() ? Team.Visibility.NEVER : Team.Visibility.ALWAYS);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        look.traits().teamColor().ifPresent((String color) -> teamColor(team, color));
        board.addPlayerToTeam(member, team);
        return List.of(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true), ClientboundSetPlayerTeamPacket.createRemovePacket(team));
    }

    /**
     * Sets a team colour by name. 26.1 takes a {@code ChatFormatting}; later versions take an optional {@code TeamColor};
     * both enums use the same constant names. A colour this server cannot set leaves the team uncoloured.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void teamColor(PlayerTeam team, String color) {
        String constant = color.toUpperCase(java.util.Locale.ROOT);
        for (java.lang.reflect.Method method : PlayerTeam.class.getMethods()) {
            if (!method.getName().equals("setColor") || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            try {
                if (parameter.isEnum()) method.invoke(team, Enum.valueOf((Class) parameter, constant));
                else if (parameter == Optional.class)
                    method.invoke(team, Optional.of(Enum.valueOf((Class) Class.forName("net.minecraft.world.scores.TeamColor"), constant)));
                else continue;
                return;
            } catch (ReflectiveOperationException | IllegalArgumentException unavailable) { return; }
        }
    }

    @Override public CompletionStage<Void> watch(ServerPlayer viewer, Consumer<Interaction> interactions) {
        ensureOpen();
        Objects.requireNonNull(interactions);
        if (!viewer.connection.isAcceptingMessages()) return unwatched("Viewer is not connected");
        Session previous = sessions.put(viewer.getUUID(), new Session(viewer, interactions));
        if (previous != null) retire(previous);
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<Void> spawn(ServerPlayer viewer, BodyTransport.Body presented, BodyPose pose) {
        Body body = checkBody(presented);
        Session session = session(viewer, pose);
        int visibleIds = session.visible.size();
        if (visibleIds + body.reservedIds > BodyRules.MAX_BODIES_PER_VIEWER) return failed("Viewer presentation ID limit reached");
        if (session.visible.values().stream().anyMatch((Body other) -> other.uuid.equals(body.uuid))) return failed("A body with this UUID is already presented to the viewer");
        if (session.visible.containsKey(body.entityId)) return failed("Body is already visible");
        if (!connected(session)) return unwatched(CONNECTION_CLOSED);
        send(session, body, PacketKind.SPAWN, spawnPackets(body, pose, viewer.registryAccess()));
        for (int id : body.entityIds()) session.visible.put(id, body);
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<Void> move(ServerPlayer viewer, BodyTransport.Body presented, BodyPose pose) {
        Body body = checkBody(presented);
        Session session = session(viewer, pose);
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(3);
        // A seated body follows its seat on the client, so the seat carries the movement.
        if (body.seatId >= 0) packets.add(BodyMotion.move(body.seatId, pose.x(), pose.y(), pose.z(), yaw, 0f, pose.onGround()));
        packets.add(BodyMotion.move(body.entityId, pose.x(), pose.y(), pose.z(), yaw, pose.pitch(), pose.onGround()));
        packets.add(rotateHead(body.entityId, yaw));
        return send(session, body, PacketKind.MOVE, packets);
    }

    @Override public CompletionStage<Void> passengers(ServerPlayer viewer, BodyTransport.Body carrier, List<? extends BodyTransport.Body> riders, ServerPlayer realRider, boolean authoritative) {
        Body vehicle = checkBody(carrier);
        List<Body> requested = riders.stream().map(this::checkBody).toList();
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(vehicle.entityId) != vehicle) return failed("Vehicle is not visible to this viewer");
        for (Body passenger : requested) if (session.visible.get(passenger.entityId) != passenger) return failed("Passenger is not visible to this viewer");
        List<Integer> passengerIds = requested.stream().map((Body passenger) -> passenger.entityId).toList();
        int realRiderId = realRider == null ? BodyRules.NO_REAL_RIDER : realRider.getId();
        int[] roster = BodyRules.withText(BodyRules.passengerRoster(passengerIds, realRiderId), vehicle.displayIds);
        if (!connected(session)) return unwatched(CONNECTION_CLOSED);
        try { session.passengers.replace(vehicle.entityId, passengerIds); }
        catch (IllegalArgumentException invalid) { return CompletableFuture.failedFuture(invalid); }
        session.rosters.put(vehicle.entityId, roster);
        return send(session, vehicle, PacketKind.PASSENGERS, List.of(passengersPacket(vehicle.entityId, withBones(roster, session.bones.get(vehicle.entityId)))));
    }

    @Override public CompletionStage<Void> dragonPhase(ServerPlayer viewer, BodyTransport.Body presented, int phase) {
        Body dragon = checkBody(presented);
        if (!dragon.typeKey.equals("minecraft:ender_dragon")) return failed("Phase applies only to dragon bodies");
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(dragon.entityId) != dragon) return failed("Dragon is not visible to this viewer");
        return send(session, dragon, PacketKind.METADATA, List.of(new ClientboundSetEntityDataPacket(dragon.entityId, List.of(SynchedEntityData.DataValue.create(EnderDragon.DATA_PHASE, phase)))));
    }

    /** The main-arm swing a player shows when placing a block. */
    @Override public CompletionStage<Void> swing(ServerPlayer viewer, BodyTransport.Body presented) {
        Body body = checkBody(presented);
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8));
        try {
            buffer.writeVarInt(body.entityId);
            buffer.writeByte(0);
            return send(session, body, PacketKind.METADATA, List.of(ClientboundAnimatePacket.STREAM_CODEC.decode(buffer)));
        } finally { buffer.release(); }
    }

    /** A damage event from no entity: the client flashes the body red and plays its own type's hurt sound. */
    @Override public CompletionStage<Void> hurt(ServerPlayer viewer, BodyTransport.Body presented) {
        Body body = checkBody(presented);
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        return send(session, body, PacketKind.METADATA, List.of(new net.minecraft.network.protocol.game.ClientboundDamageEventPacket(body.entityId,
                viewer.level().damageSources().generic().typeHolder(), -1, -1, Optional.empty())));
    }

    /** Entity event 3 plays the death sound and, for every body but a player's, the fall; zero health makes a player fall too. */
    @Override public CompletionStage<Void> die(ServerPlayer viewer, BodyTransport.Body presented) {
        Body body = checkBody(presented);
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8));
        try {
            buffer.writeInt(body.entityId);
            buffer.writeByte(3);
            Packet<ClientGamePacketListener> event = net.minecraft.network.protocol.game.ClientboundEntityEventPacket.STREAM_CODEC.decode(buffer);
            return send(session, body, PacketKind.METADATA, HEALTH == null ? List.of(event)
                    : List.of(event, new ClientboundSetEntityDataPacket(body.entityId, List.of(SynchedEntityData.DataValue.create(HEALTH, 0f)))));
        } finally { buffer.release(); }
    }
    /** {@code LivingEntity.DATA_HEALTH_ID}, private in vanilla; null where it cannot be read, and then only the death event is sent. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> HEALTH = healthAccessor();
    @SuppressWarnings("unchecked")
    private static net.minecraft.network.syncher.EntityDataAccessor<Float> healthAccessor() {
        try {
            java.lang.reflect.Field field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            field.setAccessible(true);
            return (net.minecraft.network.syncher.EntityDataAccessor<Float>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    /** The shared flags, pose and item use of a visible body from {@code look}; all are always sent, so standing again clears a crouch. */
    @Override public CompletionStage<Void> look(ServerPlayer viewer, BodyTransport.Body presented, BodyLook look) {
        Body body = checkBody(presented);
        Objects.requireNonNull(look);
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        boolean invisible = look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        Pose pose = look.pose() == NpcPose.SITTING ? Pose.STANDING : Pose.valueOf(look.nativePose());
        return send(session, body, PacketKind.METADATA, List.of(new ClientboundSetEntityDataPacket(body.entityId, List.of(
                SynchedEntityData.DataValue.create(EntityDataAccess.animus$sharedFlags(), look.sharedFlags(invisible)),
                SynchedEntityData.DataValue.create(EntityDataAccess.animus$pose(), pose),
                SynchedEntityData.DataValue.create(LivingDataAccess.animus$livingFlags(), BodyLook.livingFlags(look.usingItem()))))));
    }

    @Override public CompletionStage<Void> equipment(ServerPlayer viewer, BodyTransport.Body presented, Map<EquipmentSlot, ItemStack> items) {
        Body body = checkBody(presented);
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        List<Pair<net.minecraft.world.entity.EquipmentSlot, ItemStack>> entries = new ArrayList<>(items.size());
        for (Map.Entry<EquipmentSlot, ItemStack> item : BodyRules.shownEquipment(body.typeKey(), items).entrySet())
            entries.add(Pair.of(net.minecraft.world.entity.EquipmentSlot.valueOf(BodyRules.nativeSlot(item.getKey())), item.getValue().copy()));
        if (entries.isEmpty()) return CompletableFuture.completedFuture(null);
        return send(session, body, PacketKind.EQUIPMENT, List.of(new ClientboundSetEquipmentPacket(body.entityId, entries)));
    }

    @Override public CompletionStage<Void> attributes(ServerPlayer viewer, BodyTransport.Body presented, NpcAttributes values) {
        Body body = checkBody(presented);
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        ClientboundUpdateAttributesPacket packet = ModAttributes.packet(body.entityId, body.type, values);
        if (packet == null) return CompletableFuture.completedFuture(null);
        return send(session, body, PacketKind.METADATA, List.of(packet));
    }

    @Override public CompletionStage<Void> despawn(ServerPlayer viewer, BodyTransport.Body presented) {
        Body body = checkBody(presented);
        Session session = sessions.get(viewer.getUUID());
        if (session == null || session.viewer != viewer || session.visible.get(body.entityId) != body) return CompletableFuture.completedFuture(null);
        for (int id : body.entityIds()) session.visible.remove(id, body);
        session.passengers.remove(body.entityId);
        session.rosters.remove(body.entityId);
        Bones attached = session.bones.get(body.entityId);
        if (attached != null && attached.body == body) session.bones.remove(body.entityId); else attached = null;
        if (connected(session)) send(session, body, PacketKind.DESPAWN, removePackets(body, attached));
        return CompletableFuture.completedFuture(null);
    }

    @Override public void forget(ServerPlayer viewer) {
        Session session = sessions.get(viewer.getUUID());
        if (session != null && session.viewer == viewer) { sessions.remove(viewer.getUUID()); retire(session); }
    }

    @Override public ControlSubscription subscribeControl(ServerPlayer viewer) {
        ensureOpen();
        Session session = existing(viewer);
        if (session == null) throw new BodyTransport.ViewerUnwatched("Viewer is not watched");
        if (session.control != null) throw new IllegalStateException("Viewer already has a control subscription");
        ControlSubscription subscription = new ControlSubscription(session);
        session.control = subscription;
        return subscription;
    }

    @Override public boolean bonesAvailable() { return true; }

    @Override public Bones createBones(BodyTransport.Body presented, List<BoneDisplay<ItemStack>> displays) {
        Body body = checkBody(presented);
        if (displays.isEmpty() || displays.size() > art.arcane.animus.core.rig.NpcRig.MAX_DISPLAYS) throw new IllegalArgumentException("A rig shows 1.." + art.arcane.animus.core.rig.NpcRig.MAX_DISPLAYS + " displays");
        List<List<SynchedEntityData.DataValue<?>>> content = new ArrayList<>(displays.size());
        EntityType<?>[] boneTypes = new EntityType<?>[displays.size()];
        for (int i = 0; i < displays.size(); i++) {
            boneTypes[i] = boneType(displays.get(i));
            content.add(boneContent(displays.get(i)));
        }
        int[] boneIds = new int[displays.size()];
        for (int start = 0; start < boneIds.length; start += 16) {
            int count = Math.min(16, boneIds.length - start), first = ids.reserve(count);
            for (int i = 0; i < count; i++) boneIds[start + i] = first + i;
        }
        return new Bones(this, body, boneIds, boneTypes, content);
    }

    private EntityType<?> boneType(BoneDisplay<ItemStack> bone) {
        return switch (bone.kind()) { case ITEM -> itemDisplayType; case BLOCK -> blockDisplayType; case TEXT -> textDisplayType; };
    }
    /** The bone's fixed content. Block states are parsed here, so an invalid one is refused before anything is sent. */
    private static List<SynchedEntityData.DataValue<?>> boneContent(BoneDisplay<ItemStack> bone) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        switch (bone.kind()) {
            case ITEM -> {
                values.add(SynchedEntityData.DataValue.create(ItemDisplayDataAccess.animus$stack(), bone.item().copy()));
                values.add(SynchedEntityData.DataValue.create(ItemDisplayDataAccess.animus$context(), bone.itemContext()));
            }
            case BLOCK -> values.add(SynchedEntityData.DataValue.create(BlockDisplayDataAccess.animus$state(), blockState(bone.block())));
            case TEXT -> {
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$text(), (Component) ModText.component(bone.text())));
                values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$billboard(), bone.billboard()));
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$lineWidth(), bone.lineWidth()));
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$background(), bone.background()));
                if (bone.shadow()) values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$styleFlags(), (byte) 0x01));
            }
        }
        return values;
    }

    private static net.minecraft.world.level.block.state.BlockState blockState(String text) {
        try { return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState(); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { throw new IllegalArgumentException("Unknown block state for a rig bone: " + text); }
    }

    @Override public CompletionStage<Void> showBones(ServerPlayer viewer, BodyTransport.Body presented, BodyTransport.Bones created, BodyPose pose, List<DisplayTransform> transforms, boolean hideBody) {
        Body body = checkBody(presented);
        Bones bones = checkBones(created, body);
        if (transforms.size() != bones.ids.length) return failed("Every bone needs a transform");
        Session session = session(viewer, pose);
        if (session.visible.get(body.entityId) != body) return failed("Body is not visible to this viewer");
        if (session.bones.containsKey(body.entityId)) return failed("The body already shows bones");
        if (session.bones.values().stream().mapToInt((Bones value) -> value.ids.length).sum() + bones.ids.length > MAX_BONES_PER_VIEWER) return failed("Viewer rig bone limit reached");
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(bones.ids.length * 2 + 2);
        for (int i = 0; i < bones.ids.length; i++) {
            packets.add(new ClientboundAddEntityPacket(bones.ids[i], bones.uuids[i], pose.x(), pose.y(), pose.z(), 0f, 0f, bones.types[i], 0, Vec3.ZERO, 0d));
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(bones.content.get(i));
            values.addAll(transformValues(transforms.get(i), 0));
            packets.add(new ClientboundSetEntityDataPacket(bones.ids[i], values));
        }
        session.bones.put(body.entityId, bones);
        int[] base = session.rosters.getOrDefault(body.entityId, body.displayIds.clone());
        packets.add(passengersPacket(body.entityId, withBones(base, bones)));
        if (hideBody) packets.add(new ClientboundSetEntityDataPacket(body.entityId, List.of(visibility(body.look, true))));
        send(session, body, PacketKind.RIG_BONES, packets);
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<Void> moveBones(ServerPlayer viewer, BodyTransport.Bones moved, int[] indices, DisplayTransform[] transforms, int[] durations) {
        Bones bones = checkBones(moved, null);
        if (indices.length != transforms.length || indices.length != durations.length) return failed("Bone updates need a transform and duration each");
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.bones.get(bones.body.entityId) != bones) return failed("Bones are not shown to this viewer");
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(indices.length);
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || indices[i] >= bones.ids.length) return failed("Unknown bone index " + indices[i]);
            packets.add(new ClientboundSetEntityDataPacket(bones.ids[indices[i]], transformValues(transforms[i], Math.max(0, durations[i]))));
        }
        send(session, bones.body, PacketKind.RIG_BONES, packets);
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<Void> restyleBones(ServerPlayer viewer, BodyTransport.Bones restyled, int[] indices, List<BoneDisplay<ItemStack>> displays) {
        Bones bones = checkBones(restyled, null);
        if (indices.length != displays.size()) return failed("Every restyled bone needs a display");
        Session session = existing(viewer);
        if (session == null) return unwatched("Viewer is not watched");
        if (session.bones.get(bones.body.entityId) != bones) return failed("Bones are not shown to this viewer");
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(indices.length);
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || indices[i] >= bones.ids.length) return failed("Unknown bone index " + indices[i]);
            if (boneType(displays.get(i)) != bones.types[indices[i]]) return failed("A restyled bone keeps its kind");
            packets.add(new ClientboundSetEntityDataPacket(bones.ids[indices[i]], boneContent(displays.get(i))));
        }
        send(session, bones.body, PacketKind.RIG_BONES, packets);
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<Void> hideBones(ServerPlayer viewer, BodyTransport.Bones shown) {
        Bones bones = checkBones(shown, null);
        Session session = existing(viewer);
        if (session == null || session.bones.get(bones.body.entityId) != bones) return CompletableFuture.completedFuture(null);
        session.bones.remove(bones.body.entityId);
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(3);
        packets.add(new ClientboundRemoveEntitiesPacket(bones.ids.clone()));
        if (session.visible.get(bones.body.entityId) == bones.body) {
            int[] base = session.rosters.getOrDefault(bones.body.entityId, bones.body.displayIds.clone());
            packets.add(passengersPacket(bones.body.entityId, base));
            packets.add(new ClientboundSetEntityDataPacket(bones.body.entityId, List.of(visibility(bones.body.look, false))));
        }
        send(session, bones.body, PacketKind.RIG_BONES, packets);
        return CompletableFuture.completedFuture(null);
    }

    private Bones checkBones(BodyTransport.Bones presented, Body body) {
        ensureOpen();
        if (!(presented instanceof Bones bones) || bones.owner != this || (body != null && bones.body != body)) throw new IllegalArgumentException("Bones belong to another body or transport");
        return bones;
    }
    private static int[] withBones(int[] roster, Bones bones) {
        if (bones == null) return roster;
        int[] result = Arrays.copyOf(roster, roster.length + bones.ids.length);
        System.arraycopy(bones.ids, 0, result, roster.length, bones.ids.length);
        return result;
    }
    private static List<SynchedEntityData.DataValue<?>> transformValues(DisplayTransform transform, int duration) {
        return List.of(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$interpolationStart(), 0),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$transformInterpolation(), duration),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$translation(), new Vector3f(transform.tx(), transform.ty(), transform.tz())),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$leftRotation(), new Quaternionf(transform.lx(), transform.ly(), transform.lz(), transform.lw())),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$scale(), new Vector3f(transform.sx(), transform.sy(), transform.sz())),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$rightRotation(), new Quaternionf(transform.rx(), transform.ry(), transform.rz(), transform.rw())));
    }
    /** The body's shared flags, invisible when a rig hides it; its pose flags are kept. */
    private static SynchedEntityData.DataValue<Byte> visibility(BodyLook look, boolean hidden) {
        boolean invisible = hidden || look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        return SynchedEntityData.DataValue.create(EntityDataAccess.animus$sharedFlags(), look.sharedFlags(invisible));
    }

    @Override public void placeRider(ServerPlayer rider, double x, double y, double z) {
        ensureOpen();
        // A client that believes it is a passenger ignores position packets, so only the server side moves.
        rider.setPos(x, y, z);
        rider.connection.resetPosition();
        rider.level().getChunkSource().move(rider);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Session session : List.copyOf(sessions.values())) retire(session);
        sessions.clear();
    }

    /** Server thread: a use or attack aimed at an ID this viewer sees. True consumes the packet. */
    boolean interact(ServerPlayer viewer, int entityId, boolean attack, String hand, boolean secondary) {
        if (closed) return false;
        Session session = existing(viewer);
        Body body = session == null ? null : session.visible.get(entityId);
        if (body == null) return false;
        long now = System.nanoTime();
        if (now - session.lastInputNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS) { metrics.inbound(session.traffic, false); return true; }
        session.lastInputNanos = now;
        metrics.inbound(session.traffic, true);
        try { session.interactions.accept(new Interaction(body, attack ? Action.ATTACK : Action.INTERACT, hand, secondary, now)); }
        catch (RuntimeException failure) {
            if (!session.reportedFailure) { session.reportedFailure = true; warnings.accept("Virtual interaction callback failed: " + failure.getClass().getSimpleName()); }
        }
        return true;
    }
    void input(ServerPlayer viewer, Input input) {
        Session session = existing(viewer);
        ControlSubscription control = session == null ? null : session.control;
        if (control == null || !control.inbox.active()) return;
        control.inbox.offer(new ControlInput(input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift(), input.sprint()), System.nanoTime());
    }
    void tickEnd(ServerPlayer viewer) {
        Session session = existing(viewer);
        ControlSubscription control = session == null ? null : session.control;
        if (control != null && control.inbox.active()) control.inbox.heartbeat(System.nanoTime());
    }

    private List<Packet<ClientGamePacketListener>> spawnPackets(Body body, BodyPose pose, HolderLookup.Provider registries) {
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>();
        BodyLook look = body.look;
        if (body.playerInfo != null) packets.add(body.playerInfo);
        if (body.nameTeam != null) packets.add(body.nameTeam.get(0));
        float yaw = BodyRules.wireYaw(body.typeKey, pose.yaw());
        if (body.seatId >= 0) {
            packets.add(new ClientboundAddEntityPacket(body.seatId, body.seatUuid, pose.x(), pose.y(), pose.z(), 0f, yaw, seatType, 0, Vec3.ZERO, yaw));
            packets.add(new ClientboundSetEntityDataPacket(body.seatId, seatValues()));
        }
        packets.add(new ClientboundAddEntityPacket(body.entityId, body.uuid, pose.x(), pose.y(), pose.z(), pose.pitch(), yaw, body.type, 0, Vec3.ZERO, yaw));
        packets.add(rotateHead(body.entityId, yaw));
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        if (body.playerInfo != null) values.add(SynchedEntityData.DataValue.create(AvatarDataAccess.animus$skinLayers(), (byte) 0x7f));
        values.addAll(bodyValues(look, body.typeKey));
        values.addAll(ModBodyVariants.values(body.typeKey, look.traits().variant(), registries));
        if (!values.isEmpty()) packets.add(new ClientboundSetEntityDataPacket(body.entityId, values));
        ClientboundUpdateAttributesPacket attributes = ModAttributes.packet(body.entityId, body.type, look.traits().attributes());
        if (attributes != null) packets.add(attributes);
        if (body.displayIds.length > 0) {
            double lift = BodyLook.lift(body.height, body.seat.seatY(), look.aboveNativeNameplate(), look.traits().attributes().scale());
            for (int row = 0; row < body.displayIds.length; row++) {
                BodyLook.TextRow text = look.rows().get(row);
                packets.add(new ClientboundAddEntityPacket(body.displayIds[row], body.displayUuids[row], pose.x(), pose.y() + body.height, pose.z(), 0f, 0f, textDisplayType, 0, Vec3.ZERO, 0d));
                packets.add(new ClientboundSetEntityDataPacket(body.displayIds[row], textValues(text.text(), lift + text.offset(), look.style())));
            }
            packets.add(passengersPacket(body.entityId, body.displayIds.clone()));
        }
        if (body.seatId >= 0) packets.add(passengersPacket(body.seatId, new int[]{body.entityId}));
        return packets;
    }
    private static List<SynchedEntityData.DataValue<?>> bodyValues(BodyLook look, String typeKey) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        boolean invisible = look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        byte flags = look.sharedFlags(invisible);
        if (flags != 0) values.add(SynchedEntityData.DataValue.create(EntityDataAccess.animus$sharedFlags(), flags));
        if (look.usingItem()) values.add(SynchedEntityData.DataValue.create(LivingDataAccess.animus$livingFlags(), BodyLook.livingFlags(true)));
        if (look.traits().baby()) BodyRules.babyFlag(typeKey).ifPresent((BodyRules.BabyFlag flag) -> values.add(SynchedEntityData.DataValue.create(switch (flag) {
            case AGEABLE -> AgeableDataAccess.animus$baby();
            case ZOMBIE -> ZombieDataAccess.animus$baby();
            case PIGLIN -> PiglinDataAccess.animus$baby();
            case ZOGLIN -> ZoglinDataAccess.animus$baby();
        }, Boolean.TRUE)));
        if ((look.pose() != NpcPose.STANDING && look.pose() != NpcPose.SITTING) || (look.motion() & BodyLook.GLIDING) != 0)
            values.add(SynchedEntityData.DataValue.create(EntityDataAccess.animus$pose(), Pose.valueOf(look.nativePose())));
        if (look.armorStand().isPresent()) {
            ArmorStandLook stand = look.armorStand().get();
            values.add(SynchedEntityData.DataValue.create(ArmorStand.DATA_CLIENT_FLAGS, BodyLook.armorStandFlags(stand)));
            for (Map.Entry<ArmorStandLook.Part, ArmorStandLook.Angles> angle : stand.angles().entrySet()) {
                Rotations rotation = new Rotations(angle.getValue().x(), angle.getValue().y(), angle.getValue().z());
                values.add(switch (angle.getKey()) {
                    case HEAD -> SynchedEntityData.DataValue.create(ArmorStand.DATA_HEAD_POSE, rotation);
                    case BODY -> SynchedEntityData.DataValue.create(ArmorStand.DATA_BODY_POSE, rotation);
                    case LEFT_ARM -> SynchedEntityData.DataValue.create(ArmorStand.DATA_LEFT_ARM_POSE, rotation);
                    case RIGHT_ARM -> SynchedEntityData.DataValue.create(ArmorStand.DATA_RIGHT_ARM_POSE, rotation);
                    case LEFT_LEG -> SynchedEntityData.DataValue.create(ArmorStand.DATA_LEFT_LEG_POSE, rotation);
                    case RIGHT_LEG -> SynchedEntityData.DataValue.create(ArmorStand.DATA_RIGHT_LEG_POSE, rotation);
                });
            }
        }
        return values;
    }
    private static List<SynchedEntityData.DataValue<?>> textValues(String content, double lift, BodyLook.TextStyle style) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(List.of(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$text(), (Component) ModText.component(content)),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$billboard(), style.billboard()),
                SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$lineWidth(), BodyLook.LINE_WIDTH),
                SynchedEntityData.DataValue.create(DisplayDataAccess.animus$translation(), new Vector3f(0f, (float) lift, 0f))));
        if (style.flags() != 0) values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$styleFlags(), style.flags()));
        if (style.viewRange() != 1f) values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$viewRange(), style.viewRange()));
        if (style.interpolationDelay() != 0) values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$interpolationStart(), style.interpolationDelay()));
        if (style.interpolationDuration() != 0) values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$transformInterpolation(), style.interpolationDuration()));
        return values;
    }
    private static List<SynchedEntityData.DataValue<?>> seatValues() {
        return List.of(SynchedEntityData.DataValue.create(EntityDataAccess.animus$sharedFlags(), (byte) 0x20),
                SynchedEntityData.DataValue.create(ArmorStand.DATA_CLIENT_FLAGS, (byte) BodyLook.SEAT_FLAGS));
    }
    private static Packet<ClientGamePacketListener> rotateHead(int id, float yaw) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8));
        try {
            buffer.writeVarInt(id);
            buffer.writeByte((byte) (int) Math.floor((yaw % 360.0) * 256.0 / 360.0));
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }
    private static Packet<ClientGamePacketListener> passengersPacket(int vehicle, int[] passengers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(128));
        try {
            buffer.writeVarInt(vehicle);
            buffer.writeVarIntArray(passengers);
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }
    private static List<Packet<ClientGamePacketListener>> removePackets(Body body, Bones bones) {
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(3);
        int[] removed = body.entityIds();
        if (bones != null) {
            int[] all = Arrays.copyOf(removed, removed.length + bones.ids.length);
            System.arraycopy(bones.ids, 0, all, removed.length, bones.ids.length);
            removed = all;
        }
        packets.add(new ClientboundRemoveEntitiesPacket(removed));
        if (body.playerInfo != null) packets.add(new ClientboundPlayerInfoRemovePacket(List.of(body.uuid)));
        if (body.nameTeam != null) packets.add(body.nameTeam.get(1));
        return packets;
    }

    /** A closed connection is an ordinary failed stage, as on Bukkit; a player leaving mid-update is not a fault. */
    private CompletionStage<Void> send(Session session, Body body, PacketKind kind, List<Packet<ClientGamePacketListener>> packets) {
        if (!connected(session)) return unwatched(CONNECTION_CLOSED);
        metrics.sent(session.traffic, body.traffic(), kind, packets.size());
        for (Packet<ClientGamePacketListener> packet : packets) session.viewer.connection.send(packet);
        return CompletableFuture.completedFuture(null);
    }
    private static boolean connected(Session session) { return session.viewer.connection.isAcceptingMessages(); }
    private void retire(Session session) {
        String reason = session.viewer.connection.isAcceptingMessages() ? "session-retired" : ControlInbox.CONNECTION_CLOSED;
        if (session.control != null) { session.control.inbox.close(reason); session.control = null; }
        Set<Body> bodies = new HashSet<>(session.visible.values());
        Map<Integer, Bones> attached = new HashMap<>(session.bones);
        for (Bones bones : attached.values()) bodies.add(bones.body);
        session.visible.clear(); session.passengers.clear(); session.bones.clear(); session.rosters.clear();
        if (!session.viewer.connection.isAcceptingMessages()) return;
        for (Body body : bodies) {
            Bones bones = attached.get(body.entityId);
            send(session, body, PacketKind.DESPAWN, removePackets(body, bones != null && bones.body == body ? bones : null));
        }
    }
    private Session session(ServerPlayer viewer, BodyPose pose) {
        ensureOpen();
        if (!ModWorldKeys.key(viewer.level()).equals(pose.world())) throw new IllegalArgumentException("Viewer and body world differ");
        Session session = existing(viewer);
        if (session == null) throw new BodyTransport.ViewerUnwatched("Viewer must be watched before sending body packets");
        return session;
    }
    private Session existing(ServerPlayer viewer) {
        Session session = sessions.get(viewer.getUUID());
        return session != null && current(session) && session.viewer == viewer ? session : null;
    }
    private boolean current(Session session) { return !closed && sessions.get(session.viewer.getUUID()) == session; }
    private Body checkBody(BodyTransport.Body presented) {
        ensureOpen();
        if (!(presented instanceof Body body) || body.owner != this) throw new IllegalArgumentException("Body belongs to another transport");
        return body;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Packet transport is closed"); }
    private static final String CONNECTION_CLOSED = "Viewer connection is closed";
    private static CompletionStage<Void> failed(String reason) { return CompletableFuture.failedFuture(new IllegalStateException(reason)); }
    /** The viewer's watch has ended or was never installed: the work is dropped, not sent. */
    private static CompletionStage<Void> unwatched(String reason) { return CompletableFuture.failedFuture(new BodyTransport.ViewerUnwatched(reason)); }
}
