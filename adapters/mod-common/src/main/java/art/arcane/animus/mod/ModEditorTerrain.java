package art.arcane.animus.mod;

import art.arcane.animus.core.editor.EditorTerrain;
import art.arcane.animus.core.editor.TerrainScan;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Terrain for the editor on Fabric, NeoForge and Forge: chunks the server already holds, read on the server thread
 * through {@code getChunkNow}, which never loads or generates one. World keys are dimension ids.
 */
final class ModEditorTerrain implements EditorTerrain {
    private final MinecraftServer server;

    ModEditorTerrain(MinecraftServer server) { this.server = Objects.requireNonNull(server); }

    @Override public Optional<World> world(String reference) {
        return ModWorldKeys.resolve(server, reference).map((String key) -> {
            ServerLevel level = ModWorldKeys.level(server, key);
            return level == null ? null : new World(key, level.getMinY(), level.getMinY() + level.getHeight());
        });
    }

    @Override public CompletionStage<Optional<Surface>> surface(String world, int chunkX, int chunkZ) {
        return read(world, chunkX, chunkZ, (Loaded chunk) -> TerrainScan.surface(new Scan(chunk), chunk.minY(), chunk.maxY()));
    }

    @Override public CompletionStage<Optional<Blocks>> blocks(String world, int chunkX, int chunkZ, int minY, int height) {
        return read(world, chunkX, chunkZ, (Loaded chunk) -> {
            Map<BlockState, String> written = new HashMap<>();
            return TerrainScan.blocks((int x, int y, int z) -> written.computeIfAbsent(chunk.state(x, y, z), ModEditorTerrain::state), minY, height);
        });
    }

    private record Loaded(LevelChunk chunk, int minY, int maxY) {
        BlockState state(int x, int y, int z) {
            LevelChunkSection section = chunk.getSections()[(y - minY) >> 4];
            return section.getBlockState(x, y & 15, z);
        }
    }

    /** Runs the read on the server thread over a chunk it already holds; empty when it holds none. */
    private <T> CompletableFuture<Optional<T>> read(String world, int chunkX, int chunkZ, Function<Loaded, T> scan) {
        CompletableFuture<Optional<T>> result = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    ServerLevel level = ModWorldKeys.level(server, world);
                    LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(chunkX, chunkZ);
                    result.complete(chunk == null ? Optional.empty() : Optional.of(scan.apply(new Loaded(chunk, level.getMinY(), level.getMinY() + level.getHeight()))));
                } catch (RuntimeException failure) { result.complete(Optional.empty()); }
            });
        } catch (RuntimeException stopping) { result.complete(Optional.empty()); }
        return result;
    }

    private static String state(BlockState state) {
        Map<String, String> properties = new HashMap<>();
        for (Property<?> property : state.getProperties()) properties.put(property.getName(), value(state, property));
        return TerrainScan.state(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }

    /** A held chunk as {@link TerrainScan} reads it. */
    private record Scan(Loaded loaded) implements TerrainScan.Chunk {
        @Override public boolean sectionEmpty(int section) { return loaded.chunk().getSections()[section].hasOnlyAir(); }

        @Override public String block(int x, int y, int z) { return BuiltInRegistries.BLOCK.getKey(loaded.state(x, y, z).getBlock()).toString(); }

        @Override public boolean wet(int x, int y, int z) { return loaded.state(x, y, z).getFluidState().is(FluidTags.WATER); }
    }
}
