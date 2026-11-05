package art.arcane.animus.mod;

import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

/** Motion details of this Minecraft version: the absolute position packet and the velocity resend flag. */
final class BodyMotion {
    private BodyMotion() {}

    static Packet<ClientGamePacketListener> move(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        return new ClientboundEntityPositionSyncPacket(entityId, new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch), onGround);
    }

    /** Sends a changed velocity of a real entity to its client and trackers. */
    static void resendVelocity(Entity entity) { entity.hurtMarked = true; }
}
