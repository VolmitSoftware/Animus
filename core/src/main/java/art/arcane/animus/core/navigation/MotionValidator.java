package art.arcane.animus.core.navigation;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;

/** Bounded swept whole-voxel clearance. Unknown/partial shapes never become imaginary ground. */
public final class MotionValidator {
    public enum Medium { GROUND, AIR, WATER, AMPHIBIOUS_AIR }
    public enum Status { CLEAR, BLOCKED, UNAVAILABLE_TERRAIN, UNSUPPORTED_GEOMETRY, BUDGET_EXHAUSTED }
    @FunctionalInterface public interface Sampler { VoxelSnapshot.Cell sample(int x, int y, int z); }
    public record Result(Status status, int sampledCells, String detail) {
        public boolean clear() { return status == Status.CLEAR; }
    }
    private static final double EPSILON = 0.000001;
    private MotionValidator() {}

    public static Result check(Vec3 from, Vec3 to, double width, double height, Medium medium,
                               boolean requireSupport, int maximumCells, Sampler terrain) {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(medium); Objects.requireNonNull(terrain);
        if (maximumCells < 1 || maximumCells > 4096) throw new IllegalArgumentException("Cell budget must be 1..4096");
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || width > 4 || height <= 0 || height > 8
                || from.distance(to) > 1.000001 || (requireSupport && (Math.abs(from.y() - to.y()) > EPSILON
                || Math.abs(to.y() - Math.rint(to.y())) > EPSILON)))
            return new Result(Status.UNSUPPORTED_GEOMETRY, 0, "Requires width<=4, height<=8, <=1 block segment and integer-height ground support");
        double half = width / 2;
        int minX = floor(Math.min(from.x(), to.x()) - half + EPSILON), maxX = floor(Math.max(from.x(), to.x()) + half - EPSILON);
        int minZ = floor(Math.min(from.z(), to.z()) - half + EPSILON), maxZ = floor(Math.max(from.z(), to.z()) + half - EPSILON);
        int minY = floor(Math.min(from.y(), to.y()) + EPSILON), maxY = floor(Math.max(from.y(), to.y()) + height - EPSILON);
        // Unknown shapes can overhang their voxel (for example a fence's upper half).
        // Sample a closed one-voxel halo, charging every read to the same explicit budget.
        int sizeX = maxX - minX + 3, sizeY = maxY - minY + 3, sizeZ = maxZ - minZ + 3;
        long budget = (long) sizeX * sizeY * sizeZ;
        if (budget > maximumCells) return new Result(Status.BUDGET_EXHAUSTED, 0, "Swept volume exceeds cell budget");
        VoxelSnapshot.Cell[] cells = new VoxelSnapshot.Cell[(int) budget];
        int count = 0;
        for (int x = minX - 1; x <= maxX + 1; x++) for (int z = minZ - 1; z <= maxZ + 1; z++) for (int y = minY - 1; y <= maxY + 1; y++) {
            var cell = terrain.sample(x, y, z); count++;
            cells[((y - minY + 1) * sizeZ + z - minZ + 1) * sizeX + x - minX + 1] = cell;
            if (cell == VoxelSnapshot.Cell.UNAVAILABLE) return new Result(Status.UNAVAILABLE_TERRAIN, count, "Swept volume or collision halo reaches unavailable terrain");
            if (cell == VoxelSnapshot.Cell.OBSTACLE) return new Result(Status.UNSUPPORTED_GEOMETRY, count, "Partial or unsupported block shape in collision halo");
            if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) continue;
            if (cell == VoxelSnapshot.Cell.SOLID || cell == VoxelSnapshot.Cell.LAVA
                    || (cell == VoxelSnapshot.Cell.WATER && medium != Medium.WATER && medium != Medium.AMPHIBIOUS_AIR))
                return new Result(Status.BLOCKED, count, "Swept body is obstructed");
        }
        if (requireSupport || medium == Medium.WATER) {
            int feetY = floor(to.y() + EPSILON);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (!requireSupport) {
                    var feet = cells[((feetY - minY + 1) * sizeZ + z - minZ + 1) * sizeX + x - minX + 1];
                    if (feet == VoxelSnapshot.Cell.UNAVAILABLE) return new Result(Status.UNAVAILABLE_TERRAIN, count, "Fluid unavailable");
                    if (feet == VoxelSnapshot.Cell.WATER) continue;
                }
                var floor = cells[((feetY - minY) * sizeZ + z - minZ + 1) * sizeX + x - minX + 1];
                if (floor == VoxelSnapshot.Cell.UNAVAILABLE) return new Result(Status.UNAVAILABLE_TERRAIN, count, "Support unavailable");
                if (floor == VoxelSnapshot.Cell.OBSTACLE) return new Result(Status.UNSUPPORTED_GEOMETRY, count, "Support shape is not a verified full block");
                if (floor != VoxelSnapshot.Cell.SOLID) return new Result(Status.BLOCKED, count, "No continuous support or swimming fluid");
            }
        }
        return new Result(Status.CLEAR, count, "clear");
    }
    private static int floor(double value) {
        if (value < -30_000_000 || value > 30_000_000) throw new IllegalArgumentException("Motion outside world bounds");
        return (int) Math.floor(value);
    }
}
