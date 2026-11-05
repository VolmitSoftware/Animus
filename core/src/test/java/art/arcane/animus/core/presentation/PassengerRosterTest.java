package art.arcane.animus.core.presentation;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PassengerRosterTest {
    @Test void virtualPassengersKeepTheirOrderAndTheRealRiderIsAppendedOnce() {
        assertArrayEquals(new int[]{4, 9}, BodyRules.passengerRoster(List.of(4, 9), BodyRules.NO_REAL_RIDER));
        assertArrayEquals(new int[]{4, 9, 77}, BodyRules.passengerRoster(List.of(4, 9), 77));
        assertArrayEquals(new int[]{77}, BodyRules.passengerRoster(List.of(), 77));
        assertArrayEquals(new int[0], BodyRules.passengerRoster(List.of(), BodyRules.NO_REAL_RIDER));
    }
    @Test void aRealRiderCannotShareANativeIdWithAVirtualBody() {
        assertThrows(IllegalArgumentException.class, () -> BodyRules.passengerRoster(List.of(4, 77), 77));
    }
    @Test void rosterStaysWithinThePassengerGraphLimit() {
        List<Integer> sixteen = new ArrayList<>();
        for (int i = 1; i <= 16; i++) sixteen.add(i);
        assertEquals(16, BodyRules.passengerRoster(sixteen, BodyRules.NO_REAL_RIDER).length);
        assertThrows(IllegalArgumentException.class, () -> BodyRules.passengerRoster(sixteen, 77));
    }
    @Test void aBodysTextDisplayRidesAfterEveryOtherPassenger() {
        assertArrayEquals(new int[]{4, 77, 12}, BodyRules.withText(new int[]{4, 77}, 12));
        assertArrayEquals(new int[]{12}, BodyRules.withText(new int[0], 12));
        assertArrayEquals(new int[]{4}, BodyRules.withText(new int[]{4}, -1));
        int[] sixteen = new int[16];
        assertThrows(IllegalArgumentException.class, () -> BodyRules.withText(sixteen, 99));
    }
    @Test void multipartBodiesReserveTheirNativeRange() {
        assertEquals(9, BodyRules.reservedIds("minecraft:ender_dragon"));
        assertEquals(1, BodyRules.reservedIds("minecraft:horse"));
    }
    @Test void onlyDragonBodiesMirrorTheirWireRotation() {
        assertEquals(30f, BodyRules.wireYaw("minecraft:horse", 30f));
        assertEquals(-150f, BodyRules.wireYaw("minecraft:ender_dragon", 30f));
    }
}
