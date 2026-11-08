package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LandingTest {
    @Test void aheadIsTwoBlocksAlongTheViewTurnedBackToFaceIt() {
        Landing.Spot west = Landing.ahead(new Vec3(12.3, 64, -7.8), 90);
        assertEquals(10.3, west.at().x(), 1e-9);
        assertEquals(-7.8, west.at().z(), 1e-9);
        assertEquals(64, west.at().y());
        assertEquals(-90f, west.yaw(), 1e-4);
        Landing.Spot south = Landing.ahead(new Vec3(0, 70, 0), 0);
        assertEquals(2, south.at().z(), 1e-9);
        assertEquals(-180f, south.yaw(), 1e-4);
    }

    @Test void besideIsOnThePlayersSideFacingTheNpc() {
        Landing.Spot spot = Landing.beside(new Vec3(0, 64, 0), Optional.of(new Vec3(10, 70, 0)));
        assertEquals(2, spot.at().x(), 1e-9);
        assertEquals(0, spot.at().z(), 1e-9);
        assertEquals(64, spot.at().y(), "the NPC's feet height");
        assertEquals(90f, spot.yaw(), 1e-4, "facing west, toward the NPC");
        Landing.Spot overhead = Landing.beside(new Vec3(5, 64, 5), Optional.of(new Vec3(5, 64, 5)));
        assertEquals(new Vec3(5, 64, 7), overhead.at(), "standing on the NPC lands south of it");
        assertEquals(-180f, overhead.yaw(), 1e-4);
        assertEquals(new Vec3(5, 64, 7), Landing.beside(new Vec3(5, 64, 5), Optional.empty()).at());
    }
}
