package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether one combat action may happen. An adapter that throws or returns null denies the
 * action; a platform that cannot reach its protection plugin must answer DENY.
 */
@FunctionalInterface
public interface ProtectionAdapter {
    enum Decision { ALLOW, DENY }
    enum Action { NPC_ATTACK, PLAYER_ATTACK, MOB_ATTACK, MOB_CONTROL }

    record Request(Action action, TargetRef attacker, WorldPosition attackerPosition, TargetRef target,
                   WorldPosition targetPosition, Set<TargetCategory> targetCategories) {
        public Request {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(attacker, "attacker");
            Objects.requireNonNull(attackerPosition, "attackerPosition");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(targetPosition, "targetPosition");
            targetCategories = Set.copyOf(targetCategories);
        }
    }

    /** For a platform with no protection plugin installed. */
    ProtectionAdapter NONE = request -> Decision.ALLOW;

    Decision decide(Request request);

    /**
     * Whether the position is inside the named protection region. Empty when this adapter has no regions or the region
     * does not exist in that world, so the caller treats the region rule as unavailable.
     */
    default Optional<Boolean> inRegion(String region, WorldPosition position) { return Optional.empty(); }
}
