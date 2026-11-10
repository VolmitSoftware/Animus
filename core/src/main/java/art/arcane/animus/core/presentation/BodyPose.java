package art.arcane.animus.core.presentation;

import java.util.Objects;

/**
 * Where a presented body stands and faces, in the platform's world key. {@code headYaw} is where the head turns, which
 * differs from the body's {@code yaw} only for an NPC that turns its head alone. Checked before any packet is built.
 */
public record BodyPose(String world, double x, double y, double z, float yaw, float headYaw, float pitch, boolean onGround) {
    public BodyPose {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(y) > 30_000_000 || Math.abs(z) > 30_000_000
                || !Float.isFinite(yaw) || !Float.isFinite(headYaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90)
            throw new IllegalArgumentException("Invalid virtual body position/rotation");
    }
    /** A body whose head faces the way its body does. */
    public BodyPose(String world, double x, double y, double z, float yaw, float pitch, boolean onGround) { this(world, x, y, z, yaw, yaw, pitch, onGround); }
}
