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

    /** Flight follows the look direction, pitch included; jump rises straight up. Sneak stays the dismount key. */
    public Vec3 flight(double yaw, double pitch) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) throw new IllegalArgumentException("Control yaw and pitch must be finite");
        int ahead = (forward ? 1 : 0) - (backward ? 1 : 0);
        int sideways = (right ? 1 : 0) - (left ? 1 : 0);
        double angle = Math.toRadians(yaw % 360), tilt = Math.toRadians(Math.max(-90, Math.min(90, pitch)));
        double x = -Math.sin(angle) * Math.cos(tilt) * ahead - Math.cos(angle) * sideways;
        double y = -Math.sin(tilt) * ahead + (jump ? 1 : 0);
        double z = Math.cos(angle) * Math.cos(tilt) * ahead - Math.sin(angle) * sideways;
        if (Math.abs(x) < 1e-9 && Math.abs(y) < 1e-9 && Math.abs(z) < 1e-9) return Vec3.ZERO;
        return new Vec3(x, y, z).normalized();
    }
}
