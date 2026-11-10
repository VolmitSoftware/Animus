package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.bukkit.api.NpcPosition;
import art.arcane.animus.core.integration.MythicBinding;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** What the MythicMobs integration needs from Animus: NPCs as they stand now, and the threads that own the world. */
interface MythicHost {
    /** An NPC by UUID or by id, with where it stands now when it is active. */
    record Npc(UUID uuid, String id, String name, Optional<MythicBinding> binding, Optional<Location> location) {}

    Optional<Npc> npc(UUID npc);

    Optional<Npc> npc(String id);

    /** An online player, else a loaded entity, by UUID. Call it on the owner of where the entity is expected. */
    Optional<org.bukkit.entity.Entity> entity(UUID id);

    /** Where an event's position is, when its world is loaded. */
    Optional<Location> location(NpcPosition position);

    /** Runs {@code work} on the thread that owns {@code location}; false when it was refused. */
    boolean at(Location location, Runnable work);

    /** Runs {@code work} with the online player on the thread that owns them; false when they are offline or it was refused. */
    boolean player(UUID player, java.util.function.Consumer<Player> work);
}
