package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConservativeVoxelClassifierTest {
    @Test void admitsOnlyKnownAirAndFluidWhileUnknownAndPartialMaterialsAreClosed() {
        for (String name : new String[]{"AIR", "CAVE_AIR", "VOID_AIR"})
            assertEquals(VoxelSnapshot.Cell.AIR, ConservativeVoxelClassifier.classify(name));
        assertEquals(VoxelSnapshot.Cell.WATER, ConservativeVoxelClassifier.classify("WATER"));
        assertEquals(VoxelSnapshot.Cell.LAVA, ConservativeVoxelClassifier.classify("LAVA"));
        for (String name : new String[]{"STONE", "SHORT_GRASS", "OAK_SLAB", "OAK_DOOR", "SEAGRASS", "SNOW", "OAK_FENCE", "FUTURE_BLOCK"})
            assertEquals(VoxelSnapshot.Cell.SOLID, ConservativeVoxelClassifier.classify(name));
        assertThrows(NullPointerException.class, () -> ConservativeVoxelClassifier.classify(null));
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
