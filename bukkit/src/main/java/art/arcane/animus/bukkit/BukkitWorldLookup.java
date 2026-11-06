package art.arcane.animus.bukkit;

import art.arcane.animus.core.platform.BukkitWorlds;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Loaded Bukkit worlds by any reference a definition may hold: the folder name, the UUID or a dimension key such as
 * {@code minecraft:the_nether}, resolved as {@link BukkitWorlds} does.
 */
public final class BukkitWorldLookup {
    private BukkitWorldLookup() {}

    /** The loaded world the reference names, or null. A UUID or an exact name is found without listing the worlds. */
    public static World find(Server server, String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.length() == 36) {
            try { World byId = server.getWorld(UUID.fromString(reference)); if (byId != null) return byId; }
            catch (IllegalArgumentException notUuid) { /* A 36-character name. */ }
        }
        World named = server.getWorld(reference);
        if (named != null) return named;
        return BukkitWorlds.resolve(reference, loaded(server)).map(server::getWorld).orElse(null);
    }

    /** The loaded worlds in load order, the main world first. */
    public static List<BukkitWorlds.Loaded> loaded(Server server) {
        List<World> worlds = server.getWorlds();
        List<BukkitWorlds.Loaded> loaded = new ArrayList<>(worlds.size());
        for (World world : worlds) loaded.add(new BukkitWorlds.Loaded(world.getName(), world.getUID(), world.getKey().toString()));
        return List.copyOf(loaded);
    }
}
