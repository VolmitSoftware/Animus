import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

/** Exact 26.3 native API. Wire IDs and codecs still come from its own jar. */
final class ProtocolRevision {
    /** 26.3 sends the main-arm swing as a punch with no hand. */
    static Packet<?> swing() { return ServerboundPunchPacket.INSTANCE; }
    /** 26.3 sends an arm swing in its own packet, with the hand and the swing animation; null for any other packet. */
    static Object[] armSwing(Packet<?> packet) {
        return packet instanceof ClientboundSwingAnimationPacket p
                ? new Object[]{"id", p.entityId(), "hand", p.hand().name(), "animation", p.animation().type().getSerializedName()} : null;
    }
    static List<Packet<?>> acknowledgments(int id, PositionMoveRotation position) {
        Vec3 v = position.position();
        // Verified against 26.3 ClientPacketListener.handleMovePlayer: the
        // acknowledgment embeds position, with no following movement packet.
        return List.of(new ServerboundAcceptTeleportationPacket(id, v.x, v.y, v.z, position.yRot(), position.xRot()));
    }
    static int[] removed(ClientboundRemoveEntitiesPacket packet) { return packet.entityIds().toIntArray(); }
    static Vec3 position(ClientboundEntityPositionSyncPacket packet) { return packet.position().endPosition(); }
    static float yaw(ClientboundEntityPositionSyncPacket packet) { return packet.yRot(); }
    static Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags(ClientboundUpdateTagsPacket packet) { return packet.tags(); }
    static ParticleOptions particle(ClientboundLevelParticlesPacket packet) { return packet.particle(); }
    static int particleCount(ClientboundLevelParticlesPacket packet) { return packet.count(); }
}
