package art.arcane.animus.bukkit.gui;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.Location;
import org.bukkit.Server;

/**
 * WorldGuard's chest rule for an owned shop: the shop's owner, online or not, must pass {@code build} and
 * {@code chest-access} at the container, as when they open it by hand. Load this class only after confirming
 * WorldGuard is enabled.
 */
final class WorldGuardContainerAccess implements BiPredicate<UUID, Location> {
    private final Server server;
    private final WorldGuardPlugin plugin;

    private WorldGuardContainerAccess(Server server, WorldGuardPlugin plugin) { this.server = server; this.plugin = plugin; }

    /** Binds the installed WorldGuard; throws when its API is missing or incompatible. */
    static WorldGuardContainerAccess create(Server server) {
        WorldGuard.getInstance().getPlatform().getRegionContainer();
        return new WorldGuardContainerAccess(server, WorldGuardPlugin.inst());
    }

    @Override public boolean test(UUID owner, Location at) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .testBuild(BukkitAdapter.adapt(at), plugin.wrapOfflinePlayer(server.getOfflinePlayer(owner)), Flags.CHEST_ACCESS);
    }
}
