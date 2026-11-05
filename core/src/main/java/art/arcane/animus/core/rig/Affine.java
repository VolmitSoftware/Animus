package art.arcane.animus.core.rig;

import java.util.Arrays;

/**
 * An immutable 3x4 affine transform, row-major as {@code [r00 r01 r02 t0, r10 r11 r12 t1, r20 r21 r22 t2]}, acting on
 * column vectors. Rotations are Euler degrees applied X first, then Y, then Z: {@code R = Rz * Ry * Rx}.
 */
public final class Affine {
    public static final Affine IDENTITY = new Affine(new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0});
    private final double[] m;

    private Affine(double[] values) { m = values; }

    public static Affine of(double[] values) {
        if (values.length != 12) throw new IllegalArgumentException("An affine transform has 12 values");
        return new Affine(values.clone());
    }
    public static Affine translation(double x, double y, double z) { return new Affine(new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z}); }
    public static Affine translation(double[] v) { return translation(v[0], v[1], v[2]); }
    public static Affine scale(double x, double y, double z) { return new Affine(new double[]{x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0}); }
    public static Affine scale(double[] v) { return scale(v[0], v[1], v[2]); }
    public static Affine rotationX(double degrees) {
        double r = Math.toRadians(degrees), c = Math.cos(r), s = Math.sin(r);
        return new Affine(new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0});
    }
    public static Affine rotationY(double degrees) {
        double r = Math.toRadians(degrees), c = Math.cos(r), s = Math.sin(r);
        return new Affine(new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0});
    }
    public static Affine rotationZ(double degrees) {
        double r = Math.toRadians(degrees), c = Math.cos(r), s = Math.sin(r);
        return new Affine(new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0});
    }
    /** {@code Rz(z) * Ry(y) * Rx(x)}: X is applied first. */
    public static Affine rotation(double[] degrees) {
        if (degrees[0] == 0 && degrees[1] == 0 && degrees[2] == 0) return IDENTITY;
        return rotationZ(degrees[2]).times(rotationY(degrees[1])).times(rotationX(degrees[0]));
    }

    public Affine times(Affine other) {
        double[] a = m, b = other.m, r = new double[12];
        for (int row = 0; row < 3; row++) {
            int o = row * 4;
            for (int column = 0; column < 3; column++)
                r[o + column] = a[o] * b[column] + a[o + 1] * b[4 + column] + a[o + 2] * b[8 + column];
            r[o + 3] = a[o] * b[3] + a[o + 1] * b[7] + a[o + 2] * b[11] + a[o + 3];
        }
        return new Affine(r);
    }

    /** Entry at row 0..2 and column 0..3. */
    public double get(int row, int column) { return m[row * 4 + column]; }
    public double[] values() { return m.clone(); }
    /** The largest absolute difference between any two entries. */
    public double distance(Affine other) {
        double largest = 0;
        for (int i = 0; i < 12; i++) largest = Math.max(largest, Math.abs(m[i] - other.m[i]));
        return largest;
    }
    /** The largest difference in the translation column. */
    public double translationDistance(Affine other) {
        return Math.max(Math.abs(m[3] - other.m[3]), Math.max(Math.abs(m[7] - other.m[7]), Math.abs(m[11] - other.m[11])));
    }
    /** The largest difference in the 3x3 linear part. */
    public double linearDistance(Affine other) {
        double largest = 0;
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++)
            largest = Math.max(largest, Math.abs(get(row, column) - other.get(row, column)));
        return largest;
    }
    /** Entry-wise linear interpolation. */
    public Affine lerp(Affine other, double u) {
        double[] r = new double[12];
        for (int i = 0; i < 12; i++) r[i] = m[i] + (other.m[i] - m[i]) * u;
        return new Affine(r);
    }

    @Override public boolean equals(Object other) { return other instanceof Affine affine && Arrays.equals(m, affine.m); }
    @Override public int hashCode() { return Arrays.hashCode(m); }
    @Override public String toString() { return Arrays.toString(m); }
}
