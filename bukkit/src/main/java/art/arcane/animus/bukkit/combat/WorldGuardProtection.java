package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.virtual.WorldPosition;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Region flags decide combat involving virtual NPCs. An NPC has no WorldGuard identity, so it is queried as a
 * non-member. Load this class only after confirming WorldGuard is enabled. Any query failure denies.
 *
 * <ul>
 *   <li>Hitting a player or an NPC: {@code pvp} must not be denied where either side stands; a player target in an
 *   {@code invincible} region is never hurt.</li>
 *   <li>An NPC hitting an animal: {@code damage-animals} must not be denied where it stands.</li>
 *   <li>A mob hitting or being controlled against an NPC: {@code mob-damage} must not be denied where the NPC stands.</li>
 * </ul>
 */
public final class WorldGuardProtection implements ProtectionAdapter {
    private final Server server;
    private final WorldGuardPlugin plugin;

    private WorldGuardProtection(Server server, WorldGuardPlugin plugin) {
        this.server = server;
        this.plugin = plugin;
    }

    /** Binds the installed WorldGuard; throws when its API is missing or incompatible. */
    public static WorldGuardProtection create(Server server) {
        WorldGuard.getInstance().getPlatform().getRegionContainer();
        return new WorldGuardProtection(server, WorldGuardPlugin.inst());
    }

    @Override public Decision decide(Request request) {
        Location attacker = location(request.attackerPosition());
        Location target = location(request.targetPosition());
        if (attacker == null || target == null) return Decision.DENY;
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        RegionAssociable subject = subject(request.attacker());
        return switch (request.action()) {
            case MOB_ATTACK, MOB_CONTROL -> allowed(query, target, null, Flags.MOB_DAMAGE);
            case NPC_ATTACK, PLAYER_ATTACK -> {
                TargetKind kind = request.target().kind();
                if (kind == TargetKind.PLAYER || kind == TargetKind.NPC) {
                    if (kind == TargetKind.PLAYER && query.queryState(BukkitAdapter.adapt(target), subject(request.target()), Flags.INVINCIBILITY) == StateFlag.State.ALLOW)
                        yield Decision.DENY;
                    yield both(allowed(query, attacker, subject, Flags.PVP), allowed(query, target, subject, Flags.PVP));
                }
                if (request.targetCategories().contains(TargetCategory.ANIMALS)) yield allowed(query, target, subject, Flags.DAMAGE_ANIMALS);
                yield Decision.ALLOW;
            }
        };
    }

    /** Whether the position is inside the named region of its world; empty when that world has no such region. */
    @Override public Optional<Boolean> inRegion(String region, WorldPosition position) {
        Location at = location(position);
        if (at == null) return Optional.empty();
        RegionManager regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(at.getWorld()));
        ProtectedRegion found = regions == null ? null : regions.getRegion(region);
        if (found == null) return Optional.empty();
        return Optional.of(found.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    private static Decision both(Decision first, Decision second) {
        return first == Decision.ALLOW && second == Decision.ALLOW ? Decision.ALLOW : Decision.DENY;
    }
    private static Decision allowed(RegionQuery query, Location at, RegionAssociable subject, StateFlag flag) {
        return query.queryState(BukkitAdapter.adapt(at), subject, flag) == StateFlag.State.DENY ? Decision.DENY : Decision.ALLOW;
    }
    private RegionAssociable subject(TargetRef ref) {
        if (ref.kind() != TargetKind.PLAYER) return null;
        Player player = server.getPlayer(ref.id());
        return player == null ? null : plugin.wrapPlayer(player);
    }
    private Location location(WorldPosition position) {
        World world;
        try { world = server.getWorld(UUID.fromString(position.world())); }
        catch (IllegalArgumentException name) { world = server.getWorld(position.world()); }
        return world == null ? null : new Location(world, position.point().x(), position.point().y(), position.point().z());
    }
}
