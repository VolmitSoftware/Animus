package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcAttributes;
import java.util.OptionalDouble;

/**
 * How far a body falls under {@link IdleFall}: from the height where its fall began to where it lands. Landing in water
 * or on a ladder, vine or scaffolding breaks the fall, as in vanilla. One per body, on its owner.
 */
public final class FallTracker {
    private double start = Double.NaN;

    /**
     * One idle fall step from {@code fromY} to {@code toY}. The fall distance once the body lands, 0 when water or a
     * climbable caught it; empty while it falls or stands.
     */
    public OptionalDouble step(double fromY, double toY, boolean landed, boolean caught) {
        if (!landed) {
            if (toY < fromY && Double.isNaN(start)) start = fromY;
            return OptionalDouble.empty();
        }
        double began = Double.isNaN(start) ? fromY : start;
        start = Double.NaN;
        double distance = began - toY;
        if (!(distance > 0)) return OptionalDouble.empty();
        return OptionalDouble.of(caught ? 0 : distance);
    }

    /** Resets a fall that a route, mount or teleport ended. */
    public void reset() { start = Double.NaN; }

    /** Vanilla fall damage: the blocks past {@code safe_fall_distance}, times {@code fall_damage_multiplier}, rounded up. */
    public static double damage(double distance, NpcAttributes attributes) {
        double past = distance - attributes.value("safe_fall_distance");
        if (!(past > 0)) return 0;
        return Math.ceil(past * attributes.value("fall_damage_multiplier"));
    }
}
