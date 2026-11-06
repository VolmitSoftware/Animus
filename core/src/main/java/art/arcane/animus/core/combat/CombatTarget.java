package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
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
                           Set<TargetCategory> categories, String handle, Optional<String> squad, Details details) {
    /**
     * What item and health rules read.
     *
     * @param maximumHealth 0 when unknown
     * @param mainHand      namespaced item id, {@link CombatLoadout#EMPTY} when nothing is held
     * @param worn          namespaced ids of worn armor pieces
     * @param effects       namespaced ids of active status effects
     * @param invulnerable  nothing can hurt it, such as an invincible NPC or an invulnerable entity, so it is never chosen as a target
     */
    public record Details(double maximumHealth, String mainHand, String offHand, List<String> worn, Set<String> effects, boolean invulnerable) {
        public static final Details NONE = new Details(0, CombatLoadout.EMPTY, CombatLoadout.EMPTY, List.of(), Set.of());
        public Details(double maximumHealth, String mainHand, String offHand, List<String> worn, Set<String> effects) {
            this(maximumHealth, mainHand, offHand, worn, effects, false);
        }
        public Details {
            Objects.requireNonNull(mainHand, "mainHand");
            Objects.requireNonNull(offHand, "offHand");
            worn = List.copyOf(worn);
            effects = Set.copyOf(effects);
        }
    }

    public CombatTarget {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(squad, "squad");
        Objects.requireNonNull(details, "details");
        categories = Set.copyOf(categories);
    }
}
