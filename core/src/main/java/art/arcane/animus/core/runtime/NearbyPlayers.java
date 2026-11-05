package art.arcane.animus.core.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Online players bucketed by chunk, so "players near a point" visits the chunks around the point instead of every
 * player in the world. The owner rebuilds it once per tick before its first query and only touches it on that thread.
 * Buckets come from the rebuild; the distance test uses each player's position now, so a player who moved away since
 * the rebuild is not reported. A player who moved more than a chunk into the area is found from the next rebuild.
 *
 * @param <P> the platform's online player
 */
public final class NearbyPlayers<P> {
    /** Players walk at most a few blocks a tick; one chunk of margin keeps them in reach of their old bucket. */
    private static final int MARGIN_CHUNKS = 1;
    private final Function<? super P, WorldPosition> position;
    private final Map<String, Map<Long, List<P>>> cells = new HashMap<>();
    private final Map<String, List<P>> worlds = new HashMap<>();
    private int size;

    /** {@code position} returns null for a player who is no longer online. */
    public NearbyPlayers(Function<? super P, WorldPosition> position) { this.position = Objects.requireNonNull(position); }

    public void rebuild(Iterable<? extends P> players) {
        cells.clear(); worlds.clear(); size = 0;
        for (P player : players) {
            WorldPosition at = position.apply(player);
            if (at == null) continue;
            ChunkPosition chunk = at.chunk();
            cells.computeIfAbsent(at.world(), (String world) -> new HashMap<>())
                    .computeIfAbsent(ChunkPosition.key(chunk.x(), chunk.z()), (Long key) -> new ArrayList<>()).add(player);
            worlds.computeIfAbsent(at.world(), (String world) -> new ArrayList<>()).add(player);
            size++;
        }
    }

    public int size() { return size; }

    /** At most {@code limit} players within {@code radius} blocks of the point, in its world. */
    public List<P> near(WorldPosition at, double radius, int limit) {
        List<P> result = new ArrayList<>();
        List<P> inWorld = worlds.get(at.world());
        if (inWorld == null || limit <= 0 || !(radius >= 0)) return result;
        double x = at.point().x(), z = at.point().z();
        long minX = (long) Math.floor((x - radius) / 16) - MARGIN_CHUNKS, maxX = (long) Math.floor((x + radius) / 16) + MARGIN_CHUNKS;
        long minZ = (long) Math.floor((z - radius) / 16) - MARGIN_CHUNKS, maxZ = (long) Math.floor((z + radius) / 16) + MARGIN_CHUNKS;
        long area = (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area >= inWorld.size()) {
            // Fewer players than chunks to visit: testing each player is cheaper.
            for (P player : inWorld) if (accept(player, at, radius, result) && result.size() >= limit) break;
            return result;
        }
        Map<Long, List<P>> world = cells.get(at.world());
        for (long chunkX = minX; chunkX <= maxX; chunkX++) {
            for (long chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                List<P> bucket = world.get(ChunkPosition.key((int) chunkX, (int) chunkZ));
                if (bucket == null) continue;
                for (P player : bucket) if (accept(player, at, radius, result) && result.size() >= limit) return result;
            }
        }
        return result;
    }

    private boolean accept(P player, WorldPosition center, double radius, List<P> result) {
        WorldPosition now = position.apply(player);
        if (now == null || !now.world().equals(center.world()) || now.point().distance(center.point()) > radius) return false;
        result.add(player);
        return true;
    }
}
