package art.arcane.animus.api;

import java.util.Objects;

/**
 * A place in a world. {@code world} is the world's name, such as {@code world}, or its UUID as text. The record accepts any
 * numbers; a service call that receives a non-finite or out-of-world coordinate answers {@link Outcome.Status#INVALID}.
 *
 * @param world world name or UUID
 * @param x block x, with fraction
 * @param y block y, with fraction
 * @param z block z, with fraction
 * @param yaw facing in degrees, 0 is south
 * @param pitch head pitch in degrees, -90 to 90
 * @since 1.3
 */
public record NpcLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public NpcLocation { Objects.requireNonNull(world, "world"); }

    /**
     * A place facing south with level pitch.
     *
     * @param world world name or UUID
     * @param x block x
     * @param y block y
     * @param z block z
     * @return the location
     */
    public static NpcLocation of(String world, double x, double y, double z) { return new NpcLocation(world, x, y, z, 0, 0); }

    /**
     * The same place facing another way.
     *
     * @param yaw facing in degrees
     * @param pitch head pitch in degrees
     * @return the location
     */
    public NpcLocation facing(float yaw, float pitch) { return new NpcLocation(world, x, y, z, yaw, pitch); }
}
