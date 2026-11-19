package art.arcane.animus.bukkit.packet;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Java 25 source launcher: java NativeSignatureProbe.java <26.1.2|26.2|26.3> <extracted-server.jar> <library.jar>...
 * The libraries are the server's own datafixerupper, authlib, guava and joml jars. Reads bytecode without loading or
 * bootstrapping Minecraft. Covers the Minecraft and library signatures this package binds, not CraftBukkit's or the
 * per-type body settings, which report their own gaps at startup. Structural evidence only, never gameplay certification.
 */
public final class NativeSignatureProbe {
    private final List<ZipFile> archives;
    private final Map<String, ClassModel> classes = new HashMap<>();
    private int checks;
    private NativeSignatureProbe(List<ZipFile> archives) { this.archives = archives; }
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || !Set.of("26.1.2", "26.2", "26.3").contains(args[0]))
            throw new IllegalArgumentException("Usage: NativeSignatureProbe <exact-version> <extracted-server.jar> <library.jar>...");
        List<ZipFile> jars = new ArrayList<>();
        try {
            for (int i = 1; i < args.length; i++) jars.add(new ZipFile(Path.of(args[i]).toFile()));
            NativeSignatureProbe probe = new NativeSignatureProbe(jars);
            probe.verify(args[0]);
            System.out.println("PASS " + args[0] + ": " + probe.checks + " native signatures; structural check only");
        } finally {
            for (ZipFile jar : jars) jar.close();
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
        method("net/minecraft/world/phys/Vec3", "<init>", "(DDD)V");
        field("net/minecraft/world/phys/Vec3", "ZERO", vec3);
        method(packets + "ClientboundSetEquipmentPacket", "<init>", "(ILjava/util/List;)V");
        method("com/mojang/datafixers/util/Pair", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;");
        for (String slot : List.of("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET", "BODY", "SADDLE"))
            field(entity + "EquipmentSlot", slot, "L" + entity + "EquipmentSlot;");
        method(packets + "ClientboundPlayerInfoRemovePacket", "<init>", "(Ljava/util/List;)V");
        method(packets + "ClientboundPlayerInfoUpdatePacket", "<init>", "(Ljava/util/EnumSet;Ljava/util/Collection;)V");
        field(packets + "ClientboundPlayerInfoUpdatePacket", "entries", "Ljava/util/List;");
        method(packets + "ClientboundPlayerInfoUpdatePacket$Entry", "<init>", "(Ljava/util/UUID;Lcom/mojang/authlib/GameProfile;ZILnet/minecraft/world/level/GameType;Lnet/minecraft/network/chat/Component;ZILnet/minecraft/network/chat/RemoteChatSession$Data;)V");
        String infoAction = packets + "ClientboundPlayerInfoUpdatePacket$Action";
        for (String name : List.of("ADD_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED", "UPDATE_LATENCY", "UPDATE_DISPLAY_NAME", "UPDATE_HAT", "UPDATE_LIST_ORDER"))
            field(infoAction, name, "L" + infoAction + ";");
        field("net/minecraft/world/level/GameType", "SURVIVAL", "Lnet/minecraft/world/level/GameType;");
        method("com/mojang/authlib/GameProfile", "<init>", "(Ljava/util/UUID;Ljava/lang/String;Lcom/mojang/authlib/properties/PropertyMap;)V");
        method("com/mojang/authlib/properties/Property", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        method("com/mojang/authlib/properties/PropertyMap", "<init>", "(Lcom/google/common/collect/Multimap;)V");
        method("com/google/common/collect/ArrayListMultimap", "create", "()Lcom/google/common/collect/ArrayListMultimap;");
        method("com/google/common/collect/Multimap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
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
        // Gestures: 26.3 gives arm swings their own packet and renumbers the animate actions that remain.
        method(packets + "ClientboundAnimatePacket", "<init>", "(" + friendly + ")V");
        method(packets + "ClientboundEntityEventPacket", "<init>", "(" + friendly + ")V");
        method(packets + "ClientboundDamageEventPacket", "<init>", "(ILnet/minecraft/core/Holder;IILjava/util/Optional;)V");
        field(entity + "LivingEntity", "DATA_HEALTH_ID", "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        field("net/minecraft/core/registries/Registries", "DAMAGE_TYPE", "Lnet/minecraft/resources/ResourceKey;");
        field("net/minecraft/world/damagesource/DamageTypes", "GENERIC", "Lnet/minecraft/resources/ResourceKey;");
        method("net/minecraft/server/MinecraftServer", "registryAccess", "()Lnet/minecraft/core/RegistryAccess$Frozen;");
        for (String action : List.of("WAKE_UP", "CRITICAL_HIT", "MAGIC_CRITICAL_HIT")) field(packets + "ClientboundAnimatePacket", action, "I");
        if (version.equals("26.3")) {
            String swing = "Lnet/minecraft/world/item/component/SwingAnimation;";
            method(packets + "ClientboundSwingAnimationPacket", "<init>", "(ILnet/minecraft/world/InteractionHand;" + swing + ")V");
            field("net/minecraft/world/item/component/SwingAnimation", "DEFAULT", swing);
            method("net/minecraft/world/item/ItemStack", "getAttackAnimation", "()" + swing);
            method("net/minecraft/world/item/ItemStack", "getInteractAnimation", "()" + swing);
            for (String hand : List.of("MAIN_HAND", "OFF_HAND")) field("net/minecraft/world/InteractionHand", hand, "Lnet/minecraft/world/InteractionHand;");
        } else {
            for (String action : List.of("SWING_MAIN_HAND", "SWING_OFF_HAND")) field(packets + "ClientboundAnimatePacket", action, "I");
        }
        method(packets + "ClientboundRotateHeadPacket", "<init>", "(" + friendly + ")V");
        method(packets + "ClientboundSetPassengersPacket", "<init>", "(" + friendly + ")V");
        method(packets + "ClientboundSetPassengersPacket", "getVehicle", "()I");
        method(packets + "ClientboundSetPassengersPacket", "getPassengers", "()[I");
        method(packets + "ClientboundBundlePacket", "<init>", "(Ljava/lang/Iterable;)V");
        method("net/minecraft/network/protocol/BundlePacket", "subPackets", "()Ljava/lang/Iterable;");
        method(packets + "ClientboundAddEntityPacket", "getId", "()I");
        method(entity + "Entity", "getPose", "()L" + entity + "Pose;");
        method(entity + "Entity", "getDimensions", "(L" + entity + "Pose;)L" + entity + "EntityDimensions;");
        method("net/minecraft/network/FriendlyByteBuf", "<init>", "(Lio/netty/buffer/ByteBuf;)V");
        method("net/minecraft/network/FriendlyByteBuf", "writeVarInt", "(I)" + friendly);
        method("net/minecraft/network/FriendlyByteBuf", "writeVarIntArray", "([I)" + friendly);
        method(packets + "ServerboundAttackPacket", "entityId", "()I");
        method(packets + "ServerboundInteractPacket", "entityId", "()I");
        method(packets + "ServerboundInteractPacket", "hand", "()Lnet/minecraft/world/InteractionHand;");
        method(packets + "ServerboundInteractPacket", "usingSecondaryAction", "()Z");
        method(packets + "ServerboundPlayerInputPacket", "input", "()Lnet/minecraft/world/entity/player/Input;");
        for (String flag : List.of("forward", "backward", "left", "right", "jump", "shift", "sprint")) method(entity + "player/Input", flag, "()Z");
        load(packets + "ServerboundClientTickEndPacket");
        checks++;
        field("net/minecraft/server/level/ServerPlayer", "connection", "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;");
        field("net/minecraft/server/network/ServerCommonPacketListenerImpl", "connection", "Lnet/minecraft/network/Connection;");
        field("net/minecraft/network/Connection", "channel", "Lio/netty/channel/Channel;");
        method("net/minecraft/resources/Identifier", "parse", "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");
        method("net/minecraft/core/Registry", "containsKey", "(Lnet/minecraft/resources/Identifier;)Z");
        method("net/minecraft/core/Registry", "getValue", "(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;");
        field("net/minecraft/core/registries/BuiltInRegistries", "ENTITY_TYPE", "Lnet/minecraft/core/DefaultedRegistry;");
        method(entity + "EntityType", "getWidth", "()F");
        method(entity + "EntityType", "getHeight", "()F");
        // Attributes a body's type carries.
        String attributes = entity + "ai/attributes/";
        field("net/minecraft/core/registries/BuiltInRegistries", "ATTRIBUTE", "Lnet/minecraft/core/Registry;");
        method("net/minecraft/core/Registry", "get", "(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;");
        method(attributes + "AttributeInstance", "<init>", "(Lnet/minecraft/core/Holder;Ljava/util/function/Consumer;)V");
        method(attributes + "AttributeInstance", "setBaseValue", "(D)V");
        method(attributes + "DefaultAttributes", "hasSupplier", "(L" + entity + "EntityType;)Z");
        method(attributes + "DefaultAttributes", "getSupplier", "(L" + entity + "EntityType;)L" + attributes + "AttributeSupplier;");
        method(attributes + "AttributeSupplier", "hasAttribute", "(Lnet/minecraft/core/Holder;)Z");
        method(packets + "ClientboundUpdateAttributesPacket", "<init>", "(ILjava/util/Collection;)V");
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
        // Settings only one body type has: coats, variants, a chest and the armadillo state.
        field(entity + "animal/equine/Horse", "DATA_ID_TYPE_VARIANT", accessor);
        field(entity + "animal/equine/Llama", "DATA_VARIANT_ID", accessor);
        field(entity + "animal/equine/AbstractChestedHorse", "DATA_ID_CHEST", accessor);
        field(entity + "animal/armadillo/Armadillo", "ARMADILLO_STATE", accessor);
        for (String state : List.of("IDLE", "ROLLING", "SCARED", "UNROLLING"))
            field(entity + "animal/armadillo/Armadillo$ArmadilloState", state, "L" + entity + "animal/armadillo/Armadillo$ArmadilloState;");
        field(entity + "animal/frog/Frog", "DATA_VARIANT_ID", accessor);
        for (String variant : List.of("TEMPERATE", "WARM", "COLD"))
            field(entity + "animal/frog/FrogVariants", variant, "Lnet/minecraft/resources/ResourceKey;");
        field("net/minecraft/core/registries/Registries", "FROG_VARIANT", "Lnet/minecraft/resources/ResourceKey;");
        method("net/minecraft/core/HolderLookup$Provider", "lookupOrThrow", "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/HolderLookup$RegistryLookup;");
        method("net/minecraft/core/HolderGetter", "getOrThrow", "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;");
        field(entity + "Entity", "DATA_SHARED_FLAGS_ID", accessor);
        field(entity + "Entity", "DATA_POSE", accessor);
        field(entity + "LivingEntity", "DATA_LIVING_ENTITY_FLAGS", accessor);
        field(entity + "Mob", "DATA_MOB_FLAGS_ID", accessor);
        field(entity + "monster/illager/Pillager", "IS_CHARGING_CROSSBOW", accessor);
        for (String baby : List.of("AgeableMob", "monster/zombie/Zombie", "monster/piglin/Piglin", "monster/Zoglin")) field(entity + baby, "DATA_BABY_ID", accessor);
        for (String pose : List.of("STANDING", "CROUCHING", "SLEEPING", "SWIMMING", "SPIN_ATTACK", "FALL_FLYING", "SITTING")) field(entity + "Pose", pose, "L" + entity + "Pose;");
        for (String part : List.of("DATA_CLIENT_FLAGS", "DATA_HEAD_POSE", "DATA_BODY_POSE", "DATA_LEFT_ARM_POSE", "DATA_RIGHT_ARM_POSE", "DATA_LEFT_LEG_POSE", "DATA_RIGHT_LEG_POSE"))
            field(entity + "decoration/ArmorStand", part, accessor);
        method("net/minecraft/core/Rotations", "<init>", "(FFF)V");
        // Text displays, rig bones and holograms: every display, item, block and hitbox value they send.
        for (String name : List.of("DATA_TRANSLATION_ID", "DATA_SCALE_ID", "DATA_LEFT_ROTATION_ID", "DATA_RIGHT_ROTATION_ID", "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID",
                "DATA_VIEW_RANGE_ID", "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID", "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID",
                "DATA_POS_ROT_INTERPOLATION_DURATION_ID", "DATA_BRIGHTNESS_OVERRIDE_ID", "DATA_SHADOW_RADIUS_ID", "DATA_SHADOW_STRENGTH_ID", "DATA_GLOW_COLOR_OVERRIDE_ID"))
            field(entity + "Display", name, accessor);
        for (String name : List.of("DATA_TEXT_ID", "DATA_LINE_WIDTH_ID", "DATA_BACKGROUND_COLOR_ID", "DATA_TEXT_OPACITY_ID", "DATA_STYLE_FLAGS_ID"))
            field(entity + "Display$TextDisplay", name, accessor);
        field(entity + "Display$ItemDisplay", "DATA_ITEM_STACK_ID", accessor);
        field(entity + "Display$ItemDisplay", "DATA_ITEM_DISPLAY_ID", accessor);
        field(entity + "Display$BlockDisplay", "DATA_BLOCK_STATE_ID", accessor);
        for (String name : List.of("DATA_WIDTH_ID", "DATA_HEIGHT_ID", "DATA_RESPONSE_ID")) field(entity + "Interaction", name, accessor);
        method("org/joml/Vector3f", "<init>", "(FFF)V");
        method("org/joml/Quaternionf", "<init>", "(FFFF)V");
        field("net/minecraft/core/registries/BuiltInRegistries", "BLOCK", "Lnet/minecraft/core/DefaultedRegistry;");
        method("net/minecraft/world/level/block/Block", "defaultBlockState", "()Lnet/minecraft/world/level/block/state/BlockState;");
        String chat = "net/minecraft/network/chat/";
        method(chat + "Component", "literal", "(Ljava/lang/String;)L" + chat + "MutableComponent;");
        method(chat + "Component", "empty", "()L" + chat + "MutableComponent;");
        method(chat + "MutableComponent", "append", "(L" + chat + "Component;)L" + chat + "MutableComponent;");
        method(chat + "MutableComponent", "setStyle", "(L" + chat + "Style;)L" + chat + "MutableComponent;");
        field(chat + "Style", "EMPTY", "L" + chat + "Style;");
        method(chat + "Style", "withColor", "(I)L" + chat + "Style;");
        method(chat + "Style", "withColor", "(L" + chat + "TextColor;)L" + chat + "Style;");
        method(chat + "TextColor", "fromRgb", "(I)L" + chat + "TextColor;");
        for (String flag : List.of("withBold", "withItalic", "withUnderlined", "withStrikethrough", "withObfuscated"))
            method(chat + "Style", flag, "(Ljava/lang/Boolean;)L" + chat + "Style;");
        String scores = "net/minecraft/world/scores/";
        method(scores + "Scoreboard", "<init>", "()V");
        method(scores + "PlayerTeam", "<init>", "(L" + scores + "Scoreboard;Ljava/lang/String;)V");
        method(scores + "PlayerTeam", "setNameTagVisibility", "(L" + scores + "Team$Visibility;)V");
        method(scores + "PlayerTeam", "setCollisionRule", "(L" + scores + "Team$CollisionRule;)V");
        method(scores + "Scoreboard", "addPlayerToTeam", "(Ljava/lang/String;L" + scores + "PlayerTeam;)Z");
        field(scores + "Team$Visibility", "NEVER", "L" + scores + "Team$Visibility;");
        field(scores + "Team$Visibility", "ALWAYS", "L" + scores + "Team$Visibility;");
        if (version.equals("26.1.2")) method(scores + "PlayerTeam", "setColor", "(Lnet/minecraft/ChatFormatting;)V");
        else { method(scores + "PlayerTeam", "setColor", "(Ljava/util/Optional;)V"); load(scores + "TeamColor"); }
        field(scores + "Team$CollisionRule", "NEVER", "L" + scores + "Team$CollisionRule;");
        method(packets + "ClientboundSetPlayerTeamPacket", "createAddOrModifyPacket", "(L" + scores + "PlayerTeam;Z)L" + packets + "ClientboundSetPlayerTeamPacket;");
        method(packets + "ClientboundSetPlayerTeamPacket", "createRemovePacket", "(L" + scores + "PlayerTeam;)L" + packets + "ClientboundSetPlayerTeamPacket;");
        method(entity + "Entity", "setPos", "(DDD)V");
        method("net/minecraft/server/level/ServerPlayer", "level", "()Lnet/minecraft/server/level/ServerLevel;");
        method("net/minecraft/server/level/ServerLevel", "getChunkSource", "()Lnet/minecraft/server/level/ServerChunkCache;");
        method("net/minecraft/server/level/ServerChunkCache", "move", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method("net/minecraft/server/network/ServerGamePacketListenerImpl", "resetPosition", "()V");
        // The Folia preview's viewer epoch.
        method("net/minecraft/SharedConstants", "getCurrentVersion", "()Lnet/minecraft/WorldVersion;");
        method("net/minecraft/WorldVersion", "id", "()Ljava/lang/String;");
        field("net/minecraft/server/network/ServerGamePacketListenerImpl", "awaitingTeleport", "I");
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
        for (ZipFile archive : archives) {
            var entry = archive.getEntry(name + ".class");
            if (entry == null) continue;
            try (var stream = archive.getInputStream(entry)) {
                ClassModel model = ClassFile.of().parse(stream.readAllBytes()); classes.put(name, model); return model;
            }
        }
        throw new IOException("Missing native class " + name);
    }
}
