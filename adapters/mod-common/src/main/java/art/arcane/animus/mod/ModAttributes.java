package art.arcane.animus.mod;

import art.arcane.animus.core.npc.NpcAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/**
 * Update-attributes packets for virtual bodies on mod servers. Only attributes this server registers and the body's
 * entity type carries are sent, so a client never sees an attribute its entity lacks.
 */
final class ModAttributes {
    private ModAttributes() {}

    /** The packet setting every attribute the type carries, or null when none applies. */
    @SuppressWarnings("unchecked")
    static ClientboundUpdateAttributesPacket packet(int entityId, EntityType<?> type, NpcAttributes attributes) {
        if (attributes.isEmpty() || !DefaultAttributes.hasSupplier(type)) return null;
        AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
        List<AttributeInstance> instances = new ArrayList<>();
        for (Map.Entry<String, Double> entry : attributes.values().entrySet()) {
            Optional<Holder.Reference<Attribute>> holder = BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(entry.getKey()));
            if (holder.isEmpty() || !supplier.hasAttribute(holder.get())) continue;
            AttributeInstance instance = new AttributeInstance(holder.get(), (AttributeInstance changed) -> {});
            instance.setBaseValue(entry.getValue());
            instances.add(instance);
        }
        return instances.isEmpty() ? null : new ClientboundUpdateAttributesPacket(entityId, instances);
    }
}
