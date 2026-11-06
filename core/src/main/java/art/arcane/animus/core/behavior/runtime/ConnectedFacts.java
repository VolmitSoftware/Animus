package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.core.behavior.BehaviorFacts;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Forwards to the platform's facts provider once it connects; {@link BehaviorFacts#NONE} until then. Thread-safe. */
final class ConnectedFacts implements BehaviorFacts {
    private volatile BehaviorFacts provider = BehaviorFacts.NONE;

    void connect(BehaviorFacts next) { provider = Objects.requireNonNull(next); }

    @Override public Optional<Map<String, Object>> player(UUID player) { return provider.player(player); }
    @Override public Optional<Map<String, Object>> npc(UUID npc) { return provider.npc(npc); }
    @Override public Map<String, Object> server() { return provider.server(); }
    @Override public Optional<String> world(String reference) { return provider.world(reference); }
}
