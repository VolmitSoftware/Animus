package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import java.util.HashMap;
import java.util.Map;

/** Unit-test state: key resolution matches the store's scopes, with no files or budgets. */
public final class MemoryBehaviorState implements BehaviorState {
    private record Key(String behavior, ScopedVariable declaration, Object npc, Object player, String name) {}
    private final Map<Key, Value> values = new HashMap<>();
    private final Map<Key, Long> expiries = new HashMap<>();
    /** Epoch milliseconds that expiring values are compared with. */
    volatile long now = 1_000_000L;

    private static Key key(Subject subject, String name, ScopedVariable declaration) {
        boolean npc = declaration.scope() == art.arcane.animus.core.behavior.BehaviorProgram.Scope.NPC || declaration.scope() == art.arcane.animus.core.behavior.BehaviorProgram.Scope.RELATIONSHIP;
        boolean player = declaration.scope().requiresPlayer();
        if (player && subject.player().isEmpty()) throw new Rejected(art.arcane.animus.api.Outcome.Status.INVALID, "behavior-state-player-required", "$.variables", "No player");
        return new Key(subject.behavior(), declaration, npc ? subject.npc() : null, player ? subject.player().orElseThrow() : null, name);
    }

    @Override public synchronized Value read(Subject subject, String variable, ScopedVariable declaration, Value initial) {
        Key key = key(subject, variable, declaration);
        Long expires = expiries.get(key);
        if (expires != null && expires <= now) return initial;
        Value stored = values.get(key);
        if (stored == null) return initial;
        if (stored.type() != initial.type()) throw new Rejected(art.arcane.animus.api.Outcome.Status.CONFLICT, "behavior-state-type-mismatch", "$.variables." + variable + ".type", "Other type");
        return stored;
    }

    @Override public synchronized void write(Subject subject, Map<String, Value> writes, Map<String, ScopedVariable> declarations) {
        write(subject, writes, declarations, Map.of());
    }

    @Override public synchronized void write(Subject subject, Map<String, Value> writes, Map<String, ScopedVariable> declarations, Map<String, Long> expiresAtMillis) {
        writes.forEach((name, value) -> {
            Key key = key(subject, name, declarations.get(name));
            values.put(key, value);
            Long expires = expiresAtMillis.get(name);
            if (expires == null) expiries.remove(key); else expiries.put(key, expires);
        });
    }
}
