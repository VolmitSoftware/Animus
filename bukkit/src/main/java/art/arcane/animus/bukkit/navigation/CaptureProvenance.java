package art.arcane.animus.bukkit.navigation;

import java.util.UUID;

/** Tick numbers are bridge-pump sequence numbers, not global game time. The capture is not atomic across ticks. */
public record CaptureProvenance(UUID world, CaptureBounds bounds, long firstTick, long lastTick,
                                long firstNanos, long lastNanos, int sampledVoxels, int unavailableVoxels) {}
