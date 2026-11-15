package art.arcane.animus.api;

import java.util.Objects;
import java.util.Optional;

/**
 * An NPC as the server holds it at one moment.
 *
 * @param npc the NPC
 * @param id its id
 * @param temporary true for an NPC that is never saved
 * @param owner the plugin or Java module that created a temporary NPC; empty for a saved one
 * @param active whether its body is in the world now
 * @param location where it stands now while active, otherwise where its definition places it
 * @since 1.3
 */
public record NpcState(NpcHandle npc, String id, boolean temporary, Optional<String> owner, boolean active, NpcLocation location) {
    public NpcState {
        Objects.requireNonNull(npc, "npc"); Objects.requireNonNull(id, "id"); Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(location, "location");
    }
}
