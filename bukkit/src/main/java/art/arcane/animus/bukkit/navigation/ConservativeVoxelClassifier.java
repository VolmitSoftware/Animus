package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.Objects;

/** Material names come from Bukkit Material.name(). Everything except known air/fluid is a closed full voxel. */
public final class ConservativeVoxelClassifier {
    private ConservativeVoxelClassifier() {}
    public static VoxelSnapshot.Cell classify(String materialName) {
        return switch (Objects.requireNonNull(materialName, "materialName")) {
            case "AIR", "CAVE_AIR", "VOID_AIR" -> VoxelSnapshot.Cell.AIR;
            case "WATER" -> VoxelSnapshot.Cell.WATER;
            case "LAVA" -> VoxelSnapshot.Cell.LAVA;
            default -> VoxelSnapshot.Cell.SOLID;
        };
    }
}
