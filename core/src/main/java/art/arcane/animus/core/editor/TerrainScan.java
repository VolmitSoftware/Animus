package art.arcane.animus.core.editor;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The platform-neutral half of an {@link EditorTerrain}: scanning one chunk's blocks into a surface or a slice. */
public final class TerrainScan {
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private TerrainScan() {}

    /** One chunk as a platform reads it, local x and z 0..15, world y. */
    public interface Chunk {
        /** True when the 16-block section starting at {@code minY + section * 16} holds only air. */
        boolean sectionEmpty(int section);

        /** The block id without state properties, {@code namespace:id}. */
        String block(int x, int y, int z);

        /** Whether the block is water or holds water. */
        boolean wet(int x, int y, int z);
    }

    /** The full state of a block, {@code namespace:id[key=value,...]}. */
    @FunctionalInterface
    public interface States {
        String state(int x, int y, int z);
    }

    public static boolean air(String block) { return AIR.contains(block); }

    /** Each column's highest non-air block, its id and the water running down from it. */
    public static EditorTerrain.Surface surface(Chunk chunk, int minY, int maxY) {
        int[] heights = new int[256], water = new int[256];
        String[] blocks = new String[256];
        int sections = (maxY - minY + 15) >> 4;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int column = z * 16 + x, top = Integer.MIN_VALUE;
            for (int section = sections - 1; section >= 0 && top == Integer.MIN_VALUE; section--) {
                if (chunk.sectionEmpty(section)) continue;
                int from = Math.min(maxY - 1, minY + section * 16 + 15), to = minY + section * 16;
                for (int y = from; y >= to; y--) if (!air(chunk.block(x, y, z))) { top = y; break; }
            }
            heights[column] = top;
            blocks[column] = top == Integer.MIN_VALUE ? "minecraft:air" : chunk.block(x, top, z);
            int depth = 0;
            if (top != Integer.MIN_VALUE) for (int y = top; y >= minY && depth < 255 && chunk.wet(x, y, z); y--) depth++;
            water[column] = depth;
        }
        return new EditorTerrain.Surface(heights, blocks, water);
    }

    /** {@code height} layers from {@code minY}, index {@code ((y - minY) * 16 + z) * 16 + x}. */
    public static EditorTerrain.Blocks blocks(States states, int minY, int height) {
        String[] out = new String[256 * height];
        for (int layer = 0; layer < height; layer++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            out[(layer * 16 + z) * 16 + x] = states.state(x, minY + layer, z);
        return new EditorTerrain.Blocks(out);
    }

    /** A block state with its properties sorted by key: {@code minecraft:oak_stairs[facing=east,half=bottom]}. */
    public static String state(String id, Map<String, String> properties) {
        if (properties.isEmpty()) return id;
        StringBuilder out = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> property : new TreeMap<>(properties).entrySet()) {
            if (!first) out.append(',');
            out.append(property.getKey()).append('=').append(property.getValue());
            first = false;
        }
        return out.append(']').toString();
    }

    /** A state written {@code id[k=v,...]} in any property order, rewritten with sorted keys. */
    public static String sorted(String written) {
        int open = written.indexOf('[');
        if (open < 0 || !written.endsWith("]")) return written;
        Map<String, String> properties = new TreeMap<>();
        for (String pair : written.substring(open + 1, written.length() - 1).split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) properties.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return state(written.substring(0, open), properties);
    }
}
