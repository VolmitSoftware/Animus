package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import java.util.Objects;

/** A reload can republish equal definitions with new object identities before bounded reconciliation reaches them. */
public final class DefinitionAuthority {
    private final String serialized;
    private NpcDefinition examined;
    private boolean accepted;
    public DefinitionAuthority(NpcDefinition definition) {
        examined = Objects.requireNonNull(definition); serialized = definition.serialize(); accepted = true;
    }
    public boolean accepts(NpcDefinition candidate) {
        if (candidate != examined) {
            examined = candidate;
            accepted = candidate != null && serialized.equals(candidate.serialize());
        }
        return accepted;
    }
}
