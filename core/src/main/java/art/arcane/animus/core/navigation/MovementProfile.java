package art.arcane.animus.core.navigation;

import java.util.*;

/** Explicit voxel movement capabilities. Partial-block collision and ballistic jump physics are not modeled. */
public record MovementProfile(Set<Mode> modes, int bodyHeight, int bodyRadius, int maximumStepUp,
                              int maximumJumpUp, int maximumFall, boolean diagonals) {
    public enum Mode { WALK, SWIM, FLY }
    public MovementProfile {
        modes = Set.copyOf(Objects.requireNonNull(modes, "modes"));
        if (modes.isEmpty() || bodyHeight < 1 || bodyHeight > 8 || bodyRadius < 0 || bodyRadius > 2
                || maximumStepUp < 0 || maximumStepUp > 1 || maximumJumpUp < 0 || maximumJumpUp > 3
                || maximumFall < 0 || maximumFall > 16)
            throw new IllegalArgumentException("Movement profile exceeds supported voxel capability bounds");
    }
    public static MovementProfile walking() { return new MovementProfile(Set.of(Mode.WALK), 2, 0, 0, 1, 3, true); }
    public boolean supports(Mode mode) { return modes.contains(mode); }
}
