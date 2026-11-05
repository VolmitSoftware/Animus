import com.google.gson.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.handshake.*;
import net.minecraft.network.protocol.login.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import java.util.zip.*;

/** Local, offline QA only. Packet IDs and payloads come from the exact vanilla jar.
 * It deliberately skips unrelated game packets and is not a graphical client.
 * JSON lines on stdin control it; JSON lines on stdout contain sanitized evidence.
 */
public final class NativeProtocolClient {
    private static final PrintStream EVENTS = System.out;
    private static final Gson JSON = new Gson();
    private static final int LIMIT = 8 * 1024 * 1024;
    private final Socket socket;
    private final DataInputStream input;
    private final OutputStream output;
    private final String username;
    private final RegistryAccess.Frozen baseRegistries;
    private RegistryAccess.Frozen registries;
    private ProtocolInfo<?> gameIn;
    private ProtocolInfo<?> gameOut;
    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> networkEntries = new LinkedHashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> networkTags = new LinkedHashMap<>();
    private final Map<Integer, String> gameNames = new HashMap<>();
    private volatile ProtocolInfo<?> incoming;
    private volatile ProtocolInfo<?> outgoing;
    private volatile int compression = -1;
    private volatile boolean closed;
    private volatile boolean loaded;
    private volatile boolean tickHeartbeat = true;
    private volatile int ownEntity, ridingVehicle;
    private volatile boolean expectedDisconnect;
    // The open container window (0 is the player's own inventory) and the state id the server last sent for it.
    private volatile int windowId, windowState;
    private int positionReminder;
    private Vec3 queuedMove;
    private boolean positioned;
    private volatile PositionMoveRotation position = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0, 0);
    private static final Set<String> DECODE = Set.of(
        "add_entity", "player_info_update", "player_info_remove", "remove_entities",
        "entity_position_sync", "teleport_entity", "player_position", "system_chat", "respawn",
        "keep_alive", "ping", "disconnect", "chunk_batch_finished", "set_entity_data",
        "set_entity_motion", "set_equipment", "set_passengers", "animate", "rotate_head", "set_health",
        "login", "sound", "level_particles", "boss_event", "move_entity_rot", "move_entity_pos_rot",
        "open_screen", "container_set_content", "container_set_slot", "container_close");

    private NativeProtocolClient(int port, String username) throws Exception {
        this.username = username;
        baseRegistries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        registries = baseRegistries;
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> gameNames.put(id, type.id().getPath()));
        socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(60_000);
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = socket.getOutputStream();
        outgoing = HandshakeProtocols.SERVERBOUND;
        send(new ClientIntentionPacket(SharedConstants.getProtocolVersion(), "localhost", port, ClientIntent.LOGIN));
        incoming = LoginProtocols.CLIENTBOUND;
        outgoing = LoginProtocols.SERVERBOUND;
        send(new ServerboundHelloPacket(username, UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))));
    }

    private void bindGame() {
        gameIn = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
        gameOut = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
            public boolean hasInfiniteMaterials() { return false; }
            public boolean canUseCommandBlocks() { return false; }
        });
    }

    /** Mirrors native RegistryDataCollector using the server-shared loading APIs.
     * Advertising no known packs requires every network entry to include NBT.
     * Never substitute local datapack defaults for server-provided registry IDs.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void loadNetworkRegistries() {
        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> contents = new LinkedHashMap<>();
        networkEntries.forEach((key, entries) -> contents.put(key, new RegistryDataLoader.NetworkedRegistryData(entries, TagNetworkSerialization.NetworkPayload.EMPTY)));
        List<Registry.PendingTags<?>> staticTags = new ArrayList<>();
        networkTags.forEach((key, payload) -> {
            if (payload.isEmpty()) return;
            if (RegistrySynchronization.isNetworkable(key)) {
                var previous = contents.get(key);
                contents.put(key, new RegistryDataLoader.NetworkedRegistryData(previous == null ? List.of() : previous.elements(), payload));
            } else {
                Registry registry = baseRegistries.lookupOrThrow((ResourceKey) key);
                staticTags.add(registry.prepareTagReload(payload.resolve(registry)));
            }
        });
        var synchronizedRegistries = RegistryDataLoader.load(contents, ResourceProvider.EMPTY,
                TagLoader.buildUpdatedLookups(baseRegistries, staticTags), RegistryDataLoader.SYNCHRONIZED_REGISTRIES,
                ForkJoinPool.commonPool()).join();
        registries = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(baseRegistries.registries(), synchronizedRegistries.registries())).freeze();
        staticTags.forEach(Registry.PendingTags::apply);
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
        event("registries", "count", synchronizedRegistries.registries().count(), "entries", networkEntries.values().stream().mapToInt(List::size).sum(),
                "keys", synchronizedRegistries.listRegistryKeys().map(key -> key.identifier().toString()).sorted().toList());
        networkEntries.clear();
        networkTags.clear();
        bindGame();
    }

    public static void main(String[] args) throws Exception {
        System.setOut(System.err);
        SharedConstants.tryDetectVersion();
        if (args.length != 3 || !SharedConstants.getCurrentVersion().name().equals(args[2])) {
            throw new IllegalArgumentException("Expected port username exact-version; jar version is " + SharedConstants.getCurrentVersion().name());
        }
        Bootstrap.bootStrap();
        NativeProtocolClient client = new NativeProtocolClient(Integer.parseInt(args[0]), args[1]);
        var skinAccessor = net.minecraft.world.entity.Avatar.class.getDeclaredField("DATA_PLAYER_MODE_CUSTOMISATION");
        skinAccessor.setAccessible(true);
        int skinMetadataId = ((net.minecraft.network.syncher.EntityDataAccessor<?>) skinAccessor.get(null)).id();
        client.event("toolchain", "version", SharedConstants.getCurrentVersion().name(), "protocol", SharedConstants.getProtocolVersion(), "skinMetadataId", skinMetadataId);
        // 26.3 bounds movement packets per client tick. A socket-only observer
        // still sends vanilla client tick boundaries while remaining stationary.
        Thread.ofVirtual().name("qa-client-ticks").start(() -> {
            try {
                while (!client.closed) {
                    Thread.sleep(50);
                    client.tick();
                }
            } catch (Throwable error) { if (!client.closed) client.fail(error); }
        });
        Thread.ofVirtual().name("qa-control").start(() -> {
            try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; !client.closed && (line = commands.readLine()) != null;) client.control(JsonParser.parseString(line).getAsJsonObject());
                client.close();
            } catch (Throwable error) { if (!client.closed) client.fail(error); }
        });
        try { client.readLoop(); }
        catch (Throwable error) { if (!client.closed) client.fail(error); }
        finally { client.close(); }
    }

    private synchronized void tick() throws Exception {
        if (!loaded || !tickHeartbeat) return;
        // Exact 26.3 LocalPlayer.sendPosition sends a position reminder every 20
        // client ticks even when stationary. Use only acknowledged coordinates.
        // Serialize with teleport ACKs and reset their timer: 26.3 permits one
        // hasPosition movement packet per client tick.
        // Exact LocalPlayer.tick: a passenger sends rotation every tick and never a position.
        if (ridingVehicle != 0) {
            send(new ServerboundMovePlayerPacket.Rot(position.yRot(), position.xRot(), false, false));
            positionReminder = 0;
        } else if (queuedMove != null) {
            Vec3 feet = queuedMove; queuedMove = null;
            position = new PositionMoveRotation(feet, Vec3.ZERO, position.yRot(), position.xRot());
            send(new ServerboundMovePlayerPacket.Pos(feet, true, false));
            positionReminder = 0;
            event("movement_sent", "x", feet.x, "y", feet.y, "z", feet.z);
        } else if (++positionReminder >= 20) {
            Vec3 feet = position.position();
            send(new ServerboundMovePlayerPacket.Pos(feet, true, false));
            positionReminder = 0;
            event("position_reminder", "x", feet.x, "y", feet.y, "z", feet.z);
        }
        send(ServerboundClientTickEndPacket.INSTANCE);
    }

    private void readLoop() throws Exception {
        while (!closed) {
            int length = readVarInt(input);
            if (length <= 0 || length > LIMIT) throw new IOException("Invalid frame length " + length);
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException("Truncated packet frame");
            if (compression >= 0) {
                ByteArrayInputStream compressed = new ByteArrayInputStream(bytes);
                int decodedLength = readVarInt(compressed);
                if (decodedLength < 0 || decodedLength > LIMIT) throw new IOException("Invalid inflated length");
                if (decodedLength == 0) bytes = compressed.readAllBytes();
                else {
                    try (InflaterInputStream inflater = new InflaterInputStream(compressed)) {
                        bytes = inflater.readNBytes(decodedLength + 1);
                        if (bytes.length != decodedLength || inflater.read() != -1) throw new IOException("Invalid compressed frame length");
                    }
                }
            }
            ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            try {
                if (incoming == gameIn) {
                    int id = new FriendlyByteBuf(buffer).readVarInt();
                    String type = gameNames.get(id);
                    if (type == null) throw new IOException("Unknown exact-version packet ID " + id);
                    if (type.startsWith("chunk_batch")) event("chunk_batch", "type", type);
                    if (!DECODE.contains(type)) continue;
                    buffer.readerIndex(0);
                }
                Packet<?> packet = incoming.codec().decode(buffer);
                if (buffer.isReadable()) throw new IOException("Unread native packet bytes for " + packet.type());
                handle(packet);
            } finally { buffer.release(); }
        }
    }

    private void handle(Packet<?> packet) throws Exception {
        if (packet instanceof ClientboundLoginCompressionPacket p) compression = p.getCompressionThreshold();
        else if (packet instanceof ClientboundHelloPacket) throw new IOException("QA client only connects to offline loopback servers");
        else if (packet instanceof ClientboundLoginFinishedPacket) {
            send(ServerboundLoginAcknowledgedPacket.INSTANCE);
            incoming = ConfigurationProtocols.CLIENTBOUND;
            outgoing = ConfigurationProtocols.SERVERBOUND;
            send(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
            event("configuration");
        } else if (packet instanceof ClientboundSelectKnownPacks) send(new ServerboundSelectKnownPacks(List.of()));
        else if (packet instanceof ClientboundCodeOfConductPacket) send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
        else if (packet instanceof ClientboundRegistryDataPacket p) networkEntries.computeIfAbsent(p.registry(), ignored -> new ArrayList<>()).addAll(p.entries());
        else if (packet instanceof ClientboundUpdateTagsPacket p) networkTags.putAll(ProtocolRevision.tags(p));
        else if (packet instanceof ClientboundFinishConfigurationPacket) {
            loadNetworkRegistries();
            send(ServerboundFinishConfigurationPacket.INSTANCE);
            incoming = gameIn;
            outgoing = gameOut;
            event("play");
        } else if (packet instanceof ClientboundKeepAlivePacket p) send(new ServerboundKeepAlivePacket(p.getId()));
        else if (packet instanceof ClientboundPingPacket p) send(new ServerboundPongPacket(p.getId()));
        else if (packet instanceof ClientboundChunkBatchFinishedPacket) send(new ServerboundChunkBatchReceivedPacket(10));
        else if (packet instanceof ClientboundRespawnPacket p) {
            synchronized (this) { loaded = false; positionReminder = 0; queuedMove = null; }
            event("respawn", "dimension", p.commonPlayerSpawnInfo().dimension().toString());
        }
        else if (packet instanceof ClientboundPlayerPositionPacket p) {
            // The headless observer can finish login before the server has run
            // the joining player's first tick. Allow that initial tick before
            // acknowledging; a graphical client naturally waits on world load.
            if (!positioned) { Thread.sleep(200); positioned = true; }
            PositionMoveRotation accepted;
            synchronized (this) {
                position = PositionMoveRotation.calculateAbsolute(position, p.change(), p.relatives()); accepted = position;
                queuedMove = null;
                positionReminder = 0;
                for (Packet<?> acknowledgment : ProtocolRevision.acknowledgments(p.id(), position)) send(acknowledgment);
                if (!loaded) { send(new ServerboundPlayerLoadedPacket()); loaded = true; }
            }
            Vec3 v = accepted.position();
            event("position", "x", v.x, "y", v.y, "z", v.z, "yaw", accepted.yRot(), "pitch", accepted.xRot());
        } else if (packet instanceof ClientboundSystemChatPacket p) event("chat", "text", p.content().getString(), "clicks", clicks(p.content()));
        else if (packet instanceof ClientboundSetHealthPacket p) event("health", "health", p.getHealth(), "food", p.getFood(), "saturation", p.getSaturation());
        else if (packet instanceof ClientboundAddEntityPacket p) event("spawn", "id", p.getId(), "uuid", p.getUUID().toString(), "type", BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).toString(), "x", p.getX(), "y", p.getY(), "z", p.getZ());
        else if (packet instanceof ClientboundPlayerInfoUpdatePacket p) {
            for (var entry : p.entries()) {
                List<Map<String, Object>> textures = new ArrayList<>();
                if (entry.profile() != null) for (var property : entry.profile().properties().get("textures")) {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("valueSha256", sha256(property.value()));
                    summary.put("signed", property.signature() != null && !property.signature().isEmpty());
                    if (property.signature() != null) summary.put("signatureSha256", sha256(property.signature()));
                    textures.add(summary);
                }
                event("player_info", "uuid", entry.profileId().toString(), "name", entry.profile() == null ? null : entry.profile().name(), "actions", p.actions().stream().map(Enum::name).toList(), "textures", textures);
            }
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket p) event("player_info_remove", "uuids", p.profileIds().stream().map(UUID::toString).toList());
        else if (packet instanceof ClientboundRemoveEntitiesPacket p) event("remove", "ids", ProtocolRevision.removed(p));
        else if (packet instanceof ClientboundEntityPositionSyncPacket p) emitMove(p.id(), ProtocolRevision.position(p));
        else if (packet instanceof ClientboundTeleportEntityPacket p) emitMove(p.id(), p.change().position());
        else if (packet instanceof ClientboundSetPassengersPacket p) {
            synchronized (this) {
                boolean aboard = Arrays.stream(p.getPassengers()).anyMatch(id -> id == ownEntity);
                if (aboard) ridingVehicle = p.getVehicle();
                else if (ridingVehicle == p.getVehicle()) ridingVehicle = 0;
            }
            event("passengers", "vehicle", p.getVehicle(), "ids", p.getPassengers(), "own", ownEntity);
        }
        else if (packet instanceof ClientboundLoginPacket p) { ownEntity = p.playerId(); event("login", "id", ownEntity); }
        else if (packet instanceof ClientboundSetEquipmentPacket p) {
            Map<String, Object> slots = new LinkedHashMap<>();
            for (var pair : p.getSlots()) slots.put(pair.getFirst().getName(), pair.getSecond().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(pair.getSecond().getItem()).toString());
            event("equipment", "id", p.getEntity(), "slots", slots);
        }
        else if (packet instanceof ClientboundSoundPacket p) event("sound", "sound", p.getSound().unwrapKey().map(key -> key.identifier().toString()).orElse(p.getSound().value().location().toString()), "volume", p.getVolume(), "pitch", p.getPitch());
        else if (packet instanceof ClientboundLevelParticlesPacket p) event("particle", "particle", BuiltInRegistries.PARTICLE_TYPE.getKey(ProtocolRevision.particle(p).getType()).toString(), "count", ProtocolRevision.particleCount(p));
        else if (packet instanceof ClientboundBossEventPacket) event("boss_event");
        else if (packet instanceof ClientboundRotateHeadPacket p) event("head", "id", reflectedId(p), "yaw", p.getYHeadRot());
        else if (packet instanceof ClientboundMoveEntityPacket p && p.hasRotation()) event("rotation", "id", reflectedId(p), "yaw", p.getYRot(), "pitch", p.getXRot());
        else if (packet instanceof ClientboundSetEntityDataPacket p) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (var item : p.packedItems()) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("id", item.id());
                if (item.value() instanceof Number || item.value() instanceof Boolean) field.put("value", item.value());
                fields.add(field);
            }
            event("metadata", "id", p.id(), "entries", p.packedItems().size(), "fields", fields);
        }
        else if (packet instanceof ClientboundDisconnectPacket p) {
            if (!expectedDisconnect) throw new IOException("Disconnected: " + p.reason().getString());
            event("expected_disconnect","reason",p.reason().getString());close();
        }
        else if (packet instanceof ClientboundLoginDisconnectPacket p) throw new IOException("Login disconnected: " + p.reason().getString());
        else if (packet instanceof ClientboundOpenScreenPacket p) {
            windowId = p.getContainerId();
            windowState = 0;
            event("open_screen", "container", p.getContainerId(), "menu", String.valueOf(BuiltInRegistries.MENU.getKey(p.getType())), "title", p.getTitle().getString());
        } else if (packet instanceof ClientboundContainerSetContentPacket p) {
            if (p.containerId() == windowId) windowState = p.stateId();
            List<Map<String, Object>> items = new ArrayList<>();
            for (int slot = 0; slot < p.items().size(); slot++) {
                ItemStack stack = p.items().get(slot);
                if (!stack.isEmpty()) items.add(stack(slot, stack));
            }
            event("container_content", "container", p.containerId(), "state", p.stateId(), "size", p.items().size(), "items", items);
        } else if (packet instanceof ClientboundContainerSetSlotPacket p) {
            if (p.getContainerId() == windowId) windowState = p.getStateId();
            event("container_slot", "container", p.getContainerId(), "state", p.getStateId(), "slot", p.getSlot(),
                    "item", p.getItem().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(p.getItem().getItem()).toString(), "count", p.getItem().getCount());
        } else if (packet instanceof ClientboundContainerClosePacket p) {
            if (p.getContainerId() == windowId) { windowId = 0; windowState = 0; }
            event("container_closed", "container", p.getContainerId());
        }
    }

    private static Map<String, Object> stack(int slot, ItemStack stack) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("slot", slot);
        item.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.put("count", stack.getCount());
        return item;
    }

    private static List<String> clicks(net.minecraft.network.chat.Component root) {
        List<String> found = new ArrayList<>();
        root.visit((style, text) -> {
            if (style.getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run) found.add(run.command());
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found;
    }

    private static int reflectedId(Object packet) throws ReflectiveOperationException {
        for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass())
            for (var field : type.getDeclaredFields())
                if (field.getName().equals("entityId")) { field.setAccessible(true); return field.getInt(packet); }
        throw new NoSuchFieldException("entityId");
    }

    private void emitMove(int id, Vec3 v) { event("move", "id", id, "x", v.x, "y", v.y, "z", v.z); }

    private void control(JsonObject command) throws Exception {
        switch (command.get("op").getAsString()) {
            case "command" -> send(new ServerboundChatCommandPacket(command.get("text").getAsString().replaceFirst("^/", "")));
            case "respawn" -> {
                if (!loaded) throw new IllegalStateException("Respawn requires an active game connection");
                send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                event("respawn_request");
            }
            case "look" -> {
                synchronized (this) {
                Vec3 feet = position.position();
                double dx = command.get("x").getAsDouble() - feet.x;
                double dy = command.get("y").getAsDouble() - (feet.y + 1.62);
                double dz = command.get("z").getAsDouble() - feet.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                position = position.withRotation(yaw, pitch);
                send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, true, false));
                event("look", "yaw", yaw, "pitch", pitch);
                }
            }
            case "move" -> {
                synchronized (this) {
                    if (!loaded || !tickHeartbeat || queuedMove != null) throw new IllegalStateException("Movement requires a loaded ticking client with no pending move");
                    Vec3 feet = new Vec3(coordinate(command,"x"),coordinate(command,"y"),coordinate(command,"z"));
                    if (feet.distanceTo(position.position()) > .51) throw new IllegalArgumentException("QA movement is limited to 0.5 blocks per client tick");
                    // Sent by tick(), never a second position packet within the same client tick.
                    queuedMove = feet;
                }
            }
            case "attack" -> {
                int count=command.has("repeat")?command.get("repeat").getAsInt():1;
                if(count<1||count>64)throw new IllegalArgumentException("QA attack repeat 1..64 required");
                for(int i=0;i<count;i++)send(new ServerboundAttackPacket(command.get("id").getAsInt()));
            }
            case "use" -> {
                Vec3 point=command.has("x")?new Vec3(coordinate(command,"x"),coordinate(command,"y"),coordinate(command,"z")):Vec3.ZERO;
                send(new ServerboundInteractPacket(command.get("id").getAsInt(), InteractionHand.MAIN_HAND, point, false));
                event("interaction_point","id",command.get("id").getAsInt(),"x",point.x,"y",point.y,"z",point.z);
            }
            case "input" -> {
                if (!loaded) throw new IllegalStateException("Input requires an active game connection");
                Input keys = new Input(flag(command, "forward"), flag(command, "backward"), flag(command, "left"),
                        flag(command, "right"), flag(command, "jump"), flag(command, "shift"), flag(command, "sprint"));
                int count = command.has("repeat") ? command.get("repeat").getAsInt() : 1;
                if (count < 1 || count > 256 || (command.has("repeat") && command.get("repeat").getAsDouble() != count))
                    throw new IllegalArgumentException("QA input repeat must be an integer from 1 to 256");
                for (int i = 0; i < count; i++) send(new ServerboundPlayerInputPacket(keys));
                event("input", "forward", keys.forward(), "backward", keys.backward(), "left", keys.left(), "right", keys.right(),
                        "jump", keys.jump(), "shift", keys.shift(), "sprint", keys.sprint(), "packets", count);
            }
            case "heartbeat" -> {
                if (!command.has("enabled")) throw new IllegalArgumentException("Heartbeat enabled flag required");
                tickHeartbeat = flag(command, "enabled");
                event("heartbeat", "enabled", tickHeartbeat);
            }
            case "click_container" -> {
                // One vanilla container click. The server answers with fresh slot contents, so no predicted
                // changes are sent; a cancelled click (every Animus GUI click) is resynced the same way.
                int container = command.has("container") ? command.get("container").getAsInt() : windowId;
                if (container <= 0) throw new IllegalStateException("No open container window to click");
                int slot = command.get("slot").getAsInt();
                int button = command.has("button") ? command.get("button").getAsInt() : 0;
                if (slot < 0 || slot > 255) throw new IllegalArgumentException("QA container slot 0..255 required");
                if (button < 0 || button > 8) throw new IllegalArgumentException("QA container button 0..8 required");
                ContainerInput mode = command.has("mode") ? ContainerInput.valueOf(command.get("mode").getAsString().toUpperCase(Locale.ROOT)) : ContainerInput.PICKUP;
                int state = windowState;
                send(new ServerboundContainerClickPacket(container, state, (short) slot, (byte) button, mode, new Int2ObjectOpenHashMap<HashedStack>(), HashedStack.EMPTY));
                event("container_click_sent", "container", container, "state", state, "slot", slot, "button", button, "mode", mode.name());
            }
            case "close_container" -> {
                int container = command.has("container") ? command.get("container").getAsInt() : windowId;
                if (container <= 0) throw new IllegalStateException("No open container window to close");
                send(new ServerboundContainerClosePacket(container));
                if (container == windowId) { windowId = 0; windowState = 0; }
                event("container_close_sent", "container", container);
            }
            case "expect_disconnect" -> { expectedDisconnect=true;event("disconnect_armed"); }
            case "close" -> close();
            default -> throw new IllegalArgumentException("Unknown QA operation");
        }
    }

    private static boolean flag(JsonObject command, String key) {
        if (!command.has(key)) return false;
        if (!command.get(key).isJsonPrimitive() || !command.getAsJsonPrimitive(key).isBoolean())
            throw new IllegalArgumentException("QA input " + key + " must be a boolean");
        return command.get(key).getAsBoolean();
    }

    private static double coordinate(JsonObject command,String key) {
        double value=command.get(key).getAsDouble();
        if(!Double.isFinite(value)||Math.abs(value)>30_000_000)throw new IllegalArgumentException("Finite bounded QA coordinate required");
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void send(Packet<?> packet) throws Exception {
        ByteBuf buffer = Unpooled.buffer();
        byte[] bytes;
        try {
            ((StreamCodec) outgoing.codec()).encode(buffer, packet);
            bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
        } finally { buffer.release(); }
        if (compression >= 0) {
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            if (bytes.length >= compression) {
                writeVarInt(framed, bytes.length);
                try (DeflaterOutputStream deflater = new DeflaterOutputStream(framed)) { deflater.write(bytes); }
            } else { writeVarInt(framed, 0); framed.write(bytes); }
            bytes = framed.toByteArray();
        }
        writeVarInt(output, bytes.length);
        output.write(bytes);
        output.flush();
    }

    private void event(String type, Object... pairs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", type);
        event.put("username", username);
        for (int i = 0; i < pairs.length; i += 2) event.put((String) pairs[i], pairs[i + 1]);
        synchronized (EVENTS) { EVENTS.println(JSON.toJson(event)); }
    }
    private void fail(Throwable error) { event("error", "message", error.toString()); error.printStackTrace(System.err); close(); }
    private void close() { if (!closed) { closed = true; try { socket.close(); } catch (IOException ignored) {} event("closed"); } }
    private static String sha256(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        for (int n = 0; n < 5; n++) { int b = input.read(); if (b < 0) throw new EOFException(); value |= (b & 127) << (7 * n); if ((b & 128) == 0) return value; }
        throw new IOException("Oversized VarInt");
    }
    private static void writeVarInt(OutputStream output, int value) throws IOException {
        do { int b = value & 127; value >>>= 7; output.write(value == 0 ? b : b | 128); } while (value != 0);
    }
}
