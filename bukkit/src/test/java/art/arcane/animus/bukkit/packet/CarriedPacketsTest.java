package art.arcane.animus.bukkit.packet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** A real entity's own passengers keep their order and come first; carried IDs follow once each. */
class CarriedPacketsTest {
    @Test void theServersPassengersComeFirstAndNothingRepeats() {
        assertArrayEquals(new int[]{7, 3, 40}, CarriedPackets.union(new int[]{7, 3}, new int[]{40}));
        assertArrayEquals(new int[]{7, 40}, CarriedPackets.union(new int[]{7, 40}, new int[]{40}), "a roster Animus sent itself stays the same");
        assertArrayEquals(new int[]{40, 41}, CarriedPackets.union(new int[0], new int[]{40, 41}));
        assertArrayEquals(new int[]{7}, CarriedPackets.union(new int[]{7}, new int[0]));
    }
}
