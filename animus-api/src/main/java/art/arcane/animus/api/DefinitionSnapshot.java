package art.arcane.animus.api;

import java.util.Objects;

/**
 * Immutable accepted definition; runtime health/navigation state is intentionally absent.
 *
 * @param npc NPC the snapshot belongs to
 * @param id NPC id
 * @param group group the NPC belongs to
 * @param token revision token of this snapshot
 * @param json definition as JSON
 */
public record DefinitionSnapshot(NpcHandle npc, String id, String group, RevisionToken token, String json) {
    public DefinitionSnapshot {
        Objects.requireNonNull(npc);ApiText.id(id);ApiText.id(group);Objects.requireNonNull(token);ApiText.text(json,16*1024*1024,"definition JSON");
    }
}
