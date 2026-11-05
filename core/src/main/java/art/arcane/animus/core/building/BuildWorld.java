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
    boolean chunkLoaded(String world, int chunkX, int chunkZ);
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
