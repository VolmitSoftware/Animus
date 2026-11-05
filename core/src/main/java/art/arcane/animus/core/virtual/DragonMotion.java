package art.arcane.animus.core.virtual;

import java.util.Objects;

/**
 * Which phase a presented dragon shows: it flaps while it moved within the last {@link #REST_TICKS} ticks, otherwise it
 * sits on solid ground or hovers in the air. One instance per dragon, used by its owner only.
 */
public final class DragonMotion {
    public static final int REST_TICKS = 20;
    private Vec3 resting;
    private long lastMotion;

    /** The phase after this tick, given where the dragon stands and whether solid ground is under its feet. */
    public int observe(long tick, Vec3 point, boolean grounded) {
        Objects.requireNonNull(point);
        if (resting == null) resting = point;
        else if (!point.equals(resting)) { resting = point; lastMotion = tick; }
        boolean moving = lastMotion != 0 && tick - lastMotion < REST_TICKS;
        return DragonBody.phase(moving, grounded);
    }
}
