package art.arcane.animus.bukkit.packet;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Java 25 source launcher: java NativeSignatureProbe.java <26.1.2|26.2|26.3> <extracted-server.jar>.
 * Reads bytecode without loading/bootstrapping Minecraft. Structural evidence only, never gameplay certification.
 */
public final class NativeSignatureProbe {
    private final ZipFile archive;
    private final Map<String, ClassModel> classes = new HashMap<>();
    private int checks;
    private NativeSignatureProbe(ZipFile archive) { this.archive = archive; }
    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !Set.of("26.1.2", "26.2", "26.3").contains(args[0]))
            throw new IllegalArgumentException("Usage: NativeSignatureProbe <exact-version> <extracted-server.jar>");
        try (ZipFile jar = new ZipFile(Path.of(args[1]).toFile())) {
            NativeSignatureProbe probe = new NativeSignatureProbe(jar);
            probe.verify(args[0]);
            System.out.println("PASS " + args[0] + ": " + probe.checks + " native signatures; structural check only");
        }
    }
    private void verify(String version) throws IOException {
        String packets = "net/minecraft/network/protocol/game/";
        String entity = "net/minecraft/world/entity/";
        String vec3 = "Lnet/minecraft/world/phys/Vec3;";
        String friendly = "Lnet/minecraft/network/FriendlyByteBuf;";
        field(version.equals("26.1.2") ? entity + "Entity" : "net/minecraft/server/level/ServerLevel", "ENTITY_COUNTER", "Ljava/util/concurrent/atomic/AtomicInteger;");
        method(packets + "ClientboundAddEntityPacket", "<init>", "(ILjava/util/UUID;DDDFFLnet/minecraft/world/entity/EntityType;I" + vec3 + "D)V");
        method(packets + "ClientboundRemoveEntitiesPacket", "<init>", "([I)V");
        method(packets + "ClientboundPlayerInfoRemovePacket", "<init>", "(Ljava/util/List;)V");
        method(packets + "ClientboundPlayerInfoUpdatePacket", "<init>", "(Ljava/util/EnumSet;Ljava/util/Collection;)V");
        field(packets + "ClientboundPlayerInfoUpdatePacket", "entries", "Ljava/util/List;");
        method(packets + "ClientboundPlayerInfoUpdatePacket$Entry", "<init>", "(Ljava/util/UUID;Lcom/mojang/authlib/GameProfile;ZILnet/minecraft/world/level/GameType;Lnet/minecraft/network/chat/Component;ZILnet/minecraft/network/chat/RemoteChatSession$Data;)V");
        method(packets + "ClientboundSetEntityDataPacket", "<init>", "(ILjava/util/List;)V");
        method("net/minecraft/network/syncher/SynchedEntityData$DataValue", "create", "(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)Lnet/minecraft/network/syncher/SynchedEntityData$DataValue;");
        field(entity + "Avatar", "DATA_PLAYER_MODE_CUSTOMISATION", "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        if (version.equals("26.3")) {
            method(packets + "ClientboundEntityPositionSyncPacket", "<init>", "(ILnet/minecraft/world/entity/PositionPath;FFZ)V");
            method(entity + "PositionPath", "of", "(" + vec3 + ")Lnet/minecraft/world/entity/PositionPath;");
        } else {
            method(packets + "ClientboundEntityPositionSyncPacket", "<init>", "(ILnet/minecraft/world/entity/PositionMoveRotation;Z)V");
            method(entity + "PositionMoveRotation", "<init>", "(" + vec3 + vec3 + "FF)V");
        }
        method(packets + "ClientboundRotateHeadPacket", "<init>", "(" + friendly + ")V");
        method(packets + "ClientboundSetPassengersPacket", "<init>", "(" + friendly + ")V");
        method("net/minecraft/network/FriendlyByteBuf", "<init>", "(Lio/netty/buffer/ByteBuf;)V");
        method("net/minecraft/network/FriendlyByteBuf", "writeVarInt", "(I)" + friendly);
        method("net/minecraft/network/FriendlyByteBuf", "writeVarIntArray", "([I)" + friendly);
        method(packets + "ServerboundAttackPacket", "entityId", "()I");
        method(packets + "ServerboundInteractPacket", "entityId", "()I");
        method(packets + "ServerboundInteractPacket", "hand", "()Lnet/minecraft/world/InteractionHand;");
        method(packets + "ServerboundInteractPacket", "usingSecondaryAction", "()Z");
        field("net/minecraft/server/level/ServerPlayer", "connection", "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;");
        field("net/minecraft/server/network/ServerCommonPacketListenerImpl", "connection", "Lnet/minecraft/network/Connection;");
        field("net/minecraft/network/Connection", "channel", "Lio/netty/channel/Channel;");
        method("net/minecraft/resources/Identifier", "parse", "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");
        method("net/minecraft/core/Registry", "containsKey", "(Lnet/minecraft/resources/Identifier;)Z");
        method("net/minecraft/core/Registry", "getValue", "(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;");
        field("net/minecraft/core/registries/BuiltInRegistries", "ENTITY_TYPE", "Lnet/minecraft/core/DefaultedRegistry;");
        method(entity + "EntityType", "getWidth", "()F");
        method(entity + "EntityType", "getHeight", "()F");
    }
    private void method(String owner, String name, String descriptor) throws IOException {
        if (load(owner).methods().stream().noneMatch(method -> method.methodName().stringValue().equals(name) && method.methodType().stringValue().equals(descriptor)))
            throw new IOException("Missing native method " + owner + "." + name + descriptor);
        checks++;
    }
    private void field(String owner, String name, String descriptor) throws IOException {
        if (load(owner).fields().stream().noneMatch(field -> field.fieldName().stringValue().equals(name) && field.fieldType().stringValue().equals(descriptor)))
            throw new IOException("Missing native field " + owner + "." + name + ":" + descriptor);
        checks++;
    }
    private ClassModel load(String name) throws IOException {
        ClassModel cached = classes.get(name);
        if (cached != null) return cached;
        var entry = archive.getEntry(name + ".class");
        if (entry == null) throw new IOException("Missing native class " + name);
        try (var stream = archive.getInputStream(entry)) {
            ClassModel model = ClassFile.of().parse(stream.readAllBytes()); classes.put(name, model); return model;
        }
    }
}
