package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.ChunkPosition;
import org.junit.jupiter.api.Test;
import static art.arcane.animus.core.navigation.VoxelSnapshot.Cell.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSnapshotTest {
    @Test void capturedCellsAndAvailabilityAreImmutableAndUnknownByDefault() {
        var builder = new VoxelSnapshot.Builder("world", -1, 0, -1, 18, 4, 18);
        builder.availableChunk(-1, -1).set(-1, 1, -1, WATER);
        var snapshot = builder.build();
        assertEquals(WATER, snapshot.cell(-1, 1, -1));
        assertEquals(UNAVAILABLE, snapshot.cell(-1, 2, -1));
        assertEquals(UNAVAILABLE, snapshot.cell(0, 1, 0));
        assertEquals(UNAVAILABLE, snapshot.cell(17, 1, 0));
        builder.fill(AIR).availableChunk(0, 0);
        assertEquals(WATER, snapshot.cell(-1, 1, -1));
        assertEquals(UNAVAILABLE, snapshot.cell(0, 1, 0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.availableChunks().add(new ChunkPosition("world", 0, 0)));
    }
    @Test void rejectsUnboundedCapturesAndOutOfRangeWrites() {
        assertThrows(IllegalArgumentException.class, () -> new VoxelSnapshot.Builder("world", 0, 0, 0, 4096, 4096, 4096));
        assertThrows(IllegalArgumentException.class, () -> new VoxelSnapshot.Builder("world", 0, 0, 0, 0, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new VoxelSnapshot.Builder("world", 30_000_000, 0, 0, 2, 2, 2));
        var builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 4, 4, 4);
        assertThrows(IllegalArgumentException.class, () -> builder.set(4, 1, 1, AIR));
        assertThrows(IllegalArgumentException.class, () -> builder.availableChunk(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PathPlanner.Limits(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new MovementProfile(java.util.Set.of(), 2, 0, 0, 0, 0, false));
    }
}
