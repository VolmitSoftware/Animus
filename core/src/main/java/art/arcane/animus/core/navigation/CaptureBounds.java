package art.arcane.animus.core.navigation;

/** A conservative finite volume; at most {@link #MAXIMUM_VOXELS} voxels can enter this bridge. */
public record CaptureBounds(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    /** The widest capture a long leg asks for. */
    public static final int MAXIMUM_VOXELS = 131_072;
    public CaptureBounds {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || sizeX > 4096 || sizeY > 4096 || sizeZ > 4096
                || (long) sizeX * sizeY * sizeZ > MAXIMUM_VOXELS)
            throw new IllegalArgumentException("Capture dimensions must be positive and contain at most " + MAXIMUM_VOXELS + " voxels");
        if (minX < -30_000_000 || minY < -30_000_000 || minZ < -30_000_000
                || (long) minX + sizeX > 30_000_000 || (long) minY + sizeY > 30_000_000 || (long) minZ + sizeZ > 30_000_000)
            throw new IllegalArgumentException("Capture lies outside world bounds");
    }
    public int voxelCount() { return sizeX * sizeY * sizeZ; }
}
