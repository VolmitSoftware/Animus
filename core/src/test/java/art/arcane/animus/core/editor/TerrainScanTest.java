package art.arcane.animus.core.editor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The surface and slice scans the Bukkit and mod terrain readers share. */
class TerrainScanTest {
    /** Stone to y 62, a two-deep pool at x 0, short grass on x 1, an empty column at x 2 z 0, the rest of the sections air. */
    private static final class World implements TerrainScan.Chunk {
        final Set<Integer> sectionsRead = new HashSet<>();

        @Override public boolean sectionEmpty(int section) { return section > 8; }

        @Override public String block(int x, int y, int z) {
            sectionsRead.add((y + 64) >> 4);
            if (x == 2 && z == 0) return "minecraft:air";
            if (x == 0) return y <= 60 ? "minecraft:stone" : y <= 62 ? "minecraft:water" : "minecraft:air";
            if (x == 1 && y == 63) return "minecraft:short_grass";
            return y <= 62 ? "minecraft:stone" : y == 63 ? "minecraft:air" : "minecraft:cave_air";
        }

        @Override public boolean wet(int x, int y, int z) { return block(x, y, z).equals("minecraft:water"); }
    }

    @Test
    void surface_findsTheTopBlockAndTheWaterUnderIt() {
        World world = new World();
        EditorTerrain.Surface surface = TerrainScan.surface(world, -64, 320);
        assertEquals(62, surface.heights()[0]);
        assertEquals("minecraft:water", surface.blocks()[0]);
        assertEquals(2, surface.water()[0]);
        assertEquals(63, surface.heights()[1]);
        assertEquals("minecraft:short_grass", surface.blocks()[1]);
        assertEquals(0, surface.water()[1]);
        assertEquals(62, surface.heights()[16 + 5]);
        assertEquals("minecraft:stone", surface.blocks()[16 + 5]);
        assertEquals(Integer.MIN_VALUE, surface.heights()[2]);
        assertEquals("minecraft:air", surface.blocks()[2]);
        assertFalse(world.sectionsRead.contains(20), "empty sections are skipped");
    }

    @Test
    void blocks_orderLayersThenRowsThenColumns() {
        EditorTerrain.Blocks blocks = TerrainScan.blocks((int x, int y, int z) -> x + "," + y + "," + z, 10, 2);
        assertEquals(512, blocks.states().length);
        assertEquals("0,10,0", blocks.states()[0]);
        assertEquals("3,10,1", blocks.states()[16 + 3]);
        assertEquals("15,11,15", blocks.states()[511]);
    }

    @Test
    void states_sortTheirProperties() {
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]",
                TerrainScan.state("minecraft:oak_stairs", Map.of("shape", "straight", "half", "bottom", "facing", "east")));
        assertEquals("minecraft:stone", TerrainScan.state("minecraft:stone", Map.of()));
        assertEquals("minecraft:oak_fence[east=true,north=false,waterlogged=false]", TerrainScan.sorted("minecraft:oak_fence[waterlogged=false,north=false,east=true]"));
        assertEquals("minecraft:stone", TerrainScan.sorted("minecraft:stone"));
        assertTrue(TerrainScan.air("minecraft:cave_air"));
    }
}
