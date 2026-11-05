package art.arcane.animus.core.navigation;

/** Owner-thread reads of already-loaded terrain by world key. Never loads a chunk. */
public interface TerrainReader {
    boolean worldLoaded(String world);
    boolean chunkLoaded(String world, int chunkX, int chunkZ);
    /**
     * Live collision cells, valid for the current owner turn. A voxel outside the world's height or in an unloaded
     * chunk reads as {@link VoxelSnapshot.Cell#UNAVAILABLE}.
     */
    MotionValidator.Sampler collision(String world);
}
