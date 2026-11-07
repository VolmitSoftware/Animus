package art.arcane.animus.core.navigation;

public record NavigationLimits(int captureWorkPerTick, int maximumJobs, int maximumCaptureVoxels) {
    public static final NavigationLimits DEFAULT = new NavigationLimits(256, 8, CaptureBounds.MAXIMUM_VOXELS);
    public NavigationLimits {
        if (captureWorkPerTick < 1 || captureWorkPerTick > 65_536 || maximumJobs < 1 || maximumJobs > 256
                || maximumCaptureVoxels < 1 || maximumCaptureVoxels > CaptureBounds.MAXIMUM_VOXELS)
            throw new IllegalArgumentException("Navigation limits exceed 65536 capture units/tick, 256 jobs, or " + CaptureBounds.MAXIMUM_VOXELS + " voxels");
    }
}
