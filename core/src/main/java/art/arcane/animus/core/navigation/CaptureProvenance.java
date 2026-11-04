package art.arcane.animus.core.navigation;


/** Tick numbers are bridge-pump sequence numbers, not global game time. The capture is not atomic across ticks. */
public record CaptureProvenance(String world, CaptureBounds bounds, long firstTick, long lastTick,
                                long firstNanos, long lastNanos, int sampledVoxels, int unavailableVoxels) {}
