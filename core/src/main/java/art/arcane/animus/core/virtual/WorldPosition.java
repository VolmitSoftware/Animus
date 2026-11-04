package art.arcane.animus.core.virtual;

import java.util.Objects;

/** World identity is supplied by the platform, preferably its stable UUID string. */
public record WorldPosition(String world, Vec3 point) {
    public WorldPosition {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(point, "point");
        if (world.isBlank()) throw new IllegalArgumentException("World must not be blank");
        if (Math.abs(point.x()) > 30_000_000 || Math.abs(point.y()) > 30_000_000 || Math.abs(point.z()) > 30_000_000)
            throw new IllegalArgumentException("World coordinates must be within ±30000000");
    }

    public ChunkPosition chunk() {
        return new ChunkPosition(world, (int) Math.floor(point.x() / 16), (int) Math.floor(point.z() / 16));
    }
}
