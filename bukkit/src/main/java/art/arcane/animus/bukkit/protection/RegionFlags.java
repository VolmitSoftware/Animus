package art.arcane.animus.bukkit.protection;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * The WorldGuard answers Animus needs, with no WorldGuard type in sight, so the rules built on them load and test without
 * WorldGuard. Every method throws when the protection plugin cannot answer; every caller treats a throw as deny.
 * A world is a UUID string or a world name.
 */
public interface RegionFlags {
    enum Flag { PVP, INVINCIBLE, DAMAGE_ANIMALS, MOB_DAMAGE, BUILD, BLOCK_PLACE, BLOCK_BREAK, CHEST_ACCESS, USE }
    enum State { ALLOW, DENY, UNSET }

    /** Combined state of the flags at the point, for the player with that id while online, otherwise for a non-member. */
    State state(WorldPosition at, UUID player, Flag... flags);

    /**
     * WorldGuard's build test at the point: {@code build} and the flag must pass for the player, online or not, or for a
     * non-member when {@code player} is null.
     */
    boolean canBuild(WorldPosition at, UUID player, Flag flag);

    /** Whether the point is inside the named region of its world; empty when that world has no such region. */
    Optional<Boolean> inRegion(String region, WorldPosition at);

    /** Region flags bound to the server's WorldGuard; {@code failure} says why they can only refuse. */
    record Binding(RegionFlags flags, Optional<String> failure) {
        public Binding { Objects.requireNonNull(flags); Objects.requireNonNull(failure); }

        /** The protection name for a status line, naming the consequence when WorldGuard cannot be used. */
        public String describe(String consequence) { return failure.map((String reason) -> reason + "; " + consequence).orElse("WorldGuard region flags"); }
    }

    /**
     * Null when WorldGuard is not installed. An installed WorldGuard that is disabled, or whose API cannot be bound, gives
     * flags that refuse every question, so protection fails closed instead of treating it as absent.
     */
    static Binding bind(Server server) {
        Plugin plugin = server.getPluginManager().getPlugin("WorldGuard");
        if (plugin == null) return null;
        if (!plugin.isEnabled()) return refusing("WorldGuard is installed but disabled");
        try {
            return new Binding(WorldGuardRegionFlags.create(server), Optional.empty());
        } catch (RuntimeException | LinkageError failure) {
            return refusing("WorldGuard is installed but its API could not be used (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static Binding refusing(String reason) {
        RegionFlags refused = new RegionFlags() {
            @Override public State state(WorldPosition at, UUID player, Flag... flags) { throw new IllegalStateException(reason); }
            @Override public boolean canBuild(WorldPosition at, UUID player, Flag flag) { throw new IllegalStateException(reason); }
            @Override public Optional<Boolean> inRegion(String region, WorldPosition at) { throw new IllegalStateException(reason); }
        };
        return new Binding(refused, Optional.of(reason));
    }
}
