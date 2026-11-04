package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import java.util.Objects;

/** A reload can republish equal definitions with new object identities before bounded reconciliation reaches them. */
final class DefinitionAuthority {
    private final String serialized;
    private NpcDefinition examined;
    private boolean accepted;
    DefinitionAuthority(NpcDefinition definition) {
        examined = Objects.requireNonNull(definition); serialized = definition.serialize(); accepted = true;
    }
    boolean accepts(NpcDefinition candidate) {
        if (candidate != examined) {
            examined = candidate;
            accepted = candidate != null && serialized.equals(candidate.serialize());
        }
        return accepted;
    }
}
