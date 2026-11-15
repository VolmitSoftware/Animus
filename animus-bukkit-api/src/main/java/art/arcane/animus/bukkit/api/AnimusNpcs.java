package art.arcane.animus.bukkit.api;

import art.arcane.animus.api.NpcService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * The Animus service Bukkit's {@code ServicesManager} holds while Animus is enabled:
 * <pre>{@code
 * AnimusNpcs animus = Bukkit.getServicesManager().load(AnimusNpcs.class);
 * NpcService npcs = animus.service(this);
 * }</pre>
 * {@link #service} creates, edits, removes and drives NPCs for one plugin. The lookups answer from the definitions Animus
 * has loaded, temporary NPCs included, at once and from any thread. They reflect definitions, not runtime state such as
 * current position or health; {@link NpcService#state} and the events in {@code art.arcane.animus.bukkit.event} give that.
 */
public interface AnimusNpcs {
    /**
     * The NPC service of {@code plugin}. The plugin's temporary NPCs, definition modules, hiding and follows end when it
     * disables, and the service answers UNAVAILABLE from then on. The same plugin gets the same service. Any thread.
     *
     * @param plugin an enabled plugin
     * @return its service
     * @throws IllegalStateException when the plugin is not enabled or Animus is not running
     */
    NpcService service(Plugin plugin);

    /**
     * The NPC with this id, such as {@code guard}.
     *
     * @param key NPC id
     * @return the NPC, if any
     */
    Optional<NpcInfo> byKey(String key);

    /**
     * The NPC with this UUID, as Animus events and {@link art.arcane.animus.api.NpcHandle} carry it.
     *
     * @param uuid NPC UUID
     * @return the NPC, if any
     */
    Optional<NpcInfo> byUuid(UUID uuid);

    /**
     * NPCs whose display name equals {@code name}, ignoring case and colour codes, in id order.
     *
     * @param name display name
     * @return the matching NPCs
     */
    List<NpcInfo> byName(String name);

    /**
     * Every NPC in id order.
     *
     * @return all NPCs
     */
    List<NpcInfo> all();
}
