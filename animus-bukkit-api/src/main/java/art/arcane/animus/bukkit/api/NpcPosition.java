package art.arcane.animus.bukkit.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Where an NPC stood when an event fired. {@code world} is the world's UUID as text, or its name for a position read
 * from a definition file.
 *
 * @param world world UUID or name
 * @param x block x, with fraction
 * @param y block y, with fraction
 * @param z block z, with fraction
 */
public record NpcPosition(String world, double x, double y, double z) {
    public NpcPosition {
        Objects.requireNonNull(world);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite position");
    }

    /** The loaded world this position is in, if any. Call it on a server thread. */
    public Optional<World> loadedWorld() {
        try { return Optional.ofNullable(Bukkit.getWorld(UUID.fromString(world))); }
        catch (IllegalArgumentException name) { return Optional.ofNullable(Bukkit.getWorld(world)); }
    }

    /** A Bukkit location, when the world is loaded. Call it on a server thread. */
    public Optional<Location> toLocation() { return loadedWorld().map((World loaded) -> new Location(loaded, x, y, z)); }
}
