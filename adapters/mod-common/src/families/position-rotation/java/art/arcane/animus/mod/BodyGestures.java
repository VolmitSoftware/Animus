package art.arcane.animus.mod;

import art.arcane.animus.core.presentation.BodyRules;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.item.ItemStack;

/**
 * The packets a body's gestures go out as on this Minecraft version: every gesture is an animate action, and the client
 * takes a swing's animation from the item it shows in that hand.
 */
final class BodyGestures {
    private BodyGestures() {}

    /** The main-arm swing of placing or breaking a block. */
    static Packet<ClientGamePacketListener> swing(int entityId, ItemStack held) { return animate(entityId, ClientboundAnimatePacket.SWING_MAIN_HAND); }

    /** One melee attack: the type's own attack event, or the main-arm swing. */
    static Packet<ClientGamePacketListener> attack(int entityId, String typeKey, ItemStack held) {
        if (BodyRules.attacksWithEvent(typeKey)) return entityEvent(entityId, BodyRules.ATTACK_EVENT);
        return swing(entityId, held);
    }

    /** A gesture by its animate action before 26.3, which is this version's own; null for a code with no gesture. */
    static Packet<ClientGamePacketListener> gesture(int entityId, int action, ItemStack held) {
        return switch (action) {
            case ClientboundAnimatePacket.SWING_MAIN_HAND, ClientboundAnimatePacket.WAKE_UP, ClientboundAnimatePacket.SWING_OFF_HAND,
                 ClientboundAnimatePacket.CRITICAL_HIT, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> animate(entityId, action);
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
