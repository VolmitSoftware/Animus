package art.arcane.animus.bukkit.building;

import art.arcane.animus.core.building.BuildProtection;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Region flags decide each block a build changes. An NPC has no WorldGuard identity, so it is asked about as a
 * non-member: {@code build} and {@code block-place} (or {@code block-break} for a block turned to air) must not be
 * denied at the block. Load this class only after confirming WorldGuard is enabled. Any query failure denies.
 */
public final class WorldGuardBuildProtection implements BuildProtection {
    private final Server server;

    private WorldGuardBuildProtection(Server server) { this.server = server; }

    /** Binds the installed WorldGuard; throws when its API is missing or incompatible. */
    public static WorldGuardBuildProtection create(Server server) {
        WorldGuard.getInstance().getPlatform().getRegionContainer();
        return new WorldGuardBuildProtection(server);
    }

    @Override public Decision decide(Request request) {
        World world;
        try { world = server.getWorld(UUID.fromString(request.world())); }
        catch (IllegalArgumentException name) { world = server.getWorld(request.world()); }
        if (world == null) return Decision.DENY;
        Location at = new Location(world, request.at().x() + .5, request.at().y() + .5, request.at().z() + .5);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        StateFlag specific = request.action() == Action.PLACE ? Flags.BLOCK_PLACE : Flags.BLOCK_BREAK;
        return query.queryState(BukkitAdapter.adapt(at), (RegionAssociable) null, Flags.BUILD, specific) == StateFlag.State.DENY ? Decision.DENY : Decision.ALLOW;
    }
}
