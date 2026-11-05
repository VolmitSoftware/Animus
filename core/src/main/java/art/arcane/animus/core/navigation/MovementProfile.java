package art.arcane.animus.core.navigation;

import java.util.Objects;
import java.util.Set;

/**
 * Explicit movement capabilities of one body. Walking bodies climb up to {@code stepHeight} without a jump and up to
 * {@code jumpHeight} with one, and drop at most {@code maximumFall} blocks. Swimming with walking keeps the body at the
 * water surface; swimming alone lets it move anywhere in water. A walking body that {@code climbs} also moves straight up
 * and down ladders, vines and scaffolding. Ballistic jumps, buoyancy and currents are not modelled.
 */
public record MovementProfile(Set<Mode> modes, double width, double height, double stepHeight,
                              double jumpHeight, int maximumFall, boolean diagonals, boolean climbs) {
    public enum Mode { WALK, SWIM, FLY }
    public static final double MAX_WIDTH = 4, MAX_HEIGHT = 8, MAX_STEP = 1.5, MAX_JUMP = 3;
    public MovementProfile {
        modes = Set.copyOf(Objects.requireNonNull(modes, "modes"));
        if (modes.isEmpty() || !(width > 0 && width <= MAX_WIDTH) || !(height > 0 && height <= MAX_HEIGHT)
                || !(stepHeight >= 0 && stepHeight <= MAX_STEP) || !(jumpHeight >= 0 && jumpHeight <= MAX_JUMP)
                || maximumFall < 0 || maximumFall > 16)
            throw new IllegalArgumentException("Movement profile exceeds supported capability bounds");
    }
    /** A profile that does not climb. */
    public MovementProfile(Set<Mode> modes, double width, double height, double stepHeight, double jumpHeight, int maximumFall, boolean diagonals) {
        this(modes, width, height, stepHeight, jumpHeight, maximumFall, diagonals, false);
    }
    public boolean supports(Mode mode) { return modes.contains(mode); }
    /** Water movement stays at the surface when the body can also walk. */
    public boolean surfaceSwimmer() { return modes.contains(Mode.SWIM) && modes.contains(Mode.WALK); }
}
