package art.arcane.animus.bukkit.protection;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;

/**
 * Region flags decide each block a build changes. An NPC has no WorldGuard identity, so it may change a block only
 * where WorldGuard would let a non-member: outside every region, or where {@code build} and {@code block-place} (or
 * {@code block-break} for a block turned to air) resolve to allow. A failed query throws, and the build gate refuses
 * the change.
 */
public final class RegionBuildProtection implements BuildProtection {
    private final RegionFlags flags;

    public RegionBuildProtection(RegionFlags flags) { this.flags = Objects.requireNonNull(flags); }

    @Override public Decision decide(Request request) {
        WorldPosition at = new WorldPosition(request.world(), new Vec3(request.at().x() + .5, request.at().y() + .5, request.at().z() + .5));
        Flag specific = request.action() == Action.PLACE ? Flag.BLOCK_PLACE : Flag.BLOCK_BREAK;
        return flags.canBuild(at, null, specific) ? Decision.ALLOW : Decision.DENY;
    }
}
