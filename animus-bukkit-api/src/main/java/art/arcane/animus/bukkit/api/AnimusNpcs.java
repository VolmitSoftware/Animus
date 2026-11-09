package art.arcane.animus.bukkit.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only NPC lookup Animus registers with Bukkit's {@code ServicesManager} while it is enabled:
 * <pre>{@code
 * AnimusNpcs npcs = Bukkit.getServicesManager().load(AnimusNpcs.class);
 * }</pre>
 * Answers come from the definitions Animus has loaded and are safe to read from any thread. They reflect saved files,
 * not runtime state such as current position or health; listen to the events in {@code art.arcane.animus.bukkit.event}
 * for that.
 */
public interface AnimusNpcs {
    /** The NPC with this id, such as {@code guard}. */
    Optional<NpcInfo> byKey(String key);

    /** The NPC with this UUID, as Animus events report it. */
    Optional<NpcInfo> byUuid(UUID uuid);

    /** NPCs whose display name equals {@code name}, ignoring case and color codes, in id order. */
    List<NpcInfo> byName(String name);

    /** Every NPC in id order. */
    List<NpcInfo> all();
}
