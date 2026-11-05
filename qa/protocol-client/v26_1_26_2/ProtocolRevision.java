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

/** Shared native method signatures, compiled separately against each exact jar.
 * This does not share or relabel protocol IDs, entity data, or packet codecs.
 */
final class ProtocolRevision {
    static List<Packet<?>> acknowledgments(int id, PositionMoveRotation position) {
        // Both exact client jars send the ID acknowledgment then PosRot.
        return List.of(new ServerboundAcceptTeleportationPacket(id),
                new ServerboundMovePlayerPacket.PosRot(position.position(), position.yRot(), position.xRot(), false, false));
    }
    static int[] removed(ClientboundRemoveEntitiesPacket packet) { return packet.getEntityIds().toIntArray(); }
    static Vec3 position(ClientboundEntityPositionSyncPacket packet) { return packet.values().position(); }
    static Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags(ClientboundUpdateTagsPacket packet) { return packet.getTags(); }
    static ParticleOptions particle(ClientboundLevelParticlesPacket packet) { return packet.getParticle(); }
    static int particleCount(ClientboundLevelParticlesPacket packet) { return packet.getCount(); }
}
