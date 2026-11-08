package art.arcane.animus.core.virtual;

/** Immutable finite geometry; no platform objects or protocol fields. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Vector components must be finite");
    }

    public Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
    public Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
    public Vec3 multiply(double scale) { return new Vec3(x * scale, y * scale, z * scale); }
    public double distance(Vec3 other) { return Math.hypot(Math.hypot(x - other.x, y - other.y), z - other.z); }

    /** Scaling first avoids overflow/underflow for finite but extreme supplied directions. */
    public Vec3 normalized() {
        double scale = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (scale == 0) throw new IllegalArgumentException("Ray direction must not be zero");
        double sx = x / scale, sy = y / scale, sz = z / scale;
        double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
        return new Vec3(sx / length, sy / length, sz / length);
    }

    /** {@code x y z} for chat, each rounded to two decimals without trailing zeros, such as {@code 14 64 0.5}. */
    public String brief() { return brief(x) + " " + brief(y) + " " + brief(z); }

    private static String brief(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        return java.math.BigDecimal.valueOf(rounded == 0 ? 0 : rounded).stripTrailingZeros().toPlainString();
    }
}
