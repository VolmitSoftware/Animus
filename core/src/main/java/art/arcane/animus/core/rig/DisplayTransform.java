package art.arcane.animus.core.rig;

/**
 * The transform fields a display entity accepts: translation, left rotation, scale and right rotation, applied as
 * {@code T * L * S * R}. Quaternions are {@code (x, y, z, w)}. Built from any affine matrix by singular value
 * decomposition, so non-uniform scales under rotated children (shear) are reproduced exactly.
 */
public record DisplayTransform(float tx, float ty, float tz, float lx, float ly, float lz, float lw,
                               float sx, float sy, float sz, float rx, float ry, float rz, float rw) {
    private static final double EPSILON = 1e-9;

    /** A bone that is not shown: zero scale at its place, so the client draws nothing. */
    public static DisplayTransform hidden(Affine at) {
        return new DisplayTransform((float) at.get(0, 3), (float) at.get(1, 3), (float) at.get(2, 3), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1);
    }

    public static DisplayTransform of(Affine matrix) {
        double[][] l = new double[3][3];
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) l[row][column] = matrix.get(row, column);
        double[][] a = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double sum = 0;
            for (int k = 0; k < 3; k++) sum += l[k][i] * l[k][j];
            a[i][j] = sum;
        }
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        jacobi(a, v);
        double[] lambda = {a[0][0], a[1][1], a[2][2]};
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (Integer first, Integer second) -> Double.compare(lambda[second], lambda[first]));
        double[][] columns = new double[3][];
        double[] sigma = new double[3];
        for (int i = 0; i < 3; i++) {
            columns[i] = new double[]{v[0][order[i]], v[1][order[i]], v[2][order[i]]};
            sigma[i] = Math.sqrt(Math.max(0, lambda[order[i]]));
        }
        if (determinant(columns[0], columns[1], columns[2]) < 0) columns[2] = negate(columns[2]);
        double[][] u = new double[3][];
        int valid = 0;
        double largest = Math.max(sigma[0], EPSILON);
        for (int i = 0; i < 3; i++) {
            if (sigma[i] <= largest * 1e-7 || sigma[i] < EPSILON) break;
            double[] column = multiply(l, columns[i]);
            for (int c = 0; c < 3; c++) column[c] /= sigma[i];
            for (int previous = 0; previous < i; previous++) column = subtract(column, project(column, u[previous]));
            double length = length(column);
            if (length < 1e-6) break;
            u[i] = scaled(column, 1 / length);
            valid++;
        }
        for (int i = valid; i < 3; i++) sigma[i] = 0;
        if (valid == 0) { u[0] = new double[]{1, 0, 0}; u[1] = new double[]{0, 1, 0}; u[2] = new double[]{0, 0, 1}; }
        else if (valid == 1) {
            double[] helper = Math.abs(u[0][0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            u[1] = normalized(cross(u[0], helper));
            u[2] = cross(u[0], u[1]);
        } else if (valid == 2) u[2] = cross(u[0], u[1]);
        if (determinant(u[0], u[1], u[2]) < 0) { u[2] = negate(u[2]); sigma[2] = -sigma[2]; }
        double[] left = quaternion(u[0], u[1], u[2]);
        // The right rotation is V transposed; its rows are V's columns.
        double[] right = quaternion(new double[]{columns[0][0], columns[1][0], columns[2][0]},
                new double[]{columns[0][1], columns[1][1], columns[2][1]}, new double[]{columns[0][2], columns[1][2], columns[2][2]});
        return new DisplayTransform((float) matrix.get(0, 3), (float) matrix.get(1, 3), (float) matrix.get(2, 3),
                (float) left[0], (float) left[1], (float) left[2], (float) left[3], (float) sigma[0], (float) sigma[1], (float) sigma[2],
                (float) right[0], (float) right[1], (float) right[2], (float) right[3]);
    }

    /** The same transform moved by an offset, such as the vehicle's passenger point. */
    public DisplayTransform offset(double dx, double dy, double dz) {
        return new DisplayTransform((float) (tx + dx), (float) (ty + dy), (float) (tz + dz), lx, ly, lz, lw, sx, sy, sz, rx, ry, rz, rw);
    }

    /** The matrix this transform reproduces, for checks. */
    public Affine matrix() {
        return Affine.translation(tx, ty, tz).times(rotation(lx, ly, lz, lw)).times(Affine.scale(sx, sy, sz)).times(rotation(rx, ry, rz, rw));
    }

    private static Affine rotation(double x, double y, double z, double w) {
        return Affine.of(new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0,
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0,
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0});
    }

    /** Quaternion of the rotation whose columns are {@code c0, c1, c2}. */
    private static double[] quaternion(double[] c0, double[] c1, double[] c2) {
        double m00 = c0[0], m10 = c0[1], m20 = c0[2], m01 = c1[0], m11 = c1[1], m21 = c1[2], m02 = c2[0], m12 = c2[1], m22 = c2[2];
        double trace = m00 + m11 + m22, x, y, z, w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = 0.25 * s; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s; x = 0.25 * s; y = (m01 + m10) / s; z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = 0.25 * s; z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = 0.25 * s;
        }
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        return new double[]{x / length, y / length, z / length, w / length};
    }

    /** Cyclic Jacobi eigenvalue iteration on a symmetric matrix; {@code v} collects the eigenvectors as columns. */
    private static void jacobi(double[][] a, double[][] v) {
        for (int sweep = 0; sweep < 32; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-30) return;
            for (int p = 0; p < 2; p++) for (int q = p + 1; q < 3; q++) {
                if (Math.abs(a[p][q]) < 1e-300) continue;
                double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                if (theta == 0) t = 1;
                double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                for (int k = 0; k < 3; k++) {
                    double akp = a[k][p], akq = a[k][q];
                    a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq;
                }
                for (int k = 0; k < 3; k++) {
                    double apk = a[p][k], aqk = a[q][k];
                    a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk;
                }
                for (int k = 0; k < 3; k++) {
                    double vkp = v[k][p], vkq = v[k][q];
                    v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq;
                }
            }
        }
    }

    private static double[] multiply(double[][] m, double[] x) {
        return new double[]{m[0][0] * x[0] + m[0][1] * x[1] + m[0][2] * x[2], m[1][0] * x[0] + m[1][1] * x[1] + m[1][2] * x[2], m[2][0] * x[0] + m[2][1] * x[1] + m[2][2] * x[2]};
    }
    private static double determinant(double[] a, double[] b, double[] c) { return dot(a, cross(b, c)); }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double[] negate(double[] a) { return new double[]{-a[0], -a[1], -a[2]}; }
    private static double[] subtract(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] scaled(double[] a, double factor) { return new double[]{a[0] * factor, a[1] * factor, a[2] * factor}; }
    private static double[] project(double[] a, double[] onto) { return scaled(onto, dot(a, onto)); }
    private static double length(double[] a) { return Math.sqrt(dot(a, a)); }
    private static double[] normalized(double[] a) { return scaled(a, 1 / length(a)); }
}
