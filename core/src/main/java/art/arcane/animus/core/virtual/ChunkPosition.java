package art.arcane.animus.core.virtual;

import java.util.Objects;

public record ChunkPosition(String world, int x, int z) {
    public ChunkPosition {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) throw new IllegalArgumentException("World must not be blank");
    }
}
