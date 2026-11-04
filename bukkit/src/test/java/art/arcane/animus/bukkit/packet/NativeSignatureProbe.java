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
        method(packets + "ClientboundSetEquipmentPacket", "<init>", "(ILjava/util/List;)V");
        method("com/mojang/datafixers/util/Pair", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;");
        for (String slot : List.of("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET", "BODY", "SADDLE"))
            field(entity + "EquipmentSlot", slot, "L" + entity + "EquipmentSlot;");
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
        // Seats, dragon phase and real-rider placement.
        String attachments = entity + "EntityAttachments";
        method(entity + "EntityType", "getDimensions", "()L" + entity + "EntityDimensions;");
        method(entity + "EntityDimensions", "attachments", "()L" + attachments + ";");
        method(attachments, "getClamped", "(L" + entity + "EntityAttachment;IF)" + vec3);
        field(entity + "EntityAttachment", "PASSENGER", "L" + entity + "EntityAttachment;");
        field(entity + "EntityAttachment", "VEHICLE", "L" + entity + "EntityAttachment;");
        method("net/minecraft/world/phys/Vec3", "x", "()D");
        method("net/minecraft/world/phys/Vec3", "y", "()D");
        method("net/minecraft/world/phys/Vec3", "z", "()D");
        field(entity + "boss/enderdragon/EnderDragon", "DATA_PHASE", "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        // Poses, armor stand parts, text displays and hidden profile names.
        String accessor = "Lnet/minecraft/network/syncher/EntityDataAccessor;";
        field(entity + "Entity", "DATA_SHARED_FLAGS_ID", accessor);
        field(entity + "Entity", "DATA_POSE", accessor);
        for (String pose : List.of("STANDING", "CROUCHING", "SLEEPING", "SWIMMING")) field(entity + "Pose", pose, "L" + entity + "Pose;");
        for (String part : List.of("DATA_CLIENT_FLAGS", "DATA_HEAD_POSE", "DATA_BODY_POSE", "DATA_LEFT_ARM_POSE", "DATA_RIGHT_ARM_POSE", "DATA_LEFT_LEG_POSE", "DATA_RIGHT_LEG_POSE"))
            field(entity + "decoration/ArmorStand", part, accessor);
        method("net/minecraft/core/Rotations", "<init>", "(FFF)V");
        field(entity + "Display", "DATA_TRANSLATION_ID", accessor);
        field(entity + "Display", "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID", accessor);
        field(entity + "Display$TextDisplay", "DATA_TEXT_ID", accessor);
        field(entity + "Display$TextDisplay", "DATA_LINE_WIDTH_ID", accessor);
        method("net/minecraft/network/chat/Component", "literal", "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;");
        String scores = "net/minecraft/world/scores/";
        method(scores + "Scoreboard", "<init>", "()V");
        method(scores + "PlayerTeam", "<init>", "(L" + scores + "Scoreboard;Ljava/lang/String;)V");
        method(scores + "PlayerTeam", "setNameTagVisibility", "(L" + scores + "Team$Visibility;)V");
        method(scores + "PlayerTeam", "setCollisionRule", "(L" + scores + "Team$CollisionRule;)V");
        method(scores + "Scoreboard", "addPlayerToTeam", "(Ljava/lang/String;L" + scores + "PlayerTeam;)Z");
        field(scores + "Team$Visibility", "NEVER", "L" + scores + "Team$Visibility;");
        field(scores + "Team$CollisionRule", "NEVER", "L" + scores + "Team$CollisionRule;");
        method(packets + "ClientboundSetPlayerTeamPacket", "createAddOrModifyPacket", "(L" + scores + "PlayerTeam;Z)L" + packets + "ClientboundSetPlayerTeamPacket;");
        method(packets + "ClientboundSetPlayerTeamPacket", "createRemovePacket", "(L" + scores + "PlayerTeam;)L" + packets + "ClientboundSetPlayerTeamPacket;");
        method(entity + "Entity", "setPos", "(DDD)V");
        method("net/minecraft/server/level/ServerPlayer", "level", "()Lnet/minecraft/server/level/ServerLevel;");
        method("net/minecraft/server/level/ServerLevel", "getChunkSource", "()Lnet/minecraft/server/level/ServerChunkCache;");
        method("net/minecraft/server/level/ServerChunkCache", "move", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method("net/minecraft/server/network/ServerGamePacketListenerImpl", "resetPosition", "()V");
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
