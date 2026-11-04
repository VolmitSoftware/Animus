package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;

public final class CombatGeometry {
    private CombatGeometry() {}
    public static Vec3 center(Hitbox box) { return box.min().add(box.max()).multiply(0.5); }
    public static Vec3 nearest(Hitbox box, Vec3 point) {
        return new Vec3(clamp(point.x(), box.min().x(), box.max().x()),
                clamp(point.y(), box.min().y(), box.max().y()), clamp(point.z(), box.min().z(), box.max().z()));
    }
    /** Distance from an authoritative origin to the closest point of an authoritative target hitbox. */
    public static double reach(Vec3 origin, Hitbox target) { return origin.distance(nearest(target, origin)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
