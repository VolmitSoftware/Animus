package art.arcane.animus.core.virtual;

import java.util.Objects;
import java.util.OptionalDouble;

/** Axis-aligned bounds. A ray is clipped to server-configured reach before any hit is accepted. */
public record Hitbox(Vec3 min, Vec3 max) {
    public Hitbox {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() >= max.x() || min.y() >= max.y() || min.z() >= max.z())
            throw new IllegalArgumentException("Hitbox must have positive dimensions");
    }

    public static Hitbox atFeet(Vec3 feet, double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0 || width > 256 || height > 256)
            throw new IllegalArgumentException("Body dimensions must be finite and within (0, 256]");
        double half = width / 2;
        return new Hitbox(new Vec3(feet.x() - half, feet.y(), feet.z() - half),
                new Vec3(feet.x() + half, feet.y() + height, feet.z() + half));
    }

    public Hitbox translated(Vec3 offset) { return new Hitbox(min.add(offset), max.add(offset)); }
    public Hitbox inflated(double amount) {
        Vec3 by = new Vec3(amount, amount, amount);
        return new Hitbox(min.subtract(by), max.add(by));
    }

    public OptionalDouble rayDistance(Vec3 origin, Vec3 direction, double maxReach) {
        Objects.requireNonNull(origin, "origin");
        if (!Double.isFinite(maxReach) || maxReach < 0) throw new IllegalArgumentException("Reach must be finite and nonnegative");
        Vec3 normalized = Objects.requireNonNull(direction, "direction").normalized();
        double near = 0, far = maxReach;
        double[] o = {origin.x(), origin.y(), origin.z()};
        double[] d = {normalized.x(), normalized.y(), normalized.z()};
        double[] lo = {min.x(), min.y(), min.z()};
        double[] hi = {max.x(), max.y(), max.z()};
        for (int axis = 0; axis < 3; axis++) {
            if (d[axis] == 0) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) return OptionalDouble.empty();
                continue;
            }
            double first = (lo[axis] - o[axis]) / d[axis];
            double last = (hi[axis] - o[axis]) / d[axis];
            near = Math.max(near, Math.min(first, last));
            far = Math.min(far, Math.max(first, last));
            if (near > far) return OptionalDouble.empty();
        }
        return OptionalDouble.of(near);
    }
}
