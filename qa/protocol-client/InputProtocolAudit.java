import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exhaustive local audit using the exact native input codec, never a version alias. */
public final class InputProtocolAudit {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        if (args.length != 1 || !SharedConstants.getCurrentVersion().name().equals(args[0]))
            throw new IllegalArgumentException("Exact jar version required");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int minimum = Integer.MAX_VALUE, maximum = 0;
        for (int mask = 0; mask < 128; mask++) {
            Input keys = new Input((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                    (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0);
            if (keys.forward() != ((mask & 1) != 0) || keys.backward() != ((mask & 2) != 0)
                    || keys.left() != ((mask & 4) != 0) || keys.right() != ((mask & 8) != 0)
                    || keys.jump() != ((mask & 16) != 0) || keys.shift() != ((mask & 32) != 0)
                    || keys.sprint() != ((mask & 64) != 0)) throw new AssertionError("Native constructor order changed");
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                var packet = new ServerboundPlayerInputPacket(keys);
                ServerboundPlayerInputPacket.STREAM_CODEC.encode(buffer, packet);
                minimum = Math.min(minimum, buffer.readableBytes()); maximum = Math.max(maximum, buffer.readableBytes());
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), bytes); digest.update(bytes);
                var decoded = ServerboundPlayerInputPacket.STREAM_CODEC.decode(buffer);
                if (!decoded.input().equals(keys) || buffer.isReadable()) throw new AssertionError("Native input roundtrip failed at mask " + mask);
            } finally { buffer.release(); }
        }
        System.out.println("{\"version\":\"" + args[0] + "\",\"protocol\":" + SharedConstants.getProtocolVersion()
                + ",\"nativeInputs\":128,\"minimumPayloadBytes\":" + minimum + ",\"maximumPayloadBytes\":" + maximum
                + ",\"payloadSequenceSha256\":\"" + HexFormat.of().formatHex(digest.digest()) + "\"}");
    }
}
