package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollisionCellsTest {
    private static final VoxelSnapshot.Cell.Box FULL = new VoxelSnapshot.Cell.Box(0, 0, 0, 1, 1, 1);
    @Test void cellsFollowCollisionShapeFluidAndHazard() {
        assertEquals(VoxelSnapshot.Cell.AIR, CollisionCells.classify("AIR", List.of(), false));
        assertEquals(VoxelSnapshot.Cell.WATER, CollisionCells.classify("WATER", List.of(), false));
        assertEquals(VoxelSnapshot.Cell.LAVA, CollisionCells.classify("LAVA", List.of(), false));
        assertEquals(VoxelSnapshot.Cell.SOLID, CollisionCells.classify("STONE", List.of(FULL), false));
        VoxelSnapshot.Cell slab = CollisionCells.classify("OAK_SLAB", List.of(new VoxelSnapshot.Cell.Box(0, 0, 0, 1, .5, 1)), true);
        assertEquals(.5, slab.top()); assertTrue(slab.water()); assertFalse(slab.hazard());
        assertTrue(CollisionCells.classify("MAGMA_BLOCK", List.of(FULL), false).hazard());
        assertFalse(CollisionCells.classify("SHORT_GRASS", List.of(), false).collides());
        assertThrows(NullPointerException.class, () -> CollisionCells.classify(null, List.of(), false));
    }
    @Test void boxesAreClampedToTheVoxelAndFenceOverhang() {
        VoxelSnapshot.Cell.Box fence = CollisionCells.clamp(.375, 0, .375, .625, 1.5, .625);
        assertEquals(1.5, fence.maxY());
        assertEquals(1.5, CollisionCells.clamp(-.1, 0, 0, 1.1, 2, 1).maxY());
        assertEquals(0, CollisionCells.clamp(-.1, 0, 0, 1.1, 2, 1).minX());
        assertNull(CollisionCells.clamp(0, 1.5, 0, 1, 2, 1));
    }

    @Test void boundsAndLimitsEnforceHardMemoryAndWorkCeilings() {
        assertEquals(8192, new CaptureBounds(-16, -64, -16, 16, 32, 16).voxelCount());
        assertThrows(IllegalArgumentException.class, () -> new CaptureBounds(0, 0, 0, 17, 32, 16));
        assertThrows(IllegalArgumentException.class, () -> new CaptureBounds(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CaptureBounds(30_000_000, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NavigationLimits(257, 8, 8192));
        assertThrows(IllegalArgumentException.class, () -> new NavigationLimits(256, 9, 8192));
        assertThrows(IllegalArgumentException.class, () -> new NavigationLimits(256, 8, 8193));
        assertThrows(IllegalArgumentException.class, () -> new NavigationLimits(0, 8, 8192));
    }
}
