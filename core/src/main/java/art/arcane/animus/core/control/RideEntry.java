package art.arcane.animus.core.control;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.UUID;

/**
 * Region entry for one ride. A ride moves its rider with no movement event, so region plugins never see the move; each
 * step into a block the ride has not yet reached asks the platform's rule instead. The block the ride started in counts
 * as entered. A rule that throws denies. Owner thread only.
 */
public final class RideEntry {
    /** Whether the rider may enter the block at this position, as when they walk in. */
    @FunctionalInterface
    public interface Rule {
        boolean mayEnter(UUID rider, WorldPosition at);
    }

    /** For a platform with no region plugin. */
    public static final Rule OPEN = (UUID rider, WorldPosition at) -> true;
    /** The ride's end reason when a step is refused; shown to the rider as "Dismounted: region entry denied". */
    public static final String DENIED = "region-entry-denied";

    private final Rule rule;
    private final UUID rider;
    private String world;
    private int x, y, z;

    public RideEntry(Rule rule, UUID rider, WorldPosition start) {
        this.rule = Objects.requireNonNull(rule); this.rider = Objects.requireNonNull(rider);
        remember(start);
    }

    /** Whether the ride may step to this position; true without asking while it stays in the last block it entered. */
    public boolean allows(WorldPosition to) {
        Vec3 point = to.point();
        if (to.world().equals(world) && block(point.x()) == x && block(point.y()) == y && block(point.z()) == z) return true;
        boolean allowed;
        try { allowed = rule.mayEnter(rider, to); }
        catch (RuntimeException | LinkageError failure) { allowed = false; }
        if (allowed) remember(to);
        return allowed;
    }

    private void remember(WorldPosition at) {
        world = at.world();
        x = block(at.point().x()); y = block(at.point().y()); z = block(at.point().z());
    }

    private static int block(double coordinate) { return (int) Math.floor(coordinate); }
}
