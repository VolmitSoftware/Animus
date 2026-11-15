package art.arcane.animus.bukkit.api;

import art.arcane.animus.api.ItemSpec;
import art.arcane.animus.api.NpcLocation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Conversions between Bukkit types and the platform-neutral types of {@link art.arcane.animus.api.NpcService}. */
public final class BukkitNpcs {
    private BukkitNpcs() {}

    /**
     * A Bukkit location as the service takes it, with its world's name and facing. Any thread that may read the location.
     *
     * @param location a location in a loaded world
     * @return the location
     */
    public static NpcLocation location(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "the location has no world");
        return new NpcLocation(world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /**
     * A Bukkit location for a service location, when its world is loaded. Call it on a server thread.
     *
     * @param location a world name or UUID with coordinates
     * @return the Bukkit location
     */
    public static Optional<Location> location(NpcLocation location) {
        World world;
        try { world = Bukkit.getWorld(UUID.fromString(location.world())); }
        catch (IllegalArgumentException name) { world = Bukkit.getWorld(location.world()); }
        return Optional.ofNullable(world).map((World loaded) -> new Location(loaded, location.x(), location.y(), location.z(), location.yaw(), location.pitch()));
    }

    /**
     * An item stack as a definition stores it: its item id, count and components. Call it on a thread that may read the
     * stack.
     *
     * @param stack a stack that is not air
     * @return the item
     */
    public static ItemSpec item(ItemStack stack) {
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        String components = meta == null ? "" : meta.getAsComponentString();
        return new ItemSpec(stack.getType().getKey().toString(), stack.getAmount(),
                components.isBlank() || components.equals("[]") ? Optional.empty() : Optional.of(components));
    }
}
