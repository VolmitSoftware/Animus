package art.arcane.animus.bukkit.protection;

import art.arcane.animus.core.virtual.WorldPosition;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@link RegionFlags} read from the installed WorldGuard. The only Animus class that touches WorldGuard types; load it
 * only through {@link RegionFlags#bind}. Once WorldGuard is disabled every question throws, because a disabled
 * WorldGuard has unloaded its regions and would answer as if none existed. Without a player, the subject is an explicit
 * non-member: WorldGuard rejects a null subject for {@code build}.
 */
final class WorldGuardRegionFlags implements RegionFlags {
    /** How WorldGuard sees an NPC: in no region's member or owner list. */
    static final RegionAssociable NON_MEMBER = Associables.constant(Association.NON_MEMBER);

    private final Server server;
    private final WorldGuardPlugin plugin;

    private WorldGuardRegionFlags(Server server, WorldGuardPlugin plugin) { this.server = server; this.plugin = plugin; }

    /** Binds the installed WorldGuard; throws when its API is missing or incompatible. */
    static WorldGuardRegionFlags create(Server server) {
        WorldGuard.getInstance().getPlatform().getRegionContainer();
        return new WorldGuardRegionFlags(server, WorldGuardPlugin.inst());
    }

    @Override public State state(WorldPosition at, UUID player, Flag... flags) {
        Player online = player == null ? null : server.getPlayer(player);
        return state(applicable(at), online == null ? NON_MEMBER : plugin.wrapPlayer(online), flags);
    }

    @Override public boolean canBuild(WorldPosition at, UUID player, Flag flag) {
        if (player == null) return nonMemberMayBuild(applicable(at), flag);
        return regions().createQuery().testBuild(BukkitAdapter.adapt(location(at)), plugin.wrapOfflinePlayer(server.getOfflinePlayer(player)), flag(flag));
    }

    @Override public Optional<Boolean> inRegion(String region, WorldPosition at) {
        RegionContainer container = regions();
        World world = world(at.world());
        if (world == null) return Optional.empty();
        RegionManager manager = container.get(BukkitAdapter.adapt(world));
        ProtectedRegion found = manager == null ? null : manager.getRegion(region);
        if (found == null) return Optional.empty();
        return Optional.of(found.contains((int) Math.floor(at.point().x()), (int) Math.floor(at.point().y()), (int) Math.floor(at.point().z())));
    }

    /** Combined state of the flags among the regions at one point. */
    static State state(ApplicableRegionSet regions, RegionAssociable subject, Flag... flags) {
        StateFlag[] mapped = new StateFlag[flags.length];
        for (int i = 0; i < flags.length; i++) mapped[i] = flag(flags[i]);
        StateFlag.State state = regions.queryState(subject, mapped);
        return state == null ? State.UNSET : state == StateFlag.State.ALLOW ? State.ALLOW : State.DENY;
    }
    /** WorldGuard's build test for a non-member: allowed only when {@code build} and the flag resolve to allow. */
    static boolean nonMemberMayBuild(ApplicableRegionSet regions, Flag flag) {
        return regions.queryState(NON_MEMBER, Flags.BUILD, flag(flag)) == StateFlag.State.ALLOW;
    }

    private ApplicableRegionSet applicable(WorldPosition at) {
        return regions().createQuery().getApplicableRegions(BukkitAdapter.adapt(location(at)));
    }
    private RegionContainer regions() {
        if (!plugin.isEnabled()) throw new IllegalStateException("WorldGuard is disabled");
        return WorldGuard.getInstance().getPlatform().getRegionContainer();
    }
    private Location location(WorldPosition at) {
        World world = world(at.world());
        if (world == null) throw new IllegalStateException("world " + at.world() + " is not loaded");
        return new Location(world, at.point().x(), at.point().y(), at.point().z());
    }
    private World world(String name) {
        try { return server.getWorld(UUID.fromString(name)); }
        catch (IllegalArgumentException notUuid) { return server.getWorld(name); }
    }
    private static StateFlag flag(Flag flag) {
        return switch (flag) {
            case PVP -> Flags.PVP;
            case INVINCIBLE -> Flags.INVINCIBILITY;
            case DAMAGE_ANIMALS -> Flags.DAMAGE_ANIMALS;
            case MOB_DAMAGE -> Flags.MOB_DAMAGE;
            case BUILD -> Flags.BUILD;
            case BLOCK_PLACE -> Flags.BLOCK_PLACE;
            case BLOCK_BREAK -> Flags.BLOCK_BREAK;
            case CHEST_ACCESS -> Flags.CHEST_ACCESS;
        };
    }
}
