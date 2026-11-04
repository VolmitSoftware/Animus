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
import net.minecraft.world.phys.Vec3;

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
    private boolean positioned;
    private volatile PositionMoveRotation position = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0, 0);
    private static final Set<String> DECODE = Set.of(
        "add_entity", "player_info_update", "player_info_remove", "remove_entities",
        "entity_position_sync", "teleport_entity", "player_position", "system_chat",
        "keep_alive", "ping", "disconnect", "chunk_batch_finished", "set_entity_data",
        "set_entity_motion", "set_equipment", "set_passengers", "animate", "rotate_head");

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
                    if (client.loaded) client.send(ServerboundClientTickEndPacket.INSTANCE);
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
        else if (packet instanceof ClientboundPlayerPositionPacket p) {
            // The headless observer can finish login before the server has run
            // the joining player's first tick. Allow that initial tick before
            // acknowledging; a graphical client naturally waits on world load.
            if (!positioned) { Thread.sleep(200); positioned = true; }
            position = PositionMoveRotation.calculateAbsolute(position, p.change(), p.relatives());
            Vec3 v = position.position();
            for (Packet<?> acknowledgment : ProtocolRevision.acknowledgments(p.id(), position)) send(acknowledgment);
            if (!loaded) { loaded = true; send(new ServerboundPlayerLoadedPacket()); }
            event("position", "x", v.x, "y", v.y, "z", v.z, "yaw", position.yRot(), "pitch", position.xRot());
        } else if (packet instanceof ClientboundSystemChatPacket p) event("chat", "text", p.content().getString());
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
                event("player_info", "uuid", entry.profileId().toString(), "actions", p.actions().stream().map(Enum::name).toList(), "textures", textures);
            }
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket p) event("player_info_remove", "uuids", p.profileIds().stream().map(UUID::toString).toList());
        else if (packet instanceof ClientboundRemoveEntitiesPacket p) event("remove", "ids", ProtocolRevision.removed(p));
        else if (packet instanceof ClientboundEntityPositionSyncPacket p) emitMove(p.id(), ProtocolRevision.position(p));
        else if (packet instanceof ClientboundTeleportEntityPacket p) emitMove(p.id(), p.change().position());
        else if (packet instanceof ClientboundSetPassengersPacket p) event("passengers", "vehicle", p.getVehicle(), "ids", p.getPassengers());
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
        else if (packet instanceof ClientboundDisconnectPacket p) throw new IOException("Disconnected: " + p.reason().getString());
        else if (packet instanceof ClientboundLoginDisconnectPacket p) throw new IOException("Login disconnected: " + p.reason().getString());
    }

    private void emitMove(int id, Vec3 v) { event("move", "id", id, "x", v.x, "y", v.y, "z", v.z); }

    private void control(JsonObject command) throws Exception {
        switch (command.get("op").getAsString()) {
            case "command" -> send(new ServerboundChatCommandPacket(command.get("text").getAsString().replaceFirst("^/", "")));
            case "look" -> {
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
            case "attack" -> send(new ServerboundAttackPacket(command.get("id").getAsInt()));
            case "use" -> send(new ServerboundInteractPacket(command.get("id").getAsInt(), InteractionHand.MAIN_HAND, Vec3.ZERO, false));
            case "close" -> close();
            default -> throw new IllegalArgumentException("Unknown QA operation");
        }
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
