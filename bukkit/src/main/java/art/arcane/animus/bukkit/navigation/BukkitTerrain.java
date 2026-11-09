package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.TerrainReader;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;

/** Main-thread reads of loaded Bukkit terrain. World keys are world UUIDs. */
public final class BukkitTerrain implements TerrainReader {
    /** Keys seen before; a key that is not a UUID maps to {@link #NOT_A_WORLD}. Cleared if many unknown keys arrive. */
    private static final int MAXIMUM_KEYS = 256;
    private static final UUID NOT_A_WORLD = new UUID(0, 0);
    private final Server server;
    private final Map<String, UUID> keys = new ConcurrentHashMap<>();

    public BukkitTerrain(Server server) { this.server = Objects.requireNonNull(server, "server"); }

    /** The loaded world with this key, or null. */
    public World world(String key) {
        UUID id = keys.get(key);
        if (id == null) {
            try { id = UUID.fromString(key); }
            catch (IllegalArgumentException notUuid) { id = NOT_A_WORLD; }
            if (keys.size() >= MAXIMUM_KEYS) keys.clear();
            keys.put(key, id);
        }
        return id == NOT_A_WORLD ? null : server.getWorld(id);
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
