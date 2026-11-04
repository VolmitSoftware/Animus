package art.arcane.animus.bukkit.packet;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PacketInputValuesTest {
    @Test void rejectNonFiniteCoordinatesAndInvalidPitchBeforePacketConstruction() {
        UUID world = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Pose(world, Double.NaN, 64, 0, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Pose(world, 0, 64, Double.POSITIVE_INFINITY, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Pose(world, 0, 64, 0, Float.NaN, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Pose(world, 0, 64, 0, 0, 91, true));
        assertEquals(-90, new NativePacketAdapter.Pose(world, -1, 64, 0, 180, -90, true).pitch());
    }
    @Test void signedTexturesRemainOpaqueAndBounded() {
        var skin = new NativePacketAdapter.Skin("e30=", "c2lnbmF0dXJl");
        assertEquals("e30=", skin.textureValue());
        assertEquals("c2lnbmF0dXJl", skin.signature());
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Skin("not base64!", null));
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Skin("e30=", "bad signature!"));
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Skin("A".repeat(32769), null));
        assertThrows(IllegalArgumentException.class, () -> new NativePacketAdapter.Skin("e30=", "A".repeat(8193)));
    }
}
