package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MotionValidator;
import java.util.Optional;

/**
 * Block work for builds, on the owner thread. Block state text is the platform's own full form, so two states are the
 * same block exactly when their text is equal. Nothing here loads or generates a chunk.
 */
public interface BuildWorld {
    boolean worldLoaded(String world);
    /** The chunk is loaded and this thread may read and change it. */
    boolean chunkLoaded(String world, int chunkX, int chunkZ);
    /**
     * The chunk is loaded but another region owns it (Folia only): an NPC may walk towards a block there, and the block
     * is checked and placed once the build's turn runs on that region.
     */
    default boolean elsewhere(String world, int chunkX, int chunkZ) { return false; }
    /** Why {@link #chunkLoaded} said no, for a waiting build's reason: "an unloaded chunk" unless the platform knows more. */
    default String missing(String world, int chunkX, int chunkZ) { return "an unloaded chunk"; }
    /** The block at a position in a loaded chunk inside the world's height; empty otherwise. */
    Optional<String> block(String world, BlockPos at);
    /** The platform's full text for a state, or empty when this server has no such block or property value. */
    Optional<String> resolve(BlockState state);
    /** The item that places a block state, or empty when no item does. */
    Optional<String> item(String state);
    /** Sets the block without neighbour updates. False when the position cannot take it now. */
    boolean place(String world, BlockPos at, String state);
    /** The block's place sound and particles, for nearby players. */
    void placed(String world, BlockPos at, String state, boolean sound);
    /** Items of one kind in a container at a loaded position, or -1 when there is no container there now. */
    long containerCount(String world, BlockPos at, String item);
    /** Removes items from that container; false when it no longer holds them. */
    boolean containerTake(String world, BlockPos at, String item, int count);
    /** Live collision cells for finding a place to stand. */
    MotionValidator.Sampler collision(String world);
}
