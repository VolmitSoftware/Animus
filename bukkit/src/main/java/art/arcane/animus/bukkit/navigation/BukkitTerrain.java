package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.TerrainReader;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;

/** Main-thread reads of loaded Bukkit terrain. World keys are world UUIDs. */
public final class BukkitTerrain implements TerrainReader {
    private final Server server;

    public BukkitTerrain(Server server) { this.server = Objects.requireNonNull(server, "server"); }

    /** The loaded world with this key, or null. */
    public World world(String key) {
        try { return server.getWorld(UUID.fromString(key)); }
        catch (IllegalArgumentException notUuid) { return null; }
    }
    @Override public boolean worldLoaded(String world) { return world(world) != null; }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) {
        World loaded = world(world);
        return loaded != null && loaded.isChunkLoaded(chunkX, chunkZ);
    }
    @Override public MotionValidator.Sampler collision(String world) {
        World loaded = world(world);
        if (loaded == null) return (int x, int y, int z) -> VoxelSnapshot.Cell.UNAVAILABLE;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Live movement validation requires the owner thread");
        return LiveMovementProbe.sampler(loaded);
    }
}
