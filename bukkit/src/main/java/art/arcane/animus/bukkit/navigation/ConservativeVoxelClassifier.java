package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.*;

/** Explicit full-block support whitelist. Other materials are closed obstacles, never assumed ground. */
public final class ConservativeVoxelClassifier {
    private static final Set<String> FULL = Set.of("STONE", "GRANITE", "DIORITE", "ANDESITE", "POLISHED_GRANITE", "POLISHED_DIORITE", "POLISHED_ANDESITE",
            "COBBLESTONE", "MOSSY_COBBLESTONE", "STONE_BRICKS", "MOSSY_STONE_BRICKS", "CRACKED_STONE_BRICKS", "CHISELED_STONE_BRICKS",
            "DEEPSLATE", "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "TUFF", "CALCITE",
            "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "CLAY", "BEDROCK", "BRICKS",
            "OBSIDIAN", "CRYING_OBSIDIAN", "NETHERRACK", "END_STONE", "END_STONE_BRICKS", "BLACKSTONE", "POLISHED_BLACKSTONE",
            "SANDSTONE", "RED_SANDSTONE", "SMOOTH_SANDSTONE", "SMOOTH_RED_SANDSTONE", "SMOOTH_STONE", "GLASS",
            "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "COAL_BLOCK", "LAPIS_BLOCK", "NETHERITE_BLOCK", "QUARTZ_BLOCK",
            "SNOW_BLOCK", "TERRACOTTA", "SPONGE", "WET_SPONGE");
    private ConservativeVoxelClassifier() {}
    public static VoxelSnapshot.Cell classify(String materialName) {
        return switch (Objects.requireNonNull(materialName, "materialName")) {
            case "AIR", "CAVE_AIR", "VOID_AIR" -> VoxelSnapshot.Cell.AIR;
            case "WATER" -> VoxelSnapshot.Cell.WATER;
            case "LAVA" -> VoxelSnapshot.Cell.LAVA;
            default -> FULL.contains(materialName) || materialName.endsWith("_PLANKS") || materialName.endsWith("_LOG")
                    || materialName.endsWith("_WOOD") || materialName.endsWith("_WOOL") || materialName.endsWith("_CONCRETE")
                    || materialName.endsWith("_TERRACOTTA") || materialName.endsWith("_STAINED_GLASS")
                    ? VoxelSnapshot.Cell.SOLID : VoxelSnapshot.Cell.OBSTACLE;
        };
    }
}
