package art.arcane.animus.core.building;

import static art.arcane.animus.core.building.BuildFixtures.plan;
import static art.arcane.animus.core.building.BuildFixtures.schematic;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildPlannerTest {
    private static final Map<Character, String> LEGEND = Map.of('s', "stone", 'a', "air", 't', "torch", 'w', "water", 'g', "gravel",
            'd', "oak_door[half=lower]", 'u', "oak_door[half=upper]");
    private static final BlockPos ORIGIN = new BlockPos(100, 64, 200);
    private static final BuildOptions CORNER = BuildOptions.DEFAULT.with(Map.of("anchor", "corner", "pattern", "linear"));

    private static List<String> targets(BuildPlan plan) {
        List<String> names = new ArrayList<>();
        for (int step = 0; step < plan.size(); step++) names.add((plan.excavation(step) ? "dig:" : "") + plan.design(step).path());
        return names;
    }

    @Test void eachLayerGoesAirSolidFallingDependentThenFluid() {
        Schematic box = schematic(LEGEND, new String[]{"twgas"}, new String[]{"sawtg"});
        BuildPlan plan = plan(box, CORNER, ORIGIN);
        assertEquals(List.of("air", "stone", "gravel", "torch", "water", "air", "stone", "gravel", "torch", "water"), targets(plan));
        assertEquals(10, plan.segments());
        assertEquals(BuildPhase.CLEAR, plan.phase(0));
        assertEquals(BuildPhase.FLUID, plan.phase(4));
        assertEquals(2, plan.segmentEnd(1));
    }

    @Test void groupAllPutsEverySolidBeforeAnyDependentBlock() {
        Schematic box = schematic(LEGEND, new String[]{"ts"}, new String[]{"st"}, new String[]{"ss"});
        BuildPlan plan = plan(box, CORNER.with(Map.of("groupall", "true")), ORIGIN);
        assertEquals(List.of("stone", "stone", "stone", "stone", "torch", "torch"), targets(plan));
        BuildPlan grouped = plan(box, CORNER.with(Map.of("layers", "2")), ORIGIN);
        assertEquals(List.of("stone", "stone", "torch", "torch", "stone", "stone"), targets(grouped));
    }

    @Test void airInsideAGroupClearsFromTheTopDown() {
        Schematic box = schematic(LEGEND, new String[]{"a"}, new String[]{"a"}, new String[]{"a"});
        BuildPlan plan = plan(box, CORNER.with(Map.of("groupall", "true")), ORIGIN);
        assertEquals(List.of(66, 65, 64), List.of(plan.stepPosition(0).y(), plan.stepPosition(1).y(), plan.stepPosition(2).y()));
    }

    @Test void excavationClearsTheWholeBoxTopDownFirstAndReplacesAirSteps() {
        Schematic box = schematic(LEGEND, new String[]{"sa"}, new String[]{"at"});
        BuildPlan plan = plan(box, CORNER.with(Map.of("excavate", "true")), ORIGIN);
        assertEquals(List.of("dig:air", "dig:torch", "dig:stone", "dig:air", "stone", "torch"), targets(plan));
        assertEquals(4, plan.excavations());
        assertTrue(plan.excavation(3));
        assertFalse(plan.excavation(4));
        assertEquals(BlockState.AIR, plan.target(1));
        assertEquals(65, plan.stepPosition(0).y());
    }

    @Test void ignoreOptionsDropAirAndLiquidSteps() {
        Schematic box = schematic(LEGEND, new String[]{"saw"});
        assertEquals(List.of("stone", "water"), targets(plan(box, CORNER.with(Map.of("ignoreair", "true")), ORIGIN)));
        assertEquals(List.of("air", "stone"), targets(plan(box, CORNER.with(Map.of("ignoreliquids", "true")), ORIGIN)));
    }

    @Test void voidAndUnplaceableCellsAreLeftAlone() {
        Schematic box = schematic(Map.of('s', "stone", 'x', "unknown_block"), new String[]{"s.x"});
        boolean[] placeable = {true, false};
        BuildPlan plan = BuildPlanner.plan(box, box.palette(), placeable, CORNER.with(Map.of("excavate", "true")), ORIGIN);
        assertEquals(List.of("dig:stone", "stone"), targets(plan));
    }

    @Test void doorHalvesAreOneStepLedByTheLowerHalf() {
        Schematic box = schematic(LEGEND, new String[]{"d"}, new String[]{"u"});
        assertEquals(List.of("oak_door"), targets(plan(box, CORNER, ORIGIN)));
        BuildPlan plan = plan(box, CORNER, ORIGIN);
        assertEquals("lower", plan.target(0).property("half").orElseThrow());
        assertEquals("upper", plan.unit(0).orElseThrow().parts().get(1).state().property("half").orElseThrow());
    }

    @Test void columnPatterns() {
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, BuildPlanner.columns(3, 3, BuildOptions.Pattern.LINEAR));
        assertArrayEquals(new int[]{8, 7, 6, 5, 4, 3, 2, 1, 0}, BuildPlanner.columns(3, 3, BuildOptions.Pattern.REVERSE_LINEAR));
        assertArrayEquals(new int[]{0, 1, 2, 5, 8, 7, 6, 3, 4}, BuildPlanner.columns(3, 3, BuildOptions.Pattern.SPIRAL));
        assertArrayEquals(new int[]{4, 0, 1, 2, 5, 8, 7, 6, 3}, BuildPlanner.columns(3, 3, BuildOptions.Pattern.REVERSE_SPIRAL));
        assertArrayEquals(new int[]{0, 1, 2, 3, 7, 11, 10, 9, 8, 4, 5, 6}, BuildPlanner.columns(4, 3, BuildOptions.Pattern.SPIRAL));
    }

    @Test void anchorsPlaceTheBox() {
        Schematic box = new Schematic("test", 4, 2, 2, -3, 1, 5, List.of(BlockState.parse("stone")), new int[]{0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}, 0, List.of());
        BuildPlan corner = plan(box, CORNER, ORIGIN);
        assertEquals(new BlockPos(100, 64, 200), corner.position(0));
        BuildPlan centre = plan(box, CORNER.with(Map.of("anchor", "center")), ORIGIN);
        assertEquals(new BlockPos(98, 64, 199), centre.position(0));
        BuildPlan saved = plan(box, CORNER.with(Map.of("anchor", "schematic")), ORIGIN);
        assertEquals(new BlockPos(97, 65, 205), saved.position(0));
        BuildPlan offset = plan(box, CORNER.with(Map.of("offset", "1,2,3")), ORIGIN);
        assertEquals(new BlockPos(101, 66, 203), offset.position(0));
        assertEquals(new BlockPos(101, 66, 203), offset.minimum());
        assertEquals(new BlockPos(104, 67, 204), offset.maximum());
    }

    @Test void centreAnchorStandsTheLowestFilledLayerOnTheOrigin() {
        Schematic box = schematic(LEGEND, new String[]{"a"}, new String[]{"a"}, new String[]{"s"});
        BuildPlan plan = plan(box, BuildOptions.DEFAULT.with(Map.of("ignoreair", "true")), ORIGIN);
        assertEquals(new BlockPos(100, 64, 200), plan.stepPosition(0));
    }

    @Test void rotationTurnsPositionsAboutTheOrigin() {
        Schematic row = schematic(LEGEND, new String[]{"sss"});
        BuildPlan turned = plan(row, CORNER.with(Map.of("rotation", "90")), ORIGIN);
        assertEquals(List.of(new BlockPos(100, 64, 200), new BlockPos(100, 64, 201), new BlockPos(100, 64, 202)),
                List.of(turned.stepPosition(0), turned.stepPosition(1), turned.stepPosition(2)));
        BuildPlan mirrored = plan(row, CORNER.with(Map.of("mirror", "x")), ORIGIN);
        assertEquals(new BlockPos(98, 64, 200), mirrored.stepPosition(2));
    }

    @Test void rotatedStatesTravelWithTheirCells() {
        Schematic stairs = schematic(Map.of('n', "oak_stairs[facing=north]"), new String[]{"n"});
        BuildPlan plan = plan(stairs, CORNER.with(Map.of("rotation", "270")), ORIGIN);
        assertEquals("minecraft:oak_stairs[facing=west]", plan.target(0).toString());
    }

    @Test void planningIsDeterministic() {
        Schematic box = schematic(LEGEND, new String[]{"stwg", "astw"}, new String[]{"gwts", "ssss"});
        BuildOptions options = BuildOptions.DEFAULT.with(Map.of("excavate", "true", "rotation", "180", "pattern", "reversespiral"));
        BuildPlan first = plan(box, options, ORIGIN), second = plan(box, options, ORIGIN);
        assertEquals(first.size(), second.size());
        for (int step = 0; step < first.size(); step++) assertEquals(first.cell(step), second.cell(step));
    }
}
