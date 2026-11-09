package art.arcane.animus.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity only; never a live entity, mutable NPC, or authority lease.
 *
 * @param uuid NPC unique id
 */
public record NpcHandle(UUID uuid) { public NpcHandle { Objects.requireNonNull(uuid); } }
