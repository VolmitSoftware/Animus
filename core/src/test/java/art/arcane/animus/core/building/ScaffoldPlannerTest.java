package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScaffoldPlannerTest {
    /** Flat ground at y=63, open air above, and any extra solid cells. */
    private static ScaffoldPlanner.Site site(Set<BlockPos> solid) {
        return new ScaffoldPlanner.Site() {
            @Override public boolean free(BlockPos at) { return at.y() >= 64 && !solid.contains(at); }
            @Override public boolean ground(BlockPos at) { return at.y() < 64 || solid.contains(at); }
        };
    }

    @Test void aHighBlockGetsAColumnFromTheGroundToStandOnWithinReach() {
        BlockPos target = new BlockPos(0, 76, 0);
        ScaffoldPlanner.Layout layout = ScaffoldPlanner.find(target, 4.5, List.of(), site(Set.of()), 4096).orElseThrow();
        assertTrue(layout.walkway().isEmpty());
        assertEquals(64, layout.base().y());
        BlockPos top = layout.column().getLast();
        assertEquals(top.y() + 1, layout.stand().y());
        assertEquals(top.x(), layout.stand().x());
        assertTrue(ScaffoldPlanner.reaches(layout.stand(), target, 4.5));
        for (int index = 1; index < layout.column().size(); index++) assertEquals(layout.column().get(index - 1).y() + 1, layout.column().get(index).y());
        assertEquals(75, layout.stand().y(), "the lowest stand that reaches the block keeps the scaffold short");
    }

    @Test void theStandThatReachesMostOfTheNextBlocksWins() {
        BlockPos target = new BlockPos(0, 76, 0);
        List<BlockPos> wall = List.of(new BlockPos(1, 76, 3), new BlockPos(2, 76, 3), new BlockPos(3, 76, 3), new BlockPos(3, 76, 2));
        ScaffoldPlanner.Layout layout = ScaffoldPlanner.find(target, 4.5, wall, site(Set.of()), 4096).orElseThrow();
        int reached = 0;
        for (BlockPos next : wall) if (ScaffoldPlanner.reaches(layout.stand(), next, 4.5)) reached++;
        assertEquals(4, reached);
    }

    @Test void aRoofUnderTheStandIsBridgedWithAWalkwayFromAColumnBesideIt() {
        Set<BlockPos> roof = new HashSet<>();
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) roof.add(new BlockPos(x, 74, z));
        Set<BlockPos> blocked = new HashSet<>(roof);
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = 64; y < 74; y++) blocked.add(new BlockPos(x, y, z));
        BlockPos target = new BlockPos(0, 79, 0);
        ScaffoldPlanner.Site site = new ScaffoldPlanner.Site() {
            @Override public boolean free(BlockPos at) { return at.y() >= 64 && !blocked.contains(at); }
            @Override public boolean ground(BlockPos at) { return at.y() < 64 || roof.contains(at); }
        };
        Optional<ScaffoldPlanner.Layout> found = ScaffoldPlanner.find(target, 4.5, List.of(), site, 8192);
        assertTrue(found.isPresent());
        assertTrue(ScaffoldPlanner.reaches(found.get().stand(), target, 4.5));
        assertEquals(75, found.get().base().y(), "a column may stand on the roof itself");
    }

    @Test void aWalkwayIsUsedWhenTheColumnCannotGoStraightDown() {
        Set<BlockPos> floor = new HashSet<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 64; y <= 73; y++) floor.add(new BlockPos(x, y, z));
        ScaffoldPlanner.Site site = new ScaffoldPlanner.Site() {
            @Override public boolean free(BlockPos at) { return at.y() >= 64 && !floor.contains(at); }
            @Override public boolean ground(BlockPos at) { return at.y() < 64; }
        };
        ScaffoldPlanner.Layout layout = ScaffoldPlanner.find(new BlockPos(0, 76, 0), 4.5, List.of(), site, 20000).orElseThrow();
        assertFalse(layout.walkway().isEmpty());
        assertTrue(layout.walkway().size() <= ScaffoldPlanner.MAXIMUM_SPAN);
        assertEquals(64, layout.base().y());
        BlockPos last = layout.walkway().getLast();
        assertEquals(new BlockPos(last.x(), last.y() + 1, last.z()), layout.stand());
    }

    @Test void nothingIsFoundWhenTheReadBudgetRunsOutOrNoCellIsFree() {
        assertTrue(ScaffoldPlanner.find(new BlockPos(0, 76, 0), 4.5, List.of(), site(Set.of()), 3).isEmpty());
        ScaffoldPlanner.Site closed = new ScaffoldPlanner.Site() {
            @Override public boolean free(BlockPos at) { return false; }
            @Override public boolean ground(BlockPos at) { return at.y() < 64; }
        };
        assertTrue(ScaffoldPlanner.find(new BlockPos(0, 76, 0), 4.5, List.of(), closed, 4096).isEmpty());
    }
}
