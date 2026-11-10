package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.BodyVariant;
import java.util.List;

/**
 * The metadata values body settings pack into: bit flags shared by several settings, packed variants and ids that are
 * not a value's list position. Every transport sends these numbers; -1 means nothing is sent.
 */
public final class VariantWire {
    /** Skin layer bits in {@code DATA_PLAYER_MODE_CUSTOMISATION}, in the order of the {@code skinLayers} settings. */
    private static final List<String> LAYERS = List.of("cape", "jacket", "leftSleeve", "rightSleeve", "leftPants", "rightPants", "hat");
    private static final int[] RABBITS = {0, 1, 2, 3, 4, 5, 99};

    private VariantWire() {}

    /** The skin layers a player body shows: every layer unless {@code player} hides it. */
    public static byte skinParts(BodyVariant variant) {
        int parts = 0x7f;
        for (int bit = 0; bit < LAYERS.size(); bit++) if (variant.index(LAYERS.get(bit)) == 1) parts &= ~(1 << bit);
        return (byte) parts;
    }

    /** A sheep's wool byte: the dye in the low four bits and 0x10 when sheared. */
    public static int wool(BodyVariant variant) {
        if (variant.index("color") < 0 && !variant.flag("sheared")) return -1;
        return Math.max(0, variant.index("color")) | (variant.flag("sheared") ? 0x10 : 0);
    }

    /** A tamable body's flags: 0x01 sitting, 0x04 tamed, which a collar needs to show. */
    public static int tameFlags(BodyVariant variant) {
        int flags = (variant.flag("sitting") ? 0x01 : 0) | (variant.index("collar") >= 0 ? 0x04 : 0);
        return flags == 0 ? -1 : flags;
    }

    public static int foxFlags(BodyVariant variant) {
        return flags(variant, "sitting", 0x01, "crouching", 0x04, "interested", 0x08, "pouncing", 0x10, "sleeping", 0x20, "faceplanted", 0x40);
    }

    public static int pandaFlags(BodyVariant variant) { return flags(variant, "sneezing", 0x02, "rolling", 0x04, "sitting", 0x08); }

    public static int beeFlags(BodyVariant variant) { return flags(variant, "stung", 0x04, "nectar", 0x08); }

    /** A rabbit's type id: the killer bunny is 99. */
    public static int rabbit(BodyVariant variant) { return variant.index("type") < 0 ? -1 : RABBITS[variant.index("type")]; }

    /** A tropical fish's packed variant: the pattern's size and index, then the base and pattern dyes. */
    public static int tropicalFish(BodyVariant variant) {
        int pattern = variant.index("pattern"), base = variant.index("baseColor"), over = variant.index("patternColor");
        if (pattern < 0 && base < 0 && over < 0) return -1;
        int shape = Math.max(0, pattern);
        return (shape / 6 | shape % 6 << 8) | Math.max(0, base) << 16 | Math.max(0, over) << 24;
    }

    /** A shulker's colour byte; 16 is the undyed purple shell. */
    public static int shulkerColor(BodyVariant variant) { return variant.index("color") < 0 ? -1 : variant.index("color"); }

    /** A snow golem's pumpkin byte: 0x10 with the pumpkin, 0 without. */
    public static int pumpkin(BodyVariant variant) { return variant.index("pumpkin") < 0 ? -1 : variant.index("pumpkin") == 0 ? 0x10 : 0; }

    /** A villager's level from 1 (novice) to 5 (master). */
    public static int villagerLevel(BodyVariant variant) { return Math.max(0, variant.index("level")) + 1; }

    /** The spawn data a hanging body needs: its facing as a 3D direction id, south by default. Others send 0. */
    public static int facing(String typeKey, BodyVariant variant) {
        String facing = variant.choices().get("facing");
        return switch (typeKey) {
            case "minecraft:painting", "minecraft:item_frame", "minecraft:glow_item_frame" -> facing == null ? 3 : List.of("down", "up", "north", "south", "west", "east").indexOf(facing);
            default -> 0;
        };
    }

    /** A text display's style flags: shadow, see-through and alignment. */
    public static int textStyle(BodyVariant variant) {
        int style = Math.max(0, flags(variant, "shadowed", 0x01, "seeThrough", 0x02)) | ("left".equals(variant.choices().get("alignment")) ? 0x08 : 0)
                | ("right".equals(variant.choices().get("alignment")) ? 0x10 : 0);
        return style == 0 ? -1 : style;
    }

    /** True for bodies that are not living entities: they carry no health, attributes, arm swing or item use. */
    public static boolean living(String typeKey) { return !NON_LIVING.contains(typeKey); }
    private static final java.util.Set<String> NON_LIVING = java.util.Set.of("minecraft:painting", "minecraft:item_frame", "minecraft:glow_item_frame",
            "minecraft:falling_block", "minecraft:end_crystal", "minecraft:block_display", "minecraft:item_display", "minecraft:text_display", "minecraft:item");

    private static int flags(BodyVariant variant, Object... pairs) {
        int flags = 0;
        for (int i = 0; i < pairs.length; i += 2) if (variant.flag((String) pairs[i])) flags |= (Integer) pairs[i + 1];
        return flags == 0 ? -1 : flags;
    }
}
