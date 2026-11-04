package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;

/** Semantic key state only. It contains no client-supplied position, speed or time. */
public record ControlInput(boolean forward, boolean backward, boolean left, boolean right,
                           boolean jump, boolean shift, boolean sprint) {
    public static final ControlInput EMPTY = new ControlInput(false, false, false, false, false, false, false);

    /** Horizontal direction relative to an authoritative server yaw. Opposing keys cancel; diagonals normalize. */
    public Vec3 horizontal(double yaw) {
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Control yaw must be finite");
        int ahead = (forward ? 1 : 0) - (backward ? 1 : 0);
        int sideways = (right ? 1 : 0) - (left ? 1 : 0);
        if (ahead == 0 && sideways == 0) return Vec3.ZERO;
        double angle = Math.toRadians(yaw % 360);
        return new Vec3(-Math.sin(angle) * ahead - Math.cos(angle) * sideways, 0,
                Math.cos(angle) * ahead - Math.sin(angle) * sideways).normalized();
    }
}
