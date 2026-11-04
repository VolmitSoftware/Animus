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

    private static Key key(Subject subject, String name, ScopedVariable declaration) {
        boolean npc = declaration.scope() == art.arcane.animus.core.behavior.BehaviorProgram.Scope.NPC || declaration.scope() == art.arcane.animus.core.behavior.BehaviorProgram.Scope.RELATIONSHIP;
        boolean player = declaration.scope().requiresPlayer();
        if (player && subject.player().isEmpty()) throw new Rejected(art.arcane.animus.api.Outcome.Status.INVALID, "behavior-state-player-required", "$.variables", "No player");
        return new Key(subject.behavior(), declaration, npc ? subject.npc() : null, player ? subject.player().orElseThrow() : null, name);
    }

    @Override public synchronized Value read(Subject subject, String variable, ScopedVariable declaration, Value initial) {
        return values.getOrDefault(key(subject, variable, declaration), initial);
    }

    @Override public synchronized void write(Subject subject, Map<String, Value> writes, Map<String, ScopedVariable> declarations) {
        writes.forEach((name, value) -> values.put(key(subject, name, declarations.get(name)), value));
    }
}
