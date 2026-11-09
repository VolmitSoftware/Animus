package art.arcane.animus.bukkit.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One saved NPC as its definition describes it. No world entity stands behind an Animus NPC, so this is data, not
 * a handle to a live body.
 *
 * @param uuid the NPC's UUID, the same as {@code getNpcId()} on Animus events
 * @param key the NPC id used in commands and file names, such as {@code guard}
 * @param group folder of its file below {@code npcs/}, such as {@code world/town}; empty for {@code npcs/} itself
 * @param name display name
 * @param spawned whether the definition asks for the NPC to be active; a permadeath NPC that died is not
 */
public record NpcInfo(UUID uuid, String key, String group, String name, boolean spawned) {
    public NpcInfo {
        Objects.requireNonNull(uuid); Objects.requireNonNull(key); Objects.requireNonNull(group); Objects.requireNonNull(name);
    }
}
