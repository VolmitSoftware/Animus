package art.arcane.animus.bukkit.packet;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PassengerRosterTest {
    @Test void virtualPassengersKeepTheirOrderAndTheRealRiderIsAppendedOnce() {
        assertArrayEquals(new int[]{4, 9}, NativePacketAdapter.passengerRoster(List.of(4, 9), NativePacketAdapter.NO_REAL_RIDER));
        assertArrayEquals(new int[]{4, 9, 77}, NativePacketAdapter.passengerRoster(List.of(4, 9), 77));
        assertArrayEquals(new int[]{77}, NativePacketAdapter.passengerRoster(List.of(), 77));
        assertArrayEquals(new int[0], NativePacketAdapter.passengerRoster(List.of(), NativePacketAdapter.NO_REAL_RIDER));
    }
    @Test void aRealRiderCannotShareANativeIdWithAVirtualBody() {
        assertThrows(IllegalArgumentException.class, () -> NativePacketAdapter.passengerRoster(List.of(4, 77), 77));
    }
    @Test void rosterStaysWithinThePassengerGraphLimit() {
        List<Integer> sixteen = new ArrayList<>();
        for (int i = 1; i <= 16; i++) sixteen.add(i);
        assertEquals(16, NativePacketAdapter.passengerRoster(sixteen, NativePacketAdapter.NO_REAL_RIDER).length);
        assertThrows(IllegalArgumentException.class, () -> NativePacketAdapter.passengerRoster(sixteen, 77));
    }
    @Test void multipartBodiesReserveTheirNativeRange() {
        assertEquals(9, NativePacketAdapter.reservedIds("minecraft:ender_dragon"));
        assertEquals(1, NativePacketAdapter.reservedIds("minecraft:horse"));
    }
    @Test void onlyDragonBodiesMirrorTheirWireRotation() {
        assertEquals(30f, NativePacketAdapter.wireYaw("minecraft:horse", 30f));
        assertEquals(-150f, NativePacketAdapter.wireYaw("minecraft:ender_dragon", 30f));
    }
}
