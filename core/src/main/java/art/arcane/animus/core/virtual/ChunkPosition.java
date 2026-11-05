package art.arcane.animus.core.virtual;

import java.util.Objects;

public record ChunkPosition(String world, int x, int z) {
    public ChunkPosition {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) throw new IllegalArgumentException("World must not be blank");
    }
    /** One 64-bit key per chunk coordinate pair; negative coordinates never alias. */
    public static long key(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
}
