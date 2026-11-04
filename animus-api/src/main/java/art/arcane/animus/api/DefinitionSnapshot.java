package art.arcane.animus.api;

import java.util.Objects;

/** Immutable accepted definition; runtime health/navigation state is intentionally absent. */
public record DefinitionSnapshot(NpcHandle npc, String id, String group, RevisionToken token, String json) {
    public DefinitionSnapshot {
        Objects.requireNonNull(npc);ApiText.id(id);ApiText.id(group);Objects.requireNonNull(token);ApiText.text(json,16*1024*1024,"definition JSON");
    }
}
