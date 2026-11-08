package art.arcane.animus.core.runtime;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.ViewerSession;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Which NPC {@code /animus select} without an id means: the one the player looks at, its hitbox the first along the eye
 * ray within {@link #REACH} blocks, otherwise the one standing nearest within {@link #REACH} blocks.
 */
public final class SelectionPick {
    /** Interaction reach with slack, in blocks. */
    public static final double REACH = 16;
    /** How long a selected NPC glows for the selecting player, in ticks. */
    public static final int GLOW_TICKS = 40;

    /** One NPC the player can see: its id, its latest state and the shape its hitbox has. */
    public record Candidate(String id, VirtualNpcState.Snapshot npc, ViewerSession.HitShape shape) {}

    private SelectionPick() {}

    /** The looked-at NPC, else the nearest; empty when none is within reach. {@code eye} and {@code feet} are in the player's world. */
    public static Optional<String> choose(String world, Vec3 eye, Vec3 direction, Vec3 feet, Collection<Candidate> candidates) {
        Vec3 unit;
        try { unit = direction.normalized(); }
        catch (IllegalArgumentException still) { unit = null; }
        String aimed = null, nearest = null;
        double aimedAt = Double.POSITIVE_INFINITY, nearestAt = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            VirtualNpcState.Snapshot npc = candidate.npc();
            if (!npc.active() || !npc.position().world().equals(world)) continue;
            if (unit != null) {
                OptionalDouble hit = candidate.shape().rayDistance(npc, eye, unit, REACH);
                if (hit.isPresent() && hit.getAsDouble() < aimedAt) { aimedAt = hit.getAsDouble(); aimed = candidate.id(); }
            }
            double distance = npc.position().point().distance(feet);
            if (distance <= REACH && distance < nearestAt) { nearestAt = distance; nearest = candidate.id(); }
        }
        return Optional.ofNullable(aimed != null ? aimed : nearest);
    }
}
