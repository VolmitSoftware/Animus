package art.arcane.animus.core.navigation;

public record NavigationLimits(int captureWorkPerTick, int maximumJobs, int maximumCaptureVoxels) {
    public static final NavigationLimits DEFAULT = new NavigationLimits(256, 8, 8192);
    public NavigationLimits {
        if (captureWorkPerTick < 1 || captureWorkPerTick > 256 || maximumJobs < 1 || maximumJobs > 8
                || maximumCaptureVoxels < 1 || maximumCaptureVoxels > 8192)
            throw new IllegalArgumentException("Navigation limits exceed 256 capture units/tick, 8 jobs, or 8192 voxels");
    }
}
