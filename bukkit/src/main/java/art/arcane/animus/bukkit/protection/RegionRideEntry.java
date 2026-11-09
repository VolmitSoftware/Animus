package art.arcane.animus.bukkit.protection;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.bukkit.protection.RegionFlags.State;
import art.arcane.animus.core.control.RideEntry;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.UUID;

/**
 * WorldGuard's {@code entry} flag for a ride step: the rider may enter where they could walk in, as a member, a
 * non-member or a player with region bypass. A failed query throws, and the ride ends.
 */
public final class RegionRideEntry implements RideEntry.Rule {
    private final RegionFlags flags;

    public RegionRideEntry(RegionFlags flags) { this.flags = Objects.requireNonNull(flags); }

    /** The rule for this server: WorldGuard's entry flag when WorldGuard is installed, otherwise every step is allowed. */
    public static RideEntry.Rule bind(org.bukkit.Server server) {
        RegionFlags.Binding binding = RegionFlags.bind(server);
        return binding == null ? RideEntry.OPEN : new RegionRideEntry(binding.flags());
    }

    @Override public boolean mayEnter(UUID rider, WorldPosition at) {
        return flags.bypass(at, rider) || flags.state(at, rider, Flag.ENTRY) != State.DENY;
    }
}
