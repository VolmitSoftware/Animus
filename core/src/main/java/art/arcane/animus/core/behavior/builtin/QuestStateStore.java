package art.arcane.animus.core.behavior.builtin;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Narrow scoped state contract used by flag and quest capabilities. Implementations must be thread safe and
 * must not block the caller. The durable state lane backs this in place of {@link InMemoryQuestStateStore}.
 */
public interface QuestStateStore {
    enum Kind { FLAG, QUEST }
    enum Scope { GLOBAL, NPC, PLAYER, RELATIONSHIP }
    record Key(Kind kind, Scope scope, Optional<UUID> npc, Optional<UUID> player, String name) {
        public Key {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(npc);
            Objects.requireNonNull(player);
            Objects.requireNonNull(name);
            boolean wantsNpc = scope == Scope.NPC || scope == Scope.RELATIONSHIP;
            boolean wantsPlayer = scope == Scope.PLAYER || scope == Scope.RELATIONSHIP;
            if (npc.isPresent() != wantsNpc || player.isPresent() != wantsPlayer)
                throw new IllegalArgumentException("State key identities must match its scope");
        }
    }

    CompletionStage<OptionalLong> read(Key key);
    CompletionStage<Long> set(Key key, long value);
    /** Adds to the current value, or to zero when absent, without exceeding the maximum. */
    CompletionStage<Long> advance(Key key, long delta, long maximum);
}
