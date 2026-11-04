package art.arcane.animus.core.virtual;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HitboxTest {
    @Test void rayUsesNormalizedDirectionAndSurfaceReach() {
        Hitbox box = Hitbox.atFeet(new Vec3(0, 0, 4), 1, 2);
        assertEquals(3.5, box.rayDistance(new Vec3(0, 1.6, 0), new Vec3(0, 0, 500), 4).orElseThrow(), 1e-12);
        assertTrue(box.rayDistance(new Vec3(0, 1.6, 0), new Vec3(0, 0, 1), 3.49).isEmpty());
        assertEquals(3.5, box.rayDistance(new Vec3(0, 1.6, 0), new Vec3(0, 0, 1), 3.5).orElseThrow(), 1e-12);
    }
    @Test void rejectsMissesReverseRaysAndInvalidGeometry() {
        Hitbox box = Hitbox.atFeet(new Vec3(0, 0, 4), 1, 2);
        assertTrue(box.rayDistance(new Vec3(1, 1, 0), new Vec3(0, 0, 1), 6).isEmpty());
        assertTrue(box.rayDistance(new Vec3(0, 1, 0), new Vec3(0, 0, -1), 6).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> box.rayDistance(Vec3.ZERO, Vec3.ZERO, 6));
        assertThrows(IllegalArgumentException.class, () -> box.rayDistance(Vec3.ZERO, new Vec3(0, 0, 1), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Vec3(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Hitbox.atFeet(Vec3.ZERO, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> new Hitbox(Vec3.ZERO, Vec3.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorldPosition("world", new Vec3(30_000_001, 0, 0)));
    }
    @Test void handlesParallelPlanesInsideOriginsAndExtremeDirections() {
        Hitbox box = Hitbox.atFeet(Vec3.ZERO, 2, 2);
        assertEquals(0, box.rayDistance(new Vec3(0, 1, 0), new Vec3(0, 0, Double.MIN_VALUE), 0).orElseThrow());
        assertEquals(2, box.rayDistance(new Vec3(-1, 1, -3), new Vec3(0, 0, Double.MAX_VALUE), 4).orElseThrow());
        Vec3 normalized = new Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE).normalized();
        assertEquals(1, Math.hypot(Math.hypot(normalized.x(), normalized.y()), normalized.z()), 1e-12);
        assertEquals(new ChunkPosition("world", -1, -2), new WorldPosition("world", new Vec3(-0.1, 0, -16.1)).chunk());
    }
}
