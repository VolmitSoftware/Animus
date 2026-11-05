package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeleportSettleTest {
    private static final Cell PATH = Cell.layer(0.9375);
    private static WorldPosition at(double x, double y, double z) { return new WorldPosition("world", new Vec3(x, y, z)); }

    @Test void aCatchUpSpotOnADirtPathRestsOnThePathTop() {
        // A charge on the path stands at 63.9375; the catch-up spot names block 63, which the path fills.
        MotionValidator.Sampler road = (int x, int y, int z) -> y < 63 ? Cell.SOLID : y == 63 ? PATH : Cell.AIR;
        Optional<WorldPosition> settled = RuntimeCombatWorld.settle(road, .6, 1.8, at(4.5, 63, 0.5));
        assertEquals(Optional.of(at(4.5, 63.9375, 0.5)), settled);
    }

    @Test void fullBlocksAndEmptyColumnsAreLeftToTheExactCheck() {
        MotionValidator.Sampler grass = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        assertEquals(Optional.of(at(4.5, 64, 0.5)), RuntimeCombatWorld.settle(grass, .6, 1.8, at(4.5, 64, 0.5)));
        MotionValidator.Sampler sky = (int x, int y, int z) -> Cell.AIR;
        assertEquals(Optional.empty(), RuntimeCombatWorld.settle(sky, .6, 1.8, at(4.5, 64, 0.5)));
    }
}
