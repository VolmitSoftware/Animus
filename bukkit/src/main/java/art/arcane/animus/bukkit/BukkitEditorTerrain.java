package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.core.editor.EditorTerrain;
import art.arcane.animus.core.editor.TerrainScan;
import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.Plugin;

/**
 * Terrain for the editor on Bukkit, Paper and Folia. The chunk's owner (the main thread, or on Folia the region that holds
 * it) takes a snapshot of a chunk it already has loaded; the scan runs on the snapshot afterwards, off that thread. A
 * chunk that is not loaded, or that the region no longer owns, reads as empty. World keys are world UUIDs.
 */
final class BukkitEditorTerrain implements EditorTerrain {
    private final Plugin plugin;
    private final FoliaRegions regions;

    /** {@code regions} is null off Folia. */
    BukkitEditorTerrain(Plugin plugin, FoliaRegions regions) {
        this.plugin = plugin;
        this.regions = regions;
    }

    @Override public Optional<EditorTerrain.World> world(String reference) {
        org.bukkit.World world = regions != null ? regions.world(reference) : BukkitWorldLookup.find(plugin.getServer(), reference);
        return world == null ? Optional.empty() : Optional.of(new EditorTerrain.World(world.getUID().toString(), world.getMinHeight(), world.getMaxHeight()));
    }

    @Override public CompletionStage<Optional<Surface>> surface(String world, int chunkX, int chunkZ) {
        return snapshot(world, chunkX, chunkZ).thenApplyAsync((Optional<Taken> taken) -> taken.map((Taken chunk) ->
                TerrainScan.surface(new Scan(chunk.snapshot()), chunk.minY(), chunk.maxY())));
    }

    @Override public CompletionStage<Optional<Blocks>> blocks(String world, int chunkX, int chunkZ, int minY, int height) {
        return snapshot(world, chunkX, chunkZ).thenApplyAsync((Optional<Taken> taken) -> taken.map((Taken chunk) -> {
            Map<BlockData, String> written = new HashMap<>();
            Function<BlockData, String> state = (BlockData data) -> TerrainScan.sorted(data.getAsString());
            return TerrainScan.blocks((int x, int y, int z) -> written.computeIfAbsent(chunk.snapshot().getBlockData(x, y, z), state), minY, height);
        }));
    }

    private record Taken(ChunkSnapshot snapshot, int minY, int maxY) {}

    /** Takes the snapshot on the chunk's owner, never loading it. */
    private CompletableFuture<Optional<Taken>> snapshot(String key, int chunkX, int chunkZ) {
        CompletableFuture<Optional<Taken>> result = new CompletableFuture<>();
        Runnable read = () -> {
            try {
                org.bukkit.World world = plugin.getServer().getWorld(UUID.fromString(key));
                boolean owned = regions == null || regions.owns(key, chunkX, chunkZ, chunkX, chunkZ);
                if (world == null || !owned || !world.isChunkLoaded(chunkX, chunkZ)) { result.complete(Optional.empty()); return; }
                result.complete(Optional.of(new Taken(world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false), world.getMinHeight(), world.getMaxHeight())));
            } catch (RuntimeException failure) { result.complete(Optional.empty()); }
        };
        boolean queued;
        if (!plugin.isEnabled()) queued = false;
        else if (regions != null) queued = regions.execute(new ChunkPosition(key, chunkX, chunkZ), read);
        else {
            try { plugin.getServer().getScheduler().runTask(plugin, read); queued = true; }
            catch (IllegalStateException | IllegalArgumentException refused) { queued = false; }
        }
        if (!queued) result.complete(Optional.empty());
        return result;
    }

    /** A snapshot as {@link TerrainScan} reads it. */
    private record Scan(ChunkSnapshot snapshot) implements TerrainScan.Chunk {
        @Override public boolean sectionEmpty(int section) { return snapshot.isSectionEmpty(section); }

        @Override public String block(int x, int y, int z) { return snapshot.getBlockType(x, y, z).getKey().toString(); }

        @Override public boolean wet(int x, int y, int z) {
            BlockData data = snapshot.getBlockData(x, y, z);
            Material material = data.getMaterial();
            return material == Material.WATER || material == Material.BUBBLE_COLUMN || material == Material.KELP || material == Material.KELP_PLANT
                    || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS || data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        }
    }
}
