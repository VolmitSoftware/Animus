package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PaletteResolverTest {
    /** A server that knows these states exactly, and these names with no properties. */
    private static Function<BlockState, Optional<String>> server(Set<String> states) {
        return (BlockState state) -> states.contains(state.toString()) ? Optional.of(state.toString()) : Optional.empty();
    }

    @Test void renamedBlocksDroppedPropertiesAndUnknownBlocksAreReported() {
        Schematic schematic = BuildFixtures.schematic(Map.of('g', "grass", 'f', "furnace[facing=north,lit=false,glowing=true]", 'x', "mystery_block",
                's', "oak_stairs[facing=north]"), new String[]{"gfxs", "xx.s"});
        PaletteResolver.Result result = PaletteResolver.resolve(schematic, new BlockTransform(BlockTransform.Rotation.CLOCKWISE_90, BlockTransform.Mirror.NONE),
                server(Set.of("minecraft:short_grass", "minecraft:furnace", "minecraft:oak_stairs[facing=east]")));
        assertEquals(List.of("minecraft:short_grass", "minecraft:furnace", "minecraft:mystery_block", "minecraft:oak_stairs[facing=east]"),
                result.palette().stream().map(BlockState::toString).toList());
        assertArrayEquals(new boolean[]{true, true, false, true}, result.placeable());
        assertEquals(3, result.unknownCells());
        assertEquals(List.of("renamed minecraft:grass to minecraft:short_grass",
                "placed minecraft:furnace[facing=north,glowing=true,lit=false] without the properties this server does not have (1 cells)",
                "unknown block minecraft:mystery_block (3 cells left unchanged)"), result.report());
    }

    @Test void unparseableServerTextIsNotPlaceable() {
        Schematic schematic = BuildFixtures.schematic(Map.of('s', "stone"), new String[]{"s"});
        PaletteResolver.Result result = PaletteResolver.resolve(schematic, BlockTransform.IDENTITY, (BlockState state) -> Optional.of("Weird Text"));
        assertArrayEquals(new boolean[]{false}, result.placeable());
        assertTrue(result.report().getFirst().startsWith("unknown block"));
    }

    @Test void excessNotesAreSummarised() {
        StringBuilder row = new StringBuilder();
        Map<Character, String> legend = new java.util.HashMap<>();
        for (int index = 0; index < 70; index++) {
            char symbol = (char) ('A' + index);
            legend.put(symbol, "unknown_" + index);
            row.append(symbol);
        }
        PaletteResolver.Result result = PaletteResolver.resolve(BuildFixtures.schematic(legend, new String[]{row.toString()}), BlockTransform.IDENTITY,
                (BlockState state) -> Optional.empty());
        assertEquals(65, result.report().size());
        assertEquals("and 6 more palette notes", result.report().getLast());
    }
}
