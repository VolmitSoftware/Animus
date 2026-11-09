package art.arcane.animus.api;

import java.util.Objects;

/**
 * Full replacement draft against an accepted token; applying it must revalidate identity and conflicts.
 *
 * @param npc NPC being edited
 * @param expected revision token the edit was based on
 * @param json replacement definition as JSON
 */
public record DefinitionEdit(NpcHandle npc, RevisionToken expected, String json) {
    public DefinitionEdit { Objects.requireNonNull(npc);Objects.requireNonNull(expected);ApiText.text(json,16*1024*1024,"definition JSON"); }
}
