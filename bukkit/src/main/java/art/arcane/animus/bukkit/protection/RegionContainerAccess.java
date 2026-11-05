package art.arcane.animus.bukkit.protection;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.Location;

/**
 * WorldGuard's chest rule for an owned shop: the shop's owner, online or not, must pass {@code build} and
 * {@code chest-access} at the container, as when they open it by hand. A failed query throws, and the shop refuses.
 */
public final class RegionContainerAccess implements BiPredicate<UUID, Location> {
    private final RegionFlags flags;

    public RegionContainerAccess(RegionFlags flags) { this.flags = Objects.requireNonNull(flags); }

    @Override public boolean test(UUID owner, Location at) {
        WorldPosition position = new WorldPosition(at.getWorld().getUID().toString(), new Vec3(at.getX(), at.getY(), at.getZ()));
        return flags.canBuild(position, owner, Flag.CHEST_ACCESS);
    }
}
