package art.arcane.animus.mod;

import art.arcane.animus.core.presentation.BodyRules;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * The packets a body's gestures go out as on this Minecraft version: arm swings have their own packet, carrying the held
 * item's animation as vanilla sends it, and the animate packet keeps waking up and critical hits.
 */
final class BodyGestures {
    private BodyGestures() {}

    /** The main-arm swing of placing or breaking a block, with the held item's interact animation. */
    static Packet<ClientGamePacketListener> swing(int entityId, ItemStack held) {
        return new ClientboundSwingAnimationPacket(entityId, InteractionHand.MAIN_HAND, held == null ? SwingAnimation.DEFAULT : held.getInteractAnimation());
    }

    /** One melee attack: the type's own attack event, or the main-arm swing with the held item's attack animation. */
    static Packet<ClientGamePacketListener> attack(int entityId, String typeKey, ItemStack held) {
        if (BodyRules.attacksWithEvent(typeKey)) return entityEvent(entityId, BodyRules.ATTACK_EVENT);
        return new ClientboundSwingAnimationPacket(entityId, InteractionHand.MAIN_HAND, held == null ? SwingAnimation.DEFAULT : held.getAttackAnimation());
    }

    /** A gesture by its animate action before 26.3 (see {@code BodyTransport.gesture}); null for a code with no gesture. */
    static Packet<ClientGamePacketListener> gesture(int entityId, int action, ItemStack held) {
        return switch (action) {
            case 0 -> swing(entityId, held);
            case 3 -> new ClientboundSwingAnimationPacket(entityId, InteractionHand.OFF_HAND, SwingAnimation.DEFAULT);
            case 2 -> animate(entityId, ClientboundAnimatePacket.WAKE_UP);
            case 4 -> animate(entityId, ClientboundAnimatePacket.CRITICAL_HIT);
            case 5 -> animate(entityId, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);
            default -> null;
        };
    }

    private static Packet<ClientGamePacketListener> animate(int entityId, int action) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8));
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(action);
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }

    private static Packet<ClientGamePacketListener> entityEvent(int entityId, byte event) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8));
        try {
            buffer.writeInt(entityId);
            buffer.writeByte(event);
            return ClientboundEntityEventPacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }
}
