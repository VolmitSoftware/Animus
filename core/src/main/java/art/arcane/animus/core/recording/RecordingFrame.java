package art.arcane.animus.core.recording;

/**
 * One sample of a recording: {@code tick} since the start, the position relative to the document's origin, the absolute
 * yaw and pitch, and the movement flags. The constructor rounds the position to 0.01 blocks and the angles to 0.5
 * degrees, the grid the file stores, so a frame always equals its own serialized form.
 */
public record RecordingFrame(int tick, double dx, double dy, double dz, double yaw, double pitch, int flags) {
    public static final int ON_GROUND = 1, SNEAKING = 2, SPRINTING = 4, SWIMMING = 8, GLIDING = 16, JUMPING = 32;
    /** Every defined flag bit. */
    public static final int ALL_FLAGS = 63;
    public static final double MAXIMUM_OFFSET = 30_000_000;

    public RecordingFrame {
        if (tick < 0) throw new IllegalArgumentException("Frame tick must not be negative");
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz) || Math.abs(dx) > MAXIMUM_OFFSET || Math.abs(dy) > MAXIMUM_OFFSET
                || Math.abs(dz) > MAXIMUM_OFFSET) throw new IllegalArgumentException("Frame offset must be finite within ±30000000");
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) throw new IllegalArgumentException("Frame rotation must be finite");
        dx = position(dx); dy = position(dy); dz = position(dz);
        yaw = angle(yaw); pitch = angle(pitch);
        if (yaw < -360 || yaw > 360) throw new IllegalArgumentException("Frame yaw must be within -360..360");
        if (pitch < -90 || pitch > 90) throw new IllegalArgumentException("Frame pitch must be within -90..90");
        if ((flags & ~ALL_FLAGS) != 0) throw new IllegalArgumentException("Frame flags must be within 0..63");
    }

    public boolean onGround() { return has(flags, ON_GROUND); }
    public boolean sneaking() { return has(flags, SNEAKING); }
    public boolean sprinting() { return has(flags, SPRINTING); }
    public boolean swimming() { return has(flags, SWIMMING); }
    public boolean gliding() { return has(flags, GLIDING); }
    public boolean jumping() { return has(flags, JUMPING); }

    static boolean has(int flags, int bit) { return (flags & bit) != 0; }

    /** A value on the 0.01 block grid. */
    public static double position(double value) { return Math.round(value * 100) / 100.0; }

    /** A value on the 0.5 degree grid. */
    public static double angle(double value) { return Math.round(value * 2) / 2.0; }

    /** The signed shortest turn from {@code from} to {@code to}, in -180..180 degrees. */
    public static double yawDelta(double from, double to) {
        double delta = (to - from) % 360;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        return delta;
    }

    /** {@code yaw} wrapped into -180 (inclusive) to 180 (exclusive). */
    static double wrapYaw(double yaw) {
        double wrapped = yaw % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
}
