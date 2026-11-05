package art.arcane.animus.core.building;

import java.util.List;
import java.util.Set;

/**
 * The order blocks go down in within one group of layers: air first, then solid blocks, then blocks that fall, then
 * blocks that hang on others (torches, doors, redstone, plants and similar), and water and lava last. Excavation runs
 * before every group.
 */
public enum BuildPhase {
    EXCAVATE, CLEAR, SOLID, FALLING, DEPENDENT, FLUID;

    /** Phases of a layer group, in build order. */
    public static final List<BuildPhase> GROUP_ORDER = List.of(CLEAR, SOLID, FALLING, DEPENDENT, FLUID);

    private static final Set<String> FALLING_NAMES = Set.of("sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel",
            "anvil", "chipped_anvil", "damaged_anvil", "dragon_egg");
    private static final Set<String> DEPENDENT_NAMES = Set.of("redstone_wire", "repeater", "comparator", "lever", "ladder", "vine", "cake",
            "tripwire", "tripwire_hook", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass", "tall_seagrass", "kelp",
            "kelp_plant", "sugar_cane", "cactus", "bamboo", "bamboo_sapling", "cocoa", "nether_wart", "sweet_berry_bush", "lily_pad", "snow",
            "glow_lichen", "sculk_vein", "spore_blossom", "pointed_dripstone", "frogspawn", "sea_pickle", "turtle_egg", "sniffer_egg", "end_rod",
            "lightning_rod", "piston_head", "moving_piston", "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway", "cobweb",
            "scaffolding", "bell", "flower_pot", "wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "torchflower_crop",
            "pitcher_crop", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "wither_rose", "torchflower", "pitcher_plant", "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers", "leaf_litter",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots", "nether_sprouts",
            "hanging_roots", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "cave_vines", "cave_vines_plant",
            "small_dripleaf", "big_dripleaf", "big_dripleaf_stem", "azalea", "flowering_azalea", "chorus_plant", "chorus_flower",
            "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster", "resin_clump", "open_eyeblossom",
            "closed_eyeblossom", "firefly_bush", "short_dry_grass", "tall_dry_grass", "bush", "cactus_flower", "pale_hanging_moss");
    private static final List<String> DEPENDENT_PARTS = List.of("torch", "door", "button", "pressure_plate", "rail", "sign", "banner", "carpet",
            "sapling", "candle", "_head", "skull", "potted_", "propagule", "attached_", "coral_fan");
    private static final List<String> DEPENDENT_SUFFIXES = List.of("_bed", "chain", "lantern", "_coral");
    private static final Set<String> SOLID_NAMES = Set.of("jack_o_lantern", "sea_lantern", "chain_command_block");

    /** The group phase of a block, by its name. */
    public static BuildPhase of(BlockState state) {
        if (state.air()) return CLEAR;
        String path = state.path();
        if (path.equals("water") || path.equals("lava") || path.equals("bubble_column")) return FLUID;
        if (FALLING_NAMES.contains(path) || path.endsWith("_concrete_powder")) return FALLING;
        if (SOLID_NAMES.contains(path)) return SOLID;
        if (DEPENDENT_NAMES.contains(path)) return DEPENDENT;
        for (String part : DEPENDENT_PARTS) if (path.contains(part)) return DEPENDENT;
        for (String suffix : DEPENDENT_SUFFIXES) if (path.endsWith(suffix)) return DEPENDENT;
        return SOLID;
    }
}
