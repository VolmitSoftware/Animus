package art.arcane.animus.core.editor;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Loaded-chunk reads for the editor's terrain view. Each platform implements it once. Reads run on the thread that owns
 * the chunk and never load or generate one: a chunk that is not loaded completes empty.
 */
public interface EditorTerrain {
    /** A loaded world: its platform key and build range, {@code maxY} exclusive. */
    record World(String key, int minY, int maxY) {}

    /**
     * The top of every column of one chunk, index {@code z * 16 + x}. {@code heights} holds the y of the highest non-air
     * block ({@link Integer#MIN_VALUE} for an empty column), {@code blocks} that block's id without state properties,
     * {@code water} how many water or waterlogged blocks run down from the top (0 when the top is dry).
     */
    record Surface(int[] heights, String[] blocks, int[] water) {
        public Surface {
            if (heights.length != 256 || blocks.length != 256 || water.length != 256) throw new IllegalArgumentException("a chunk surface has 256 columns");
        }
    }

    /** Block states of one chunk slice, index {@code ((y - minY) * 16 + z) * 16 + x}, written {@code namespace:id[key=value,...]} with keys sorted. */
    record Blocks(String[] states) {}

    /** The loaded world a definition's world reference names, on any thread. */
    Optional<World> world(String reference);

    /** The surface of a loaded chunk, read on its owning thread; empty when the chunk is not loaded. */
    CompletionStage<Optional<Surface>> surface(String world, int chunkX, int chunkZ);

    /** The states of {@code height} block layers from {@code minY} of a loaded chunk, read on its owning thread; empty when it is not loaded. */
    CompletionStage<Optional<Blocks>> blocks(String world, int chunkX, int chunkZ, int minY, int height);
}
