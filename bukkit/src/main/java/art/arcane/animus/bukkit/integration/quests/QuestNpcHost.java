package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.api.AnimusNpcs;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/**
 * What every quest plugin integration reads from and does to Animus NPCs: Quests, BeautyQuests and BetonQuest.
 * Lookups are safe from any thread; changes are queued on the file lane and reach the world on the next refresh.
 */
public interface QuestNpcHost {
    AnimusNpcs npcs();

    /** The NPC's name without color or formatting, as quest prompts print it. */
    static String plainName(String name) { return art.arcane.animus.core.text.RichText.plain(name); }

    /** Where the NPC stands now, or its saved position when it is not active; empty when its world is not loaded. */
    Optional<Location> location(UUID npc);

    /** The NPC's body height in blocks, when it is active. */
    Optional<Double> height(UUID npc);

    /** Saves a new position for the NPC. */
    void teleport(String key, Location to);

    /** Saves whether the NPC is spawned. */
    void spawned(String key, boolean spawned);

    /** Hides the NPC from one viewer, or shows it again; the viewer neither sees it nor fires its triggers while hidden. */
    void hide(UUID viewer, UUID npc, boolean hidden);

    boolean hidden(UUID viewer, UUID npc);
}
