package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.npc.NpcAttributes;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Update-attributes packets for virtual bodies. Only attributes this server registers and the body's entity type carries
 * are sent, so a client never sees an attribute its entity lacks; the rest are left out of the packet.
 */
final class NativeAttributes {
    private final Object registry;
    private final Method identifierParse, registryGet, setBase, hasSupplier, getSupplier, hasAttribute;
    private final Constructor<?> instance, packet;
    private final Map<String, Optional<Object>> holders = new ConcurrentHashMap<>();

    NativeAttributes(Method identifierParse) throws ReflectiveOperationException {
        this.identifierParse = identifierParse;
        Class<?> holder = Class.forName("net.minecraft.core.Holder");
        Class<?> attributeInstance = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeInstance");
        Class<?> defaults = Class.forName("net.minecraft.world.entity.ai.attributes.DefaultAttributes");
        Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
        registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ATTRIBUTE").get(null);
        registryGet = Class.forName("net.minecraft.core.Registry").getMethod("get", identifierParse.getReturnType());
        instance = attributeInstance.getConstructor(holder, Consumer.class);
        setBase = attributeInstance.getMethod("setBaseValue", double.class);
        hasSupplier = defaults.getMethod("hasSupplier", entityType);
        getSupplier = defaults.getMethod("getSupplier", entityType);
        hasAttribute = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeSupplier").getMethod("hasAttribute", holder);
        packet = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket").getConstructor(int.class, java.util.Collection.class);
    }

    /** The packet setting every attribute the body's type carries, or null when none applies. */
    Object packet(int entityId, Object nativeType, NpcAttributes attributes) throws ReflectiveOperationException {
        if (attributes.isEmpty() || !(boolean) hasSupplier.invoke(null, nativeType)) return null;
        Object supplier = getSupplier.invoke(null, nativeType);
        List<Object> instances = new ArrayList<>();
        Consumer<Object> ignored = (Object changed) -> {};
        for (Map.Entry<String, Double> entry : attributes.values().entrySet()) {
            Optional<Object> found = holder(entry.getKey());
            if (found.isEmpty() || !(boolean) hasAttribute.invoke(supplier, found.get())) continue;
            Object created = instance.newInstance(found.get(), ignored);
            setBase.invoke(created, entry.getValue());
            instances.add(created);
        }
        return instances.isEmpty() ? null : packet.newInstance(entityId, instances);
    }

    private Optional<Object> holder(String id) {
        return holders.computeIfAbsent(id, (String key) -> {
            try { return ((Optional<?>) registryGet.invoke(registry, identifierParse.invoke(null, NpcAttributes.NAMESPACE + key))).map((Object found) -> found); }
            catch (ReflectiveOperationException unavailable) { return Optional.empty(); }
        });
    }
}
