package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable observation of a real entity or virtual NPC, taken on the owner thread.
 *
 * @param type   namespaced entity type
 * @param name   player name, custom name or NPC display name
 * @param handle readable NPC ID, or blank for real entities
 * @param squad  NPC squad, when it has one
 */
public record CombatTarget(TargetRef ref, String type, String name, WorldPosition position, Hitbox bounds, double health,
                           Set<TargetCategory> categories, String handle, Optional<String> squad) {
    public CombatTarget {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(squad, "squad");
        categories = Set.copyOf(categories);
    }
}
