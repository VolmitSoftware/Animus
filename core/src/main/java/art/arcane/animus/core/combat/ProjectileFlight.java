package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One real projectile's flight past NPC bodies, which it cannot touch because they are virtual. Each observed segment is
 * tested against the bounds of nearby NPCs from the spatial index. A body is struck at most once, and the flight is spent
 * after its first hit, or after {@code pierce + 1} hits for a piercing arrow. Used only by the thread that owns the projectile.
 */
public final class ProjectileFlight {
    /** NPCs near a point, from the NPC spatial index. */
    @FunctionalInterface public interface Scanner { SpatialNpcIndex.Scan scan(WorldPosition center, double radius, SpatialNpcIndex.Budget budget); }
    /** The body struck and where the segment met its bounds. */
    public record Hit(UUID npc, Vec3 point) {}
    /** A longer segment is a teleport, not a flight. */
    static final double MAXIMUM_SEGMENT = 32;
    /** How far a body's bounds may reach from the position the index holds it at, as combat helper shots assume. */
    private static final double BODY_REACH = 4;
    private static final SpatialNpcIndex.Budget BUDGET = new SpatialNpcIndex.Budget(16, 256);
    private final Set<UUID> struck = new HashSet<>();
    private int hitsLeft;
    private Vec3 last, origin;
    private String world;

    public ProjectileFlight(int pierce) { hitsLeft = Math.max(0, pierce) + 1; }

    public boolean spent() { return hitsLeft <= 0; }
    /** Where the flight began: the launch point, or the start of the first observed segment; empty before either. */
    public Optional<Vec3> origin() { return Optional.ofNullable(origin); }
    /** The projectile was just fired from here; its first segment starts here. */
    public void launched(WorldPosition at) {
        origin = at.point(); last = at.point(); world = at.world();
    }

    /**
     * The projectile is at {@code now} moving by {@code velocity} per tick. The segment runs from the last observation, or
     * from one tick back along the velocity on the first one, to {@code now}.
     */
    public Optional<Hit> advance(WorldPosition now, Vec3 velocity, Scanner scanner, Predicate<UUID> shootable) {
        Vec3 from = last != null && now.world().equals(world) ? last : now.point().subtract(velocity);
        if (origin == null) origin = from;
        last = now.point();
        world = now.world();
        double length = from.distance(now.point());
        if (spent() || length < .0001 || length > MAXIMUM_SEGMENT) return Optional.empty();
        Vec3 direction = now.point().subtract(from);
        SpatialNpcIndex.Scan near = scanner.scan(new WorldPosition(now.world(), from.add(now.point()).multiply(.5)), length / 2 + BODY_REACH, BUDGET);
        UUID chosen = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (VirtualNpcState.Snapshot candidate : near.candidates()) {
            if (!candidate.active() || struck.contains(candidate.npcId())) continue;
            OptionalDouble distance = candidate.bounds().rayDistance(from, direction, length);
            if (distance.isEmpty() || distance.getAsDouble() >= nearest || !shootable.test(candidate.npcId())) continue;
            nearest = distance.getAsDouble();
            chosen = candidate.npcId();
        }
        if (chosen == null) return Optional.empty();
        struck.add(chosen);
        hitsLeft--;
        return Optional.of(new Hit(chosen, from.add(direction.normalized().multiply(nearest))));
    }
}
