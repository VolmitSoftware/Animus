package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.presentation.NativeEntityIds;
import io.netty.channel.Channel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The native handles bodies and displays both use, bound once per server. Each transport binds what only it needs on
 * top, so a signature one of them lacks never turns the other off.
 */
final class NativeBindings {
    private static NativeBindings bound;

    private final String version;
    final NativeEntityIds ids;
    final Object entityRegistry, zeroVelocity;
    final Method identifierParse, registryContains, registryValue, dataValue;
    final Constructor<?> addEntity, removeEntities, metadataPacket;
    private final Constructor<?> vector, movement, positionValues;
    /** PositionPath.of on 26.3, which moves in steps; null where a position sync carries PositionMoveRotation. */
    private final Method positionPath;
    final String craftPackage;
    final Method craftPlayerHandle;
    /** CraftItemStack.asNMSCopy, or null on a build without it; see {@link #nativeStack()}. */
    private final Method nativeStack;
    final Field playerConnection;
    private final Field networkConnection, channel;
    final Class<?> attackPacket, interactPacket;
    final Method attackId, interactId, interactHand, interactSecondary;

    static synchronized NativeBindings bind(Plugin plugin, String version) throws ReflectiveOperationException {
        if (bound == null || !bound.version.equals(version)) bound = new NativeBindings(plugin, version);
        return bound;
    }

    private NativeBindings(Plugin plugin, String version) throws ReflectiveOperationException {
        this.version = version;
        Class<?> entityType = type("net.minecraft.world.entity.EntityType");
        Class<?> identifier = type("net.minecraft.resources.Identifier");
        Class<?> registry = type("net.minecraft.core.Registry");
        entityRegistry = type("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
        identifierParse = identifier.getMethod("parse", String.class);
        registryContains = registry.getMethod("containsKey", identifier);
        registryValue = registry.getMethod("getValue", identifier);
        Class<?> allocatorOwner = type(version.equals("26.1.2") ? "net.minecraft.world.entity.Entity" : "net.minecraft.server.level.ServerLevel");
        ids = new NativeEntityIds((AtomicInteger) field(allocatorOwner, "ENTITY_COUNTER").get(null));
        Class<?> vec3 = type("net.minecraft.world.phys.Vec3");
        vector = vec3.getConstructor(double.class, double.class, double.class);
        zeroVelocity = vec3.getField("ZERO").get(null);
        addEntity = type("net.minecraft.network.protocol.game.ClientboundAddEntityPacket").getConstructor(int.class, UUID.class, double.class, double.class,
                double.class, float.class, float.class, entityType, int.class, vec3, double.class);
        removeEntities = type("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
        metadataPacket = type("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);
        dataValue = type("net.minecraft.network.syncher.SynchedEntityData$DataValue").getMethod("create", type("net.minecraft.network.syncher.EntityDataAccessor"), Object.class);
        Class<?> positionSync = type("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
        if (version.equals("26.3")) {
            Class<?> path = type("net.minecraft.world.entity.PositionPath");
            positionPath = path.getMethod("of", vec3); positionValues = null;
            movement = positionSync.getConstructor(int.class, path, float.class, float.class, boolean.class);
        } else {
            Class<?> values = type("net.minecraft.world.entity.PositionMoveRotation");
            positionValues = values.getConstructor(vec3, vec3, float.class, float.class); positionPath = null;
            movement = positionSync.getConstructor(int.class, values, boolean.class);
        }
        craftPackage = plugin.getServer().getClass().getPackageName();
        if (!craftPackage.startsWith("org.bukkit.craftbukkit")) throw new ClassNotFoundException("CraftBukkit implementation unavailable");
        craftPlayerHandle = type(craftPackage + ".entity.CraftPlayer").getMethod("getHandle");
        Method stack = null;
        try { stack = type(craftPackage + ".inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class); }
        catch (ReflectiveOperationException | LinkageError missing) { /* Equipment, rig bones, body settings and displays each refuse without it. */ }
        nativeStack = stack;
        playerConnection = type("net.minecraft.server.level.ServerPlayer").getField("connection");
        networkConnection = field(type("net.minecraft.server.network.ServerCommonPacketListenerImpl"), "connection");
        channel = field(type("net.minecraft.network.Connection"), "channel");
        attackPacket = type("net.minecraft.network.protocol.game.ServerboundAttackPacket");
        interactPacket = type("net.minecraft.network.protocol.game.ServerboundInteractPacket");
        attackId = attackPacket.getMethod("entityId"); interactId = interactPacket.getMethod("entityId");
        interactHand = interactPacket.getMethod("hand"); interactSecondary = interactPacket.getMethod("usingSecondaryAction");
    }

    Object entityType(String key) throws ReflectiveOperationException {
        Object id = identifierParse.invoke(null, key);
        if (!(boolean) registryContains.invoke(entityRegistry, id)) throw new IllegalArgumentException("Native entity type is unavailable: " + key);
        return registryValue.invoke(entityRegistry, id);
    }

    /** A position sync for {@code id}; it carries no velocity. */
    Object move(int id, double x, double y, double z, float yaw, float pitch, boolean onGround) throws ReflectiveOperationException {
        Object position = vector.newInstance(x, y, z);
        if (positionPath != null) return movement.newInstance(id, positionPath.invoke(null, position), yaw, pitch, onGround);
        return movement.newInstance(id, positionValues.newInstance(position, zeroVelocity, yaw, pitch), onGround);
    }

    Channel channel(Player viewer) throws ReflectiveOperationException {
        Object connection = networkConnection.get(playerConnection.get(craftPlayerHandle.invoke(viewer)));
        Channel result = (Channel) channel.get(connection);
        if (result == null || !result.isActive()) throw new BodyTransport.ViewerUnwatched("Viewer is not connected");
        return result;
    }

    /** Each feature that converts items binds this, so a build without it turns off only those features. */
    Method nativeStack() throws NoSuchMethodException {
        if (nativeStack == null) throw new NoSuchMethodException("CraftItemStack.asNMSCopy");
        return nativeStack;
    }

    static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        if (!result.trySetAccessible()) throw new NoSuchFieldException("Cannot access " + owner.getName() + "." + name);
        return result;
    }
    static Object accessor(Class<?> owner, String name) throws ReflectiveOperationException { return field(owner, name).get(null); }
}
