package art.arcane.animus.core.navigation;

/** Whether the calling thread owns a chunk of the world being read. Checked before every live read on Folia. */
@FunctionalInterface
public interface ChunkOwnership {
    boolean owns(int chunkX, int chunkZ);
}
