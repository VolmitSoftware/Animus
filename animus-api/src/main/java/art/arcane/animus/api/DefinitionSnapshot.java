package art.arcane.animus.api;

import java.util.Objects;

/**
 * Immutable accepted definition; runtime health/navigation state is intentionally absent.
 *
 * @param npc NPC the snapshot belongs to
 * @param id NPC id
 * @param group folder of the NPC's file below {@code npcs/}, empty for {@code npcs/} itself
 * @param token revision token of this snapshot
 * @param json definition as JSON
 */
public record DefinitionSnapshot(NpcHandle npc, String id, String group, RevisionToken token, String json) {
    public DefinitionSnapshot {
        Objects.requireNonNull(npc);ApiText.id(id);ApiText.group(group);Objects.requireNonNull(token);ApiText.text(json,16*1024*1024,"definition JSON");
    }
}
